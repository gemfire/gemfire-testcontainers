/*
 *  Copyright (c) VMware, Inc. 2023. All rights reserved.
 */

package com.vmware.gemfire.testcontainers;

import static com.vmware.gemfire.testcontainers.GemFireCluster.ALL_GLOB;
import static com.vmware.gemfire.testcontainers.GemFireCluster.LOCATOR_GLOB;
import static com.vmware.gemfire.testcontainers.GemFireCluster.SERVER_GLOB;
import static com.vmware.gemfire.testcontainers.GemFireCluster.readAllBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import org.junit.Test;
import org.testcontainers.images.builder.Transferable;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;

public class GemFireTestcontainersTest {

  @Test
  public void testBasicSetup() {
    try (GemFireCluster cluster = new GemFireCluster()) {
      cluster.acceptLicense();
      cluster.start();
    }
  }

  @Test
  public void failWhenLicenseIsNotAccepted() {
    assertThatThrownBy(() -> new GemFireCluster().start())
        .hasRootCauseMessage("Container did not start correctly.");
  }

  @Test
  public void resourceIsClosedWithoutBeingStarted() {
    assertThatCode(() -> {
          try (GemFireCluster cluster = new GemFireCluster()) {
            cluster.acceptLicense();
          }
        }
    ).doesNotThrowAnyException();
  }

  @Test
  public void startMultipleLocators() {
    try (GemFireCluster cluster = new GemFireCluster(2, 2)) {
      cluster.acceptLicense();
      cluster.start();

      String result = cluster.gfsh(true, "list members");

      assertThat(result).contains("locator-0", "locator-1", "server-0", "server-1");
    }
  }

  @Test
  public void testSetupWithGfsh() {
    try (GemFireCluster cluster = new GemFireCluster()) {
      cluster.acceptLicense();
      cluster.start();

      cluster.gfsh(
          true,
          "list members",
          "create region --name=FOO --type=REPLICATE",
          "describe region --name=FOO"
      );

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

  @Test
  public void testStartWithCacheXml() {
    try (GemFireCluster cluster = new GemFireCluster()) {
      cluster.withCacheXml(SERVER_GLOB, "/test-cache.xml");
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
  public void testStartWithSingleServer() {
    try (GemFireCluster cluster = new GemFireCluster(0, 1)) {
      cluster.withCacheXml(SERVER_GLOB, "/test-cache.xml");
      cluster.acceptLicense();
      cluster.start();

      try (
          ClientCache cache = new ClientCacheFactory()
              .addPoolServer("localhost", cluster.getServerPorts().get(0))
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
  public void testStartWithCacheXmlExplicitlyCopiedToServers() throws Exception {
    String CACHE_XML = "/test-cache.xml";
    byte[] rawBytes;
    rawBytes = readAllBytes(Objects.requireNonNull(getClass().getResourceAsStream(CACHE_XML)));
    Transferable fileData = Transferable.of(new String(rawBytes));

    try (GemFireCluster cluster = new GemFireCluster()) {
      cluster.withPreStart(SERVER_GLOB, x -> x.copyFileToContainer(fileData, CACHE_XML));
      cluster.withGemFireProperty(SERVER_GLOB, "cache-xml-file", CACHE_XML);
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
  public void testStartWithPdx() {
    try (GemFireCluster cluster = new GemFireCluster(2, 2)) {
      cluster.withPdx("com\\.acme.*", true);
      cluster.acceptLicense();
      cluster.start();

      String result = cluster.gfsh(
          true,
          "export cluster-configuration"
      );
      assertThat(result).contains("<pdx read-serialized=\"true\"", "ReflectionBasedAutoSerializer");
    }
  }

  @Test
  public void testWithSecurityManagerOnClasspath() {
    try (GemFireCluster cluster = new GemFireCluster()) {
      cluster.withClasspath(ALL_GLOB, "build/classes/java/test", "out/test/classes")
          .withGemFireProperty(ALL_GLOB, "security-manager", SimpleSecurityManager.class.getName())
          .withGemFireProperty(ALL_GLOB, "security-username", "cluster")
          .withGemFireProperty(ALL_GLOB, "security-password", "cluster")
          .acceptLicense()
          .start();

      cluster.gfsh(true, "list members",
          "create region --name=FOO --type=REPLICATE");

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

    try (GemFireCluster cluster = new GemFireCluster()) {
      cluster.withPorts(LOCATOR_GLOB, locatorPort);
      cluster.acceptLicense();
      cluster.start();

      assertThat(cluster.getLocatorPort()).isEqualTo(locatorPort);

      cluster.gfsh(true, "list members", "create region --name=FOO --type=REPLICATE");

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

  @Test
  public void testWithStaticServerPorts() {
    final int port1 = 54321;
    final int port2 = 54322;

    try (GemFireCluster cluster = new GemFireCluster()) {
      cluster.withPorts(SERVER_GLOB, port1, port2);
      cluster.acceptLicense();
      cluster.start();

      assertThat(cluster.getServerPorts()).containsExactly(port1, port2);

      cluster.gfsh(true, "list members", "create region --name=FOO --type=REPLICATE");

      try (
          ClientCache cache = new ClientCacheFactory()
              .addPoolServer("localhost", cluster.getServerPorts().get(0))
              .addPoolServer("localhost", cluster.getServerPorts().get(1))
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
  public void testWithEphemeralServerPorts() {
    try (GemFireCluster cluster = new GemFireCluster()) {
      cluster.acceptLicense();
      cluster.start();

      assertThat(cluster.getServerPorts()).hasSize(2);

      cluster.gfsh(true, "list members", "create region --name=FOO --type=REPLICATE");

      try (
          ClientCache cache = new ClientCacheFactory()
              .addPoolServer("localhost", cluster.getServerPorts().get(0))
              .addPoolServer("localhost", cluster.getServerPorts().get(1))
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
  public void testWithLogConsumer() {
    AtomicInteger lineCounter = new AtomicInteger();
    BiConsumer<String, String> logger = (s1, s2) -> {
      assertThat(s1).isEqualTo("locator-0");
      lineCounter.addAndGet(1);
    };

    try (GemFireCluster cluster = new GemFireCluster()) {
      cluster.withLogConsumer(LOCATOR_GLOB, logger);
      cluster.acceptLicense();
      cluster.start();
    }

    assertThat(lineCounter.get()).isGreaterThan(100);
  }

}
