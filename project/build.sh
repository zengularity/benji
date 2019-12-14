#! /bin/sh

set -e

S2_11="2.11.12"
S2_12="2.12.10"
S2_13="2.13.1"

# Scala 2.11
export PLAY_VERSION=2.6.7 PLAY_JSON_VERSION=2.6.7 WS_VERSION=1.1.6
sbt clean ++${S2_11} publishLocal

# Scala 2.12
export PLAY_VERSION=2.6.7 PLAY_JSON_VERSION=2.6.7 WS_VERSION=1.1.6
sbt ++${S2_12} publishLocal

export PLAY_VERSION=2.7.1 PLAY_JSON_VERSION=2.7.4 WS_VERSION=2.0.6
sbt ++${S2_12} publishLocal

# Scala 2.13
export PLAY_VERSION=2.7.3 PLAY_JSON_VERSION=2.7.4 WS_VERSION=2.0.6
sbt ++${S2_13} publishLocal
