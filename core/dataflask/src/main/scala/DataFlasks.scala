import java.io.File
import java.util.UUID

import akka.actor._
import akka.actor.ActorSystem
import akka.actor.Props
import com.typesafe.config._
import main.scala.communication.Messages.CyclonManagerStartMessage
import main.scala.group.GroupManager
import main.scala.peers.{DFPeer, Peer}
import main.scala.pss.CyclonManager
import whisk.common.{AkkaLogging, Logging}

import scala.collection.mutable

class DataFlasks {

    //TODO: Mudar porque resource tem de se ir buscar a outro sitio
    def initializeActorSystem(confFolderPath: String,
                              systemPathPrefix: String,
                              localId: String,
                              actorSystemName: String) : ActorSystem = {
        val configPath = s"$confFolderPath/${systemPathPrefix}$localId.conf"
        val config = ConfigFactory.parseFile(new File(configPath))
        //println(System.getProperty("user.dir"))

        return ActorSystem(actorSystemName , config)
    }

    def startLocalCyclonManager(localId: String,
                                localPeer: Peer,
                                initialView: mutable.HashMap[UUID, Peer],
                                system: ActorSystem,
                                cyclonManagerPathPrefix: String,
                                logging: Logging): ActorRef = {
        val groupManager = new GroupManager(localPeer)
        val remote: ActorRef = system.actorOf(Props(new CyclonManager(localPeer, initialView, groupManager, logging)), name=s"${cyclonManagerPathPrefix}${localId}")

        remote ! CyclonManagerStartMessage(remote)

        return remote
    }
/*
    def startLocalActionManager(localId: String,
                                localCapacity: Int,
                                localPeer: Peer,
                                cyclonManagerActorRef: ActorRef,
                                system: ActorSystem,
                                actionManagerPathPrefix: String): Unit = {
        val remote: ActorRef = system.actorOf(Props(new ActionManager(localCapacity, localPeer, cyclonManagerActorRef)), name=s"${actionManagerPathPrefix}${localId}")

        remote ! ActionManagerStartMessage(remote)
    }
*/
    // This test version doesn't support action dissemination yet
    //startLocalActionManager(localCapacity.toInt, localPeer, cyclonManagerActorRef)
}

object DataFlasks {
    val cyclonManagerPathPrefix = "cyclon"
    val actionManagerPathPrefix = "action"
    val systemPathPrefix = "app"
    val actorSystemName = "ActorFlasks"

    def main(args: Array[String]): Unit = {

        //Parse local peer arguments
        if(args.length < 4) {
            print("Insufficient number of arguments")
            System.exit(1)
        }

        val localId = args(0)
        val localIP = args(1)
        val localPort = args(2)
        val localCapacity = args(3)
        val confFolderPath = args(4)

        val flasks = new DataFlasks()
        val localPeer = new DFPeer(localId, localIP, localPort.toInt, localCapacity.toInt)
        val system = flasks.initializeActorSystem(confFolderPath, systemPathPrefix, localId, actorSystemName)

        implicit val logger = new AkkaLogging(akka.event.Logging.getLogger(system, this))

        var initialView: mutable.HashMap[UUID, Peer] = mutable.HashMap()

        val balancerId = args(5)
        val balancerIP = args(6)
        val balancerPort = args(7)
        val balancerCapacity = 0

        //TODO: FIX POSITION
        val balancerPeer = new DFPeer(balancerId, balancerIP, balancerPort.toInt, balancerCapacity, _age = 0)

        if(!localPeer.uuid.equals(balancerPeer.uuid))
            initialView += (balancerPeer.uuid -> balancerPeer)

        val cyclonManagerActorRef = flasks.startLocalCyclonManager(localId, localPeer, initialView, system, cyclonManagerPathPrefix, logger)
    }
}

