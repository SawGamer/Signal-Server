/*
 * Self-hosting support. SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.selfhost;

import com.fasterxml.jackson.annotation.JsonTypeName;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.util.Map;
import org.whispersystems.textsecuregcm.configuration.PaymentsServiceClientsFactory;
import org.whispersystems.textsecuregcm.currency.CoinGeckoClient;
import org.whispersystems.textsecuregcm.currency.FixerClient;

/**
 * Turns off currency-rate lookups (Fixer, CoinGecko) used by Signal's MobileCoin payments feature, so no API keys
 * are needed. Use {@code type: disabled}.
 */
@JsonTypeName("disabled")
public class DisabledPaymentsServiceClientsFactory implements PaymentsServiceClientsFactory {

  @Override
  public FixerClient buildFixerClient(final HttpClient httpClient) {
    return new FixerClient(null, null) {
      @Override
      public Map<String, BigDecimal> getConversionsForBase(final String base) {
        // Only the identity rate: the server caches this map in Redis, which rejects empty maps.
        return Map.of(base, BigDecimal.ONE);
      }
    };
  }

  @Override
  public CoinGeckoClient buildCoinGeckoClient(final HttpClient httpClient) {
    return new CoinGeckoClient(null, null, null) {
      @Override
      public BigDecimal getSpotPrice(final String currency, final String base) {
        return BigDecimal.ZERO;
      }
    };
  }
}
