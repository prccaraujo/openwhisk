/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.loadBalancer

import java.nio.charset.StandardCharsets

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success
import org.apache.kafka.clients.producer.RecordMetadata
import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import main.scala.communication.Messages.ComputingEnv
import whisk.common.Logging
import whisk.common.LoggingMarkers
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig._
import whisk.core.connector.MessagingProvider
import whisk.core.connector.{ActivationMessage, CompletionMessage}
import whisk.core.connector.MessageFeed
import whisk.core.connector.MessageProducer
import whisk.core.entity.{ActivationId, WhiskActivation}
import whisk.core.entity.InstanceId
import whisk.core.entity.ExecutableWhiskAction
import whisk.core.entity.UUID
import whisk.core.entity.types.EntityStore

import scala.annotation.tailrec
import whisk.core.entity.EntityName
import whisk.core.entity.Identity
import whisk.spi.SpiLoader

trait LoadBalancer {

  val activeAckTimeoutGrace = 1.minute

  /** Gets the number of in-flight activations for a specific user. */
  def activeActivationsFor(namspace: UUID): Int

  /** Gets the number of in-flight activations in the system. */
  def totalActiveActivations: Int

  /**
    * Publishes activation message on internal bus for an invoker to pick up.
    *
    * @param action the action to invoke
    * @param msg the activation message to publish on an invoker topic
    * @param transid the transaction id for the request
    * @return result a nested Future the outer indicating completion of publishing and
    *         the inner the completion of the action (i.e., the result)
    *         if it is ready before timeout (Right) otherwise the activation id (Left).
    *         The future is guaranteed to complete within the declared action time limit
    *         plus a grace period (see activeAckTimeoutGrace).
    */
  def publish(action: ExecutableWhiskAction, msg: ActivationMessage)(implicit transid: TransactionId): Future[Future[Either[ActivationId, WhiskActivation]]]
}

