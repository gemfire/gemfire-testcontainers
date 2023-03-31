package com.vmware.gemfire;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.Base58;
import org.testcontainers.utility.DockerImageName;

public class GemFireClusterContainer<SELF extends GemFireClusterContainer<SELF>> extends GenericContainer<SELF> {

  private static final Logger LOG = LoggerFactory.getLogger(GemFireClusterContainer.class);

  private static final String DEFAULT_IMAGE =
      "dev.registry.pivotal.io/pivotal-gemfire/vmware-gemfire:10.0.0-build.1806-dev_photon4";

  private final DockerImageName image;
  private final String suffix;
  private int serverCount = 2;
  private String classpath = null;

  private GemFireProxyContainer proxy;
  private GemFireLocatorContainer locator;
  private final List<GemFireServerContainer> servers = new ArrayList<>();

  public GemFireClusterContainer() {
    this(DEFAULT_IMAGE);
  }

  public GemFireClusterContainer(String imageName) {
    this(DockerImageName.parse(imageName));
  }

  public GemFireClusterContainer(DockerImageName image) {
    this.image = image;
    this.suffix = Base58.randomString(6);
  }

  @Override
  public void start() {
    Network network = Network.builder()
        .createNetworkCmdModifier(it -> it.withName("gemfire-cluster-" + suffix))
        .build();

    List<String> serverNames = generateServerNames();

    proxy = new GemFireProxyContainer(serverNames);
    proxy.withNetwork(network);
    proxy.start();

    locator = new GemFireLocatorContainer(image);
    locator.withNetwork(network);
    locator.start();

    for (Map.Entry<String, Integer> entry : proxy.getMappedPorts().entrySet()) {
      GemFireServerContainer server =
          new GemFireServerContainer(entry.getKey(), entry.getValue(), DEFAULT_IMAGE);
      server.withNetwork(network);
      if (classpath != null) {
        server.withFileSystemBind(classpath, "/build", BindMode.READ_ONLY);
      }

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

  private List<String> generateServerNames() {
    List<String> names = new ArrayList<>(serverCount);
    for (int i = 1; i <= serverCount; i++) {
      String name = String.format("server-%d-%s", i, suffix);
      names.add(name);
    }

    return names;
  }

  public SELF withServers(int serverCount) {
    this.serverCount = serverCount;
    return self();
  }

  public SELF withClasspath(String classpath) {
    this.classpath = new File(classpath).getAbsolutePath();
    return self();
  }

  public int getLocatorPort() {
    return locator.getLocatorPort();
  }

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
