package main.scala.communication

import akka.actor.ActorRef
import main.scala.peers.Peer

import scala.collection.mutable.ListBuffer

object Messages {

  sealed trait CyclonMessage{
    def sender: Peer
  }

  /*Cyclon messages*/
  case class CyclonManagerStartMessage(destination: ActorRef) extends Serializable
  case class CyclonDisseminateMessage() extends Serializable

  case class CyclonRequestMessage(sender: Peer, peerList: Set[Peer])
    extends CyclonMessage with Serializable
  case class CyclonResponseMessage(sender: Peer, peerList: Set[Peer])
    extends CyclonMessage with Serializable

  /*User request messages*/
  case class UserRequestMessage(requestId: Int, requestLoad: Int, faultToleranceLevel: Int) extends Serializable

  /*Action messages*/
  case class ActionManagerStartMessage(destination: ActorRef) extends Serializable
  case class ActionRequestMessage(actionId: Int, actionLoad: Int, target: Peer) extends Serializable
  case class ActionResponseMessage(actionId: Int, responder: Peer)

  /*Load Balancer messages*/
  case class LoadBalancerStartMessage(destination: ActorRef) extends Serializable
  case class PeerInfoRequest(infoSize: Int) extends Serializable
  case class PeerInfoResponse(listBuffer: ListBuffer[Peer]) extends Serializable

  /*Fake reliever messages*/
  case class RelieveLoadMessage() extends Serializable
}
