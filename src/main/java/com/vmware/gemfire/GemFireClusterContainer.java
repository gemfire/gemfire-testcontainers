package com.vmware.gemfire;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Bind;
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
 *     cluster.acceptLicense();
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
public class GemFireClusterContainer<SELF extends GemFireClusterContainer<SELF>> extends AbstractGemFireContainer<SELF> {

  private static final Logger LOG = LoggerFactory.getLogger(GemFireClusterContainer.class);

  protected static final String LOCATOR_NAME = "locator-1";

  protected static final int LOCATOR_PORT = 10334;

  private static final int HTTP_PORT = 7070;

  private static final List<String> DEFAULT_LOCATOR_JVM_ARGS = Arrays.asList(
      "-server",
      "--add-exports=java.management/com.sun.jmx.remote.security=ALL-UNNAMED",
      "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "-Dgemfire.use-cluster-configuration=true",
      "-Dgemfire.log-level=info",
      "-Dgemfire.http-service-port=7070",
      "-XX:OnOutOfMemoryError=kill",
      "-Dgemfire.launcher.registerSignalHandlers=true",
      "-Djava.awt.headless=true",
      "-Dsun.rmi.dgc.server.gcInterval=9223372036854775806");

  private static final String DEFAULT_IMAGE =
      "dev.registry.pivotal.io/pivotal-gemfire/vmware-gemfire:10.0.0-build.1806-dev_photon4";

  private static final int DEFAULT_SERVER_COUNT = 2;

  private final Network network;
  private final DockerImageName image;
  private final String suffix;

  private GemFireProxyContainer proxy;
  private final List<GemFireServerContainer<?>> servers = new ArrayList<>();

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

  public GemFireClusterContainer(String imageName) {
    this(DEFAULT_SERVER_COUNT, DockerImageName.parse(imageName));
  }

  public GemFireClusterContainer(int serverCount, String imageName) {
    this(serverCount, DockerImageName.parse(imageName));
  }

  public GemFireClusterContainer(int serverCount, DockerImageName image) {
    super(image);
    this.image = image;
    this.suffix = Base58.randomString(6);

    jvmArgs = new ArrayList<>(DEFAULT_LOCATOR_JVM_ARGS);

    memberConfigs = new ArrayList<>(serverCount);

    for (int i = 0; i < serverCount; i++) {
      String name = String.format("server-%d-%s", i, suffix);
      memberConfigs.add(new MemberConfig(name));
    }

    network = Network.builder()
        .createNetworkCmdModifier(it -> it.withName("gemfire-cluster-" + suffix))
        .build();
    withNetwork(network);
  }

  @Override
  protected void configure() {
    super.configure();

    withCreateContainerCmdModifier(it -> it.withName(LOCATOR_NAME));
    withExposedPorts(LOCATOR_PORT, HTTP_PORT);

    String execPart =
        "export BOOTSTRAP_JAR=$(basename /gemfire/lib/gemfire-bootstrap-*.jar); exec java ";

    String classpathPart = "-classpath /gemfire/lib/${BOOTSTRAP_JAR}";
    for (Bind bind : getBinds()) {
      classpathPart = classpathPart + ":" + bind.getVolume().getPath();
    }
    classpathPart += " ";

    String launcherPart = " com.vmware.gemfire.bootstrap.LocatorLauncher start " + LOCATOR_NAME +
        " --automatic-module-classpath=/gemfire/extensions/*" +
        " --port=" + LOCATOR_PORT +
        " --hostname-for-clients=localhost";

    String jvmArgsPart = String.join(" ", jvmArgs);

    withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh"));
    withCommand("-c", execPart + classpathPart + jvmArgsPart + launcherPart);

    // The default Wait strategy waits for the first exposed (mapped) port to start listening
  }

  @Override
  protected void containerIsStarted(InspectContainerResponse containerInfo) {
    proxy = new GemFireProxyContainer(memberConfigs);
    proxy.withNetwork(network);
    // Once the proxy has started, all the forwarding ports, for each MemberConfig, will be set.
    proxy.start();

    for (int i = 0; i < memberConfigs.size(); i++) {
      MemberConfig config = memberConfigs.get(i);
      GemFireServerContainer<?> server = new GemFireServerContainer<>(config, image);
      server.withNetwork(network);

      server.start();
      servers.add(server);
    }
  }

  @Override
  public void stop() {
    proxy.stop();
    servers.forEach(GenericContainer::stop);
    super.stop();
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
   * Add the given gemfire property to a specific GemFire server instance.
   *
   * @param serverIndex the instance to target. Instances are numbered from 0.
   * @param name        the name of the property. The property will automatically be prefixed with
   *                    {@code gemfire.}
   * @param value       the value of the property to set
   */
  public SELF withGemFireProperty(int serverIndex, String name, String value) {
    memberConfigs.get(serverIndex)
        .addConfig(container -> container.addJvmArg(String.format("-Dgemfire.%s=%s", name, value)));
    return self();
  }

  /**
   * Add the property to all GemFire members - this includes both the locator and all servers,
   * @param name        the name of the property. The property will automatically be prefixed with
   *                    {@code gemfire.}
   * @param value       the value of the property to set
   */
  public SELF withGemFireProperty(String name, String value) {
    addJvmArg(String.format("-Dgemfire.%s=%s", name, value));
    memberConfigs.forEach(member -> member.addConfig(container ->
        container.addJvmArg(String.format("-Dgemfire.%s=%s", name, value))));
    return self();
  }

  /**
   * Open a debug port for a given instance. The provided port will be exposed externally and the
   * server instance will wait until a debugger connects before continuing.
   *
   * @param serverIndex the instance to target. Instances are numbered from 1.
   * @param port        the port to use for debugging.
   */
  public SELF withDebugPort(int serverIndex, int port) {
    memberConfigs.get(serverIndex).addConfig(container -> {
      container.setPortBindings(Collections.singletonList(String.format("%d:%d", port, port)));
      container.addJvmArg(
          "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=0.0.0.0:" + port);
      System.err.println("Waiting for debugger to connect on port " + port);
    });
    return self();
  }

  /**
   * Accepts the license for the GemFire container by setting the ACCEPT_EULA=Y
   * variable as described at [someplace to be decided...]
   */
  public SELF acceptLicense() {
    addEnv("ACCEPT_EULA", "Y");
    memberConfigs.forEach(member -> member.addConfig(container -> container
        .addEnv("ACCEPT_EULA", "Y")));
    return self();
  }

  /**
   * Return the port at which the locator is listening. This would be used to configure a
   * GemFire client to connect.
   */
  public int getLocatorPort() {
    return getMappedPort(LOCATOR_PORT);
  }

  /**
   * Return the port that can be used to connect {@code gfsh} over HTTP.
   */
  public int getHttpPort() {
    return getMappedPort(HTTP_PORT);
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
   *
   * @param logOutput boolean indicating whether to log output. If the script returns a non-zero
   *                  error code, the output will always be logged.
   * @param commands  a list of commands to execute. There is no need to provide an explicit
   *                  {@code connect} command unless additional parameters are required.
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

    copyFileToContainer(Transferable.of(fullCommand, 06666), "/script.gfsh");
    try {
      ExecResult result = execInContainer("gfsh", "-e", "run --file=/script.gfsh");

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
