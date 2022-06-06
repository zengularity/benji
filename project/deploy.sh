#! /bin/sh

REPO="https://oss.sonatype.org/service/local/staging/deploy/maven2/"

if [ $# -lt 2 ]; then
    echo "Usage $0 version gpg-key"
    exit 1
fi

VERSION="$1"
KEY="$2"

echo "Password: "
read -s PASS

function deploy {
  BASE="$1"
  POM="$BASE.pom"
  FILES="$BASE.jar $BASE-javadoc.jar:javadoc $BASE-sources.jar:sources"

  for FILE in $FILES; do
    JAR=`echo "$FILE" | cut -d ':' -f 1`
    CLASSIFIER=`echo "$FILE" | cut -d ':' -f 2`

    if [ ! "$CLASSIFIER" = "$JAR" ]; then
      ARG="-Dclassifier=$CLASSIFIER"
    else
      ARG=""
    fi

    expect << EOF
set timeout 300
log_user 0
spawn mvn gpg:sign-and-deploy-file -Dkeyname=$KEY -Dpassphrase=$PASS -DpomFile=$POM -Dfile=$JAR $ARG -Durl=$REPO -DrepositoryId=sonatype-nexus-staging
log_user 1
expect "BUILD SUCCESS"
expect eof
EOF
  done
}

if [ "x$SCALA_MODULES" = "x" ]; then
    SCALA_MODULES="core:benji-core s3:benji-s3 google:benji-google vfs:benji-vfs play:benji-play"
fi

SCALA_VERSIONS="2.11 2.12 2.13 3.1.3-RC2"
PLAY_VERSIONS="26 27 28"

BASES=""

for V in $SCALA_VERSIONS; do
    MV=`echo "$V" | sed -e 's/^3.*/3/'`

    for M in $SCALA_MODULES; do
        B=`echo "$M" | cut -d ':' -f 1`
        SCALA_DIR="$B/target/scala-$V"
        N=`echo "$M" | cut -d ':' -f 2`
        BASE_NAME="$SCALA_DIR/${N}_$MV"
        FOUND=0

        if [ -r "${BASE_NAME}-$VERSION.pom" ]; then
            BASES="$BASES ${BASE_NAME}-$VERSION"
            FOUND=1
        fi

        for PV in $PLAY_VERSIONS; do
            if [ -r "${BASE_NAME}-${VERSION}-play$PV.pom" ]; then
                BASES="$BASES ${BASE_NAME}-${VERSION}-play$PV"
                FOUND=`expr $FOUND + 1`
            fi
        done

        if [ $FOUND -eq 0 ]; then
            echo "Skip Scala version $V for $M"
        fi
    done
done

for B in $BASES; do
  deploy "$B"
done
