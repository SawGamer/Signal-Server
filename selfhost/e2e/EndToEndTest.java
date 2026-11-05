/*
 * Self-hosting end-to-end check. SPDX-License-Identifier: AGPL-3.0-only
 *
 * Exercises a running self-hosted stack the way a real app does:
 *   1. registers two accounts through phone verification (registration service, test numbers with fixed codes)
 *   2. Alice fetches Bob's pre-keys and encrypts a message with the Signal protocol (libsignal)
 *   3. Alice sends it; Bob receives it over his authenticated websocket and decrypts it
 *
 * Run (needs a JDK 27; the chat-server jar provides libsignal and the server's API types):
 *   java -cp chat-server.jar EndToEndTest.java http://localhost:8080 +15555550100=123456 +15555550101=654321
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.SessionBuilder;
import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyType;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import org.signal.libsignal.protocol.message.PreKeySignalMessage;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore;
import org.signal.libsignal.protocol.util.KeyHelper;
import org.whispersystems.textsecuregcm.entities.AccountAttributes;
import org.whispersystems.textsecuregcm.entities.AccountIdentityResponse;
import org.whispersystems.textsecuregcm.entities.CreateVerificationSessionRequest;
import org.whispersystems.textsecuregcm.entities.DeviceActivationRequest;
import org.whispersystems.textsecuregcm.entities.ECSignedPreKey;
import org.whispersystems.textsecuregcm.entities.IncomingMessage;
import org.whispersystems.textsecuregcm.entities.IncomingMessageList;
import org.whispersystems.textsecuregcm.entities.KEMSignedPreKey;
import org.whispersystems.textsecuregcm.entities.MessageProtos;
import org.whispersystems.textsecuregcm.entities.PreKeyResponse;
import org.whispersystems.textsecuregcm.entities.PreKeyResponseItem;
import org.whispersystems.textsecuregcm.entities.RegistrationRequest;
import org.whispersystems.textsecuregcm.entities.SubmitVerificationCodeRequest;
import org.whispersystems.textsecuregcm.entities.UpdateVerificationSessionRequest;
import org.whispersystems.textsecuregcm.entities.VerificationCodeRequest;
import org.whispersystems.textsecuregcm.entities.VerificationSessionResponse;
import org.whispersystems.textsecuregcm.storage.DeviceCapability;
import org.whispersystems.textsecuregcm.util.SystemMapper;
import org.whispersystems.websocket.messages.protobuf.ProtobufWebSocketMessageFactory;
import org.whispersystems.websocket.messages.WebSocketMessage;

public class EndToEndTest {

  private static final ObjectMapper JSON = SystemMapper.jsonMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int SIGNED_PRE_KEY_ID = 1;
  private static final int KYBER_PRE_KEY_ID = 2;

  /** A registered account plus the private key material only its device would hold. */
  record User(String number, String password, UUID aci, int registrationId, InMemorySignalProtocolStore store) {
  }

  static String base;

  public static void main(final String[] args) throws Exception {
    base = args[0].replaceAll("/$", "");
    final String[] alice = args[1].split("=");
    final String[] bob = args[2].split("=");

    final User a = register(alice[0], alice[1]);
    System.out.println("[ok] registered Alice  " + a.number() + "  aci=" + a.aci());
    final User b = register(bob[0], bob[1]);
    System.out.println("[ok] registered Bob    " + b.number() + "  aci=" + b.aci());

    // Alice: fetch Bob's pre-key bundle and start a Signal-protocol session
    final PreKeyResponse keys = getJson("/v2/keys/" + b.aci() + "/1", a, PreKeyResponse.class);
    final PreKeyResponseItem device = keys.getDevice((byte) 1);
    final PreKeyBundle bundle = new PreKeyBundle(
        device.getRegistrationId(), 1,
        PreKeyBundle.NULL_PRE_KEY_ID, null,
        (int) device.getSignedPreKey().keyId(), device.getSignedPreKey().publicKey(), device.getSignedPreKey().signature(),
        keys.getIdentityKey(),
        (int) device.getPqPreKey().keyId(), device.getPqPreKey().publicKey(), device.getPqPreKey().signature());
    final SignalProtocolAddress aliceAddress = new SignalProtocolAddress(a.aci().toString(), 1);
    final SignalProtocolAddress bobAddress = new SignalProtocolAddress(b.aci().toString(), 1);
    // note: SessionBuilder takes (remote, local) while SessionCipher takes (local, remote)
    new SessionBuilder(a.store(), bobAddress, aliceAddress).process(bundle);
    final String plaintext = "Hello Bob, this is end-to-end encrypted. " + UUID.randomUUID();
    final CiphertextMessage ciphertext = new SessionCipher(a.store(), aliceAddress, bobAddress)
        .encrypt(plaintext.getBytes(StandardCharsets.UTF_8));
    System.out.println("[ok] Alice encrypted a message (" + ciphertext.serialize().length + " bytes, type "
        + ciphertext.getType() + ")");

    // Alice: send it
    final IncomingMessageList outgoing = new IncomingMessageList(List.of(new IncomingMessage(
        MessageProtos.Envelope.Type.PREKEY_BUNDLE_VALUE, (byte) 1, b.registrationId(), ciphertext.serialize())),
        false, true, System.currentTimeMillis());
    send("PUT", "/v1/messages/" + b.aci(), a, outgoing, 200);
    System.out.println("[ok] server accepted the message");

    // Bob: connect, receive, decrypt
    final MessageProtos.Envelope envelope = receiveOne(b);
    final SessionCipher bobCipher = new SessionCipher(b.store(), bobAddress, aliceAddress);
    final String decrypted = new String(
        bobCipher.decrypt(new PreKeySignalMessage(envelope.getContent().toByteArray())), StandardCharsets.UTF_8);
    System.out.println("[ok] Bob received and decrypted: \"" + decrypted + "\"");

    if (!decrypted.equals(plaintext)) {
      throw new AssertionError("decrypted text does not match");
    }
    System.out.println("\nEND-TO-END TEST PASSED");
  }

  static User register(final String number, final String code) throws Exception {
    // 1. verification session; the open-source server always asks for a captcha (or push challenge)
    VerificationSessionResponse session = send("POST", "/v1/verification/session", null,
        new CreateVerificationSessionRequest(number,
            new UpdateVerificationSessionRequest(null, null, null, null, null, null)), 200,
        VerificationSessionResponse.class);
    if (session.requestedInformation().stream().anyMatch(i -> i.name().equals("CAPTCHA"))) {
      // Without Signal's private spam filter the server uses a no-op captcha checker; any token with site key
      // "noop" passes. A public deployment must plug in a real captcha provider.
      session = send("PATCH", "/v1/verification/session/" + session.id(), null,
          new UpdateVerificationSessionRequest(null, null, null, "noop.noop.registration.selfhost-e2e", null, null),
          200, VerificationSessionResponse.class);
    }

    // 2. SMS code (a configured test number: never sent, fixed code)
    session = send("POST", "/v1/verification/session/" + session.id() + "/code", null,
        new VerificationCodeRequest(VerificationCodeRequest.Transport.SMS, "android-2021-03"), 200,
        VerificationSessionResponse.class);
    session = send("PUT", "/v1/verification/session/" + session.id() + "/code", null,
        new SubmitVerificationCodeRequest(code), 200, VerificationSessionResponse.class);
    if (!session.verified()) {
      throw new AssertionError("phone number not verified");
    }

    // 3. register with fresh identity and pre-keys
    final IdentityKeyPair aciIdentity = IdentityKeyPair.generate();
    final IdentityKeyPair pniIdentity = IdentityKeyPair.generate();
    final int registrationId = KeyHelper.generateRegistrationId(false);
    final int pniRegistrationId = KeyHelper.generateRegistrationId(false);
    final InMemorySignalProtocolStore store = new InMemorySignalProtocolStore(aciIdentity, registrationId);

    final ECKeyPair signedPreKey = ECKeyPair.generate();
    final byte[] signedPreKeySignature = aciIdentity.getPrivateKey().calculateSignature(signedPreKey.getPublicKey().serialize());
    store.storeSignedPreKey(SIGNED_PRE_KEY_ID,
        new SignedPreKeyRecord(SIGNED_PRE_KEY_ID, System.currentTimeMillis(), signedPreKey, signedPreKeySignature));

    final KEMKeyPair kyberPreKey = KEMKeyPair.generate(KEMKeyType.KYBER_1024);
    final byte[] kyberSignature = aciIdentity.getPrivateKey().calculateSignature(kyberPreKey.getPublicKey().serialize());
    store.storeKyberPreKey(KYBER_PRE_KEY_ID,
        new KyberPreKeyRecord(KYBER_PRE_KEY_ID, System.currentTimeMillis(), kyberPreKey, kyberSignature));

    final String password = Base64.getEncoder().encodeToString(bytes(18));
    final AccountAttributes attributes = new AccountAttributes(true, registrationId, pniRegistrationId,
        new byte[0], null, true, DeviceCapability.CAPABILITIES_REQUIRED_FOR_NEW_DEVICES, null)
        .setUnidentifiedAccessKey(bytes(16));

    final RegistrationRequest request = new RegistrationRequest(session.id(), null, null, null, null, attributes, true,
        aciIdentity.getPublicKey(), pniIdentity.getPublicKey(),
        new DeviceActivationRequest(
            new ECSignedPreKey(SIGNED_PRE_KEY_ID, signedPreKey.getPublicKey(), signedPreKeySignature),
            Optional.of(signedEcPreKey(SIGNED_PRE_KEY_ID, pniIdentity)),
            new KEMSignedPreKey(KYBER_PRE_KEY_ID, kyberPreKey.getPublicKey(), kyberSignature),
            Optional.of(signedKemPreKey(KYBER_PRE_KEY_ID, pniIdentity)),
            Optional.empty(), Optional.empty()));

    final AccountIdentityResponse response = sendBasic("POST", "/v1/registration", number, password, request, 200,
        AccountIdentityResponse.class);
    return new User(number, password, response.uuid(), registrationId, store);
  }

  static ECSignedPreKey signedEcPreKey(final int id, final IdentityKeyPair identity) {
    final ECKeyPair key = ECKeyPair.generate();
    return new ECSignedPreKey(id, key.getPublicKey(), identity.getPrivateKey().calculateSignature(key.getPublicKey().serialize()));
  }

  static KEMSignedPreKey signedKemPreKey(final int id, final IdentityKeyPair identity) {
    final KEMKeyPair key = KEMKeyPair.generate(KEMKeyType.KYBER_1024);
    return new KEMSignedPreKey(id, key.getPublicKey(), identity.getPrivateKey().calculateSignature(key.getPublicKey().serialize()));
  }

  // --- websocket ---------------------------------------------------------------------------------------------

  static MessageProtos.Envelope receiveOne(final User user) throws Exception {
    final ProtobufWebSocketMessageFactory factory = new ProtobufWebSocketMessageFactory();
    final CompletableFuture<MessageProtos.Envelope> received = new CompletableFuture<>();

    final WebSocket.Listener listener = new WebSocket.Listener() {
      private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();

      @Override
      public CompletionStage<?> onBinary(final WebSocket ws, final ByteBuffer data, final boolean last) {
        final byte[] chunk = new byte[data.remaining()];
        data.get(chunk);
        buffer.writeBytes(chunk);
        if (last) {
          try {
            final WebSocketMessage message = factory.parseMessage(ByteBuffer.wrap(buffer.toByteArray()));
            buffer.reset();
            if (message.getType() == WebSocketMessage.Type.REQUEST_MESSAGE
                && message.getRequestMessage().getPath().equals("/api/v1/message")) {
              final MessageProtos.Envelope envelope =
                  MessageProtos.Envelope.parseFrom(message.getRequestMessage().getBody().orElseThrow());
              ws.sendBinary(ByteBuffer.wrap(factory.createResponse(message.getRequestMessage().getRequestId(), 200,
                  "OK", List.of(), Optional.empty()).toByteArray()), true);
              received.complete(envelope);
            }
          } catch (final Exception e) {
            received.completeExceptionally(e);
          }
        }
        ws.request(1);
        return null;
      }
    };

    final String wsBase = base.replaceFirst("^http", "ws");
    final WebSocket ws = HTTP.newWebSocketBuilder()
        .header("Authorization", basic(user.aci() + ".1", user.password()))
        .buildAsync(URI.create(wsBase + "/v1/websocket/"), listener)
        .get(10, TimeUnit.SECONDS);
    try {
      return received.get(15, TimeUnit.SECONDS);
    } finally {
      ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }
  }

  // --- HTTP helpers --------------------------------------------------------------------------------------------

  static <T> T getJson(final String path, final User auth, final Class<T> type) throws Exception {
    final HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(base + path))
        .header("Authorization", basic(auth.aci() + ".1", auth.password()))
        .GET().build(), HttpResponse.BodyHandlers.ofString());
    check(response, 200, "GET " + path);
    return JSON.readValue(response.body(), type);
  }

  static void send(final String method, final String path, final User auth, final Object body, final int expect)
      throws Exception {
    final HttpResponse<String> response = HTTP.send(request(method, path, body,
        auth == null ? null : basic(auth.aci() + ".1", auth.password())), HttpResponse.BodyHandlers.ofString());
    check(response, expect, method + " " + path);
  }

  static <T> T send(final String method, final String path, final User auth, final Object body, final int expect,
      final Class<T> type) throws Exception {
    final HttpResponse<String> response = HTTP.send(request(method, path, body,
        auth == null ? null : basic(auth.aci() + ".1", auth.password())), HttpResponse.BodyHandlers.ofString());
    check(response, expect, method + " " + path);
    return JSON.readValue(response.body(), type);
  }

  static <T> T sendBasic(final String method, final String path, final String user, final String password,
      final Object body, final int expect, final Class<T> type) throws Exception {
    final HttpResponse<String> response = HTTP.send(request(method, path, body, basic(user, password)),
        HttpResponse.BodyHandlers.ofString());
    check(response, expect, method + " " + path);
    return JSON.readValue(response.body(), type);
  }

  static HttpRequest request(final String method, final String path, final Object body, final String authorization)
      throws Exception {
    final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
        .timeout(Duration.ofSeconds(20))
        .header("Content-Type", "application/json")
        .header("User-Agent", "Signal-Android/7.80.0 Android/34")
        .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    if (authorization != null) {
      builder.header("Authorization", authorization);
    }
    return builder.build();
  }

  static void check(final HttpResponse<String> response, final int expect, final String what) {
    if (response.statusCode() != expect) {
      throw new IllegalStateException(what + " -> HTTP " + response.statusCode() + ": " + response.body());
    }
  }

  static String basic(final String user, final String password) {
    return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  static byte[] bytes(final int n) {
    final byte[] b = new byte[n];
    RANDOM.nextBytes(b);
    return b;
  }
}
