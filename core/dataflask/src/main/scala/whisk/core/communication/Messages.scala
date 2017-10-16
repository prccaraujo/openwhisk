package main.scala.communication

import akka.actor.ActorRef
import main.scala.peers.Peer
import whisk.core.computing.ComputingOperation

object Messages {

  sealed trait Message
  sealed trait CyclonMessage extends Message {
    def sender: Peer
  }

  object ComputingEnv {
    sealed trait EnvVal
    case object Cloud extends EnvVal
    case object Edge extends EnvVal
    case object Any extends EnvVal

    def getFromString(value: String): ComputingEnv.EnvVal = {
      if (value == null || value.isEmpty)
        return ComputingEnv.Any

      if (value.toLowerCase.equals("cloud"))
        return ComputingEnv.Cloud
      else if (value.toLowerCase.equals("edge"))
        return ComputingEnv.Edge
      else
        return ComputingEnv.Any
    }
  }

  /*Cyclon messages*/
  case class CyclonManagerStartMessage(destination: ActorRef) extends Serializable
  case class CyclonDisseminateMessage() extends Serializable

  case class CyclonRequestMessage(sender: Peer, peerList: Set[Peer])
    extends CyclonMessage with Serializable
  case class CyclonResponseMessage(sender: Peer, peerList: Set[Peer])
    extends CyclonMessage with Serializable

 /*Load Balancer messages*/
  case class LoadBalancerStartMessage(destination: ActorRef) extends Message with Serializable
  case class ControllerPeerInfoRequest(operation: ComputingOperation, numberOfPeersNeeded: Int,  balancer: ActorRef) extends Message with Serializable
  case class ControllerPeerInfoResponse(operationId: Long, peerList: Set[Peer]) extends Message with Serializable

  /*Operation messages*/
  case class OperationRequestMessage(operation: ComputingOperation, source: ActorRef) extends Message with Serializable
  case class OperationResponseMessage(operationId: Long, remainingAvailMem: Double, peer: Peer) extends Message with Serializable
}