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
JVM_MAX_MEM="2048M"
JVM_OPTS="-Xms$JVM_MAX_MEM -Xmx$JVM_MAX_MEM -XX:+CMSClassUnloadingEnabled -XX:ReservedCodeCacheSize=192m -XX:MetaspaceSize=512M -XX:MaxMetaspaceSize=512M"

SBT_OPTS="-Dsbt.scala.version=2.12.4"

sbt "$SBT_OPTS" testQuick
