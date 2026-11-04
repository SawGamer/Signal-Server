package org.selfhost.registration;

import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.signal.registration.rpc.CheckVerificationCodeError;
import org.signal.registration.rpc.CheckVerificationCodeErrorType;
import org.signal.registration.rpc.CheckVerificationCodeRequest;
import org.signal.registration.rpc.CheckVerificationCodeResponse;
import org.signal.registration.rpc.CreateRegistrationSessionError;
import org.signal.registration.rpc.CreateRegistrationSessionErrorType;
import org.signal.registration.rpc.CreateRegistrationSessionRequest;
import org.signal.registration.rpc.CreateRegistrationSessionResponse;
import org.signal.registration.rpc.GetRegistrationSessionMetadataError;
import org.signal.registration.rpc.GetRegistrationSessionMetadataErrorType;
import org.signal.registration.rpc.GetRegistrationSessionMetadataRequest;
import org.signal.registration.rpc.GetRegistrationSessionMetadataResponse;
import org.signal.registration.rpc.MessageTransport;
import org.signal.registration.rpc.RegistrationServiceGrpc;
import org.signal.registration.rpc.SendVerificationCodeError;
import org.signal.registration.rpc.SendVerificationCodeErrorType;
import org.signal.registration.rpc.SendVerificationCodeRequest;
import org.signal.registration.rpc.SendVerificationCodeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements the contract in {@code service/src/main/proto/RegistrationService.proto}. Error semantics follow the
 * comments in that file and how {@code RegistrationServiceClient} in the chat server interprets them.
 */
final class RegistrationServiceImpl extends RegistrationServiceGrpc.RegistrationServiceImplBase {

  private static final Logger log = LoggerFactory.getLogger(RegistrationServiceImpl.class);

  private final SessionStore store;
  private final VerificationSender sender;
  private final Map<String, String> testNumbers;
  private final Config.Policy policy;
  private final Clock clock;

  RegistrationServiceImpl(final SessionStore store, final VerificationSender sender,
      final Map<String, String> testNumbers, final Config.Policy policy, final Clock clock) {
    this.store = store;
    this.sender = sender;
    this.testNumbers = testNumbers;
    this.policy = policy;
    this.clock = clock;
  }

  @Override
  public void createSession(final CreateRegistrationSessionRequest request,
      final StreamObserver<CreateRegistrationSessionResponse> responseObserver) {

    final long e164 = request.getE164();
    final int digits = Long.toString(e164).length();
    if (e164 <= 0 || digits < 7 || digits > 15) {
      respond(responseObserver, CreateRegistrationSessionResponse.newBuilder()
          .setError(CreateRegistrationSessionError.newBuilder()
              .setErrorType(CreateRegistrationSessionErrorType.CREATE_REGISTRATION_SESSION_ERROR_TYPE_ILLEGAL_PHONE_NUMBER)
              .setMayRetry(false))
          .build());
      return;
    }

    final String fixedCode = testNumbers.get("+" + e164);
    if (fixedCode == null) {
      final String rateLimitKey = request.getRateLimitCollationKey().isEmpty()
          ? Long.toString(e164)
          : request.getRateLimitCollationKey();
      final Optional<Duration> retryAfter = store.checkCreationRateLimit(rateLimitKey);
      if (retryAfter.isPresent()) {
        respond(responseObserver, CreateRegistrationSessionResponse.newBuilder()
            .setError(CreateRegistrationSessionError.newBuilder()
                .setErrorType(CreateRegistrationSessionErrorType.CREATE_REGISTRATION_SESSION_ERROR_TYPE_RATE_LIMITED)
                .setMayRetry(true)
                .setRetryAfterSeconds(Math.max(1, retryAfter.get().toSeconds())))
            .build());
        return;
      }
    }

    final Session session = store.create(e164, fixedCode);
    synchronized (session) {
      respond(responseObserver, CreateRegistrationSessionResponse.newBuilder()
          .setSessionMetadata(session.toMetadata(clock.instant(), policy))
          .build());
    }
  }

