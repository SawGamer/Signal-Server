package org.selfhost.registration;

import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Verification message wording in English and Arabic.
 * <p>
 * The language is chosen from the phone's {@code Accept-Language} (sent by the app with each request); when it is
 * missing, {@link #defaultLanguage()} is used. Codes always use Western digits (0-9) so that phones can auto-fill
 * them.
 *
 * @param appName          substituted for {@code {app}}
 * @param smsEnglish       SMS template; {@code {app}} and {@code {code}} are replaced
 * @param smsArabic        Arabic SMS template
 * @param defaultLanguage  language when the phone doesn't say
 * @param arabicVoice      Twilio voice for Arabic calls (e.g. {@code Polly.Zeina})
 * @param arabicVoiceLanguage  Twilio language code for that voice (e.g. {@code arb})
 */
public record MessageTexts(
    String appName,
    String smsEnglish,
    String smsArabic,
    Language defaultLanguage,
    String arabicVoice,
    String arabicVoiceLanguage) {

  public enum Language { EN, AR }

  static final String DEFAULT_SMS_EN = "Your {app} code: {code}\n\nDo not share this code with anyone.";
  static final String DEFAULT_SMS_AR = "رمز التحقق في {app}: {code}\n\nلا تشارك هذا الرمز مع أي شخص.";

  /**
   * Picks the most preferred language from an HTTP Accept-Language value such as {@code "ar-SY,ar;q=0.9,en;q=0.8"}.
   */
  Language pick(final String acceptLanguage) {
    if (acceptLanguage == null || acceptLanguage.isBlank()) {
      return defaultLanguage;
    }
    String best = null;
    double bestQ = -1;
    for (final String part : acceptLanguage.split(",")) {
      final String[] fields = part.trim().split(";");
      final String tag = fields[0].trim();
      if (tag.isEmpty() || tag.equals("*")) {
        continue;
      }
      double q = 1.0;
      for (int i = 1; i < fields.length; i++) {
        final String f = fields[i].trim();
        if (f.startsWith("q=")) {
          try {
            q = Double.parseDouble(f.substring(2));
          } catch (final NumberFormatException ignored) {
            q = 0;
          }
        }
      }
      if (q > bestQ) {
        bestQ = q;
        best = tag;
      }
    }
    if (best == null) {
      return defaultLanguage;
    }
    return best.toLowerCase(Locale.ROOT).startsWith("ar") ? Language.AR : Language.EN;
  }

  String sms(final Language language, final String code) {
    final String template = language == Language.AR ? smsArabic : smsEnglish;
    return template.replace("{app}", appName).replace("{code}", code);
  }

  /** Reads the digits one at a time, twice, so they're intelligible over a phone line. */
  String voiceTwiml(final Language language, final String code) {
    if (language == Language.AR) {
      final String spaced = spaced(code, "، ");
      return "<Response><Say voice=\"" + xml(arabicVoice) + "\" language=\"" + xml(arabicVoiceLanguage) + "\">"
          + "رمز التحقق الخاص بك هو: " + spaced + ". أكرر، رمز التحقق هو: " + spaced + "."
          + "</Say></Response>";
    }
    final String spaced = spaced(code, ", ");
    return "<Response><Say>Your verification code is: " + spaced + ". Again, your code is: " + spaced
        + ".</Say></Response>";
  }

  private static String spaced(final String code, final String separator) {
    return code.chars().mapToObj(c -> String.valueOf((char) c)).collect(Collectors.joining(separator));
  }

  private static String xml(final String s) {
    return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
