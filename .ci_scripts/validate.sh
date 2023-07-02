#!/usr/bin/env bash

set -e

SCRIPT_DIR=`dirname $0 | sed -e "s|^\./|$PWD/|"`

cd "$SCRIPT_DIR/.."

# Prepare S3 settings
cat > s3/src/test/resources/local.conf << EOF
ceph.s3.host="$CEPH_HOST"
ceph.s3.accessKey="$CEPH_ACCESSKEY"
ceph.s3.secretKey="$CEPH_SECRETKEY"
ceph.s3.protocol=https

aws.s3.host=s3.amazonaws.com
aws.s3.accessKey="$AWS_ACCESSKEY"
aws.s3.secretKey="$AWS_SECRETKEY"
aws.s3.protocol=https
aws.s3.region=us-east-1
EOF

# Prepare Google settings
cat > google/src/test/resources/local.conf << EOF
google.storage.projectId=$GOOGLE_PROJECTID
EOF

echo "$GOOGLE_CREDENTIALS" | base64 -d | gzip -dc > \
  google/src/test/resources/gcs-test.json

# Runtime settings
JVM_MAX_MEM="1G"

JAVA_MAJOR=`java -version 2>&1 | head -n 1 | cut -d '"' -f 2 | sed -e 's/\.[0-9a-zA-Z_]*$//'`

if [ "v$JAVA_MAJOR" = "v10.0" -o "v$JAVA_MAJOR" = "v11.0" ]; then
  JVM_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=70"
else
  JVM_MAX_MEM="1632M" # 1760M"
  JVM_OPTS="-Xmx$JVM_MAX_MEM -XX:ReservedCodeCacheSize=192m"
fi

export _JAVA_OPTIONS="$JVM_OPTS"

SBT_OPTS="++$SCALA_VERSION"

# Scalariform check
echo "[info] Check the source format and backward compatibility"

if [ ! "v$SCALA_VERSION" = "v2.11.12" ]; then
  sbt "$SBT_OPTS" ';error ;scalafixAll' || (
    cat >> /dev/stdout <<EOF
[ERROR] Scalafix check failed. To fix, run scalafixAll before pushing.
EOF
    false
  )
fi

sbt "$SBT_OPTS" ';error ;scalafmtCheckAll ;scalafmtSbtCheck'

# MiMa, Tests
SBT_CMD=";error ;test:compile ;doc ;mimaReportBinaryIssues; info"

if [ "x$SBT_TEST_PROJECTS" = "x" ]; then
  SBT_CMD="$SBT_CMD ;testQuick -- stopOnFail"
else
  for M in $SBT_TEST_PROJECTS; do
    SBT_CMD="$SBT_CMD ;$M/testQuick -- stopOnFail"
  done
fi

sbt "$SBT_OPTS" "$SBT_CMD"
