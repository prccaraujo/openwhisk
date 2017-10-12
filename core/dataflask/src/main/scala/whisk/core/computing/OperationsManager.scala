package whisk.core.computing

import main.scala.communication.Messages.ComputingEnv
import main.scala.peers.Peer
import whisk.common.Logging

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import sys.process._

class OperationsManager(val localPeer: Peer,
                        val env: ComputingEnv.EnvVal,
                        val computable: Boolean = true) (implicit logging: Logging){

  var currentlyBookedMemory: Double = 0
  val approvedOperations: mutable.ListBuffer[ComputingOperation] = ListBuffer[ComputingOperation]()

  def bookOperationTemporarily(operation: ComputingOperation): Unit = {
    currentlyBookedMemory = operation.memory
    approvedOperations += operation
  }

  def canComputeOperation(operation: ComputingOperation) : Boolean = {
    return isComputable &&
      isSameEnv(operation) &&
      hasSuficientPower(operation)
  }

  //It accounts for an interval upon which it was assigned an operation but
  //it hasn't started computing it yet
  //TODO: Improve
  def hasSuficientPower(operation: ComputingOperation): Boolean = {
    return (getCurrentFreeMemory() - currentlyBookedMemory) > operation.memory
  }

  def isSameEnv(operation: ComputingOperation) : Boolean = {
    return (operation.env == env || operation.env == ComputingEnv.Any)
  }

  //TODO: Check if it returns a true value, or if the jvm needs the -Xm argument
  def getCurrentTotalMemory(): Double = {
    val command: String = ("free -m" #| "grep Mem" #| "awk {print$1}").!!

    command.toDouble
  }

  def getCurrentUsedMemory(): Double = {
    val command: String = ("free -m" #| "grep Mem" #| "awk {print$2}").!!

    command.toDouble
  }

  def getCurrentFreeMemory(): Double = {
    val command: String = ("free -m" #| "grep Mem" #| "awk {print$3}").!!

    command.toDouble
  }

  def isComputable: Boolean = computable
}