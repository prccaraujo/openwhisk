package whisk.core.loadBalancer

import scala.collection.mutable
import scala.concurrent.duration._
import akka.actor.{Actor, ActorRef, Props}
import akka.util.Timeout
import main.scala.communication.Messages._
import main.scala.peers.{DFPeer, Peer}
import whisk.common.AkkaLogging
import whisk.core.computing.ComputingOperation
import whisk.core.connector._
import whisk.core.entity._

import scala.concurrent.Future
import scala.util.{Failure, Success}

// Received events
case class GetInvokers(numberOfInvokers: Int,
                       transid: Long,
                       actionDataTag: String,
                       actionMinExpectedMem: Double,
                       actionEnv: ComputingEnv.EnvVal)

case class ActivationRequest(msg: ActivationMessage, invoker: InstanceId)
case class InvocationFinishedMessage(invokerInstance: InstanceId, successful: Boolean)

//Comunicates with local DataFlask container and asks for information about available invokers
class DataFlaskPool(pingConsumer: MessageConsumer) extends Actor {

  val timeoutTime = 15.seconds
  val dataFlasksBaseSystemAddress = "akka.tcp://ActorFlasks"
  val localDataFlaskIp = sys.env("DATAFLASK_IP")
  val localDataFlaskPort = sys.env("DATAFLASK_PORT")
  val localDataFlaskPrefix = "cyclon"
  val localDataFlaskName = sys.env("DATAFLASK_ID")

  implicit val logging = new AkkaLogging(context.system.log)
  implicit val timeout = Timeout(timeoutTime)
  implicit val ec = context.dispatcher
  implicit val localDataFlaskPeer = new DFPeer(localDataFlaskName, localDataFlaskIp, localDataFlaskPort.toInt, 0)
  implicit var localDataFlaskRef : ActorRef = _

  //Resolve local dataflasks actor reference
  resolveDataFlaskActorRef(dataFlasksBaseSystemAddress, localDataFlaskPeer)

  val instanceToRef = mutable.Map[InstanceId, ActorRef]() //TODO: REMOVE?
  val refToInstance = mutable.Map[ActorRef, InstanceId]() //TODO: REMOVE?
  val operationToController = mutable.Map[Long, ActorRef]() //Maps requested operation ID to the ref of the controller that did it

  //TODO: At this moment this is implemented as if every operation responds in order to the load balancer (can bring problems, f.e. by returning invokers to the wrong request)
  //TODO: This means that the controller is also assigning the operations in order to the invokers.
  def receive = {

    case msg: GetInvokers => {
      operationToController += (msg.transid -> sender())

      //Create request
      val request = ControllerPeerInfoRequest(
        new ComputingOperation(
          msg.transid,
          msg.actionDataTag,
          msg.actionMinExpectedMem,
          msg.actionEnv),
        msg.numberOfInvokers,
        this.context.self)

      localDataFlaskRef ! request
    }

    case msg: ControllerPeerInfoResponse =>
      logging.info(this, s"RECEIVED SET OF NODES TO SEND STUFF FOR OPERATION ${msg.operationId}")
      val controller = operationToController.getOrElse(msg.operationId, null)

      if(controller != null){
        controller ! msg.peerList.map(peer => InstanceId(peer.name.toInt)).toIndexedSeq
        operationToController.remove(msg.operationId)
      }

    //TODO: Adaptar para dataflasks
    case msg: InvocationFinishedMessage => {
      // Forward message to invoker, if InvokerActor exists
      instanceToRef.get(msg.invokerInstance).map(_.forward(msg))
    }
  }

  def resolveDataFlaskActorRef(baseSystemAddress: String, peer: DFPeer): Unit = {
    getPeerActorRef(baseSystemAddress, peer, localDataFlaskPrefix).onComplete{
      case Success(peerRef) =>
        localDataFlaskRef = peerRef
      case Failure(f) =>
        logging.info(this, s"Failure trying to find peer ${peer.name} - ${f.toString}")
        resolveDataFlaskActorRef _
    }
  }

  def getPeerActorRef(baseSystemAddress: String, peer: Peer, actorName: String): Future[ActorRef] = {
    val path = s"$baseSystemAddress@" +
      s"${peer.ip}:" +
      s"${peer.port}/user/" +
      s"${actorName}${peer.name}"
    logging.info(this, s"Trying to find peer at $path")
    return context.actorSelection(path).resolveOne(timeoutTime)
  }

  /** Pads a list to a given length using the given function to compute entries */
  //TODO: Provavelmente não utilizar depois. Ao receber um invoker, preenche a lista com os numeros que faltam até ao invoker recebido
  def padToIndexed[A](list: IndexedSeq[A], n: Int, f: (Int) => A) = list ++ (list.size until n).map(f)
}

//TODO: Penso que messageConsumer é inutil
object DataFlaskPool {
  def props(pc: MessageConsumer) = {
    Props(new DataFlaskPool(pc))
  }
}
