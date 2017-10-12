package main.scala.peers

import java.util.UUID

import main.scala.communication.Messages.ComputingEnv

trait Peer {
  def name: String
  def uuid: UUID
  def age: Int
  def position: Double
  def ip: String
  def port: Int
  def env: ComputingEnv.EnvVal
}