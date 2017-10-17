package whisk.core.computing

import akka.actor.ActorRef
import main.scala.communication.Messages.{ControllerPeerInfoRequest, OperationResponseMessage}
import main.scala.peers.Peer
import main.scala.pss.CyclonManager
import whisk.common.Logging

import scala.collection.mutable

class OperationControlHub(val localPeer: Peer)(implicit logging: Logging) {

  var requestToPeers = mutable.Map[Long, (Int, mutable.Seq[Peer])]()
  var requestToSource = mutable.Map[Long, ActorRef]()

  def processControllerInfoRequest(request: ControllerPeerInfoRequest, sender: ActorRef, pss: CyclonManager): Unit = {
    val operation = request.operation

    val entry = requestToPeers.get(operation.id).getOrElse(null)
    if (entry == null) {
      requestToPeers += (operation.id -> (request.numberOfPeersNeeded, mutable.Seq[Peer]()))

      pss.disseminateOperationRequest(operation)
    }

    requestToSource += (operation.id -> sender)
  }

  def processOperationResponse(message: OperationResponseMessage): Unit = {
    val currentState = requestToPeers.get(message.operationId).getOrElse(null)
      if (currentState != null) {
        requestToPeers(message.operationId) = (requestToPeers(message.operationId)._1, requestToPeers(message.operationId)._2 :+ message.peer)
      }
  }

  def hasEnoughPeers(operation: Long): Boolean = {
    val currentState = requestToPeers.get(operation).getOrElse(null)
    if(currentState != null)
      return currentState._1 <= currentState._2.size

    return false
  }

  def operationIsOngoing(operation: Long) = {
    requestToPeers.contains(operation)
  }

  //Remove operation whose response was already sent to controller
  def resolveOperation(operation: Long): Unit = {
    requestToPeers.remove(operation)
    requestToSource.remove(operation)
  }

  def getControllerResponse(operation: Long): Set[Peer] = {
    requestToPeers.get(operation).get._2.toSet
  }

  def getControllerRef(operation: Long): ActorRef = {
    requestToSource.get(operation).get
  }
}
