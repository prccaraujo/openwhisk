#!/usr/bin/env bash

######################################
# BUILD NEW CYCLON CONFIG FILE
######################################

# Receives arguments through env vars and outputs configuration file

echo "akka {
  actor {
    provider = \"akka.remote.RemoteActorRefProvider\"
  }
  remote {
    enabled-transports = [\"akka.remote.netty.tcp\"]
    netty.tcp {
      hostname = \"${CONTROLLER_IP}\"
      port = ${CONTROLLER_PORT}
      bind-hostname = \"${CONTROLLER_BIND_IP}\"
      bind-port = ${CONTROLLER_BIND_PORT}
    }
 }
}" > $CONFIG_PATH/controller-actor-system.conf