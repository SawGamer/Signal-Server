package org.selfhost.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signal.registration.rpc.CheckVerificationCodeErrorType;
import org.signal.registration.rpc.CheckVerificationCodeRequest;
import org.signal.registration.rpc.CheckVerificationCodeResponse;
import org.signal.registration.rpc.ClientType;
import org.signal.registration.rpc.CreateRegistrationSessionErrorType;
import org.signal.registration.rpc.CreateRegistrationSessionRequest;
import org.signal.registration.rpc.CreateRegistrationSessionResponse;
import org.signal.registration.rpc.GetRegistrationSessionMetadataErrorType;
import org.signal.registration.rpc.GetRegistrationSessionMetadataRequest;
import org.signal.registration.rpc.MessageTransport;
import org.signal.registration.rpc.RegistrationServiceGrpc;
import org.signal.registration.rpc.RegistrationSessionMetadata;
import org.signal.registration.rpc.SendVerificationCodeErrorType;
import org.signal.registration.rpc.SendVerificationCodeRequest;
import org.signal.registration.rpc.SendVerificationCodeResponse;

class RegistrationServiceImplTest {

  private static final String SECRET = "0123456789abcdef0123456789abcdef";
  private static final long NUMBER = 4915123456789L;
  private static final long TEST_NUMBER = 15555550100L;

  private MutableClock clock;
  private RecordingSender sender;
  private Server server;
  private ManagedChannel channel;
  private RegistrationServiceGrpc.RegistrationServiceBlockingStub stub;

  @BeforeEach
  void setUp() throws Exception {
    clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    sender = new RecordingSender();
    final Config.Policy policy = Config.Policy.defaults();
    final RegistrationServiceImpl service = new RegistrationServiceImpl(new SessionStore(clock, policy), sender,
        Map.of("+" + TEST_NUMBER, "424242"), policy, clock);

    final String name = InProcessServerBuilder.generateName();
    server = InProcessServerBuilder.forName(name)
        .addService(ServerInterceptors.intercept(service, new SharedSecretAuthInterceptor(SECRET)))
        .directExecutor()
        .build()
        .start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    stub = withToken(RegistrationServiceGrpc.newBlockingStub(channel), "Bearer " + SECRET);
  }

  @AfterEach
  void tearDown() {
    channel.shutdownNow();
    server.shutdownNow();
  }

  @Test
  void happyPath() {
    final RegistrationSessionMetadata created = create(NUMBER);
    assertEquals(NUMBER, created.getE164());
    assertFalse(created.getVerified());
    assertTrue(created.getMayRequestSms());
    assertEquals(0, created.getNextSmsSeconds());
    assertFalse(created.getMayRequestVoiceCall(), "voice is only offered after an SMS attempt");
    assertFalse(created.getMayCheckCode(), "nothing to check before a code is sent");

    final SendVerificationCodeResponse sent = send(created.getSessionId(), MessageTransport.MESSAGE_TRANSPORT_SMS);
    assertFalse(sent.hasError());
    assertEquals(1, sender.sent.size());
    assertEquals("+" + NUMBER, sender.sent.getFirst().e164);
    assertTrue(sent.getSessionMetadata().getMayCheckCode());
    assertTrue(sent.getSessionMetadata().getMayRequestVoiceCall());
    assertEquals(60, sent.getSessionMetadata().getNextSmsSeconds());

    final CheckVerificationCodeResponse wrong = check(created.getSessionId(), "000000".equals(sender.lastCode()) ? "111111" : "000000");
    assertFalse(wrong.hasError(), "a wrong code is reported via verified=false, not an error");
    assertFalse(wrong.getSessionMetadata().getVerified());

    final CheckVerificationCodeResponse right = check(created.getSessionId(), sender.lastCode());
    assertFalse(right.hasError());
    assertTrue(right.getSessionMetadata().getVerified());

    assertTrue(stub.getSessionMetadata(GetRegistrationSessionMetadataRequest.newBuilder()
        .setSessionId(created.getSessionId()).build()).getSessionMetadata().getVerified());

    final SendVerificationCodeResponse again = send(created.getSessionId(), MessageTransport.MESSAGE_TRANSPORT_SMS);
    assertEquals(SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_SESSION_ALREADY_VERIFIED,
        again.getError().getErrorType());
  }

