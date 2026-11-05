package org.selfhost.registration;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory session storage.
 * <p>
 * Sessions live at most {@link Config.Policy#sessionTtl()} (one hour by default), so losing them on restart only
 * means anyone mid-registration has to tap "resend". That trade-off keeps a single-VPS deployment simple; to run
 * several replicas, back this with Redis instead.
 */
final class SessionStore {

  private static final Duration CREATION_WINDOW = Duration.ofHours(1);

  private final Map<String, Session> sessions = new ConcurrentHashMap<>();
  private final Map<String, Deque<Instant>> creationsByKey = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();
  private final Clock clock;
  private final Config.Policy policy;

  SessionStore(final Clock clock, final Config.Policy policy) {
    this.clock = clock;
    this.policy = policy;
  }

  /**
   * Records a session-creation attempt for {@code rateLimitKey}.
   *
   * @return empty if allowed, otherwise how long the caller must wait
   */
  Optional<Duration> checkCreationRateLimit(final String rateLimitKey) {
    final Instant now = clock.instant();
    final Deque<Instant> recent = creationsByKey.computeIfAbsent(rateLimitKey, k -> new ArrayDeque<>());
    synchronized (recent) {
      while (!recent.isEmpty() && !recent.peekFirst().plus(CREATION_WINDOW).isAfter(now)) {
        recent.pollFirst();
      }
      if (recent.size() >= policy.maxSessionsPerNumberPerHour()) {
        return Optional.of(Duration.between(now, recent.peekFirst().plus(CREATION_WINDOW)));
      }
      recent.addLast(now);
      return Optional.empty();
    }
  }

  Session create(final long e164, final String fixedCode) {
    final byte[] id = new byte[16];
    random.nextBytes(id);
    final Session session = new Session(id, e164, clock.instant().plus(policy.sessionTtl()), fixedCode);
    sessions.put(key(id), session);
    return session;
  }

  Optional<Session> get(final byte[] id) {
    final Session session = sessions.get(key(id));
    if (session == null) {
      return Optional.empty();
    }
    if (session.isExpired(clock.instant())) {
      sessions.remove(key(id));
      return Optional.empty();
    }
    return Optional.of(session);
  }

  /** 6 random digits, uniformly distributed. */
  String newCode() {
    return String.format("%06d", random.nextInt(1_000_000));
  }

  /** Drops expired sessions and stale rate-limit buckets; called periodically. */
  void sweep() {
    final Instant now = clock.instant();
    sessions.values().removeIf(s -> s.isExpired(now));
    creationsByKey.entrySet().removeIf(e -> {
      synchronized (e.getValue()) {
        return e.getValue().isEmpty() || !e.getValue().peekLast().plus(CREATION_WINDOW).isAfter(now);
      }
    });
  }

  int size() {
    return sessions.size();
  }

  private static String key(final byte[] id) {
    return Base64.getEncoder().encodeToString(id);
  }
}
