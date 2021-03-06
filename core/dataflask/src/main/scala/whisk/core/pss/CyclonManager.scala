package main.scala.pss

import java.util.UUID

import akka.actor._
import main.scala.communication.Messages._
import main.scala.peers.{DFPeer, Peer}
import whisk.common.Logging
import whisk.core.computing.{ComputingOperation, OperationControlHub, OperationsManager}
import whisk.core.group.HybridGroupManager

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.util.{Failure, Random, Success}

class CyclonManager(private val _localPeer: Peer,
                    val initialView: mutable.HashMap[UUID, Peer],
                    val groupManager: HybridGroupManager = null,
                    val operationsManager: OperationsManager = null,
                    val operationControlHub: OperationControlHub = null)(implicit logging: Logging) extends Actor {

  import main.scala.config.Configs.CyclonManagerConfig._
  import main.scala.config.Configs.SystemConfig._
  import system.dispatcher

  var localView: mutable.HashMap[UUID, Peer] = initialView
  var sentPeerData: ListBuffer[Peer] = ListBuffer[Peer]()
  def localPeer: Peer = _localPeer
  var processedOperationRequests: mutable.Set[Long] = mutable.Set[Long]()

  def insertSentToView(source: ListBuffer[Peer] = sentPeerData): Unit = {
    var peersToProcess: ListBuffer[Peer] = if(source == null) sentPeerData else source
    while (localView.size < localViewSize && peersToProcess.size > 0) {
      val tempPeer: Peer = peersToProcess(0)
      peersToProcess -= tempPeer

      //Discard entries pointing at self
      if(tempPeer != localPeer) {
        //If new peer already in view but is older, replace peer (refresh age). Otherwise, add peer to view
        val currentPeer = localView.get(tempPeer.uuid)
        currentPeer match {
          case Some(peer) =>
            if (peer.age > tempPeer.age) localView += (peer.uuid -> peer)
          case None =>
            localView += (tempPeer.uuid -> tempPeer)
        }
      }
    }
  }

  //Increase by on the age of all neighbours
  def ageGlobalView : Unit = {
    localView.values.foreach(peer => peer.asInstanceOf[DFPeer].increaseAgeByOne)
  }

  def getOlderGlobal: Peer = {
    var oldestPeer: Peer = null
    localView.values.foreach{ peer =>
      if (oldestPeer == null)
        oldestPeer = peer
      else
        oldestPeer = if (peer.age > oldestPeer.age) peer else oldestPeer
    }

    return oldestPeer
  }

  def getRandomGlobal(numberOfPeers: Int): ListBuffer[Peer] = {
    val globalPeersArray = scala.util.Random.shuffle(localView.values)
    var peerList = ListBuffer[Peer]()

    if (globalPeersArray.size <= numberOfPeers)
      peerList ++= globalPeersArray
    else
      peerList ++= globalPeersArray.slice(0, numberOfPeers)

    return peerList
  }

  //Select list of whisk.core.peers in view to send to neighbours
  def selectPeerInfoToDisseminate(target: Peer): Set[Peer] = {
    val localPeerInfo: Peer = localPeer.asInstanceOf[DFPeer].clone()
    var toDisseminate: ListBuffer[Peer] = ListBuffer[Peer]() //Mutable List

    //Shuffle to avoid duplicate dissemination cycles and conform to message size
    localView.values.foreach{ peer =>
      if (target != null && !peer.equals(target)){
        toDisseminate += peer.asInstanceOf[DFPeer].clone() //TODO: Test
      } else {
        toDisseminate += peer.asInstanceOf[DFPeer].clone()
      }
    }

    toDisseminate = Random.shuffle(toDisseminate)

    while(toDisseminate.size >= gossipSize) {
      val dropPeer: Peer = toDisseminate.head
      toDisseminate -= dropPeer
      localView -= dropPeer.uuid
    }

    toDisseminate += localPeerInfo

    return toDisseminate.toSet
  }

  def sendMessageToPeer(peer: Peer, message: Message): Unit = {
    getPeerActorRef(peer, cyclonManagerPathPrefix, context).onComplete{
       case Success(peerRef) =>
         println(s"Success at finding peer ${peer.name}")
         peerRef ! message
       case Failure(f) =>
         logging.error(this, s"Failure trying to find peer ${peer.name}")
    }
  }

  //Disseminate Cyclon information to selected peer (oldest peer in the view)
  def disseminateCyclonInfo: Unit = {
    insertSentToView()
    ageGlobalView

    val target: Peer = getOlderGlobal //Select neighbour Q with the highest age
    if (target != null) {
      localView -= target.uuid //Replace Q's entry with a new entry of age 0 and P's address

      val toDisseminate: Set[Peer] = selectPeerInfoToDisseminate(target)

      sentPeerData = ListBuffer[Peer]()
      sentPeerData ++= toDisseminate.toList
      sentPeerData.filter(peer => peer != localPeer)

      val messageToSend = CyclonRequestMessage(localPeer, toDisseminate)
      sendMessageToPeer(target, messageToSend)
    } else {
      logging.error(this, "View doesn't have any more peers right now.")
    }
  }

  // Peer sends a message to himself every gossipInterval, and disseminates view info upon receival
  def scheduleMessageDissemination(localActor: ActorRef) = {
    system.scheduler.schedule(3000.milliseconds, gossipInterval, localActor, CyclonDisseminateMessage)
  }

  def processRequestMessage(message: CyclonRequestMessage): Unit = {
    val messagePeerInfo: ListBuffer[Peer] = ListBuffer[Peer]()
    messagePeerInfo ++= message.peerList.toList

    val peerInfoToDisseminate: Set[Peer] = selectPeerInfoToDisseminate(message.sender)
    val toFillEmptyView: ListBuffer[Peer] = ListBuffer[Peer]()

    peerInfoToDisseminate.foreach{ peer =>
      if(peer != localPeer)
        toFillEmptyView += peer.asInstanceOf[DFPeer].clone()
    }

    insertSentToView(messagePeerInfo)
    insertSentToView(toFillEmptyView)

    while(localView.size > localViewSize) localView -= getOlderGlobal.uuid
    val messageToSend = CyclonResponseMessage(localPeer, peerInfoToDisseminate)
    sendMessageToPeer(message.sender, messageToSend)
  }

  def processResponseMessage(message: CyclonResponseMessage): Unit = {
    val messagePeerInfo: ListBuffer[Peer] = ListBuffer[Peer]()
    messagePeerInfo ++= message.peerList.toList

    insertSentToView()
    insertSentToView(messagePeerInfo)

    //Remove oldest neighbour from view if view has surpassed its peer limit
    while(localView.size > localViewSize) {
      localView -= getOlderGlobal.uuid
    }

    if (groupManager != null) groupManager.refreshGroup(message.peerList.toList);
    sentPeerData = ListBuffer[Peer]()
  }

  def processOperationRequest(operation: ComputingOperation, source: ActorRef): Unit = {
    if (!processedOperationRequests.contains(operation.id)) {
      processedOperationRequests += operation.id
      logging.info(this, "Processing operation request from controller")

      disseminateOperationRequest(operation, source)

      if (operationsManager.canComputeOperation(operation)) {
        logging.info(this, "CAN COMPUTE OPERATION!")
        if (groupManager.holdsDataTag(operation.tag)) {
          logging.info(this, "HOLDS TAG!")
          operationsManager.bookOperationTemporarily(operation)

          //Respond to controller
          logging.info(this, s"RESPONDING TO CONTROLLER WITH OPERATION RESPONSE MESSAGE: ${source.path}")
          source ! OperationResponseMessage(operation.id, operationsManager.getCurrentFreeMemory(), localPeer)
        }
      }
    }
  }

  def disseminateOperationRequest(operation: ComputingOperation, source: ActorRef = context.self): Unit = {
      val toDisseminate: Set[Peer] = selectPeerInfoToDisseminate(null)
      val operationMessage = new OperationRequestMessage(operation, source)

      toDisseminate.foreach { peer =>
        sendMessageToPeer(peer, operationMessage)
      }
  }

  def processOperationResponse(response: OperationResponseMessage): Unit = {
    logging.info(this, s"Received operation response from ${response.peer.name}")

    val operation = response.operationId

    if(operationControlHub != null) {
      if(operationControlHub.operationIsOngoing(operation)) {
        logging.info(this, s"OPERATION ${response.operationId} IS STILL ONGOING!")

        operationControlHub.processOperationResponse(response)

        if (operationControlHub.hasEnoughPeers(operation)) {
          logging.info(this, s"OPERATION ${response.operationId} HAS ENOUGH PEEERS! RESPONDING to ${operationControlHub.getControllerRef(operation).path.toString}")
          operationControlHub.getControllerRef(operation) ! ControllerPeerInfoResponse(operation, operationControlHub.getControllerResponse(operation))
          operationControlHub.resolveOperation(operation)
        }
      }
    }
  }

  def processControllerInfoRequest(request: ControllerPeerInfoRequest, ref: ActorRef): Unit = {
    if (operationControlHub != null) {
      operationControlHub.processControllerInfoRequest(request, ref, this)
    }
  }

  override def receive: Receive = {
    case msg: CyclonManagerStartMessage =>
      logging.info(this, s"Cyclon Manager Started")
      scheduleMessageDissemination(msg.destination)
    case CyclonDisseminateMessage =>
      disseminateCyclonInfo
    case msg: CyclonRequestMessage =>
      processRequestMessage(msg)
    case msg: CyclonResponseMessage =>
      processResponseMessage(msg)
    case msg: ControllerPeerInfoRequest =>
      processControllerInfoRequest(msg, msg.balancer)
    case msg: OperationRequestMessage =>
      processOperationRequest(msg.operation, msg.source)
    case msg: OperationResponseMessage =>
      processOperationResponse(msg)
    case msg =>
      logging.error(this, s"Received unrecognized message from ${sender().path} ---- ${msg.toString}")
  }
}