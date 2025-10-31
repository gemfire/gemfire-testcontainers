/*
 *  Copyright (c) VMware, Inc. 2023. All rights reserved.
 */

package com.vmware.gemfire.testcontainers;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
 *   try (GemFireCluster cluster = new GemFireCluster()) {
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
 * Note that locators are named "locator-0", "locator-1", ... "locator-N" and servers are similarly
 * named "server-0", "server-1", ... "server-N".
 */
public class GemFireCluster implements Closeable {

  public static final String DEFAULT_IMAGE =
      System.getProperty("gemfire.image", "gemfire/gemfire:10.1");

  public static final String ALL_GLOB = "*";
  public static final String LOCATOR_GLOB = "locator-*";
  public static final String SERVER_GLOB = "server-*";

  static final int JMX_PORT = 1099;
  static final int HTTP_PORT = 7070;

  private static final int DEFAULT_LOCATOR_COUNT = 1;
  private static final int DEFAULT_SERVER_COUNT = 2;

  private final DockerImageName image;
  private final String suffix;

  private GemFireProxyContainer proxy;
  private final List<Integer> locatorPorts = new ArrayList<>();
  private final List<Integer> locatorHttpPorts = new ArrayList<>();
  private final List<Integer> serverPorts = new ArrayList<>();
  private final List<MemberConfig> locatorConfigs = new ArrayList<>();
  private final List<MemberConfig> serverConfigs = new ArrayList<>();
  private Network network;

  private Runnable postDeployGfsh = () -> {
  };

  private Runnable configurePdxGfsh = () -> {
  };

  public GemFireCluster() {
    this(DEFAULT_IMAGE);
  }

  public GemFireCluster(String imageName) {
    this(DockerImageName.parse(imageName), DEFAULT_LOCATOR_COUNT, DEFAULT_SERVER_COUNT);
  }

  public GemFireCluster(int locatorCount, int serverCount) {
    this(DEFAULT_IMAGE, locatorCount, serverCount);
  }

  public GemFireCluster(String imageName, int locatorCount, int serverCount) {
    this(DockerImageName.parse(imageName), locatorCount, serverCount);
  }

  public GemFireCluster(DockerImageName image, int locatorCount, int serverCount) {
    this.image = image;
    suffix = Base58.randomString(6);

    for (int i = 0; i < locatorCount; i++) {
      locatorConfigs.add(new MemberConfig("locator", i, suffix));
    }

    for (int i = 0; i < serverCount; i++) {
      serverConfigs.add(new MemberConfig("server", i, suffix));
    }
  }

  /**
   * Start the cluster. The following steps are performed:
   * <ul>
   *   <li>Launch the proxy container</li>
   *   <li>Create locator containers and apply preStart configuration</li>
   *   <li>Start locators and wait for complete startup</li>
   *   <li>Execute any PDX configuration command</li>
   *   <li>Create server containers and apply preStart configuration</li>
   *   <li>Start servers and wait for complete startup</li>
   *   <li>Execute any post-deploy gfsh commands (defined using <code>withGfsh()</code>)</li>
   * </ul>
   */
  public void start() {
    network =
        Network.builder().createNetworkCmdModifier(it -> it.withName("gemfire-" + suffix)).build();

    proxy = new GemFireProxyContainer(locatorConfigs, serverConfigs);
    proxy.withNetwork(network);
    proxy.start();

    // Now we can build up our locator addresses
    String locatorAddresses = locatorConfigs.stream()
        .map(x -> String.format("%s[%d]", x.getHostname(), x.getPort()))
        .collect(Collectors.joining(","));

    for (MemberConfig config : locatorConfigs) {
      GemFireLocatorContainer<?> locator =
          new GemFireLocatorContainer<>(config, image, network, locatorAddresses);
      config.setContainer(locator);
      locator.start();
      locatorPorts.add(config.getPort());
      locatorHttpPorts.add(config.getProxyHttpPublicPort());
    }

    locatorConfigs.forEach(x -> x.getContainer().waitToStart());
    configurePdxGfsh.run();

    for (MemberConfig config : serverConfigs) {
      GemFireServerContainer<?> server =
          new GemFireServerContainer<>(config, image, network, locatorAddresses);
      config.setContainer(server);
      server.start();
      serverPorts.add(config.getPort());
    }

    serverConfigs.forEach(x -> x.getContainer().waitToStart());
    postDeployGfsh.run();
  }

  /**
   * Stop all locator and server containers
   */
  @Override
  public void close() {
    stopProxy();
    serverConfigs.stream()
        .map(MemberConfig::getContainer)
        .filter(Objects::nonNull)
        .forEach(GenericContainer::stop);
    locatorConfigs.stream()
        .map(MemberConfig::getContainer)
        .filter(Objects::nonNull)
        .forEach(GenericContainer::stop);
  }

  /**
   * Return all the containers mapped by member name: locator-0, locator-1..., server-0, server-1...
   *
   * @return a map of member-name : container
   */
  public Map<String, AbstractGemFireContainer<?>> getContainers() {
    Map<String, AbstractGemFireContainer<?>> result = new HashMap<>();
    locatorConfigs.forEach(c -> result.put(c.getMemberName(), c.getContainer()));
    serverConfigs.forEach(c -> result.put(c.getMemberName(), c.getContainer()));
    return result;
  }

  /**
   * Return the Docker network the cluster is running on.
   *
   * @return the docker network
   */
  public Network getNetwork() {
    return network;
  }

  /**
   * Explicitly stop the socat proxy container. Typically this is not necessary but might be useful
   * in some testing scenarios.
   */
  public void stopProxy() {
    if (proxy != null) {
      proxy.stop();
    }
  }

  /**
   * A low-level convenience method to be able to apply a given configuration to each GemFire
   * member container before startup. The configuration can be applied to locators or servers
   * separately, or to all locators and servers.
   * <p>
   * For example:
   * <pre>
   *   cluster.withConfiguration(ApplyTo.SERVERS, container -&gt;
   *       container.addJvmArg("--J=-Dcustom.property=true"));
   * </pre>
   *
   * @param memberGlob a member name glob to select which members should receive the configuration
   * @param config     the configuration that is applied before container startup
   * @return this
   */
  public GemFireCluster withConfiguration(String memberGlob,
                                          Consumer<AbstractGemFireContainer<?>> config) {
    findMembers(memberGlob).forEach(member -> member.addConfig(config));
    return this;
  }

  /**
   * Configuration that can be applied to containers after container creation, but before GemFire
   * is actually started. For example, this could be used to copy any files to the containers before
   * startup.
   *
   * @param memberGlob a member name glob to select which members should receive the configuration
   * @param config     the configuration to apply
   * @return this
   */
  public GemFireCluster withPreStart(String memberGlob,
                                     Consumer<AbstractGemFireContainer<?>> config) {
    findMembers(memberGlob).forEach(member -> member.addPreStart(config));
    return this;
  }

  /**
   * The provided consumer is applied to the given members and will receive all log messages. The
   * consumer receives the name of the member and the log message. Member names will appear as
   * <code>locator-N</code> or <code>server-N</code> where N starts at 0.
   *
   * @param memberGlob a member name glob to select which members should receive the configuration
   * @param consumer   consumer that output frames should be sent to
   * @return this
   */
  public GemFireCluster withLogConsumer(String memberGlob, BiConsumer<String, String> consumer) {
    return withConfiguration(memberGlob, container -> container
        .withLogConsumer(new MemberLoggingConsumer(container.getMemberName(), consumer)));
  }

  private static class MemberLoggingConsumer implements Consumer<OutputFrame> {
    private final BiConsumer<String, String> realConsumer;
    private final String memberName;

    MemberLoggingConsumer(String memberName, BiConsumer<String, String> realConsumer) {
      this.memberName = memberName;
      this.realConsumer = realConsumer;
    }

    @Override
    public void accept(OutputFrame outputFrame) {
      realConsumer.accept(memberName, outputFrame.getUtf8String());
    }
  }

  /**
   * The given local paths will be made available on the classpath of specied members.
   *
   * @param memberGlob a member name glob to select which members should receive the configuration
   * @param classpaths which are resolved to absolute paths and then bind-mounted in each container
   *                   at the location {@code /classpath/0, /classpath/1, etc.}. These paths are
   *                   added to the classpath of the started JVM instance.
   * @return this
   */
  public GemFireCluster withClasspath(String memberGlob, String... classpaths) {
    return withConfiguration(memberGlob, container -> {
      for (int i = 0; i < classpaths.length; i++) {
        container.addFileSystemBind(classpaths[i], "/classpath/" + i, BindMode.READ_ONLY);
      }
    });
  }

  /**
   * Add the property to the specified GemFire members. The property is set as a GemFire Java
   * system property and will automatically be prefixed with '{@code gemfire.}'.
   *
   * @param memberGlob a member name glob to select which members should receive the configuration
   * @param name       the name of the property. The property need not be prefixed with
   *                   {@code gemfire.}
   * @param value      the value of the property to set
   * @return this
   */
  public GemFireCluster withGemFireProperty(String memberGlob, String name, String value) {
    return withConfiguration(memberGlob,
        container -> container.addJvmArg(String.format("--J=-Dgemfire.%s=%s", name, value)));
  }

  /**
   * Open a debug port for a given instance. The provided port will be exposed externally and the
   * server instance will wait until a debugger connects before continuing.
   *
   * @param memberGlob a member name glob to select which members should receive the configuration
   * @param basePort   the port to use for debugging. If multiple members are selected, the port will
   *                   be incremented for each additional member.
   * @return this
   */
  public GemFireCluster withDebugPort(String memberGlob, int basePort) {
    AtomicInteger port = new AtomicInteger(basePort);
    return withConfiguration(memberGlob,
        container -> {
          int localPort = port.getAndIncrement();
          container.setPortBindings(
              Collections.singletonList(String.format("%d:%d", localPort, localPort)));
          container.addJvmArg(
              "--J=-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=0.0.0.0:"
                  + localPort);
          System.err.println("Waiting for debugger to connect on port " + localPort);
        });
  }

  /**
   * Set the timeout to wait for GemFire members to start.
   *
   * @param memberGlob a member name glob to select which members should receive the configuration
   * @param timeout    the timeout in seconds
   * @return this
   */
  public GemFireCluster withStartupTimeout(String memberGlob, int timeout) {
    return withConfiguration(memberGlob, container -> container.setStartupTimeout(timeout));
  }

  /**
   * Set the hostname-for-clients option when starting members. This defaults to {@code localhost}
   * and should only need to be changed for special testing scenarios; for example when using a
   * SNI proxy.
   *
   * @param memberGlob         a member name glob to select which members should receive the
   *                           configuration
   * @param hostnameForClients the hostname to provide to clients for connecting.
   * @return this
   */
  public GemFireCluster withHostnameForClients(String memberGlob, String hostnameForClients) {
    return withConfiguration(memberGlob,
        container -> container.setHostnameForClients(hostnameForClients));
  }

  /**
   * Accepts the license for the GemFire cluster by setting the ACCEPT_EULA=Y
   * variable as described at [someplace to be decided...]
   *
   * @return this
   */
  public GemFireCluster acceptLicense() {
    return withConfiguration(ALL_GLOB, container -> container.addEnv("ACCEPT_TERMS", "y")
    );
  }

  /**
   * Provide a {@code cache.xml} to be used by each GemFire members on startup. This files needs
   * to be a class resource.
   *
   * @param memberGlob a member name glob to select which members should receive the configuration
   * @param cacheXml   the cache xml file to use
   * @return this
   */
  public GemFireCluster withCacheXml(String memberGlob, String cacheXml) {
    byte[] rawBytes;
    try (InputStream is = getClass().getResourceAsStream(cacheXml)) {
      if (is == null) {
        throw new RuntimeException("Unable to locate resource: " + cacheXml);
      }
      rawBytes = readAllBytes(is);
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to read resource: " + cacheXml, e);
    }
    Transferable fileData = Transferable.of(new String(rawBytes));

    return withConfiguration(memberGlob, container -> {
      container.withCopyToContainer(fileData, "/cache.xml");
      container.addJvmArg("--J=-Dgemfire.cache-xml-file=/cache.xml");
    });
  }

  protected static byte[] readAllBytes(InputStream input) throws IOException {
    byte[] buffer = new byte[8192];
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    int readCount;
    while ((readCount = input.read(buffer)) != -1) {
      baos.write(buffer, 0, readCount);
    }
    return baos.toByteArray();
  }

  private List<MemberConfig> findMembers(String memberGlob) {
    List<MemberConfig> result = new ArrayList<>();
    Pattern regex = globToRegex(memberGlob);

    for (MemberConfig member : locatorConfigs) {
      if (regex.matcher(member.getMemberName()).matches()) {
        result.add(member);
      }
    }

    for (MemberConfig member : serverConfigs) {
      if (regex.matcher(member.getMemberName()).matches()) {
        result.add(member);
      }
    }

    if (result.isEmpty()) {
      throw new IllegalArgumentException("No members matching '" + memberGlob + "' found");
    }

    return result;
  }

  public static Pattern globToRegex(String glob) {
    return Pattern.compile(
        "(?s)^\\Q" +
            glob.replace("\\E", "\\E\\\\E\\Q")
                .replace("*", "\\E.*\\Q")
                .replace("?", "\\E.\\Q") +
            "\\E$"
    );
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
  public GemFireCluster withPdx(String pdxAutoSerializerRegex, boolean pdxReadSerialized) {
    configurePdxGfsh =
        () -> gfsh(
            true,
            String.format(
                "configure pdx --disk-store=DEFAULT --read-serialized=%s --auto-serializable-classes=%s",
                pdxReadSerialized,
                pdxAutoSerializerRegex
            )
        );
    return this;
  }

  /**
   * Configure explicit port to expose for locators and servers.
   * <p>
   * Under most circumstances, the locator's  port can be ephemeral and a test can simply use
   * {@link #getLocatorPort()} to retrieve it. At other times it may be necessary to explicitly
   * provide the port for the locator to use which is when this API should be used. Similarly for
   * server ports.
   *
   * @param memberGlob a member name glob to select which members should receive the configuration
   * @param ports      the ports to expose. The number of ports must match the number of locators
   *                   or servers that match the glob.
   * @return this
   */
  public GemFireCluster withPorts(String memberGlob, int... ports) {
    List<MemberConfig> members = findMembers(memberGlob);
    if (members.size() != ports.length) {
      throw new IllegalArgumentException(String.format(
          "Found %d members but supplied %d ports. They must be the same.",
          members.size(), ports.length));
    }
    for (int i = 0; i < ports.length; i++) {
      members.get(i).setPort(ports[i]);
    }
    return this;
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
  public GemFireCluster withGfsh(boolean logOutput, String... commands) {
    postDeployGfsh = () -> gfsh(logOutput, commands);
    return this;
  }

  /**
   * Return the port at which the locator is listening. This would be used to configure a
   * GemFire client to connect. If multiple locators are defined then the first locator's port
   * is returned.
   *
   * @return the locator port
   */
  public int getLocatorPort() {
    return locatorPorts.get(0);
  }

  /**
   * Return a list of all locator ports.
   *
   * @return all locator ports
   */
  public List<Integer> getLocatorPorts() {
    return locatorPorts;
  }

  /**
   * Return a list of all server ports.
   *
   * @return all server ports
   */
  public List<Integer> getServerPorts() {
    return serverPorts;
  }

  /**
   * Return the ports that can be used to connect {@code gfsh} over HTTP. One port for each
   * configured locator is returned.
   *
   * @return the http ports for gfsh connections
   * @deprecated use {@link #getHttpPorts(String)} instead
   */
  @Deprecated
  public List<Integer> getHttpPorts() {
    return locatorHttpPorts;
  }

  /**
   * Return the ports that can be used to connect to the found members over HTTP.
   *
   * @param memberGlob a member name glob to select which members should receive the configuration
   * @return the http ports to connect to the member's HTTP service
   */
  public List<Integer> getHttpPorts(String memberGlob) {
    return findMembers(memberGlob).stream()
        .map(MemberConfig::getProxyHttpPublicPort)
        .collect(Collectors.toList());
  }

  /**
   * Return a builder object that can be used to configure and create a {@link Gfsh} instance
   * when additional arguments are required particularly for security and SSL connectivity.
   *
   * @return a gfsh builder instance
   */
  public Gfsh.Builder gfshBuilder() {
    return new Gfsh.Builder(locatorConfigs.get(0).getContainer());
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
   * <p>
   * This method does not provide the ability configure additional gfsh connection options.
   * Instead, use {@link #gfshBuilder()} and the {@link Gfsh} class for that.
   *
   * @param logOutput boolean indicating whether to log output. If the script returns a non-zero
   *                  error code, the output will always be logged.
   * @param commands  a list of commands to execute. There is no need to provide an explicit
   *                  {@code connect} command unless additional parameters are required.
   * @return the result output of executing the gfsh commands as a script.
   */
  public String gfsh(boolean logOutput, String... commands) {
    return gfshBuilder()
        .withLogging(logOutput)
        .build()
        .run(commands);
  }

}
