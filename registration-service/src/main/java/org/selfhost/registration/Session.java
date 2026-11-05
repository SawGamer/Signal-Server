package org.selfhost.registration;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.signal.registration.rpc.RegistrationSessionMetadata;

/**
 * One registration attempt for one phone number. Callers must hold the session's monitor ({@code synchronized
 * (session)}) while reading or mutating it.
 */
final class Session {

  final byte[] id;
  final long e164;
  final Instant expiresAt;
  /** Non-null for configured test numbers: no SMS is ever sent and this code is always accepted. */
  final String fixedCode;

  String code;
  Instant codeExpiresAt;
  int smsSent;
  Instant lastSmsAt;
  int voiceSent;
  Instant lastVoiceAt;
  int failedChecks;
  Instant lastFailedCheckAt;
  boolean verified;

  Session(final byte[] id, final long e164, final Instant expiresAt, final String fixedCode) {
    this.id = id;
    this.e164 = e164;
    this.expiresAt = expiresAt;
    this.fixedCode = fixedCode;
  }

  String e164String() {
    return "+" + e164;
  }

  boolean isExpired(final Instant now) {
    return !now.isBefore(expiresAt);
  }

  // --- SMS -------------------------------------------------------------------------------------------------------

  boolean maySendSms(final Config.Policy policy) {
    return !verified && smsSent < policy.maxSmsPerSession();
  }

  Instant nextSmsAt(final Config.Policy policy) {
    return smsSent == 0 ? Instant.MIN : lastSmsAt.plus(delayFor(policy.smsResendDelays(), smsSent));
  }

  // --- Voice -----------------------------------------------------------------------------------------------------

  /** Like Signal, a voice call is only offered after at least one SMS attempt. */
  boolean maySendVoice(final Config.Policy policy) {
    return !verified && smsSent > 0 && voiceSent < policy.maxVoicePerSession();
  }

  Instant nextVoiceAt(final Config.Policy policy) {
    if (smsSent == 0) {
      return Instant.MAX;
    }
    final Instant afterSms = lastSmsAt.plus(policy.voiceDelayAfterSms());
    if (voiceSent == 0) {
      return afterSms;
    }
    final Instant afterVoice = lastVoiceAt.plus(delayFor(policy.smsResendDelays(), voiceSent));
    return afterVoice.isAfter(afterSms) ? afterVoice : afterSms;
  }

  // --- Code checks -----------------------------------------------------------------------------------------------

  boolean mayCheckCode(final Config.Policy policy) {
    return !verified && code != null && failedChecks < policy.maxFailedChecksPerCode();
  }

  /** First three guesses are free; after that, back off exponentially (30s, 60s, ...). */
  Instant nextCheckAt() {
    if (failedChecks < 3) {
      return Instant.MIN;
    }
    return lastFailedCheckAt.plus(Duration.ofSeconds(30L << Math.min(failedChecks - 3, 10)));
  }

  // --- Wire format -----------------------------------------------------------------------------------------------

  RegistrationSessionMetadata toMetadata(final Instant now, final Config.Policy policy) {
    final boolean maySms = maySendSms(policy);
    final boolean mayVoice = maySendVoice(policy);
    final boolean mayCheck = mayCheckCode(policy);

    return RegistrationSessionMetadata.newBuilder()
        .setSessionId(com.google.protobuf.ByteString.copyFrom(id))
        .setE164(e164)
        .setVerified(verified)
        .setMayRequestSms(maySms)
        .setNextSmsSeconds(maySms ? secondsUntil(now, nextSmsAt(policy)) : 0)
        .setMayRequestVoiceCall(mayVoice)
        .setNextVoiceCallSeconds(mayVoice ? secondsUntil(now, nextVoiceAt(policy)) : 0)
        .setMayCheckCode(mayCheck)
        .setNextCodeCheckSeconds(mayCheck ? secondsUntil(now, nextCheckAt()) : 0)
        .setExpirationSeconds(secondsUntil(now, expiresAt))
        .build();
  }

  static long secondsUntil(final Instant now, final Instant then) {
    if (then.equals(Instant.MIN) || !then.isAfter(now)) {
      return 0;
    }
    if (then.equals(Instant.MAX)) {
      return Long.MAX_VALUE;
    }
    // round up so clients never retry a fraction of a second too early
    return (Duration.between(now, then).toMillis() + 999) / 1000;
  }

  private static Duration delayFor(final List<Duration> delays, final int attemptsSoFar) {
    return delays.get(Math.min(attemptsSoFar - 1, delays.size() - 1));
  }
}
