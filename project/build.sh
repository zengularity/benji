#! /bin/sh

set -e

S2_11="2.11.12"
S2_12="2.12.19"
S2_13="2.13.14"
S3="3.4.3"

# Play 2.6.x
export PLAY_VERSION=2.6.7 PLAY_JSON_VERSION=2.6.7 WS_VERSION=1.1.6
sbt clean ++${S2_11} makePom packageBin packageSrc packageDoc \
    ++${S2_12} makePom packageBin packageSrc packageDoc

# Play 2.7.x
export PLAY_VERSION=2.7.1 PLAY_JSON_VERSION=2.7.4 WS_VERSION=2.0.6
sbt ++${S2_12} makePom packageBin packageSrc packageDoc

# Play 2.8.x
export PLAY_VERSION=2.8.11 PLAY_JSON_VERSION=2.8.1 WS_VERSION=2.1.2
sbt ++${S2_13} makePom packageBin packageSrc packageDoc

# Scala 3
export PLAY_VERSION=2.9.5 PLAY_JSON_VERSION=2.10.6 WS_VERSION=2.2.0-M1+68-da80b259-SNAPSHOT
sbt ++${S3} makePom packageBin packageSrc packageDoc
