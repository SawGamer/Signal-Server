# Registration service (self-hosted)

Signal's chat server does not send verification SMS itself. It calls a separate, closed-source
**registration service** over gRPC. This module is an open replacement that implements the same
contract ([`service/src/main/proto/RegistrationService.proto`](../service/src/main/proto/RegistrationService.proto)),
so the chat server needs only a small client factory that connects with the shared secret below. That
factory is not added yet: it can only be compiled once the server's `libsignal` dependency is available.

It handles:

- Creating registration sessions, rate-limited per phone number (10 per hour).
- Sending 6-digit codes by **SMS** or **voice call** through Twilio, with growing resend delays
  (60s → 2m → 5m → 10m, at most 5 SMS and 3 calls per session). As with Signal, a call is only
  offered after an SMS attempt.
- Checking codes in constant time: 3 free guesses, then exponential backoff, and at most 5 wrong
  guesses per code. Codes expire after 10 minutes and sessions after 1 hour.
- **Test numbers** with fixed codes. They never send an SMS, which is useful for app-store
  review accounts and automated tests.

Only the chat server may call it: every request must carry `authorization: Bearer <SHARED_SECRET>`,
over TLS.

## Configuration (environment variables)

| Variable | Required | Meaning |
|---|---|---|
| `TLS_CERT_CHAIN` | yes | PEM certificate the service presents (the chat server must trust its CA) |
| `TLS_PRIVATE_KEY` | yes | PEM PKCS#8 private key for that certificate |
| `SHARED_SECRET` | yes | ≥32 chars; the chat server sends it as a bearer token |
| `PORT` | no | default `9443` |
| `SENDER` | no | `log` (default, **development only**: the code is printed to the log) or `twilio` |
| `APP_NAME` | no | your app's name, used in the SMS text |
| `SMS_TEMPLATE` | no | default `Your {app} code: {code}\n\nDo not share this code with anyone.` |
| `TEST_NUMBERS` | no | e.g. `+15555550100=123456,+15555550101=654321` |
| `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN` | with `twilio` | from the Twilio console |
| `TWILIO_MESSAGING_SERVICE_SID` or `TWILIO_FROM_NUMBER` | with `twilio` | sender for SMS (a messaging service is recommended for international delivery) |
| `TWILIO_FROM_NUMBER` | for voice | a voice-capable Twilio number |

In Twilio, enable **Geo permissions** for every country you want to serve, for both SMS and Voice.
Otherwise those numbers get a "no sender available" error.

## Build and run

```bash
./mvnw -f registration-service/pom.xml -Djgitver.skip=true package
```

```bash
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 3650 \
  -subj "/CN=registration" -addext "subjectAltName=DNS:registration" \
  -keyout registration-key.pem -out registration-cert.pem
```

```bash
TLS_CERT_CHAIN=registration-cert.pem TLS_PRIVATE_KEY=registration-key.pem \
SHARED_SECRET=$(openssl rand -hex 32) SENDER=log \
java -jar registration-service/target/registration-service.jar
```

In Docker (`registration-service/Dockerfile`) the service runs as the unprivileged uid `10001`.
Make the private key readable by that user only:

```bash
sudo chown 10001 registration-key.pem && sudo chmod 400 registration-key.pem
```

## Known limitations

- Sessions are kept in memory. A restart only forces users who are mid-registration to tap
  "resend", but you cannot run several replicas until storage moves to Redis.
- Fraud and SMS-pumping protection is limited to the rate limits above. Before a public launch,
  turn on Twilio's *SMS Pumping Protection* and restrict Geo permissions to the countries you
  actually serve.