  @Test
  void smsResendIsRateLimitedAndReusesCode() {
    final ByteString id = create(NUMBER).getSessionId();
    send(id, MessageTransport.MESSAGE_TRANSPORT_SMS);
    final String firstCode = sender.lastCode();

    final SendVerificationCodeResponse tooSoon = send(id, MessageTransport.MESSAGE_TRANSPORT_SMS);
    assertEquals(SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_RATE_LIMITED,
        tooSoon.getError().getErrorType());
    assertTrue(tooSoon.getError().getMayRetry());
    assertEquals(60, tooSoon.getError().getRetryAfterSeconds());
    assertEquals(1, sender.sent.size());

    clock.advance(Duration.ofSeconds(61));
    final SendVerificationCodeResponse resent = send(id, MessageTransport.MESSAGE_TRANSPORT_SMS);
    assertFalse(resent.hasError());
    assertEquals(firstCode, sender.lastCode(), "resend within code lifetime keeps the same code");
    assertEquals(120, resent.getSessionMetadata().getNextSmsSeconds(), "backoff grows");
  }

  @Test
  void smsSendsAreCappedPerSession() {
    final ByteString id = create(NUMBER).getSessionId();
    for (int i = 0; i < 5; i++) {
      assertFalse(send(id, MessageTransport.MESSAGE_TRANSPORT_SMS).hasError(), "send " + i);
      clock.advance(Duration.ofMinutes(10).plusSeconds(1));
    }
    final SendVerificationCodeResponse capped = send(id, MessageTransport.MESSAGE_TRANSPORT_SMS);
    assertEquals(SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_RATE_LIMITED,
        capped.getError().getErrorType());
    assertFalse(capped.getError().getMayRetry());
    assertFalse(capped.getSessionMetadata().getMayRequestSms());
  }

  @Test
  void voiceRequiresPriorSmsAndDelay() {
    final ByteString id = create(NUMBER).getSessionId();

    final SendVerificationCodeResponse voiceFirst = send(id, MessageTransport.MESSAGE_TRANSPORT_VOICE);
    assertEquals(SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_RATE_LIMITED,
        voiceFirst.getError().getErrorType());
    assertFalse(voiceFirst.getError().getMayRetry());

    send(id, MessageTransport.MESSAGE_TRANSPORT_SMS);
    final SendVerificationCodeResponse voiceTooSoon = send(id, MessageTransport.MESSAGE_TRANSPORT_VOICE);
    assertTrue(voiceTooSoon.getError().getMayRetry());

    clock.advance(Duration.ofSeconds(60));
    final SendVerificationCodeResponse voice = send(id, MessageTransport.MESSAGE_TRANSPORT_VOICE);
    assertFalse(voice.hasError());
    assertEquals(MessageTransport.MESSAGE_TRANSPORT_VOICE, sender.sent.getLast().transport);
  }

  @Test
  void bruteForceIsBounded() {
    final ByteString id = create(NUMBER).getSessionId();
    send(id, MessageTransport.MESSAGE_TRANSPORT_SMS);
    final String wrong = sender.lastCode().equals("999999") ? "999998" : "999999";

    for (int i = 0; i < 3; i++) {
      assertFalse(check(id, wrong).hasError(), "first three guesses are not throttled");
    }
    final CheckVerificationCodeResponse throttled = check(id, wrong);
    assertEquals(CheckVerificationCodeErrorType.CHECK_VERIFICATION_CODE_ERROR_TYPE_RATE_LIMITED,
        throttled.getError().getErrorType());
    assertTrue(throttled.getError().getMayRetry());
    assertEquals(30, throttled.getError().getRetryAfterSeconds());

    clock.advance(Duration.ofSeconds(30));
    assertFalse(check(id, wrong).hasError());
    clock.advance(Duration.ofSeconds(60));
    assertFalse(check(id, wrong).hasError());

    // five failures: this code is burned, even the right answer is refused
    clock.advance(Duration.ofMinutes(5));
    final CheckVerificationCodeResponse exhausted = check(id, sender.lastCode());
    assertEquals(CheckVerificationCodeErrorType.CHECK_VERIFICATION_CODE_ERROR_TYPE_RATE_LIMITED,
        exhausted.getError().getErrorType());
    assertFalse(exhausted.getError().getMayRetry());
    assertFalse(exhausted.getSessionMetadata().getVerified());
  }

