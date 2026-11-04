/*
 * Self-hosting support. SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.selfhost;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.configuration.S3ObjectMonitorFactory;
import org.whispersystems.textsecuregcm.s3.S3ObjectMonitor;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

/**
 * Reads a "monitored S3 object" (dynamic configuration, ASN table) from a local file instead, re-reading it when it
 * changes. Lets you edit {@code dynamic-config.yml} on the host and have the server pick it up within seconds.
 *
 * <pre>
 * dynamicConfig:
 *   type: file
 *   path: /etc/signal/dynamic-config.yml
 *   refreshInterval: PT10S
 * </pre>
 */
@JsonTypeName("file")
public class FileObjectMonitorFactory implements S3ObjectMonitorFactory {

  @JsonProperty
  @NotBlank
  private String path;

  @JsonProperty
  @NotNull
  private Duration refreshInterval = Duration.ofSeconds(10);

  @Override
  public S3ObjectMonitor build(final AwsCredentialsProvider awsCredentialsProvider,
      final ScheduledExecutorService refreshExecutorService) {
    return new FileObjectMonitor(Path.of(path), refreshInterval, awsCredentialsProvider, refreshExecutorService);
  }

  static final class FileObjectMonitor extends S3ObjectMonitor {

    private static final Logger log = LoggerFactory.getLogger(FileObjectMonitor.class);

    private final Path path;
    private final Duration refreshInterval;
    private final ScheduledExecutorService executor;

    private ScheduledFuture<?> refreshFuture;
    private FileTime lastModified;
    private long lastSize = -1;

    FileObjectMonitor(final Path path, final Duration refreshInterval,
        final AwsCredentialsProvider awsCredentialsProvider, final ScheduledExecutorService executor) {
      // The parent builds an S3 client that is never used; the region/bucket values are placeholders.
      super(awsCredentialsProvider, "us-east-1", "unused", path.toString(), 0L, executor, refreshInterval);
      this.path = path;
      this.refreshInterval = refreshInterval;
      this.executor = executor;
    }

    @Override
    public synchronized void start(final Consumer<InputStream> changeListener) {
      if (refreshFuture != null) {
        throw new IllegalStateException("already started");
      }
      // First read is synchronous and must succeed, mirroring the S3 behavior.
      if (!refresh(changeListener)) {
        throw new UncheckedIOException(new IOException("Cannot read " + path));
      }
      refreshFuture = executor.scheduleAtFixedRate(() -> refresh(changeListener),
          refreshInterval.toMillis(), refreshInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
      if (refreshFuture != null) {
        refreshFuture.cancel(true);
      }
    }

    /** @return false if the file could not be read */
    private synchronized boolean refresh(final Consumer<InputStream> changeListener) {
      try {
        final FileTime modified = Files.getLastModifiedTime(path);
        final long size = Files.size(path);
        if (Objects.equals(modified, lastModified) && size == lastSize) {
          return true;
        }
        final byte[] contents = Files.readAllBytes(path);
        lastModified = modified;
        lastSize = size;
        log.info("Loaded {} ({} bytes)", path, contents.length);
        changeListener.accept(new ByteArrayInputStream(contents));
        return true;
      } catch (final IOException e) {
        log.warn("Failed to read {}", path, e);
        return false;
      }
    }
  }
}
