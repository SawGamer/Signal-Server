/*
 * Self-hosting support. SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.selfhost;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.dropwizard.core.setup.Environment;
import io.grpc.CallCredentials;
import io.grpc.Metadata;
import io.grpc.Status;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import org.whispersystems.textsecuregcm.configuration.RegistrationServiceClientFactory;
import org.whispersystems.textsecuregcm.configuration.secrets.SecretBytes;
import org.whispersystems.textsecuregcm.configuration.secrets.SecretString;
import org.whispersystems.textsecuregcm.registration.RegistrationServiceClient;

/**
 * Connects to the self-hosted registration service in {@code registration-service/} (instead of Signal's, which
 * authenticates with Google Cloud identity tokens). Calls carry {@code authorization: Bearer <sharedSecret>}.
 *
 * <pre>
 * registrationService:
 *   type: selfHosted
 *   host: registration
 *   port: 9443
 *   caCertificate: |
 *     -----BEGIN CERTIFICATE-----
 *     ...
 *   sharedSecret: secret://registrationService.sharedSecret
 *   collationKeySalt: secret://registrationService.collationKeySalt
 * </pre>
 */
@JsonTypeName("selfHosted")
public class SelfHostedRegistrationServiceClientFactory implements RegistrationServiceClientFactory {

  private static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

  @JsonProperty
  @NotBlank
  private String host;

  @JsonProperty
  private int port = 9443;

  /** PEM certificate (or CA) that the registration service's TLS certificate chains to. */
  @JsonProperty
  @NotBlank
  private String caCertificate;

  @JsonProperty
  @NotNull
  @Valid
  private SecretString sharedSecret;

  @JsonProperty
  @NotNull
  @Valid
  private SecretBytes collationKeySalt;

  @Override
  public RegistrationServiceClient build(final Environment environment,
      final ScheduledExecutorService identityRefreshExecutor) {
    try {
      return new RegistrationServiceClient(host, port, new SharedSecretCallCredentials(sharedSecret.value()),
          caCertificate, collationKeySalt.value());
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static final class SharedSecretCallCredentials extends CallCredentials {

    private final String headerValue;

    SharedSecretCallCredentials(final String sharedSecret) {
      this.headerValue = "Bearer " + sharedSecret;
    }

    @Override
    public void applyRequestMetadata(final RequestInfo requestInfo, final Executor appExecutor,
        final MetadataApplier applier) {
      try {
        final Metadata headers = new Metadata();
        headers.put(AUTHORIZATION, headerValue);
        applier.apply(headers);
      } catch (final RuntimeException e) {
        applier.fail(Status.UNAUTHENTICATED.withCause(e));
      }
    }
  }
}
