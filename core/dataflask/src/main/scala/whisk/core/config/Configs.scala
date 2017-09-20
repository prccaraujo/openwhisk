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
    val system = akka.actor.ActorSystem("ActorFlasks")
    val baseSystemAddress = "akka.tcp://ActorFlasks"
    val peerFindingTimeLimit = 5.seconds

    def getPeerActorRef(peer: Peer, actorName: String, context: ActorContext): Future[ActorRef] = {
      val path = s"$baseSystemAddress@" +
        s"${peer.ip}:" +
        s"${peer.port}/user/" +
        s"${actorName}${peer.name}"
      println(s"Trying to find peer at $path")
      return context.actorSelection(path).resolveOne(peerFindingTimeLimit)
    }
  }
}
