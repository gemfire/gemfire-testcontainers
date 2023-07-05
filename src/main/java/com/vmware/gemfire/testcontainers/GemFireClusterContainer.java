/*
 *  Copyright (c) VMware, Inc. 2023. All rights reserved.
 */

package com.vmware.gemfire.testcontainers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.github.dockerjava.api.command.InspectContainerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.Base58;
import org.testcontainers.utility.DockerImageName;

/**
 * Class used to instantiate a GemFire cluster. The default settings will launch a locator and 2
 * servers.
 * <p>
 * Example:
 * <pre>
 *   try (GemFireClusterContainer&lt;?&gt; cluster = new GemFireClusterContainer&lt;&gt;()) {
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
 *     Region&lt;Integer, String&gt; region =
 *         cache.&lt;Integer, String&gt;createClientRegionFactory(ClientRegionShortcut.PROXY)
 *             .create("FOO");
 *
 *     region.put(1, "Hello World");
 *
 *     assertThat(region.get(1)).isEqualTo("Hello World");
 *   }
 * </pre>
 */
public class GemFireClusterContainer<SELF extends GemFireClusterContainer<SELF>>
    extends AbstractGemFireContainer<SELF> {

  public static final String DEFAULT_IMAGE = "gemfire/gemfire:9.15.6";

  private static final String LOCATOR_NAME_PREFIX = "locator-0";

  private static final int HTTP_PORT = 7070;

  private static final int JMX_PORT = 1099;

  private static final List<String> DEFAULT_LOCATOR_ARGS = Arrays.asList(
      "--J=-Dgemfire.use-cluster-configuration=true",
      "--J=-Dgemfire.http-service-port=" + HTTP_PORT,
      "--J=-Dgemfire.jmx-manager-start=true",
      "--hostname-for-clients=localhost"
  );

  private static final int DEFAULT_SERVER_COUNT = 2;

  private final Network network;
  private final DockerImageName image;
  private final String suffix;

  private GemFireProxyContainer proxy;
  private final List<GemFireServerContainer<?>> servers = new ArrayList<>();
  private final String locatorName;
  private final int serverCount;
  private int locatorPort = 0;
  private Runnable postDeployGfsh = () -> {};
  private Runnable configurePdxGfsh = () -> {};

  /**
   * List that holds configuration for each cluster server.
   */
  private final List<MemberConfig> memberConfigs;

  public GemFireClusterContainer() {
    this(DEFAULT_IMAGE);
  }

  public GemFireClusterContainer(String imageName) {
    this(DEFAULT_SERVER_COUNT, DockerImageName.parse(imageName));
  }

  public GemFireClusterContainer(int serverCount, String imageName) {
    this(serverCount, DockerImageName.parse(imageName));
  }

  public GemFireClusterContainer(int serverCount, DockerImageName image) {
    super(image);
    this.serverCount = serverCount;
    this.image = image;
    suffix = Base58.randomString(6);

    locatorName = LOCATOR_NAME_PREFIX + "-" + suffix;
    jvmArgs = new ArrayList<>(DEFAULT_LOCATOR_ARGS);

    memberConfigs = new ArrayList<>();
    memberConfigs.add(new MemberConfig(locatorName));

    for (int i = 0; i < serverCount; i++) {
      String name = String.format("server-%d-%s", i, suffix);
      MemberConfig config = new MemberConfig(name);
      memberConfigs.add(config);
    }

    network =
        Network.builder().createNetworkCmdModifier(it -> it.withName("gemfire-" + suffix)).build();
    withNetwork(network);

    proxy = new GemFireProxyContainer(memberConfigs);
    proxy.withNetwork(network);
    // Once the proxy has started, all the public ports, for each MemberConfig, will be set.
    proxy.start();
  }

  @Override
  protected void configure() {
    super.configure();

    withCreateContainerCmdModifier(it -> it.withName(locatorName));

    // If we didn't request an explicit locator port then just expose an ephemeral port
    if (locatorPort == 0) {
      locatorPort = memberConfigs.get(0).getProxyPublicPort();
    } else {
      setPortBindings(Collections.singletonList(String.format("%d:%d", locatorPort, locatorPort)));
    }

    // Once the locator port is established, update the MemberConfigs
    memberConfigs.forEach(m -> m.setLocatorHostPort(locatorName, locatorPort));

    List<String> command = new ArrayList<>();
    command.add("gfsh");
    command.add("start");
    command.add("locator");
    command.add("--name=" + locatorName);
    command.add("--port=" + locatorPort);
    command.addAll(jvmArgs);

    String classpathPart = getBinds()
        .stream()
        .map(bind -> bind.getVolume().getPath())
        .collect(Collectors.joining(":"));

    if (!classpathPart.isEmpty()) {
      command.add("--classpath=" + classpathPart);
    }

    withCommand(command.toArray(new String[]{}));
  }

  @Override
  protected void containerIsStarted(InspectContainerResponse containerInfo) {
    configurePdxGfsh.run();

    // Exclude the first (locator) entry
    for (MemberConfig config : memberConfigs.subList(1, memberConfigs.size())) {
      GemFireServerContainer<?> server = new GemFireServerContainer<>(config, image);
      server.withNetwork(network);

      server.start();
      servers.add(server);
    }

    postDeployGfsh.run();
  }

  @Override
  public void stop() {
    if (proxy != null) {
      proxy.stop();
    }
    servers.forEach(GenericContainer::stop);
    super.stop();
  }

  /**
   * A low-level convenience method to be able to apply a given configuration to  each GemFire
   * server container on startup. Note that this method does not apply the configuration to the
   * locator. For that, simply use the regular methods exposed by this class.
   * <p>
   * For example:
   * <pre>
   *   cluster.withServerConfiguration(container -&gt;
   *       container.addJvmArg("--J=-Dcustom.property=true"));
   * </pre>
   *
   * @param config the configuration that is applied before container startup
   * @return this
   */
  public SELF withServerConfiguration(Consumer<AbstractGemFireContainer<?>> config) {
    memberConfigs.forEach(member -> member.addConfig(config));
    return self();
  }

  /**
   * The provided consumer is applied to this container, (effectively the locator), and all
   * additional GemFire server containers managed by this instance.
   *
   * @param consumer consumer that output frames should be sent to
   * @return this
   */
  @Override
  public SELF withLogConsumer(Consumer<OutputFrame> consumer) {
    super.withLogConsumer(consumer);
    return withServerConfiguration(container -> container.withLogConsumer(consumer));
  }

  /**
   * The given local paths will be made available on the classpath of the container.
   *
   * @param classpaths which are resolved to absolute paths and then bind-mounted in each container
   *                   at the location {@code /classpath/0, /classpath/1, etc.}. These paths are
   *                   added to the classpath of the started JVM instance.
   * @return this
   */
  public SELF withClasspath(String... classpaths) {
    for (int i = 0; i < classpaths.length; i++) {
      addFileSystemBind(classpaths[i], "/classpath/" + i, BindMode.READ_ONLY);
    }
    return withServerConfiguration(container -> {
      for (int i = 0; i < classpaths.length; i++) {
        container.addFileSystemBind(classpaths[i], "/classpath/" + i, BindMode.READ_ONLY);
      }
    });
  }

  /**
   * Add the given gemfire property to a specific GemFire server instance.
   *
   * @param serverIndex the instance to target. Instances are numbered from 0.
   * @param name        the name of the property. The property will automatically be prefixed with
   *                    {@code gemfire.}
   * @param value       the value of the property to set
   * @return this
   */
  public SELF withGemFireProperty(int serverIndex, String name, String value) {
    memberConfigs
        .get(serverIndex)
        .addConfig(container -> container.addJvmArg(String.format("--J=-Dgemfire.%s=%s", name, value)));
    return self();
  }

  /**
   * Add the property to all GemFire members - this includes both the locator and all servers.
   * The property is set as a GemFire java system property and will automatically be prefixed with
   * '{@code gemfire.}'.
   *
   * @param name  the name of the property. The property need not be prefixed with
   *              {@code gemfire.}
   * @param value the value of the property to set
   * @return this
   */
  public SELF withGemFireProperty(String name, String value) {
    addJvmArg(String.format("--J=-Dgemfire.%s=%s", name, value));
    return withServerConfiguration(
        container -> container.addJvmArg(String.format("--J=-Dgemfire.%s=%s", name, value)));
  }

  /**
   * Open a debug port for a given instance. The provided port will be exposed externally and the
   * server instance will wait until a debugger connects before continuing.
   *
   * @param serverIndex the instance to target. Instances are numbered from 1.
   * @param port        the port to use for debugging.
   * @return this
   */
  public SELF withDebugPort(int serverIndex, int port) {
    memberConfigs
        .get(serverIndex)
        .addConfig(container -> {
          container.setPortBindings(Collections.singletonList(String.format("%d:%d", port, port)));
          container.addJvmArg(
              "--J=-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=0.0.0.0:" + port);
          System.err.println("Waiting for debugger to connect on port " + port);
        });
    return self();
  }

  /**
   * Accepts the license for the GemFire container by setting the ACCEPT_EULA=Y
   * variable as described at [someplace to be decided...]
   *
   * @return this
   */
  public SELF acceptLicense() {
    addEnv("ACCEPT_TERMS", "y");
    return withServerConfiguration(container -> container.addEnv("ACCEPT_TERMS", "y"));
  }

  /**
   * Provide a {@code cache.xml} to be used by each GemFire server on startup. This files needs
   * to be a class resource.
   *
   * @param cacheXml the cache xml file to use
   * @return this
   */
  public SELF withCacheXml(String cacheXml) {
    byte[] rawBytes;
    try (InputStream is = getClass().getResourceAsStream(cacheXml)) {
      if (is == null) {
        throw new RuntimeException("Unable to locate resource: " + cacheXml);
      }
      rawBytes = readAllBytes(is);
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to read resource: " + cacheXml, e);
    }
    String localFile = new String(rawBytes);

    return withServerConfiguration(container -> {
      container.withCopyToContainer(Transferable.of(localFile), "/cache.xml");
      container.addJvmArg("--J=-Dgemfire.cache-xml-file=/cache.xml");
    });
  }

  private byte[] readAllBytes(InputStream input) throws IOException {
    byte[] buffer = new byte[8192];
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    int readCount;
    while ((readCount = input.read(buffer)) != -1) {
      baos.write(buffer, 0, readCount);
    }
    return baos.toByteArray();
  }

  /**
   * Configure PDX. This would typically need to happen after the locator starts but before
   * servers start. To facilitate that, the configuration is provided with this API before the
   * cluster is started.
   *
   * @param pdxAutoSerializerRegex the regex that defines classes to be considered for PDX
   *                               serialization
   * @param pdxReadSerialized      boolean that determines whether values are retrieved as
   *                               {@code PdxInstance}s
   * @return this
   */
  public SELF withPdx(String pdxAutoSerializerRegex, boolean pdxReadSerialized) {
    configurePdxGfsh =
        () -> {
          logger().info("Configuring PDX");
          gfsh(
              true,
              String.format(
                  "configure pdx --disk-store=DEFAULT --read-serialized=%s --auto-serializable-classes=%s",
                  pdxReadSerialized,
                  pdxAutoSerializerRegex
              )
          );
        };
    return self();
  }

  /**
   * Configure an explicit port to expose for the locator.
   * <p>
   * Under most circumstances, the locator's  port can be ephemeral and a test can simply use
   * {@link #getLocatorPort()} to retrieve it. At other times it may be necessary to explicitly
   * provide the port for the locator to use which is when this API should be used.
   *
   * @param locatorPort the port to expose for the locator
   * @return this
   */
  public SELF withLocatorPort(int locatorPort) {
    this.locatorPort = locatorPort;
    return self();
  }

  /**
   * Specifies gfsh commands to run immediately as part of the container / cluster startup. This is
   * useful when the {@code GemFireClusterContainer} is configured as a test {@code Rule} or
   * annotated with {@code  org.testcontainers.junit.jupiter.Container}.
   * <p>
   * In order to execute gfsh commands after startup, use the {@link #gfsh(boolean, String...)}
   * method instead.
   *
   * @param logOutput boolean indicating whether to log output. If the script returns a non-zero
   *                  error code, the output will always be logged.
   * @param commands  a list of commands to execute. There is no need to provide an explicit
   *                  {@code connect} command unless additional parameters are required.
   * @return this
   */
  public SELF withGfsh(boolean logOutput, String... commands) {
    postDeployGfsh = () -> gfsh(logOutput, commands);
    return self();
  }

  /**
   * Return the port at which the locator is listening. This would be used to configure a
   * GemFire client to connect.
   *
   * @return the locator port
   */
  public int getLocatorPort() {
    return locatorPort;
  }

  /**
   * Return the port that can be used to connect {@code gfsh} over HTTP.
   *
   * @return the http port for gfsh connections
  public int getHttpPort() {
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
   * <p>
   * In order to execute gfsh commands as <i>part of startup</i>, use the
   * {@link #withGfsh(boolean, String...)} method instead.
   *
   * @param logOutput boolean indicating whether to log output. If the script returns a non-zero
   *                  error code, the output will always be logged.
   * @param commands  a list of commands to execute. There is no need to provide an explicit
   *                  {@code connect} command unless additional parameters are required.
   * @return the result output of executing the gfsh commands as a script.
   */
  public String gfsh(boolean logOutput, String... commands) {
    if (commands.length == 0) {
      return null;
    }

    Logger logger = LoggerFactory.getLogger("gfsh");
    String fullCommand;
    if (commands[0].startsWith("connect")) {
      fullCommand = "";
    } else {
      fullCommand = String.format("connect --jmx-manager=localhost[%d]\n", JMX_PORT);
    }
    fullCommand += String.join("\n", commands);

    copyFileToContainer(Transferable.of(fullCommand, 06666), "/script.gfsh");
    ExecResult result;
    try {
      result = execInContainer("gfsh", "-e", "run --file=/script.gfsh");

      boolean scriptError = result.getExitCode() != 0;
      if (logOutput || scriptError) {
        for (String line : result.toString().split("\n")) {
          if (scriptError) {
            logger.error(line);
          } else {
            logger.info(line);
          }
        }
      }
    } catch (Exception ex) {
      throw new RuntimeException("Error executing gfsh command: " + Arrays.asList(commands), ex);
    }

    if (result.getExitCode() != 0) {
      throw new RuntimeException(
          "Error executing gfsh command. Return code: " + result.getExitCode());
    }

    return result.toString();
  }
}
