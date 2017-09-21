package whisk.core.dataflask

import java.io.File
import java.util.UUID

import akka.actor.{ActorSystem, Props, _}
import com.typesafe.config._
import main.scala.communication.Messages.CyclonManagerStartMessage
import main.scala.group.GroupManager
import main.scala.peers.{DFPeer, Peer}
import main.scala.pss.CyclonManager
import whisk.common.{AkkaLogging, Logging}

import scala.collection.mutable

class DataFlask {

    def initializeActorSystem(confFolderPath: String,
                              systemPathPrefix: String,
                              localId: String,
                              actorSystemName: String) : ActorSystem = {
        val configPath = s"$confFolderPath/${systemPathPrefix}${localId}.conf"
        val config = ConfigFactory.parseFile(new File(configPath))

        return ActorSystem(actorSystemName , config)
    }

    def startLocalCyclonManager(localId: String,
                                localPeer: Peer,
                                initialView: mutable.HashMap[UUID, Peer],
                                system: ActorSystem,
                                cyclonManagerPathPrefix: String)(implicit logging: Logging): ActorRef = {
        val groupManager = new GroupManager(localPeer)
        val remote: ActorRef = system.actorOf(Props(new CyclonManager(localPeer, initialView, groupManager)), name=s"${cyclonManagerPathPrefix}${localId}")

        remote ! CyclonManagerStartMessage(remote)

        return remote
    }
}

object DataFlask {

    //TODO: Refactor to config file
    val cyclonManagerPathPrefix = "cyclon"
    val actionManagerPathPrefix = "action"
    val systemPathPrefix = "app"
    val actorSystemName = "ActorFlasks"

    def main(args: Array[String]): Unit = {

        //Parse local peer arguments
        val localId = sys.env("LOCAL_ID")
        val localIP = sys.env("LOCAL_IP")
        val flasksPort = sys.env("FLASKS_PORT")
        val localCapacity = sys.env("LOCAL_CAPACITY")
        val confFolderPath = sys.env("CONFIG_PATH")
        val allNodes = sys.env("ALL_NODES")

        val flasks = new DataFlask()
        val localPeer = new DFPeer(localId, localIP, flasksPort.toInt, localCapacity.toInt)
        val system = flasks.initializeActorSystem(confFolderPath, systemPathPrefix, localId, actorSystemName)

        implicit val logging = new AkkaLogging(akka.event.Logging.getLogger(system, this))

        logging.info(this, s"Local ID $localId\n" +
          s"Local IP $localIP\n" +
          s"PORT $flasksPort" +
          s"\nCONFIG PATH $confFolderPath" +
          s"\nALL NODES - $allNodes")

        var initialView: mutable.HashMap[UUID, Peer] = mutable.HashMap()

        for((node, index) <- allNodes.split(" ").zipWithIndex) {
            val newPeer = new DFPeer(index.toString, node, flasksPort.toInt, 10, _age = 0, _position = (index+1)/allNodes.split(" ").length)
            if(!newPeer.uuid.equals(localPeer.uuid))
                initialView += (newPeer.uuid -> newPeer)
        }

        //Initiate Cyclon
        flasks.startLocalCyclonManager(localId, localPeer, initialView, system, cyclonManagerPathPrefix)
    }
}