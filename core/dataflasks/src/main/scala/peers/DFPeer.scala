package main.scala.peers

import java.util.UUID

import scala.util.Random

// Encapsulates Node info
class DFPeer(val id: String,
             private val _ip: String,
             private val _port: Int,
             private val _capacity: Int,
             private var _age: Int = 0,
             private val _position: Double = Random.nextDouble()) extends Peer with Serializable {

  //def apply(uuid: UUID, ip: String, port: Int, age: Int, position: Double): Unit = new peers.DFPeer(uuid, ip, port, age, position)

  val uuid: UUID = UUID.nameUUIDFromBytes(id.getBytes())

  //Getters and setters
  def age_= (new_age: Int): Unit = _age = new_age
  def ip: String = _ip
  def name: String = id
  def port: Int = _port
  def age: Int = _age
  def position: Double = _position
  def capacity: Int = _capacity

  def increaseAgeByOne = _age += 1

  override def equals(obj: scala.Any): Boolean = obj match {
    case obj: DFPeer => obj.isInstanceOf[DFPeer] && obj.hashCode() == this.hashCode()
    case _ => false
  }

  //Peers are compared by their id
  override def hashCode(): Int = {
    return id.hashCode()
  }

  override def clone(): DFPeer = {
    return new DFPeer(this.id, this._ip, this._port, this._capacity, this._age, this._position)
  }
}