  @Test
  void expiredCodeIsRejected() {
    final ByteString id = create(NUMBER).getSessionId();
    send(id, MessageTransport.MESSAGE_TRANSPORT_SMS);
    final String code = sender.lastCode();

    clock.advance(Duration.ofMinutes(10));
    final CheckVerificationCodeResponse expired = check(id, code);
    assertEquals(CheckVerificationCodeErrorType.CHECK_VERIFICATION_CODE_ERROR_TYPE_ATTEMPT_EXPIRED,
        expired.getError().getErrorType());

    send(id, MessageTransport.MESSAGE_TRANSPORT_SMS);
    assertNotEquals(null, sender.lastCode());
    assertTrue(check(id, sender.lastCode()).getSessionMetadata().getVerified(), "a fresh code works");
  }

  @Test
  void checkBeforeSendIsRejected() {
    final ByteString id = create(NUMBER).getSessionId();
    assertEquals(CheckVerificationCodeErrorType.CHECK_VERIFICATION_CODE_ERROR_TYPE_NO_CODE_SENT,
        check(id, "123456").getError().getErrorType());
  }

  @Test
  void sessionsExpire() {
    final ByteString id = create(NUMBER).getSessionId();
    clock.advance(Duration.ofHours(1));
    assertEquals(GetRegistrationSessionMetadataErrorType.GET_REGISTRATION_SESSION_METADATA_ERROR_TYPE_NOT_FOUND,
        stub.getSessionMetadata(GetRegistrationSessionMetadataRequest.newBuilder().setSessionId(id).build())
            .getError().getErrorType());
    assertEquals(SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_SESSION_NOT_FOUND,
        send(id, MessageTransport.MESSAGE_TRANSPORT_SMS).getError().getErrorType());
    assertEquals(CheckVerificationCodeErrorType.CHECK_VERIFICATION_CODE_ERROR_TYPE_SESSION_NOT_FOUND,
        check(id, "123456").getError().getErrorType());
  }

  @Test
  void testNumbersUseFixedCodeAndNeverSend() {
    final ByteString id = create(TEST_NUMBER).getSessionId();
    assertFalse(send(id, MessageTransport.MESSAGE_TRANSPORT_SMS).hasError());
    assertTrue(sender.sent.isEmpty());
    assertTrue(check(id, "424242").getSessionMetadata().getVerified());
  }

  @Test
  void sessionCreationIsRateLimitedPerNumber() {
    for (int i = 0; i < 10; i++) {
      create(NUMBER);
    }
    final CreateRegistrationSessionResponse limited = createRaw(NUMBER, "");
    assertEquals(CreateRegistrationSessionErrorType.CREATE_REGISTRATION_SESSION_ERROR_TYPE_RATE_LIMITED,
        limited.getError().getErrorType());
    assertTrue(limited.getError().getMayRetry());
    assertEquals(3600, limited.getError().getRetryAfterSeconds());

    clock.advance(Duration.ofHours(1));
    assertTrue(createRaw(NUMBER, "").hasSessionMetadata());
  }

  @Test
  void collationKeyIsUsedForRateLimitWhenProvided() {
    for (int i = 0; i < 10; i++) {
      assertTrue(createRaw(NUMBER, "key-a").hasSessionMetadata());
    }
    assertTrue(createRaw(NUMBER, "key-a").hasError());
    assertTrue(createRaw(NUMBER, "key-b").hasSessionMetadata());
  }

  @Test
  void illegalNumbersAreRejected() {
    assertEquals(CreateRegistrationSessionErrorType.CREATE_REGISTRATION_SESSION_ERROR_TYPE_ILLEGAL_PHONE_NUMBER,
        createRaw(0, "").getError().getErrorType());
    assertEquals(CreateRegistrationSessionErrorType.CREATE_REGISTRATION_SESSION_ERROR_TYPE_ILLEGAL_PHONE_NUMBER,
        createRaw(1234567890123456L, "").getError().getErrorType());
  }

