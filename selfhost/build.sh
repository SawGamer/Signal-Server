#!/usr/bin/env bash
# Builds the two Docker images used by docker-compose.yml:
#   selfhost/chat-server            (Signal-Server + self-hosting additions)
#   selfhost/registration-service   (SMS/voice verification)
#
# Maven runs on the host if Java 27 is installed, otherwise inside a temporary JDK container, so a VPS with only
# Docker can build too. Set SKIP_MAVEN=1 to reuse artifacts from a previous build.
set -euo pipefail

cd "$(dirname "$0")/.."
repo=$PWD
stage=selfhost/build

maven() {
  if java -version 2>&1 | grep -q 'version "27'; then
    ./mvnw -B "$@"
  else
    echo "Java 27 not found on host; running Maven in a container"
    docker run --rm -v "$repo":/src -v "${HOME}/.m2":/root/.m2 -w /src eclipse-temurin:27-jdk ./mvnw -B "$@"
  fi
}

if [ "${SKIP_MAVEN:-0}" != 1 ]; then
  # -DskipTests still compiles the tests, which we need: the tests jar provides the DynamoDB table definitions.
  # exclude-spam-filter (as in Signal's CI) builds the self-contained jar and fetches the FoundationDB client library.
  maven -DskipTests -Pexclude-spam-filter -pl service -am clean package
  maven -f registration-service/pom.xml -DskipTests package
fi

mkdir -p "$stage"
server_jar=$(ls -t service/target/TextSecureServer-*.jar | grep -v -e '-tests\.jar$' | head -1)
tests_jar=$(ls -t service/target/TextSecureServer-*-tests.jar | head -1)
cp "$server_jar" "$stage/chat-server.jar"
cp "$tests_jar" "$stage/chat-server-tests.jar"
cp service/target/jib-extra/usr/lib/libfdb_c.so "$stage/libfdb_c.so"
echo "Staged $(basename "$server_jar") ($(du -h "$stage/chat-server.jar" | cut -f1))"

docker build -t selfhost/chat-server:latest -f selfhost/server.Dockerfile .
docker build -t selfhost/registration-service:latest registration-service

echo "Built selfhost/chat-server:latest and selfhost/registration-service:latest"
