package org.selfhost.registration;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Only the chat server may call this service: it must send {@code authorization: Bearer <SHARED_SECRET>}.
 */
final class SharedSecretAuthInterceptor implements ServerInterceptor {

  static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

  private final byte[] expected;

  SharedSecretAuthInterceptor(final String sharedSecret) {
    if (sharedSecret.length() < 32) {
      throw new IllegalArgumentException("SHARED_SECRET must be at least 32 characters");
    }
    this.expected = ("Bearer " + sharedSecret).getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(final ServerCall<ReqT, RespT> call,
      final Metadata headers, final ServerCallHandler<ReqT, RespT> next) {

    final String provided = headers.get(AUTHORIZATION);
    if (provided == null || !MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8))) {
      call.close(Status.UNAUTHENTICATED.withDescription("bad credentials"), new Metadata());
      return new ServerCall.Listener<>() {
      };
    }
    return next.startCall(call, headers);
  }
}
