/*
 *  Copyright (c) VMware, Inc. 2023. All rights reserved.
 */

package com.vmware.gemfire.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Rule;
import org.junit.Test;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;

public class GemFireTestcontainersRuleTest {

  @Rule
  public GemFireCluster cluster = new GemFireCluster()
      .acceptLicense()
      .withGfsh(true, "list members", "create region --name=BAZ --type=REPLICATE");

  @Test
  public void testSetupWithRule() {
    try (ClientCache cache = new ClientCacheFactory()
        .addPoolLocator("localhost", cluster.getLocatorPort())
        .create()
    ) {
      Region<Integer, String> region = cache
          .<Integer, String>createClientRegionFactory(ClientRegionShortcut.PROXY)
          .create("BAZ");

      region.put(1, "Hello Earth");

      assertThat(region.get(1)).isEqualTo("Hello Earth");
    }
  }
}
