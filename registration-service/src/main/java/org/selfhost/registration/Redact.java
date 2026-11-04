package org.selfhost.registration;

/** Keeps phone numbers out of logs. */
final class Redact {

  private Redact() {
  }

  static String e164(final String e164) {
    if (e164 == null || e164.length() < 4) {
      return "+***";
    }
    return "+***" + e164.substring(e164.length() - 2);
  }
}
