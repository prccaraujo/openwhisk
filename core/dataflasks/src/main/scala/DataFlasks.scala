import java.io.File
import java.util.UUID

import akka.actor._
import akka.actor.ActorSystem
import akka.actor.Props
import com.sun.java.swing.action.ActionManager
import com.typesafe.config._
import communication.Messages.ActionManagerStartMessage
import group.GroupManager
import main.scala.communication.Messages.{ActionManagerStartMessage, CyclonManagerStartMessage}
import main.scala.group.GroupManager
import main.scala.peers.{DFPeer, Peer}
import main.scala.pss.CyclonManager
import peers.{DFPeer, Peer}
import pss.CyclonManager

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
                                cyclonManagerPathPrefix: String): ActorRef = {
        val groupManager = new GroupManager(localPeer)
        val remote: ActorRef = system.actorOf(Props(new CyclonManager(localPeer, initialView, groupManager)), name=s"${cyclonManagerPathPrefix}${localId}")

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
        //TODO: Mandar para log o print
        if(args.length < 4) {
            print("Insufficient number of arguments")
            System.exit(1)
        }

        val localId = args(0)
        val localIP = args(1)
        val localPort = args(2)
        val localCapacity = args(3)
        val confFolderPath = args(4) //TODO: Isto deixará de ser válido

        val flasks = new DataFlasks()
        val localPeer = new DFPeer(localId, localIP, localPort.toInt, localCapacity.toInt)
        val system = flasks.initializeActorSystem(confFolderPath, systemPathPrefix, localId, actorSystemName)

        //Parse known nodes to initial view
        //TODO: Remove this and parse only the infomation about the load balancer
        val numberOfArgumentsPerNode = 4
        val numberOfArgumentsForLocalNode = 5

        var initialView: mutable.HashMap[UUID, Peer] = mutable.HashMap()

        for (i <- numberOfArgumentsForLocalNode to args.length - 1) {
            if ((i == numberOfArgumentsForLocalNode + numberOfArgumentsPerNode * initialView.size) && args(i) != null && !args(i).isEmpty) {
                val newPeer = new DFPeer(args(i), args(i+1), args(i+2).toInt, args(i+3).toInt, _age = 0, _position = numberOfArgumentsPerNode/(args.length-1))
                if(!newPeer.uuid.equals(localPeer.uuid))
                    initialView += (newPeer.uuid -> newPeer)
            }
        }

        val cyclonManagerActorRef = flasks.startLocalCyclonManager(localId, localPeer, initialView, system, cyclonManagerPathPrefix)
    }
}

