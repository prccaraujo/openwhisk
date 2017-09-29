package whisk.core.loadBalancer

import scala.collection.mutable
import scala.concurrent.duration._
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorRefFactory
import akka.actor.Props
import akka.util.Timeout
import whisk.common.AkkaLogging
import whisk.common.RingBuffer
import whisk.core.connector._
import whisk.core.entity._

// Received events
case object GetInvokers

case class ActivationRequest(msg: ActivationMessage, invoker: InstanceId)
case class InvocationFinishedMessage(invokerInstance: InstanceId, successful: Boolean)

// Data stored in the Invoker
final case class InvokerInfo(buffer: RingBuffer[Boolean])

//Comunicates with local DataFlask container and asks for information about available invokers
class DataFlaskPool(
                   childFactory: (ActorRefFactory, InstanceId) => ActorRef,
                   pingConsumer: MessageConsumer) extends Actor {

  //implicit val transid = TransactionId.invokerHealth
  implicit val logging = new AkkaLogging(context.system.log)
  implicit val timeout = Timeout(5.seconds)
  implicit val ec = context.dispatcher

  // State of the actor. It's important not to close over these
  // references directly, so they don't escape the Actor.
  val instanceToRef = mutable.Map[InstanceId, ActorRef]()
  val refToInstance = mutable.Map[ActorRef, InstanceId]()
  //TODO: No futuro pode-se guardar aqui a info sobre cada instance id, da mesma forma que originalmente se guardava o state.
  //TODO: No entanto esta info não vai servir para nada possivelmente
  var status = IndexedSeq[InstanceId]()

  def receive = {

    case GetInvokers => {
      status = status :+ InstanceId(0)
      sender() ! status
      logging.info(this, s"Received GetInfokers msg")
      logging.info(this, s"Returned ${status.head}")
    }

    //TODO: Adaptar para dataflasks
    case msg: InvocationFinishedMessage => {
      // Forward message to invoker, if InvokerActor exists
      instanceToRef.get(msg.invokerInstance).map(_.forward(msg))
    }
  }

/*
  def getPeerActorRef(baseSystemAddress: String, peer: Peer, actorName: String, context: ActorContext): Future[ActorRef] = {
    val path = s"$baseSystemAddress@" +
      s"${peer.ip}:" +
      s"${peer.port}/user/" +
      s"${actorName}${peer.name}"
    logging.info(this, s"Trying to find peer at $path")
    return context.actorSelection(path).resolveOne(10.seconds)
  }

  def sendTestMessageToDataFlasks(): Unit = {
    val system = akka.actor.ActorSystem("ActorFlasks")
    val baseSystemAddress = "akka.tcp://ActorFlasks"
    val peerFindingTimeLimit = 5.seconds

    val peer: Peer = new DFPeer("4", "192.168.115.128", 50000, 1)

    getPeerActorRef(baseSystemAddress, peer, "cyclon", context).onComplete{
      case Success(peerRef) =>
        logging.info(this, s"Success at finding peer ${peer.name}")
        peerRef ! ControllerTestRequest
      case Failure(f) =>
        logging.info(this, s"Failure trying to find peer ${peer.name}")
    }
  }
*/

  /** Pads a list to a given length using the given function to compute entries */
  //TODO: Provavelmente não utilizar depois. Ao receber um invoker, preenche a lista com os numeros que faltam até ao invoker recebido
  def padToIndexed[A](list: IndexedSeq[A], n: Int, f: (Int) => A) = list ++ (list.size until n).map(f)
}

//TODO: Penso que messageConsumer é inutil
object DataFlaskPool {
  def props(
             f: (ActorRefFactory, InstanceId) => ActorRef,
             pc: MessageConsumer) = {
    Props(new DataFlaskPool(f, pc))
  }
}

/**
  * Actor representing an Invoker
  */
//TODO: Probably is not worth to store this info
class InvokerActor(invokerInstance: InstanceId, controllerInstance: InstanceId) extends Actor {
  //implicit val transid = TransactionId.invokerHealth
  implicit val logging = new AkkaLogging(context.system.log)
  val name = s"invoker${invokerInstance.toInt}"

  val healthyTimeout = 10.seconds

  // This is done at this point to not intermingle with the state-machine
  // especially their timeouts.
  def customReceive: Receive = {
    case _ => // The response of putting testactions to the MessageProducer. We don't have to do anything with them.
  }
  override def receive = customReceive
}

object InvokerActor {
  def props(invokerInstance: InstanceId, controllerInstance: InstanceId) = Props(new InvokerActor(invokerInstance, controllerInstance))

  val bufferSize = 10
  val bufferErrorTolerance = 3

  val timerName = "testActionTimer"
}
