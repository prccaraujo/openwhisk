package main.scala.peers

import java.util.UUID

import main.scala.communication.Messages.ComputingEnv

import scala.util.Random

// Encapsulates Node info
class DFPeer(val id: String,
             private val _ip: String,
             private val _port: Int,
             private var _age: Int = 0,
             private val _position: Double = Random.nextDouble(),
             private val _env: ComputingEnv.EnvVal = ComputingEnv.Cloud) extends Peer with Serializable {

  val uuid: UUID = UUID.nameUUIDFromBytes(id.getBytes())

  def age_= (new_age: Int): Unit = _age = new_age
  def ip: String = _ip
  def name: String = id
  def port: Int = _port
  def age: Int = _age
  def position: Double = _position
  def env: ComputingEnv.EnvVal = _env

  def increaseAgeByOne = _age += 1

  override def equals(obj: scala.Any): Boolean = obj match {
    case obj: DFPeer => obj.isInstanceOf[DFPeer] && obj.hashCode() == this.hashCode()
    case _ => false
  }

  override def hashCode(): Int = {
    return id.hashCode()
  }

  override def clone(): DFPeer = {
    return new DFPeer(this.id, this._ip, this._port, this._age, this._position)
  }
}