  @Test
  void senderFailuresMapToProtocolErrors() {
    final ByteString id = create(NUMBER).getSessionId();

    sender.failWith = VerificationSender.Failure.ILLEGAL_ARGUMENT;
    assertEquals(SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_SENDER_ILLEGAL_ARGUMENT,
        send(id, MessageTransport.MESSAGE_TRANSPORT_SMS).getError().getErrorType());

    sender.failWith = VerificationSender.Failure.UNAVAILABLE;
    final SendVerificationCodeResponse unavailable = send(id, MessageTransport.MESSAGE_TRANSPORT_SMS);
    assertEquals(SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_NO_SENDER_AVAILABLE,
        unavailable.getError().getErrorType());
    assertTrue(unavailable.getError().getMayRetry());

    sender.failWith = null;
    final SendVerificationCodeResponse ok = send(id, MessageTransport.MESSAGE_TRANSPORT_SMS);
    assertFalse(ok.hasError(), "failed sends don't count against resend timers");
  }

  @Test
  void callersWithoutTheSharedSecretAreRejected() {
    final var noToken = RegistrationServiceGrpc.newBlockingStub(channel);
    final StatusRuntimeException missing = assertThrows(StatusRuntimeException.class,
        () -> noToken.createSession(CreateRegistrationSessionRequest.newBuilder().setE164(NUMBER).build()));
    assertEquals(Status.Code.UNAUTHENTICATED, missing.getStatus().getCode());

    final var wrongToken = withToken(RegistrationServiceGrpc.newBlockingStub(channel), "Bearer nope");
    assertThrows(StatusRuntimeException.class,
        () -> wrongToken.createSession(CreateRegistrationSessionRequest.newBuilder().setE164(NUMBER).build()));
  }

  // --- helpers ---------------------------------------------------------------------------------------------------

  private RegistrationSessionMetadata create(final long e164) {
    final CreateRegistrationSessionResponse response = createRaw(e164, "");
    assertTrue(response.hasSessionMetadata(), () -> "expected a session but got " + response);
    return response.getSessionMetadata();
  }

  private CreateRegistrationSessionResponse createRaw(final long e164, final String collationKey) {
    return stub.createSession(CreateRegistrationSessionRequest.newBuilder()
        .setE164(e164)
        .setRateLimitCollationKey(collationKey)
        .build());
  }

  private SendVerificationCodeResponse send(final ByteString id, final MessageTransport transport) {
    return stub.sendVerificationCode(SendVerificationCodeRequest.newBuilder()
        .setSessionId(id)
        .setTransport(transport)
        .setClientType(ClientType.CLIENT_TYPE_ANDROID_WITH_FCM)
        .build());
  }

  private CheckVerificationCodeResponse check(final ByteString id, final String code) {
    return stub.checkVerificationCode(CheckVerificationCodeRequest.newBuilder()
        .setSessionId(id)
        .setVerificationCode(code)
        .build());
  }

  private static RegistrationServiceGrpc.RegistrationServiceBlockingStub withToken(
      final RegistrationServiceGrpc.RegistrationServiceBlockingStub stub, final String value) {
    final Metadata headers = new Metadata();
    headers.put(SharedSecretAuthInterceptor.AUTHORIZATION, value);
    return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
  }

  private record Sent(String e164, String code, MessageTransport transport) {

  }

  private static final class RecordingSender implements VerificationSender {

    final List<Sent> sent = new ArrayList<>();
    Failure failWith;

    @Override
    public void send(final String e164, final String code, final MessageTransport transport,
        final ClientType clientType, final String acceptLanguage) throws SenderException {
      if (failWith != null) {
        throw new SenderException(failWith, "test");
      }
      sent.add(new Sent(e164, code, transport));
    }

    String lastCode() {
      return sent.getLast().code;
    }
  }

  static final class MutableClock extends Clock {

    private Instant now;

    MutableClock(final Instant now) {
      this.now = now;
    }

    void advance(final Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
