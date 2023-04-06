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
  public void testBasicSetup() {
    try (GemFireClusterContainer<?> cluster = new GemFireClusterContainer<>()) {

      cluster.withServerConfiguration(container ->
          container.addJvmArg("-Dcustom.property=true"));

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
    } catch (Exception ex) {
      ex.printStackTrace();
      throw ex;
    }
  }

  @Test
  public void testStartWithCacheXml() {
    try (GemFireClusterContainer<?> cluster = new GemFireClusterContainer<>()) {

      cluster.withCacheXml("/test-cache.xml");
      cluster.start();

      try (ClientCache cache = new ClientCacheFactory()
          .addPoolLocator("localhost", cluster.getLocatorPort())
          .create()) {

        Region<Integer, String> region =
            cache.<Integer, String>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create("BAZ");

        region.put(1, "Hello Earth");

        assertThat(region.get(1)).isEqualTo("Hello Earth");
      }
    } catch (Exception ex) {
      ex.printStackTrace();
      throw ex;
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

  @Test
  public void testWithStaticLocatorPort() {
    final int locatorPort = 54321;

    try (GemFireClusterContainer<?> cluster = new GemFireClusterContainer<>()) {
      cluster.withLocatorPort(locatorPort);
      cluster.start();

      assertThat(cluster.getLocatorPort()).isEqualTo(locatorPort);

      cluster.gfsh(false, "create region --name=FOO --type=REPLICATE");

      try (ClientCache cache = new ClientCacheFactory()
          .addPoolLocator("localhost", cluster.getLocatorPort())
          .create()) {

        Region<Integer, String> region =
            cache.<Integer, String>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create("FOO");

        region.put(1, "Hello World");

        assertThat(region.get(1)).isEqualTo("Hello World");
      }
    } catch (Exception ex) {
      ex.printStackTrace();
      throw ex;
    }
  }

}
