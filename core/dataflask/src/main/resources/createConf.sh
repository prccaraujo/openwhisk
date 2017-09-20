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
      hostname = \"${LOCAL_IP}\"
      port = ${FLASKS_PORT}
      bind-hostname = \"0.0.0.0\"
      bind-port = 50000
    }
 }
}" > $CONFIG_PATH/app${LOCAL_ID}.conf