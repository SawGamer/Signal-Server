package org.selfhost.registration;

import org.signal.registration.rpc.ClientType;
import org.signal.registration.rpc.MessageTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development-only sender: writes the code to the service log instead of sending an SMS. Never use in production —
 * anyone who can read the logs can take over any account.
 */
final class LogSender implements VerificationSender {

  private static final Logger log = LoggerFactory.getLogger(LogSender.class);

  LogSender() {
    log.warn("*** SENDER=log: verification codes are written to this log and NOT sent. Development use only. ***");
  }

  @Override
  public void send(final String e164, final String code, final MessageTransport transport,
      final ClientType clientType, final String acceptLanguage) {
    log.info("[DEV] Verification code for {} via {}: {}", e164, transport, code);
  }
}
