package whisk.core.dataflask

import java.io.File
import java.util.UUID

import akka.actor.{ActorSystem, Props, _}
import com.typesafe.config._
import main.scala.communication.Messages.{ComputingEnv, CyclonManagerStartMessage}
import main.scala.config.Configs.SystemConfig
import main.scala.peers.{DFPeer, Peer}
import main.scala.pss.CyclonManager
import whisk.common.{AkkaLogging, Logging}
import whisk.core.computing.{OperationControlHub, OperationsManager}
import whisk.core.group.HybridGroupManager

import scala.collection.mutable

class DataFlask {

    def initializeActorSystem(confFolderPath: String,
                              systemPathPrefix: String,
                              localId: String,
                              actorSystemName: String) : ActorSystem = {
        val configPath = s"$confFolderPath/${systemPathPrefix}${localId}.conf"
        val config = ConfigFactory.parseFile(new File(configPath))

        return ActorSystem(actorSystemName, config)
    }

    def startLocalCyclonManager(localId: String,
                                localPeer: Peer,
                                initialView: mutable.HashMap[UUID, Peer],
                                system: ActorSystem,
                                cyclonManagerPathPrefix: String,
                                computingEnv: ComputingEnv.EnvVal,
                                computable: Boolean,
                                controllerPeer: Boolean)(implicit logging: Logging): ActorRef = {
        val groupManager = new HybridGroupManager(localPeer, computingEnv)(logging)
        val operationsManager = new OperationsManager(localPeer, computingEnv, computable)(logging)
        val operationHub = if(controllerPeer) new OperationControlHub(localPeer)(logging) else null

        val remote: ActorRef = system.actorOf(Props(new CyclonManager(localPeer, initialView, groupManager, operationsManager, operationHub)), name=s"${cyclonManagerPathPrefix}${localId}")

        remote ! CyclonManagerStartMessage(remote)

        return remote
    }
}

object DataFlask {

    val cyclonManagerPathPrefix = "cyclon"
    val actionManagerPathPrefix = "action"
    val systemPathPrefix = "app"
    val actorSystemName = "ActorFlasks"

    def main(args: Array[String]): Unit = {

        //Parse local peer arguments
        val localId = sys.env("LOCAL_ID")
        val localIP = sys.env("LOCAL_IP")
        val flasksPort = sys.env("FLASKS_PORT")
        val confFolderPath = sys.env("CONFIG_PATH")
        val allNodes = sys.env("ALL_NODES")
        val computingEnv = sys.env("COMP_ENV")
        val computable = sys.env("COMPUTES") == "1"
        val controllerPeer = sys.env("CONTROLLER_PEER") == "1"

        val flasks = new DataFlask()
        val localPeer = new DFPeer(localId, localIP, flasksPort.toInt)

        val system = flasks.initializeActorSystem(confFolderPath, systemPathPrefix, localId, actorSystemName)

        implicit val logging = new AkkaLogging(akka.event.Logging.getLogger(system, this))

        logging.info(this, s"Local ID $localId\n" +
          s"Local IP $localIP\n" +
          s"PORT $flasksPort" +
          s"\nCONFIG PATH $confFolderPath" +
          s"\nALL NODES - $allNodes")

        //TODO: Remedy this
        var initialView: mutable.HashMap[UUID, Peer] = mutable.HashMap()
        for((node, index) <- allNodes.split(" ").zipWithIndex) {
            val newPeer = new DFPeer(
                if (controllerPeer) SystemConfig.controllerId else index.toString,
                node,
                flasksPort.toInt,
                _age = 0,
                _position = (index+1)/allNodes.split(" ").length
            )
            if(!newPeer.uuid.equals(localPeer.uuid))
                logging.info(this, s"ADDED PEER ${newPeer.id} WITH IP ${newPeer.ip} TO LOCAL VIEW")
                initialView += (newPeer.uuid -> newPeer)
        }

        //Initiate Cyclon
        flasks.startLocalCyclonManager(localId, localPeer, initialView, system,
            cyclonManagerPathPrefix, ComputingEnv.getFromString(computingEnv), computable, controllerPeer)
    }
}