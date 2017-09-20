package main.scala.group

import java.util.UUID

import main.scala.peers.Peer

import scala.collection.mutable

class GroupManager(localPeer: Peer) {

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
    //println(s"Old group is $currentGroup")
    currentGroup = calculateGroupNumber(localPeer.position)
    println(s"New group is $currentGroup")
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