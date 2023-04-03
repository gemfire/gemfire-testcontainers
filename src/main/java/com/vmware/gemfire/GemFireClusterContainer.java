package com.vmware.gemfire;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.Base58;
import org.testcontainers.utility.DockerImageName;

/**
 * Class used to instantiate a GemFire cluster. The default settings will launch a locator and 2
 * servers.
 * <p>
 * Example:
 * <pre>
 *   try (GemFireClusterContainer<?> cluster = new GemFireClusterContainer<>()) {
 *     cluster.start();
 *
 *     cluster.gfsh(true, "list members",
 *         "create region --name=FOO --type=REPLICATE",
 *         "describe region --name=FOO");
 *
 *     ClientCache cache = new ClientCacheFactory()
 *         .addPoolLocator("localhost", cluster.getLocatorPort())
 *         .create();
 *
 *     Region<Integer, String> region =
 *         cache.<Integer, String>createClientRegionFactory(ClientRegionShortcut.PROXY)
 *             .create("FOO");
 *
 *     region.put(1, "Hello World");
 *
 *     assertThat(region.get(1)).isEqualTo("Hello World");
 *   }
 * </pre>
 */
public class GemFireClusterContainer<SELF extends GemFireClusterContainer<SELF>> extends GenericContainer<SELF> {

  private static final Logger LOG = LoggerFactory.getLogger(GemFireClusterContainer.class);

  private static final String DEFAULT_IMAGE =
      "dev.registry.pivotal.io/pivotal-gemfire/vmware-gemfire:10.0.0-build.1806-dev_photon4";

  private static final int DEFAULT_SERVER_COUNT = 2;

  private final DockerImageName image;
  private final String suffix;

  private GemFireProxyContainer proxy;
  private GemFireLocatorContainer locator;
  private final List<GemFireServerContainer> servers = new ArrayList<>();

  /**
   * List that holds configuration for each cluster server.
   */
  private final List<MemberConfig> memberConfigs;

  public GemFireClusterContainer() {
    this(DEFAULT_SERVER_COUNT, DEFAULT_IMAGE);
  }

  public GemFireClusterContainer(int serverCount) {
    this(serverCount, DEFAULT_IMAGE);
  }

  public GemFireClusterContainer(int serverCount, String imageName) {
    this(serverCount, DockerImageName.parse(imageName));
  }

  public GemFireClusterContainer(int serverCount, DockerImageName image) {
    this.image = image;
    this.suffix = Base58.randomString(6);

    memberConfigs = new ArrayList<>(serverCount);

    for (int i = 0; i < serverCount; i++) {
      String name = String.format("server-%d-%s", i + 1, suffix);
      memberConfigs.add(new MemberConfig(name));
    }
  }

  @Override
  public void start() {
    Network network = Network.builder()
        .createNetworkCmdModifier(it -> it.withName("gemfire-cluster-" + suffix))
        .build();

    proxy = new GemFireProxyContainer(memberConfigs);
    proxy.withNetwork(network);
    // Once the proxy has started, all the forwarding ports, for each MemberConfig, will be set.
    proxy.start();

    locator = new GemFireLocatorContainer(image);
    locator.withNetwork(network);
    locator.start();

    for (int i = 0; i < memberConfigs.size(); i++) {
      MemberConfig config = memberConfigs.get(i);
      GemFireServerContainer server = new GemFireServerContainer(config, DEFAULT_IMAGE);
      server.withNetwork(network);

      server.start();
      servers.add(server);
    }
  }

  @Override
  public void stop() {
    proxy.stop();
    servers.forEach(GenericContainer::stop);
    locator.stop();
  }

  /**
   * The given local paths will be made available on the classpath of the container.
   *
   * @param classpaths which are resolved to absolute paths and then bind-mounted to each container
   *                   at the location {@code /classpath/0, /classpath/1, etc.}. These paths are
   *                   added to the classpath of the started JVM instance.
   */
  public SELF withClasspath(String... classpaths) {
    memberConfigs.forEach(member -> member.addConfig(container -> {
      for (int i = 0; i < classpaths.length; i++) {
        container.addFileSystemBind(classpaths[i], "/classpath/" + i, BindMode.READ_ONLY);
      }
    }));
    return self();
  }

  /**
   * Add the given gemfire property to a specific started GemFire server instance.
   * @param serverIndex the instance to target. Instances are numbered from 1.
   * @param name the name of the property. The property will automatically be prefixed with
   *        {@code gemfire.}
   * @param value the value of the property to set
   */
  public SELF withGemFireProperty(int serverIndex, String name, String value) {
    memberConfigs.get(serverIndex)
        .addConfig(container -> container.addJvmArg(String.format("-Dgemfire.%s=%s", name, value)));
    return self();
  }

  /**
   * Open a debug port for a given instance. The provided port will be exposed externally and the
   * server instance will wait until a debugger connects before continuing.
   * @param serverIndex the instance to target. Instances are numbered from 1.
   * @param port the port to use for debugging.
   */
  public SELF withDebugPort(int serverIndex, int port) {
    memberConfigs.get(serverIndex).addConfig(container -> {
      container.setPortBindings(Collections.singletonList(String.format("%d:%d", port, port)));
      container.addJvmArg("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=0.0.0.0:" + port);
      System.err.println("Waiting for debugger to connect on port " + port);
    });
    return self();
  }

  /**
   * Accepts the license for the GemFire container by setting the ACCEPT_EULA=Y
   * variable as described at [someplace to be decided...]
   */
  public SELF acceptLicense() {
    memberConfigs.forEach(member -> member.addConfig(container -> container
        .addEnv("ACCEPT_EULA", "Y")));
    return self();
  }

  /**
   * Return the port at which the locator is listening. This would be used to configure a
   * GemFire client to connect.
   */
  public int getLocatorPort() {
    return locator.getLocatorPort();
  }

  /**
   * Execute the provided commands as a {@code gfsh} script.
   * <p>
   * For example:
   * <pre>
   *   cluster.gfsh(true, "list members",
   *       "create region --name=FOO --type=REPLICATE",
   *       "describe region --name=FOO");
   * </pre>
   * @param logOutput boolean indicating whether to log output. If the script returns a non-zero
   *                  error code, the output will always be logged.
   * @param commands a list of commands to execute. There is no need to provide an explicit
   *                 {@code connect} command unless additional parameters are required.
   */
  public void gfsh(boolean logOutput, String... commands) {
    if (commands.length == 0) {
      return;
    }

    String fullCommand;
    if (commands[0].startsWith("connect")) {
      fullCommand = "";
    } else {
      fullCommand = "connect\n";
    }
    fullCommand += String.join("\n", commands);

    locator.copyFileToContainer(Transferable.of(fullCommand, 06666), "/script.gfsh");
    try {
      ExecResult result = locator.execInContainer("gfsh", "-e", "run --file=/script.gfsh");

      if (logOutput || result.getExitCode() != 0) {
        for (String line : result.toString().split("\n")) {
          LOG.info(line);
        }
      }
    } catch (Exception ex) {
      throw new RuntimeException("Error executing gfsh command: " + fullCommand, ex);
    }
  }

}
