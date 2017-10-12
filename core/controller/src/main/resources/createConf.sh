#!/usr/bin/env bash

######################################
# BUILD NEW CYCLON CONFIG FILE
######################################

# Receives arguments through env vars and outputs configuration file

echo "akka {
  actor {
    provider = \"akka.remote.RemoteActorRefProvider\"
    warn-about-java-serializer-usage = false
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
  http {
    parsing {
        # The limits for the various parts of the HTTP message parser.
        max-method-length = 100M
        max-content-length = infinite
     }
  }
}" > $CONFIG_PATH/controller-actor-system.conf