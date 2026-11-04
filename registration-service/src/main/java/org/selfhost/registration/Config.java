package org.selfhost.registration;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * All settings come from environment variables so the service drops straight into Docker Compose.
 *
 * @param port                 gRPC listen port
 * @param tlsCertChainPath     PEM certificate chain served to the chat server
 * @param tlsPrivateKeyPath    PEM (PKCS#8) private key for that certificate
 * @param sharedSecret         bearer token the chat server must present; prevents anyone else on the network from
 *                             triggering (paid) SMS sends
 * @param sender               which {@link VerificationSender} to use: {@code log} (development only) or
 *                             {@code twilio}
 * @param texts                SMS / voice wording in English and Arabic
 * @param testNumbers          E.164 numbers that never receive a real SMS and always accept a fixed code (useful for
 *                             app-store review accounts and automated tests)
 * @param twilio               Twilio settings, required when {@code sender=twilio}
 * @param policy               timing / rate-limit policy
 */
public record Config(
    int port,
    String tlsCertChainPath,
    String tlsPrivateKeyPath,
    String sharedSecret,
    String sender,
    MessageTexts texts,
    Map<String, String> testNumbers,
    TwilioConfig twilio,
    Policy policy) {

  public record TwilioConfig(String accountSid, String authToken, String fromNumber, String messagingServiceSid,
                             String apiBaseUrl) {

  }

  /**
   * Timing rules. Defaults are deliberately conservative: SMS costs money and is a common abuse target.
   */
  public record Policy(
      Duration sessionTtl,
      Duration codeTtl,
      List<Duration> smsResendDelays,
      int maxSmsPerSession,
      Duration voiceDelayAfterSms,
      int maxVoicePerSession,
      int maxFailedChecksPerCode,
      int maxSessionsPerNumberPerHour) {

    public static Policy defaults() {
      return new Policy(
          Duration.ofHours(1),
          Duration.ofMinutes(10),
          List.of(Duration.ofSeconds(60), Duration.ofSeconds(120), Duration.ofMinutes(5), Duration.ofMinutes(10)),
          5,
          Duration.ofSeconds(60),
          3,
          5,
          10);
    }
  }

  public static Config fromEnvironment() {
    return fromMap(System.getenv());
  }

  static Config fromMap(final Map<String, String> env) {
    final Function<String, String> required = key -> {
      final String value = env.get(key);
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("Missing required environment variable " + key);
      }
      return value;
    };

    final String sender = env.getOrDefault("SENDER", "log").trim().toLowerCase();

    final TwilioConfig twilio = "twilio".equals(sender)
        ? new TwilioConfig(
            required.apply("TWILIO_ACCOUNT_SID"),
            required.apply("TWILIO_AUTH_TOKEN"),
            env.get("TWILIO_FROM_NUMBER"),
            env.get("TWILIO_MESSAGING_SERVICE_SID"),
            env.getOrDefault("TWILIO_API_BASE_URL", "https://api.twilio.com"))
        : null;

    if (twilio != null && isBlank(twilio.fromNumber()) && isBlank(twilio.messagingServiceSid())) {
      throw new IllegalArgumentException("Set TWILIO_FROM_NUMBER or TWILIO_MESSAGING_SERVICE_SID");
    }

    return new Config(
        Integer.parseInt(env.getOrDefault("PORT", "9443")),
        required.apply("TLS_CERT_CHAIN"),
        required.apply("TLS_PRIVATE_KEY"),
        required.apply("SHARED_SECRET"),
        sender,
        new MessageTexts(
            env.getOrDefault("APP_NAME", "Messenger"),
            env.getOrDefault("SMS_TEMPLATE", MessageTexts.DEFAULT_SMS_EN),
            env.getOrDefault("SMS_TEMPLATE_AR", MessageTexts.DEFAULT_SMS_AR),
            parseLanguage(env.getOrDefault("DEFAULT_LANGUAGE", "en")),
            env.getOrDefault("VOICE_AR", "Polly.Zeina"),
            env.getOrDefault("VOICE_AR_LANGUAGE", "arb")),
        parseTestNumbers(env.getOrDefault("TEST_NUMBERS", "")),
        twilio,
        Policy.defaults());
  }

  static MessageTexts.Language parseLanguage(final String value) {
    return switch (value.trim().toLowerCase()) {
      case "ar" -> MessageTexts.Language.AR;
      case "en" -> MessageTexts.Language.EN;
      default -> throw new IllegalArgumentException("DEFAULT_LANGUAGE must be en or ar");
    };
  }

  /**
   * Parses {@code "+15555550100=123456,+15555550101=654321"}.
   */
  static Map<String, String> parseTestNumbers(final String value) {
    final Map<String, String> result = new HashMap<>();
    for (final String entry : value.split(",")) {
      if (entry.isBlank()) {
        continue;
      }
      final String[] parts = entry.trim().split("=", 2);
      if (parts.length != 2 || !parts[0].startsWith("+") || !parts[1].matches("\\d{6}")) {
        throw new IllegalArgumentException("TEST_NUMBERS entries must look like +15555550100=123456");
      }
      result.put(parts[0], parts[1]);
    }
    return Map.copyOf(result);
  }

  private static boolean isBlank(final String s) {
    return s == null || s.isBlank();
  }
}
