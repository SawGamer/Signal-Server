package org.selfhost.registration;

import io.grpc.Grpc;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.TlsServerCredentials;
import java.io.File;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. See {@link Config} for the environment variables it reads.
 */
public final class RegistrationServer {

  private static final Logger log = LoggerFactory.getLogger(RegistrationServer.class);

  public static void main(final String[] args) throws Exception {
    final Config config = Config.fromEnvironment();
    final Clock clock = Clock.systemUTC();

    final VerificationSender sender = switch (config.sender()) {
      case "log" -> new LogSender();
      case "twilio" -> new TwilioSender(config.twilio(), config.texts());
      default -> throw new IllegalArgumentException("Unknown SENDER '" + config.sender() + "' (use log or twilio)");
    };

    for (final String path : new String[]{config.tlsCertChainPath(), config.tlsPrivateKeyPath()}) {
      if (!new File(path).canRead()) {
        throw new IllegalStateException("Cannot read " + path + ". In Docker this service runs as uid "
            + "10001, so the file must be readable by that user (e.g. `chown 10001 " + path + "`).");
      }
    }

    final SessionStore store = new SessionStore(clock, config.policy());
    final RegistrationServiceImpl service =
        new RegistrationServiceImpl(store, sender, config.testNumbers(), config.policy(), clock);

    final Server server = Grpc.newServerBuilderForPort(config.port(),
            TlsServerCredentials.create(new File(config.tlsCertChainPath()), new File(config.tlsPrivateKeyPath())))
        .addService(ServerInterceptors.intercept(service, new SharedSecretAuthInterceptor(config.sharedSecret())))
        .build()
        .start();

    final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor();
    sweeper.scheduleAtFixedRate(store::sweep, 1, 1, TimeUnit.MINUTES);

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      sweeper.shutdownNow();
      server.shutdown();
      try {
        server.awaitTermination(10, TimeUnit.SECONDS);
      } catch (final InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
    }));

    log.info("Registration service listening on port {} (sender={}, default language={}, test numbers={})",
        config.port(), config.sender(), config.texts().defaultLanguage(), config.testNumbers().size());
    server.awaitTermination();
  }
}