class LoadBalancerService(
                           config: WhiskConfig,
                           instance: InstanceId,
                           entityStore: EntityStore)(
                           implicit val actorSystem: ActorSystem,
                           logging: Logging)
  extends LoadBalancer {

  /** The execution context for futures */
  implicit val executionContext: ExecutionContext = actorSystem.dispatcher

  /** How many invokers are dedicated to blackbox images.  We range bound to something sensical regardless of configuration. */
  private val blackboxFraction: Double = Math.max(0.0, Math.min(1.0, config.controllerBlackboxFraction))
  logging.info(this, s"blackboxFraction = $blackboxFraction")

  private val loadBalancerData = new LoadBalancerData()

  override def activeActivationsFor(namespace: UUID) = loadBalancerData.activationCountOn(namespace)

  override def totalActiveActivations = loadBalancerData.totalActivationCount

  // logging.info(this, s"Chose Invoker named ${invokerName} with activationId ${msg.activationId} for user ${msg.user.uuid.toString}")(transid)
  // [LoadBalancerService] Chose Invoker named InstanceId(0) with activationId e5e8ed98932e46a3b9306c8ae6db964 for user 23bc46b1-71f6-4ed5-8c54-816aa4f8c502
  //TODO: Mais tarde, os metadados para escolher invoker vão estar incluidos na ActivationMessage
  override def publish(action: ExecutableWhiskAction, msg: ActivationMessage)(
    implicit transid: TransactionId): Future[Future[Either[ActivationId, WhiskActivation]]] = {

    val actionDataTag: String = action.annotations.get("tag").getOrElse("").toString
    val actionMinExpectedMem: Double = action.annotations.get("min_mem").getOrElse("0").toString.toDouble
    val actionEnv: ComputingEnv.EnvVal = ComputingEnv.getFromString(action.annotations.get("env").getOrElse("ANY").toString)

    logging.info(this, s"RECEIVED PUBLISH REQUEST WITH PARAMS tag: ${actionDataTag} mem: ${actionMinExpectedMem} env: ${actionEnv.toString}")

    chooseInvoker(msg.user, action, transid, actionDataTag, actionMinExpectedMem, actionEnv).flatMap { invokerName =>
      val entry = setupActivation(action, msg.activationId, msg.user.uuid, invokerName, transid)
      sendActivationToInvoker(messageProducer, msg, invokerName).map { _ =>
        entry.promise.future
      }
    }
  }

  /** An indexed sequence of all invokers in the current system able to execute a given function*/
  def allInvokers(transid: TransactionId,
                  actionDataTag: String,
                  actionMinExpectedMem: Double,
                  actionEnv: ComputingEnv.EnvVal): Future[IndexedSeq[InstanceId]] = dataflaskPool
    .ask(GetInvokers(2, transid.id, actionDataTag, actionMinExpectedMem, actionEnv))(Timeout(15.seconds))
    .mapTo[IndexedSeq[InstanceId]]

  /**
    * Tries to fill in the result slot (i.e., complete the promise) when a completion message arrives.
    * The promise is removed form the map when the result arrives or upon timeout.
    *
    * @param response is the kafka message payload as Json
    */
  private def processCompletion(response: Either[ActivationId, WhiskActivation], tid: TransactionId, forced: Boolean): Unit = {
    val aid = response.fold(l => l, r => r.activationId)
    loadBalancerData.removeActivation(aid) match {
      case Some(entry) =>
        logging.info(this, s"${if (!forced) "received" else "forced"} active ack for '$aid'")(tid)
        if (!forced) {
          entry.promise.trySuccess(response)
        } else {
          entry.promise.tryFailure(new Throwable("no active ack received"))
        }
      case None =>
        // the entry was already removed
        logging.debug(this, s"received active ack for '$aid' which has no entry")(tid)
    }
  }

  /**
    * Creates an activation entry and insert into various maps.
    */
  private def setupActivation(action: ExecutableWhiskAction, activationId: ActivationId, namespaceId: UUID, invokerName: InstanceId, transid: TransactionId): ActivationEntry = {
    val timeout = action.limits.timeout.duration + activeAckTimeoutGrace
    // Install a timeout handler for the catastrophic case where an active ack is not received at all
    // (because say an invoker is down completely, or the connection to the message bus is disrupted) or when
    // the active ack is significantly delayed (possibly dues to long queues but the subject should not be penalized);
    // in this case, if the activation handler is still registered, remove it and update the books.
    loadBalancerData.putActivation(activationId, {
      actorSystem.scheduler.scheduleOnce(timeout) {
        processCompletion(Left(activationId), transid, forced = true)
      }

      ActivationEntry(activationId, namespaceId, invokerName, Promise[Either[ActivationId, WhiskActivation]]())
    })
  }

  /** Gets a producer which can publish messages to the kafka bus. */
  private val messasgingProvider = SpiLoader.get[MessagingProvider]()
  private val messageProducer = messasgingProvider.getProducer(config, executionContext)

  private def sendActivationToInvoker(producer: MessageProducer, msg: ActivationMessage, invoker: InstanceId): Future[RecordMetadata] = {
    implicit val transid = msg.transid

    val topic = s"invoker${invoker.toInt}"
    val start = transid.started(this, LoggingMarkers.CONTROLLER_KAFKA, s"posting topic '$topic' with activation id '${msg.activationId}'")

    producer.send(topic, msg).andThen {
      case Success(status) => transid.finished(this, start, s"posted to ${status.topic()}[${status.partition()}][${status.offset()}]")
      case Failure(e)      => transid.failed(this, start, s"error on posting to topic $topic")
    }
  }

  private val dataflaskPool = {
    val maxPingsPerPoll = 128
    val pingConsumer = messasgingProvider.getConsumer(config, s"health${instance.toInt}", "health", maxPeek = maxPingsPerPoll)
    //TODO: Remover invoker factory
    //val invokerFactory = (f: ActorRefFactory, invokerInstance: InstanceId) => f.actorOf(InvokerActor.props(invokerInstance, instance))

    actorSystem.actorOf(DataFlaskPool.props(
      //invokerFactory,
      pingConsumer))
  }

  /**
    * Subscribes to active acks (completion messages from the invokers), and
    * registers a handler for received active acks from invokers.
    */
  //TODO: No futuro, informação sobre os invokers podem ser geridas aqui e no método processActiveAck
  val maxActiveAcksPerPoll = 128
  val activeAckPollDuration = 1.second
  private val activeAckConsumer = messasgingProvider.getConsumer(config, "completions", s"completed${instance.toInt}", maxPeek = maxActiveAcksPerPoll)
  val activationFeed = actorSystem.actorOf(Props {
    new MessageFeed("activeack", logging,
      activeAckConsumer, maxActiveAcksPerPoll, activeAckPollDuration, processActiveAck)
  })

  def processActiveAck(bytes: Array[Byte]): Future[Unit] = Future {
    val raw = new String(bytes, StandardCharsets.UTF_8)
    CompletionMessage.parse(raw) match {
      case Success(m: CompletionMessage) =>
        processCompletion(m.response, m.transid, false)
        // treat left as success (as it is the result a the message exceeding the bus limit)
        val isSuccess = m.response.fold(l => true, r => !r.response.isWhiskError)
        activationFeed ! MessageFeed.Processed
        dataflaskPool ! InvocationFinishedMessage(m.invoker, isSuccess)

      case Failure(t) =>
        activationFeed ! MessageFeed.Processed
        logging.error(this, s"failed processing message: $raw with $t")
    }
  }

  /** Compute the number of blackbox-dedicated invokers by applying a rounded down fraction of all invokers (but at least 1). */
  private def numBlackbox(totalInvokers: Int) = Math.max(1, (totalInvokers.toDouble * blackboxFraction).toInt)

  /** Return invokers (almost) dedicated to running blackbox actions. */
  private def blackboxInvokers(invokers: IndexedSeq[InstanceId]): IndexedSeq[InstanceId] = {
    val blackboxes = numBlackbox(invokers.size)
    invokers.takeRight(blackboxes)
  }

  /**
    * Return (at least one) invokers for running non black-box actions.
    * This set can overlap with the blackbox set if there is only one invoker.
    */
  private def managedInvokers(invokers: IndexedSeq[InstanceId]): IndexedSeq[InstanceId] = {
    val managed = Math.max(1, invokers.length - numBlackbox(invokers.length))
    invokers.take(managed)
  }

  /** Determine which invoker this activation should go to. Due to dynamic conditions, it may return no invoker. */
  private def chooseInvoker(user: Identity, action: ExecutableWhiskAction, transid: TransactionId,
                            actionDataTag: String, actionMinExpectedMem: Double, actionEnv: ComputingEnv.EnvVal): Future[InstanceId] = {
    val hash = generateHash(user.namespace, action)

    allInvokers(transid, actionDataTag, actionMinExpectedMem, actionEnv).flatMap { invokers =>
      val invokersToUse = if (action.exec.pull) blackboxInvokers(invokers) else managedInvokers(invokers)
      val invokersWithUsage = invokersToUse.view.map {
        // Using a view defers the comparably expensive lookup to actual access of the element
        case instance =>
          logging.info(this, s"Found invoker with instance id ${instance.toInt}")
          (instance, loadBalancerData.activationCountOn(instance))
      }

     LoadBalancerService.schedule(invokersWithUsage, hash) match {
       case Some(invoker) => Future.successful(invoker)
       case None =>
         logging.error(this, s"all invokers down")
         Future.failed(new LoadBalancerException("no invokers available"))
      }
    }
  }

  /** Generates a hash based on the string representation of namespace and action */
  //TODO: Modificar isto para o nosso algoritmo?
  private def generateHash(namespace: EntityName, action: ExecutableWhiskAction): Int = {
    (namespace.asString.hashCode() ^ action.fullyQualifiedName(false).asString.hashCode()).abs
  }
}

