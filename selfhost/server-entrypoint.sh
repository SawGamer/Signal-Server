#!/bin/sh
# Entrypoint for the self-hosted chat-server image.
#   server            run the chat server (default)
#   create-tables     create DynamoDB tables (idempotent)
#   generate-secrets  write secrets.yml / public.yml / selfhost.env into /out
#   check-dynamic-config  validate /etc/signal/dynamic-config.yml
set -eu

# Same flags Signal uses in its own container image (service/pom.xml, jib configuration)
# --enable-native-access: libsignal and FoundationDB load native libraries (required by future JDKs).
NATIVE="--enable-native-access=ALL-UNNAMED"
JVM_FLAGS="$NATIVE -server -Djava.awt.headless=true -Djdk.nio.maxCachedBufferSize=262144 -Dlog4j2.formatMsgNoLookups=true \
  -Djdk.tls.server.newSessionTicketCount=0 -Djdk.tls.server.enableSessionTicketExtension=false \
  -XX:MaxRAMPercentage=75 ${JAVA_OPTS:-}"

if [ -n "${FDB_CLUSTER_FILE_CONTENTS:-}" ]; then
  echo "$FDB_CLUSTER_FILE_CONTENTS" > /etc/foundationdb/fdb.cluster
fi

case "${1:-server}" in
  server)
    # shellcheck disable=SC2086
    exec java $JVM_FLAGS -Dsecrets.bundle.filename=/etc/signal-secrets/secrets.yml \
      -jar /opt/signal/chat-server.jar server /etc/signal/server.yml
    ;;
  create-tables)
    exec java $NATIVE -cp /opt/signal/chat-server.jar:/opt/signal/chat-server-tests.jar \
      org.whispersystems.textsecuregcm.selfhost.CreateDynamoDbTables "${DYNAMODB_ENDPOINT:-http://dynamodb:8000}"
    ;;
  generate-secrets)
    exec java $NATIVE -cp /opt/signal/chat-server.jar org.whispersystems.textsecuregcm.selfhost.GenerateSecrets "${2:-/out}"
    ;;
  check-dynamic-config)
    exec java $NATIVE -Dsecrets.bundle.filename=/etc/signal-secrets/secrets.yml -jar /opt/signal/chat-server.jar \
      check-dynamic-config "${2:-/etc/signal/dynamic-config.yml}"
    ;;
  *)
    exec "$@"
    ;;
esac
