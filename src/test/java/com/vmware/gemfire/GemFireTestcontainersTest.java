package com.vmware.gemfire;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.examples.SimpleSecurityManager;

public class GemFireTestcontainersTest {

  @Test
  public void basicSetupTest() {
    try (GemFireClusterContainer<?> cluster = new GemFireClusterContainer<>()) {

      cluster.withClasspath("build");
      cluster.start();

      cluster.gfsh(true, "list members",
          "create region --name=FOO --type=REPLICATE",
          "describe region --name=FOO");

      try (ClientCache cache = new ClientCacheFactory()
          .addPoolLocator("localhost", cluster.getLocatorPort())
          .create()) {

        Region<Integer, String> region =
            cache.<Integer, String>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create("FOO");

        region.put(1, "Hello World");

        assertThat(region.get(1)).isEqualTo("Hello World");
      }
    }
  }

  @Test
  public void testWithSecurityManager() {
    try (GemFireClusterContainer<?> cluster = new GemFireClusterContainer<>()) {
      cluster.withGemFireProperty("security-manager", SimpleSecurityManager.class.getName());
      cluster.withGemFireProperty("security-username", "cluster");
      cluster.withGemFireProperty("security-password", "cluster");
      cluster.start();

      cluster.gfsh(true, "create region --name=FOO --type=REPLICATE");

      try (ClientCache cache = new ClientCacheFactory()
          .set("security-username", "data")
          .set("security-password", "data")
          .set("security-client-auth-init", UserPasswordAuthInit.class.getName())
          .addPoolLocator("localhost", cluster.getLocatorPort())
          .create()) {

        Region<Integer, String> region =
            cache.<Integer, String>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create("FOO");

        region.put(1, "Hello World");

        assertThat(region.get(1)).isEqualTo("Hello World");
      }
    }
  }

}