object LoadBalancerService {
  def requiredProperties = kafkaHost ++ Map(loadbalancerInvokerBusyThreshold -> null)

  /** Memoizes the result of `f` for later use. */
  def memoize[I, O](f: I => O): I => O = new scala.collection.mutable.HashMap[I, O]() {
    override def apply(key: I) = getOrElseUpdate(key, f(key))
  }

  /** Euclidean algorithm to determine the greatest-common-divisor */
  @tailrec
  def gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

  /** Returns pairwise coprime numbers until x. Result is memoized. */
  val pairwiseCoprimeNumbersUntil: Int => IndexedSeq[Int] = LoadBalancerService.memoize {
    case x =>
      (1 to x).foldLeft(IndexedSeq.empty[Int])((primes, cur) => {
        if (gcd(cur, x) == 1 && primes.forall(i => gcd(i, cur) == 1)) {
          primes :+ cur
        } else primes
      })
  }

  /**
    * Scans through all invokers and searches for an invoker.
    *
    * @param invokers a list of available invokers to search in, including their state and usage
    * @param hash stable identifier of the entity to be scheduled
    * @return an invoker to schedule to or None of no invoker is available
    */
  //TODO: No futuro, aqui vão ser definidas as regras de filtragem do algoritmo, como p.e. o número de invokers que devem executar a action
  def schedule(invokers: Seq[(InstanceId, Int)], hash: Int): Option[InstanceId] = {
    Some(invokers.head._1) //invokers.Int é o número de invocations atualmente no invoker
  }
}

private case class LoadBalancerException(msg: String) extends Throwable(msg)
