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

export _JAVA_OPTIONS="$JVM_OPTS"

SBT_OPTS="++$SCALA_VERSION"

# Scalariform check
echo "[info] Check the source format and backward compatibility"

sbt "$SBT_OPTS" ';error ;scalafixAll' || (
  cat >> /dev/stdout <<EOF
[ERROR] Scalafix check failed. To fix, run scalafixAll before pushing.
EOF
  false
)

sbt "$SBT_OPTS" ';error ;scalariformFormat ;test:scalariformFormat' > /dev/null
git diff --exit-code || (cat >> /dev/stdout <<EOF
[ERROR] Scalariform check failed, see differences above.
To fix, format your sources using sbt scalariformFormat test:scalariformFormat before submitting a pull request.
Additionally, please squash your commits (eg, use git commit --amend) if you're going to update this pull request.
EOF
  false
)

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
