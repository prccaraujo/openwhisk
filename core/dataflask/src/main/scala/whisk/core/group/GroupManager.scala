package main.scala.group

import java.util.UUID

import main.scala.peers.Peer
import whisk.common.Logging

import scala.collection.mutable

class GroupManager(localPeer: Peer)(implicit logging: Logging) {

  import main.scala.config.Configs.GroupManagerConfig._

  var localView: mutable.HashMap[UUID, Peer] = mutable.HashMap[UUID, Peer]()

  var numberOfGroups = 1
  var currentGroup = 1

  def refreshGroup(newPeerList: List[Peer]): Unit = {
    newPeerList.foreach{ peer =>
      if(isSameGroupAsLocal(peer)) localView += (peer.uuid -> peer)
    }

    cleanLocalView
    tryMergeSplitGroup

    val oldGroup = currentGroup
    currentGroup = calculateGroupNumber(localPeer.position)

    if(oldGroup != currentGroup)
      logging.info(this, s"Changed from group $oldGroup to $currentGroup")
  }

  def cleanLocalView: Unit = {
    localView = localView.filter(entry => isSameGroupAsLocal(entry._2))
  }

  def isSameGroupAsLocal(peer: Peer): Boolean = {
    return calculateGroupNumber(peer.position) == currentGroup
  }

  def calculateGroupNumber(position: Double): Int = {
    return Math.ceil(position * numberOfGroups).toInt
  }

  def tryMergeSplitGroup: Unit = {
    if(localView.size < minGroupSize && numberOfGroups > 1) {
      numberOfGroups /= growingFactor
    } else {
      if (localView.size > maxGroupSize) {
        numberOfGroups *= growingFactor
      }
    }
  }
}