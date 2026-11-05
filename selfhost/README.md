# Self-hosting

This directory runs Signal-Server on **one Linux machine with Docker**, without AWS or Google Cloud. It's the
backend for your own fork of the Signal apps. Your apps and server form a separate network: they can't talk
to Signal's users or servers.

```
                    ┌──────────────── docker compose ─────────────────────────────────────┐
 apps ── HTTPS ──▶  │ caddy ─▶ chat-server ─┬─ redis        (single-node cluster)         │
 (chat.DOMAIN,      │   │                   ├─ dynamodb     (DynamoDB Local: accounts, keys)│
  cdn.DOMAIN)       │   └──────▶ s3 ◀───────┼─ foundationdb (message queues)              │
                    │       (SeaweedFS)     └─ registration ──▶ Twilio (SMS / voice codes) │
                    └──────────────────────────────────────────────────────────────────────┘
```

| Signal production | Here |
|---|---|
| AWS DynamoDB | DynamoDB Local (`dynamoDbClient.type: endpoint`) |
| AWS S3 / Cloudflare R2 | SeaweedFS |
| ElastiCache Redis clusters | one Redis node in cluster mode |
| FoundationDB clusters | one FoundationDB node |
| AWS AppConfig / S3 dynamic config | `config/dynamic-config.yml` (`type: file`, hot-reloaded) |
| Closed-source registration service | [`../registration-service`](../registration-service) + Twilio |

The self-hosting code is in `service/src/main/java/org/whispersystems/textsecuregcm/selfhost/`. Each piece is
a pluggable config type, so upstream Signal code is unchanged and future Signal releases merge cleanly.

## Requirements

- Linux server with Docker Engine 24+ and the Compose plugin; 4 GB RAM minimum, 8 GB recommended.
- A domain with DNS `A`/`AAAA` records for `chat.<domain>` and `cdn.<domain>` pointing at the server.
- Ports 80 and 443 open (Caddy obtains Let's Encrypt certificates automatically).
- For real SMS: a Twilio account (see `../registration-service/README.md`).

The build needs Signal's `libsignal-server` from `build-artifacts.signal.org`. A normal server can reach it;
Maven downloads it automatically.

## First start

```bash
git clone https://github.com/SawGamer/Signal-Server.git && cd Signal-Server
```

```bash
./selfhost/build.sh
```

```bash
./selfhost/setup.sh --domain example.com --app-name "My Messenger"
```

Then edit `selfhost/.env` (Twilio credentials, `SMS_SENDER=twilio`) and start:

```bash
cd selfhost && docker compose --profile public up -d
```

```bash
docker compose logs -f chat-server
```

The first start creates the database tables, storage buckets, Redis cluster and FoundationDB database.
`setup.sh` writes these files, which you must keep:

| File | What it is |
|---|---|
| `secrets/secrets.yml` | server private keys. **Back up; never commit; never regenerate.** Losing them means every user must re-register and every app must be rebuilt. |
| `secrets/public.yml` | public values your **apps** must embed (sealed-sender trust root, zero-knowledge public parameters) |
| `.env` | deployment settings and generated values |

## Check that it works

`e2e/EndToEndTest.java` registers two accounts through phone verification, sends a Signal-protocol encrypted
message from one to the other, and decrypts it on the receiving side.

Run it against the local port with two test numbers. First set this in `.env`:

```
TEST_NUMBERS=+15555550100=123456,+15555550101=654321
```

Then recreate the registration container and run the test:

```bash
docker compose up -d registration
```

```bash
docker run --rm --network host -v "$PWD/build:/b:ro" -v "$PWD/e2e:/e2e:ro" eclipse-temurin:27-jdk \
  java --enable-native-access=ALL-UNNAMED -cp /b/chat-server.jar /e2e/EndToEndTest.java http://127.0.0.1:8080 +15555550100=123456 +15555550101=654321
```

## Operating it

- **Change feature flags or rate limits:** edit `config/dynamic-config.yml`; it's picked up within 10 seconds.
- **Update to a new Signal release:**
  1. Merge the upstream tag.
  2. Regenerate the server config with
     `python3 selfhost/tools/derive-server-config.py service/src/test/resources/config/test.yml selfhost/config/server.yml`
     and review the diff.
  3. Run `./selfhost/build.sh`, then `docker compose up -d`.
- **Backups:** the Docker volumes `dynamodb-data` (accounts, keys, profiles), `fdb-data` (undelivered
  messages), `s3-data` (avatars) and `redis-data`, plus `secrets/` and `.env`.

## Limitations: read before a public launch

- **No real captcha.** Without Signal's private spam-filter module, the server accepts any captcha token.
  Registration abuse (SMS fraud) can then cost you real money. Before launch, add a captcha provider such as
  hCaptcha through the `SpamFilter` extension point (`service/.../spam/SpamFilter.java`), and turn on Twilio's
  fraud protection.
- **Push notifications** need your own Firebase (Android) and APNs (iOS) credentials in `secrets/secrets.yml`
  (`fcm.credentials`, `apn.*`). Placeholders are generated so the server starts.
- **Not yet self-hosted** (the apps will show errors for these features until added):
  - attachments (cdn2/cdn3);
  - contact discovery (CDSI) and PIN backup (SVR2/SVRB), which need Intel SGX hardware;
  - storage service (cross-device settings sync);
  - group calling (SFU), TURN servers, key transparency;
  - payments and donations.
- **Scale:** DynamoDB Local and single-node Redis/FoundationDB suit a few thousand users. Beyond that, move to
  managed DynamoDB or another DynamoDB-compatible database, and to real clusters.
