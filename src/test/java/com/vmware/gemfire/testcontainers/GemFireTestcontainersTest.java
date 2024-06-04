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

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import org.junit.Test;
import org.testcontainers.containers.Container;
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
  public void testSetupWithSimpleGfsh() {
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
  public void testSetupWithGfshBuilder() throws Exception {
    try (GemFireCluster cluster = new GemFireCluster(1, 0)) {
      cluster.acceptLicense();
      cluster.start();

      String classResource = getClass().getName().replace(".", "/") + ".class";
      String classFile = getClass().getClassLoader().getResource(classResource).getFile();

      // Check that the files are copied correctly
      Gfsh gfsh = cluster.gfshBuilder()
          .withKeyStore(classFile, "key_pass")
          .withTrustStore(classFile, "trust_pass")
          .build();

      try {
        gfsh.run();
      } catch (Exception ignored) {
        // We just care about the contents of the gfsh script and other relevant files that
        // should have been created.
      }

      AbstractGemFireContainer<?> locator = cluster.getContainers().get("locator-0");

      String scriptContents = locator.execInContainer("cat", "/script.gfsh").getStdout();
      assertThat(scriptContents).contains(
          "connect --jmx-manager=localhost[1099]",
          "--trust-store=/trust-store",
          "--trust-store-password=trust_pass",
          "--key-store-password=key_pass",
          "--key-store=/key-store");

      Container.ExecResult execResult1 = locator.execInContainer("test", "-e", "/key-store");
      assertThat(execResult1.getExitCode())
          .as("Command failed with: " + execResult1)
          .isEqualTo(0);
      Container.ExecResult execResult2 = locator.execInContainer("test", "-e", "/trust-store");
      assertThat(execResult2.getExitCode())
          .as("Command failed with: " + execResult2)
          .isEqualTo(0);

      String securityFile = getClass().getClassLoader().getResource("security.properties").getFile();
      gfsh = cluster.gfshBuilder()
          .withSecurityProperties(securityFile).build();

      try {
        gfsh.run();
      } catch (Exception ignored) {
      }

      String fileContents = locator.execInContainer("cat", "/security.properties").getStdout();
      assertThat(fileContents).contains(
          "ssl-keystore=ks.jks",
          "ssl-keystore-password=key_pass",
          "ssl-truststore=ts.jks",
          "ssl-truststore-password=trust_pass");

      Properties securityProperties = new Properties();
      securityProperties.setProperty("foo", "bar");
      gfsh = cluster.gfshBuilder()
          .withSecurityProperties(securityProperties).build();

      try {
        gfsh.run();
      } catch (Exception ignored) {
      }

      fileContents = locator.execInContainer("cat", "/security.properties").getStdout();
      assertThat(fileContents).contains("foo=bar");
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
    byte[] rawBytes = readAllBytes(Objects.requireNonNull(getClass().getResourceAsStream(CACHE_XML)));
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

      String result = cluster.gfsh(true, "export cluster-configuration");
      assertThat(result).contains("<pdx read-serialized=\"true\"", "ReflectionBasedAutoSerializer");
    }
  }

  @Test
  public void testWithSecurityManagerOnClasspath() {
    String username = "cluster,data";
    String password = "cluster,data";

    try (GemFireCluster cluster = new GemFireCluster()) {
      cluster.withClasspath(ALL_GLOB, "out/test/classes", "target/test-classes")
          .withGemFireProperty(ALL_GLOB, "security-manager", SimpleSecurityManager.class.getName())
          .withGemFireProperty(ALL_GLOB, "security-username", username)
          .withGemFireProperty(ALL_GLOB, "security-password", password)
          .acceptLicense()
          .start();

      Gfsh gfsh = cluster.gfshBuilder()
          .withCredentials(username, password)
          .withLogging(true)
          .build();

      gfsh.run("list members",
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

  @Test
  public void testHttpPortsAreCorrect() throws Exception {
    try (GemFireCluster cluster = new GemFireCluster()) {
      cluster.withGemFireProperty(SERVER_GLOB, "start-dev-rest-api", "true");
      cluster.acceptLicense();
      cluster.start();

      List<Integer> locatorHttpPorts = cluster.getHttpPorts(LOCATOR_GLOB);
      assertThat(locatorHttpPorts).hasSize(1);

      for (Integer port : locatorHttpPorts) {
        getAndValidate("http://localhost:" + port + "/management");
      }

      List<Integer> serverHttpPorts = cluster.getHttpPorts(SERVER_GLOB);
      assertThat(serverHttpPorts).hasSize(2);

      for (Integer port : serverHttpPorts) {
        getAndValidate("http://localhost:" + port + "/gemfire-api");
      }
    }
  }

  private void getAndValidate(String target) throws Exception {
    URL url = new URL(target);
    HttpURLConnection con = (HttpURLConnection) url.openConnection();
    con.setInstanceFollowRedirects(true);
    con.setRequestMethod("GET");

    assertThat(con.getResponseCode()).as("URL at " + target + " failed to respond")
        .isEqualTo(200);
  }

}
