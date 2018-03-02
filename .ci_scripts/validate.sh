#!/usr/bin/env bash

set -e

SCRIPT_DIR=`dirname $0 | sed -e "s|^\./|$PWD/|"`

cd "$SCRIPT_DIR/.."

JVM_MAX_MEM="2048M"
JVM_OPTS="-Xms$JVM_MAX_MEM -Xmx$JVM_MAX_MEM -XX:+CMSClassUnloadingEnabled -XX:ReservedCodeCacheSize=192m -XX:MetaspaceSize=512M -XX:MaxMetaspaceSize=512M"

SBT_OPTS="-Dsbt.scala.version=2.12.4"

sbt "$SBT_OPTS" testQuick "$TEST_OPTS"
