/*
 * Self-hosting support. SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.selfhost;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.google.api.core.ApiFutures;
import com.google.cloud.pubsub.v1.PublisherInterface;
import java.util.UUID;
import org.whispersystems.textsecuregcm.configuration.PubSubPublisherFactory;

/**
 * Discards analytics events that Signal publishes to Google Cloud Pub/Sub (call-quality surveys, Braintree
 * webhooks). Use {@code type: disabled} when you don't run Google Cloud.
 */
@JsonTypeName("disabled")
public class DisabledPubSubPublisherFactory implements PubSubPublisherFactory {

  @Override
  public PublisherInterface build() {
    return message -> ApiFutures.immediateFuture(UUID.randomUUID().toString());
  }
}
