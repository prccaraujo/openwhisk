package whisk.core.computing

import main.scala.communication.Messages.ComputingEnv

//Encapsulates operation info
class ComputingOperation(private val _requestId: Long,
                         private val _dataTag: String,
                         private val _minExpectedMem: Double,
                         private val _env: ComputingEnv.EnvVal) extends Serializable {

  def id: Long = _requestId
  def tag: String = _dataTag
  def memory: Double = _minExpectedMem
  def env: ComputingEnv.EnvVal = _env

  override def equals(obj: scala.Any): Boolean = obj match {
    case obj: ComputingOperation => obj.isInstanceOf[ComputingOperation] && obj.hashCode() == this.hashCode()
    case _ => false
  }

  override def hashCode(): Int = {
    return id.hashCode()
  }

  override def clone(): ComputingOperation = {
    return new ComputingOperation(this.id, this.tag, this.memory, this.env)
  }
}
