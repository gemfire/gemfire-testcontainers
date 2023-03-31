package com.vmware.gemfire;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;

public class GemFireTestcontainersTest {

  @Test
  public void basicTest() {
    try (GemFireClusterContainer<?> cluster = new GemFireClusterContainer<>()) {

      cluster.withClasspath("out/production/classes");
      cluster.start();

      cluster.gfsh(true, "list members",
          "create region --name=FOO --type=REPLICATE",
          "describe region --name=FOO");

      ClientCache cache = new ClientCacheFactory()
          .addPoolLocator("localhost", cluster.getLocatorPort())
          .create();

      Region<Integer, String> region =
          cache.<Integer, String>createClientRegionFactory(ClientRegionShortcut.PROXY)
              .create("FOO");

      region.put(1, "Hey");

      assertThat(region.get(1)).isEqualTo("Hey");
    }
  }

}
