#! /bin/sh

# Scala 2.11
export PLAY_VERSION=2.6.7 WS_VERSION=1.1.6
sbt clean ++2.11.12 publishLocal

# Scala 2.12
export PLAY_VERSION=2.6.7 WS_VERSION=1.1.6
sbt ++2.12.8 publishLocal

export PLAY_VERSION=2.7.1 WS_VERSION=2.0.6
sbt ++2.12.8 publishLocal

# Scala 2.13
export PLAY_VERSION=2.7.3 PLAY_JSON_VERSION=2.7.4 WS_VERSION=2.0.6
sbt ++2.13.0 publishLocal
