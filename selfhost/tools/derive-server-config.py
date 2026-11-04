#!/usr/bin/env python3
"""
Regenerates selfhost/config/server.yml from Signal's local test config.

Signal maintains service/src/test/resources/config/test.yml so the server can run locally; it always matches
the current code. After merging a new upstream release, run:

    python3 selfhost/tools/derive-server-config.py \
        service/src/test/resources/config/test.yml selfhost/config/server.yml

then review `git diff selfhost/config/server.yml`. The script stops with an error if an expected section has
moved, so upstream changes never get silently skipped.
"""
import re, sys
src = open(sys.argv[1]).read()
out = src

def sub(old, new, count=1):
    global out
    n = out.count(old)
    if n != count:
        raise SystemExit(f"expected {count} occurrence(s) of {old!r}, found {n}")
    out = out.replace(old, new)

def section(name, body):
    global out
    m = re.search(rf'^{re.escape(name)}:.*?(?=^\S|\Z)', out, re.S | re.M)
    if not m:
        raise SystemExit(f"section {name} not found")
    out = out[:m.start()] + body.rstrip('\n') + '\n\n' + out[m.end():]

sub("dynamoDbClient:\n  type: local\n",
    "dynamoDbClient:\n  type: endpoint            # DynamoDB-compatible database (DynamoDB Local) instead of AWS\n"
    "  endpoint: ${DYNAMODB_ENDPOINT:-http://dynamodb:8000}\n  region: local\n")
sub("tableName: apple_device_checks_test", "tableName: apple_device_check")
sub("tableName: apple_device_check_public_keys_test", "tableName: apple_device_check_key_constraint")
sub("tableName: remote_config_test", "tableName: remote_configs_test")

section("pagedSingleUseKEMPreKeyStore", """pagedSingleUseKEMPreKeyStore:
  bucket: prekeys
  region: us-east-1
  endpointOverride: ${S3_ENDPOINT:-http://s3:8333}""")

for cluster in ("cacheCluster", "pushSchedulerCluster", "rateLimitersCluster"):
    out, n = re.subn(rf'^({cluster}:[^\n]*\n)  type: local\n',
                     r'\1  configurationUri: ${REDIS_URI:-redis://redis:6379}\n', out, flags=re.M)
    if n != 1: raise SystemExit(f"{cluster} not replaced")
out, n = re.subn(r'^(pubsub:[^\n]*\n)  type: local\n', r'\1  uri: ${REDIS_URI:-redis://redis:6379}\n', out, flags=re.M)
if n != 1: raise SystemExit("pubsub not replaced")

section("cdn", """cdn:
  bucket: cdn                # profile avatars; served publicly via Caddy
  credentials:
    accessKeyId: secret://cdn.accessKey
    secretAccessKey: secret://cdn.accessSecret
  region: us-east-1
  endpointOverride: ${S3_ENDPOINT:-http://s3:8333}""")

sub('    "messages-0":\n      type: local\n',
    '    "messages-0":\n      clusterFileUrl: file:///etc/foundationdb/fdb.cluster\n')

out, n = re.subn(r'^(unidentifiedDelivery:\n  certificate: )\S+', r'\1${UNIDENTIFIED_DELIVERY_CERTIFICATE}', out, flags=re.M)
if n != 1: raise SystemExit("unidentifiedDelivery not replaced")
out, n = re.subn(r'^(groupsZkConfig:\n  serverPublic: )\S+', r'\1${GROUPS_ZK_SERVER_PUBLIC}', out, flags=re.M)
if n != 1: raise SystemExit("groupsZkConfig not replaced")

section("registrationService", """registrationService:
  type: selfHosted           # ../registration-service (SMS/voice verification)
  host: ${REGISTRATION_HOST:-registration}
  port: 9443
  caCertificate: "${REGISTRATION_CA_CERT}"
  sharedSecret: secret://registrationService.sharedSecret
  collationKeySalt: secret://registrationService.collationKeySalt""")

section("dynamicConfig", """dynamicConfig:
  type: file                 # edit this file on the host; reloaded within 10s
  path: /etc/signal/dynamic-config.yml
  refreshInterval: PT10S""")

section("asnTable", """asnTable:
  type: file
  path: /etc/signal/asn.tsv.gz      # gzipped TSV (IP range -> ASN); empty is fine
  refreshInterval: PT1H""")

sub("  externalClients:\n    type: stub\n", "  externalClients:\n    type: disabled           # no MobileCoin currency lookups\n")
sub("  pubSubPublisher:\n    type: stub\n", "  pubSubPublisher:\n    type: disabled\n", count=2)

section("grpc", """grpc:
  bindAddress: 0.0.0.0       # "omnibus" HTTP/2 front door: gRPC + websocket routing
  port: 50051
  websocketPort: 8080
  h2c: true                  # TLS is terminated by Caddy""")

section("openTelemetry", """openTelemetry:
  enabled: false
  environment: selfhost
  shutdownWaitDuration: PT0S
  url: http://127.0.0.1:4318/""")

section("attachments", """attachments:
  maxAttachmentUploadSizeInBytes: 104857600       # 100 MiB
  maxMessageBackupUploadSizeInBytes: 107374182400 # 100 GiB""")

section("registrationWebAuthn", """registrationWebAuthn:
  relyingPartyId: ${DOMAIN:-localhost}
  origins:
    - https://${DOMAIN:-localhost}
  challengeTtl: PT2M
  userHandleBlindingSecret: secret://registrationWebAuthn.userHandleBlindingSecret""")

sub("  bundleId: com.example.textsecuregcm\n", "  bundleId: ${IOS_BUNDLE_ID:-com.example.messenger}\n")

header = """# Signal-Server configuration for SELF-HOSTING (docker compose in this directory).
#
# Derived from service/src/test/resources/config/test.yml, the config Signal uses to run the server locally,
# with every AWS/Google dependency swapped for a self-hosted equivalent:
#   DynamoDB -> DynamoDB Local          S3 -> SeaweedFS            Redis cluster -> single-node Redis cluster
#   FoundationDB -> single-node FDB     dynamic config -> local file
#   registration service -> ../registration-service (Twilio)
#
# Dollar-brace variables come from .env (written by setup.sh); secret:// values from secrets/secrets.yml.
# Sections for features not run yet (donations, payments, contact discovery, PIN backup, key transparency,
# Cloudflare TURN, Apple/Google billing) keep Signal's inert test values so the server starts.

"""
open(sys.argv[2], 'w').write(header + out)
print("ok", len(out.splitlines()), "lines")
