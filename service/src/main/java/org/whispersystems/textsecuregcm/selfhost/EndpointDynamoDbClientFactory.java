/*
 * Self-hosting support. SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.selfhost;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.whispersystems.textsecuregcm.configuration.DynamoDbClientFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DynamoDB client pointed at any DynamoDB-compatible endpoint (e.g. DynamoDB Local in Docker) instead of AWS.
 *
 * <pre>
 * dynamoDbClient:
 *   type: endpoint
 *   endpoint: http://dynamodb:8000
 *   region: local
 * </pre>
 */
@JsonTypeName("endpoint")
public class EndpointDynamoDbClientFactory implements DynamoDbClientFactory {

  @JsonProperty
  @NotNull
  private URI endpoint;

  @JsonProperty
  @NotBlank
  private String region = "local";

  @JsonProperty
  @NotNull
  private Duration clientExecutionTimeout = Duration.ofSeconds(30);

  @JsonProperty
  @NotNull
  private Duration clientRequestTimeout = Duration.ofSeconds(10);

  @JsonProperty
  private int maxConnections = 50;

  @Override
  public DynamoDbClient buildSyncClient(final AwsCredentialsProvider credentialsProvider,
      final MetricPublisher metricPublisher) {
    return DynamoDbClient.builder()
        .endpointOverride(endpoint)
        .region(Region.of(region))
        .credentialsProvider(credentialsProvider)
        .overrideConfiguration(overrideConfiguration(metricPublisher))
        .httpClientBuilder(AwsCrtHttpClient.builder().maxConcurrency(maxConnections))
        .build();
  }

  @Override
  public DynamoDbAsyncClient buildAsyncClient(final AwsCredentialsProvider credentialsProvider,
      final MetricPublisher metricPublisher) {
    return DynamoDbAsyncClient.builder()
        .endpointOverride(endpoint)
        .region(Region.of(region))
        .credentialsProvider(credentialsProvider)
        .overrideConfiguration(overrideConfiguration(metricPublisher))
        .httpClientBuilder(NettyNioAsyncHttpClient.builder().maxConcurrency(maxConnections))
        .build();
  }

  private ClientOverrideConfiguration overrideConfiguration(final MetricPublisher metricPublisher) {
    return ClientOverrideConfiguration.builder()
        .apiCallTimeout(clientExecutionTimeout)
        .apiCallAttemptTimeout(clientRequestTimeout)
        .addMetricPublisher(metricPublisher)
        .build();
  }
}
