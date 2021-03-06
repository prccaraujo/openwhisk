package main.scala.config

import akka.actor.{ActorContext, ActorRef}
import main.scala.peers.Peer

import scala.concurrent.Future
import scala.concurrent.duration._

object Configs {
  object GroupManagerConfig {
    val growingFactor = 2
    val minGroupSize = 3
    val maxGroupSize = 6
  }

  object HybridGroupManagerConfig {
    val growingFactor = 2
    val minGroupSizeEdge = 3
    val maxGroupSizeEdge = 6
    val minGroupSizeCloud = 2
    val maxGroupSizeCloud = 4
  }

  object ActionManagerConfig {
    val relieverInterval = 1.second
    val initialDissemination = 3
  }

  object LoadBalancerConfig {
    val initialDissemination = 3
  }

  object CyclonManagerConfig {
    val gossipInterval = 2.seconds
    val gossipSize = 5
    val localViewSize = 8
  }

  object SystemConfig {
    val cyclonManagerPathPrefix = "cyclon"
    val actionManagerPathPrefix = "action"
    val systemPathPrefix = "app"
    val actorSystemName = "ActorFlasks"
    val controllerId = "99999"

    val system = akka.actor.ActorSystem(actorSystemName)
    val baseSystemAddress = s"akka.tcp://$actorSystemName"
    val peerFindingTimeLimit = 5.seconds

    def getPeerActorRef(peer: Peer, actorName: String, context: ActorContext): Future[ActorRef] = {
      val path = s"$baseSystemAddress@" +
        s"${peer.ip}:" +
        s"${peer.port}/user/" +
        s"${actorName}${peer.name}"
      return context.actorSelection(path).resolveOne(peerFindingTimeLimit)
    }
  }
}
