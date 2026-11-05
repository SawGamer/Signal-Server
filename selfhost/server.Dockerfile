# Chat-server image for self-hosting. Built by ./build.sh, which first compiles the server with Maven and
# stages its artifacts in selfhost/build/. Build context: the repository root.
FROM eclipse-temurin:27-jre

RUN useradd --system --uid 10002 --home-dir /opt/signal signal \
 && mkdir -p /etc/foundationdb \
 && chown signal /etc/foundationdb

# FoundationDB client library (downloaded and checksum-verified by the Maven build)
COPY selfhost/build/libfdb_c.so /usr/lib/libfdb_c.so
COPY selfhost/build/chat-server.jar selfhost/build/chat-server-tests.jar /opt/signal/
COPY --chmod=755 selfhost/server-entrypoint.sh /opt/signal/entrypoint.sh

USER signal
WORKDIR /opt/signal
# 8080: HTTP + websocket API   8081: admin/health   50051: gRPC ("omnibus" HTTP/2 front door)
EXPOSE 8080 8081 50051
ENTRYPOINT ["/opt/signal/entrypoint.sh"]
CMD ["server"]
