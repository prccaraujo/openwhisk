package whisk.core.group

import java.util.UUID

import main.scala.communication.Messages.ComputingEnv
import main.scala.peers.Peer
import whisk.common.Logging

import scala.collection.mutable

class HybridGroupManager(localPeer: Peer,
                         env: ComputingEnv.EnvVal)(implicit logging: Logging) {

  import main.scala.config.Configs.HybridGroupManagerConfig._

  var localViewEdge: mutable.HashMap[UUID, Peer] = mutable.HashMap[UUID, Peer]()
  var localViewCloud: mutable.HashMap[UUID, Peer] = mutable.HashMap[UUID, Peer]()

  var numberOfGroupsCloud = 1
  var currentGroupCloud = 1

  var numberOfGroupsEdge = 1
  var currentGroupEdge = 1

  def refreshGroup(newPeerList: List[Peer]): Unit = {
    newPeerList.foreach{ peer =>
      if(isSameGroupAsLocal(peer))
        if(peer.env == ComputingEnv.Edge) {
          localViewEdge += (peer.uuid -> peer)
        } else {
          if(peer.env == ComputingEnv.Cloud) {
            localViewCloud += (peer.uuid -> peer)
          }
        }
    }

    cleanLocalView
    tryMergeSplitGroupEdge
    tryMergeSplitGroupCloud

    val oldGroupEdge = currentGroupEdge
    val oldGroupCloud = currentGroupCloud

    currentGroupEdge = calculateGroupNumberEdge(localPeer.position)
    currentGroupCloud = calculateGroupNumberCloud(localPeer.position)

    if(oldGroupEdge != currentGroupEdge)
      logging.info(this, s"Edge changed from group $oldGroupEdge to $currentGroupEdge")

    if(oldGroupCloud != currentGroupCloud)
      logging.info(this, s"Cloud changed from group $oldGroupCloud to $currentGroupCloud")
  }

  def cleanLocalView: Unit = {
    localViewEdge = localViewEdge.filter(entry => isSameGroupAsLocal(entry._2))
    localViewCloud = localViewCloud.filter(entry => isSameGroupAsLocal(entry._2))
  }

  def isSameGroupAsLocal(peer: Peer): Boolean = {
    if(peer.env == ComputingEnv.Edge)
      return calculateGroupNumberEdge(peer.position) == currentGroupEdge
    else if(peer.env == ComputingEnv.Cloud)
      return calculateGroupNumberCloud(peer.position) == currentGroupCloud
    else
      return false
  }

  def calculateGroupNumberEdge(position: Double): Int = {
    return Math.ceil(position * numberOfGroupsEdge).toInt
  }

  def calculateGroupNumberCloud(position: Double): Int = {
    return Math.ceil(position * numberOfGroupsCloud).toInt
  }

  def tryMergeSplitGroupEdge: Unit = {
    if(localViewEdge.size < minGroupSizeEdge && numberOfGroupsEdge > 1) {
      numberOfGroupsEdge /= growingFactor
    } else {
      if (localViewEdge.size > maxGroupSizeEdge) {
        numberOfGroupsEdge *= growingFactor
      }
    }
  }

  def tryMergeSplitGroupCloud: Unit = {
    if(localViewCloud.size < minGroupSizeCloud && numberOfGroupsCloud > 1) {
      numberOfGroupsCloud /= growingFactor
    } else {
      if (localViewCloud.size > maxGroupSizeCloud) {
        numberOfGroupsCloud *= growingFactor
      }
    }
  }

  //This is a temporary hash
  //TODO: REMOVE RETURNING TRUE
  def holdsDataTag(dataTag: String): Boolean = {
    return true

    if(dataTag == null || dataTag.equals("")) return true

    val r = scala.util.Random
    r.setSeed(dataTag.hashCode)

    val hashValue = r.nextDouble()

    if (env == ComputingEnv.Edge) {
      return calculateGroupNumberEdge(hashValue) == currentGroupEdge
    } else {
      if (env == ComputingEnv.Cloud) {
        return calculateGroupNumberCloud(hashValue) == currentGroupCloud
      }
    }
    return false
  }
}
