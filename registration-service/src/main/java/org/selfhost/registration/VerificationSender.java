package org.selfhost.registration;

import org.signal.registration.rpc.ClientType;
import org.signal.registration.rpc.MessageTransport;

/**
 * Delivers a verification code to a phone number.
 */
public interface VerificationSender {

  void send(String e164, String code, MessageTransport transport, ClientType clientType, String acceptLanguage)
      throws SenderException;

  /**
   * Why a code could not be delivered. Maps onto {@code SendVerificationCodeErrorType}.
   */
  enum Failure {
    /** Provider understood the request but refused it (e.g. recipient opted out). */
    REJECTED,
    /** Number is malformed or cannot receive this kind of message. */
    ILLEGAL_ARGUMENT,
    /** Requested transport (e.g. voice) is not supported for this destination. */
    TRANSPORT_NOT_ALLOWED,
    /** No provider configured/available for this destination; temporary provider outage. */
    UNAVAILABLE
  }

  final class SenderException extends Exception {

    private final Failure failure;

    public SenderException(final Failure failure, final String message) {
      super(message);
      this.failure = failure;
    }

    public Failure failure() {
      return failure;
    }
  }
}
