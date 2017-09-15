package main.scala.peers

import java.util.UUID

trait Peer {
  def name: String
  def uuid: UUID
  def age: Int
  def position: Double
  def ip: String
  def port: Int
  def capacity: Int
}