  @Override
  public void getSessionMetadata(final GetRegistrationSessionMetadataRequest request,
      final StreamObserver<GetRegistrationSessionMetadataResponse> responseObserver) {

    final Optional<Session> maybeSession = store.get(request.getSessionId().toByteArray());
    if (maybeSession.isEmpty()) {
      respond(responseObserver, GetRegistrationSessionMetadataResponse.newBuilder()
          .setError(GetRegistrationSessionMetadataError.newBuilder()
              .setErrorType(
                  GetRegistrationSessionMetadataErrorType.GET_REGISTRATION_SESSION_METADATA_ERROR_TYPE_NOT_FOUND))
          .build());
      return;
    }

    final Session session = maybeSession.get();
    synchronized (session) {
      respond(responseObserver, GetRegistrationSessionMetadataResponse.newBuilder()
          .setSessionMetadata(session.toMetadata(clock.instant(), policy))
          .build());
    }
  }

  @Override
  public void sendVerificationCode(final SendVerificationCodeRequest request,
      final StreamObserver<SendVerificationCodeResponse> responseObserver) {

    final Optional<Session> maybeSession = store.get(request.getSessionId().toByteArray());
    if (maybeSession.isEmpty()) {
      respond(responseObserver, sendError(null, SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_SESSION_NOT_FOUND, false, 0));
      return;
    }

    final Session session = maybeSession.get();
    final boolean voice = request.getTransport() == MessageTransport.MESSAGE_TRANSPORT_VOICE;
    final MessageTransport transport = voice
        ? MessageTransport.MESSAGE_TRANSPORT_VOICE
        : MessageTransport.MESSAGE_TRANSPORT_SMS;

    synchronized (session) {
      final Instant now = clock.instant();

      if (session.verified) {
        respond(responseObserver, sendError(session, SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_SESSION_ALREADY_VERIFIED, false, 0));
        return;
      }

      final boolean allowed = voice ? session.maySendVoice(policy) : session.maySendSms(policy);
      if (!allowed) {
        respond(responseObserver, sendError(session, SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_RATE_LIMITED, false, 0));
        return;
      }

      final Instant nextAllowed = voice ? session.nextVoiceAt(policy) : session.nextSmsAt(policy);
      if (now.isBefore(nextAllowed)) {
        respond(responseObserver, sendError(session, SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_RATE_LIMITED, true,
            Session.secondsUntil(now, nextAllowed)));
        return;
      }

      // Resends reuse a still-valid code, so a late-arriving first SMS keeps working.
      final boolean needNewCode = session.code == null || !now.isBefore(session.codeExpiresAt);
      final String code = session.fixedCode != null
          ? session.fixedCode
          : needNewCode ? store.newCode() : session.code;

      if (session.fixedCode == null) {
        try {
          sender.send(session.e164String(), code, transport, request.getClientType(), request.getAcceptLanguage());
        } catch (final VerificationSender.SenderException e) {
          log.warn("Could not send {} to {}: {} ({})", transport, Redact.e164(session.e164String()), e.failure(),
              e.getMessage());
          respond(responseObserver, sendError(session, switch (e.failure()) {
            case REJECTED -> SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_SENDER_REJECTED;
            case ILLEGAL_ARGUMENT -> SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_SENDER_ILLEGAL_ARGUMENT;
            case TRANSPORT_NOT_ALLOWED -> SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_TRANSPORT_NOT_ALLOWED;
            case UNAVAILABLE -> SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_NO_SENDER_AVAILABLE;
          }, e.failure() == VerificationSender.Failure.UNAVAILABLE, 30));
          return;
        }
      }

      if (needNewCode || !code.equals(session.code)) {
        session.failedChecks = 0;
        session.lastFailedCheckAt = null;
      }
      session.code = code;
      session.codeExpiresAt = now.plus(policy.codeTtl());
      if (voice) {
        session.voiceSent++;
        session.lastVoiceAt = now;
      } else {
        session.smsSent++;
        session.lastSmsAt = now;
      }

      respond(responseObserver, SendVerificationCodeResponse.newBuilder()
          .setSessionMetadata(session.toMetadata(now, policy))
          .build());
    }
  }

