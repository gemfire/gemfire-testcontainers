/*
 *  Copyright (c) VMware, Inc. 2022. All rights reserved.
 */

package com.vmware.gemfire.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;

public class GemFireTestcontainersTest {

  private static final String IMAGE = "gemfire/gemfire:9.15.5";

  @Test
  public void testBasicSetup() {
    try (GemFireClusterContainer<?> cluster = new GemFireClusterContainer<>(IMAGE)) {
      cluster.acceptLicense();
      cluster.start();
    }
  }

  @Test
  public void testSetupWithGfsh() {
    try (GemFireClusterContainer<?> cluster = new GemFireClusterContainer<>(IMAGE)) {
      cluster.acceptLicense();
      cluster.start();

      // gfshUsage {
      cluster.gfsh(
          true,
          "list members",
          "create region --name=FOO --type=REPLICATE",
          "describe region --name=FOO"
      );
      // }

      // connectUsingLocator {
      try (
          ClientCache cache = new ClientCacheFactory()
              .addPoolLocator("localhost", cluster.getLocatorPort())
              .create()
      ) {
        Region<Integer, String> region = cache
            .<Integer, String>createClientRegionFactory(ClientRegionShortcut.PROXY)
            .create("FOO");

        region.put(1, "Hello World");

        assertThat(region.get(1)).isEqualTo("Hello World");
      }
      // }
    }
  }

  @Test
  public void testStartWithCacheXml() {
    try (GemFireClusterContainer<?> cluster = new GemFireClusterContainer<>(IMAGE)) {
      // withCacheXml {
      cluster.withCacheXml("/test-cache.xml");
      // }
      cluster.acceptLicense();
      cluster.start();

      try (
          ClientCache cache = new ClientCacheFactory()
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

  @Test
  public void testWithSecurityManagerOnClasspath() {
    try (GemFireClusterContainer<?> cluster = new GemFireClusterContainer<>(IMAGE)) {
      // withGemFireProperty {
      cluster.withGemFireProperty("security-manager", SimpleSecurityManager.class.getName());
      // }
      // These paths should work for running using gradle CLI as well as with IntelliJ
      // withClasspath {
      cluster.withClasspath("build/classes/java/test", "out/test/classes");
      // }
      cluster
          .withGemFireProperty("security-username", "cluster")
          .withGemFireProperty("security-password", "cluster")
          .acceptLicense()
          .start();

      cluster.gfsh(true, "create region --name=FOO --type=REPLICATE");

      try (
          ClientCache cache = new ClientCacheFactory()
              .set("security-username", "data")
              .set("security-password", "data")
              .set("security-client-auth-init", UserPasswordAuthInit.class.getName())
              .addPoolLocator("localhost", cluster.getLocatorPort())
              .create()
      ) {
        Region<Integer, String> region = cache
            .<Integer, String>createClientRegionFactory(ClientRegionShortcut.PROXY)
            .create("FOO");

        region.put(1, "Hello World");

        assertThat(region.get(1)).isEqualTo("Hello World");
      }
    }
  }

  @Test
  public void testWithStaticLocatorPort() {
    final int locatorPort = 54321;

    try (GemFireClusterContainer<?> cluster = new GemFireClusterContainer<>(IMAGE)) {
      cluster.withLocatorPort(locatorPort);
      cluster.acceptLicense();
      cluster.start();

      assertThat(cluster.getLocatorPort()).isEqualTo(locatorPort);

      cluster.gfsh(false, "create region --name=FOO --type=REPLICATE");

      try (
          ClientCache cache = new ClientCacheFactory()
              .addPoolLocator("localhost", cluster.getLocatorPort())
              .create()
      ) {
        Region<Integer, String> region = cache
            .<Integer, String>createClientRegionFactory(ClientRegionShortcut.PROXY)
            .create("FOO");

        region.put(1, "Hello World");

        assertThat(region.get(1)).isEqualTo("Hello World");
      }
    }
  }
}
