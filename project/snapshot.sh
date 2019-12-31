#! /bin/sh

export PUBLISH_REPO_NAME="Sonatype Nexus Repository Manager"
export PUBLISH_REPO_ID="oss.sonatype.org"
export PUBLISH_REPO_URL="https://oss.sonatype.org/content/repositories/snapshots"

if [ "x$PUBLISH_USER" = "x" ]; then
  echo "User: "
  read PUBLISH_USER
fi

echo "Password: "
read PASS
export PUBLISH_PASS="$PASS"

sbt