  @Override
  public void checkVerificationCode(final CheckVerificationCodeRequest request,
      final StreamObserver<CheckVerificationCodeResponse> responseObserver) {

    final Optional<Session> maybeSession = store.get(request.getSessionId().toByteArray());
    if (maybeSession.isEmpty()) {
      respond(responseObserver, checkError(null, CheckVerificationCodeErrorType.CHECK_VERIFICATION_CODE_ERROR_TYPE_SESSION_NOT_FOUND, false, 0));
      return;
    }

    final Session session = maybeSession.get();
    synchronized (session) {
      final Instant now = clock.instant();

      if (!session.verified) {
        if (session.code == null) {
          respond(responseObserver, checkError(session, CheckVerificationCodeErrorType.CHECK_VERIFICATION_CODE_ERROR_TYPE_NO_CODE_SENT, false, 0));
          return;
        }
        if (!now.isBefore(session.codeExpiresAt)) {
          respond(responseObserver, checkError(session, CheckVerificationCodeErrorType.CHECK_VERIFICATION_CODE_ERROR_TYPE_ATTEMPT_EXPIRED, false, 0));
          return;
        }
        if (!session.mayCheckCode(policy)) {
          // Too many wrong guesses for this code; the user must request a new one.
          respond(responseObserver, checkError(session, CheckVerificationCodeErrorType.CHECK_VERIFICATION_CODE_ERROR_TYPE_RATE_LIMITED, false, 0));
          return;
        }
        if (now.isBefore(session.nextCheckAt())) {
          respond(responseObserver, checkError(session, CheckVerificationCodeErrorType.CHECK_VERIFICATION_CODE_ERROR_TYPE_RATE_LIMITED, true,
              Session.secondsUntil(now, session.nextCheckAt())));
          return;
        }

        if (MessageDigest.isEqual(session.code.getBytes(StandardCharsets.UTF_8),
            request.getVerificationCode().trim().getBytes(StandardCharsets.UTF_8))) {
          session.verified = true;
        } else {
          session.failedChecks++;
          session.lastFailedCheckAt = now;
        }
      }

      // A wrong code is not an error: the chat server reads `verified == false` from the metadata.
      respond(responseObserver, CheckVerificationCodeResponse.newBuilder()
          .setSessionMetadata(session.toMetadata(now, policy))
          .build());
    }
  }

  private SendVerificationCodeResponse sendError(final Session session, final SendVerificationCodeErrorType type,
      final boolean mayRetry, final long retryAfterSeconds) {
    final SendVerificationCodeResponse.Builder builder = SendVerificationCodeResponse.newBuilder()
        .setError(SendVerificationCodeError.newBuilder()
            .setErrorType(type)
            .setMayRetry(mayRetry)
            .setRetryAfterSeconds(retryAfterSeconds));
    if (session != null) {
      builder.setSessionMetadata(session.toMetadata(clock.instant(), policy));
    }
    return builder.build();
  }

  private CheckVerificationCodeResponse checkError(final Session session, final CheckVerificationCodeErrorType type,
      final boolean mayRetry, final long retryAfterSeconds) {
    final CheckVerificationCodeResponse.Builder builder = CheckVerificationCodeResponse.newBuilder()
        .setError(CheckVerificationCodeError.newBuilder()
            .setErrorType(type)
            .setMayRetry(mayRetry)
            .setRetryAfterSeconds(retryAfterSeconds));
    if (session != null) {
      builder.setSessionMetadata(session.toMetadata(clock.instant(), policy));
    }
    return builder.build();
  }

  private static <T> void respond(final StreamObserver<T> observer, final T response) {
    observer.onNext(response);
    observer.onCompleted();
  }
}
