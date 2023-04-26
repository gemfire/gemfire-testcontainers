/*
 *  Copyright (c) VMware, Inc. 2022. All rights reserved.
 */

package com.vmware.gemfire.testcontainers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.testcontainers.utility.DockerImageName;

public class GemFireServerContainer<SELF extends GemFireServerContainer<SELF>>
    extends AbstractGemFireContainer<SELF> {

  private static final List<String> DEFAULT_JVM_ARGS = Arrays.asList(
      "--J=-Dgemfire.use-cluster-configuration=true",
      "--J=-Dgemfire.locator-wait-time=120",
      "--hostname-for-clients=localhost"
  );

  public GemFireServerContainer(MemberConfig config, String imageName) {
    this(config, DockerImageName.parse(imageName));
  }

  public GemFireServerContainer(MemberConfig config, DockerImageName image) {
    super(image);
    jvmArgs = new ArrayList<>(DEFAULT_JVM_ARGS);

    withCreateContainerCmdModifier(it -> it.withName(config.getServerName()));

    // This is just so that TC can use the mapped port for the initial wait strategy.
    withExposedPorts(config.getProxyForwardPort());

    jvmArgs.add(String.format("--J=-Dgemfire.locators=%s[%d]",
        config.getLocatorHost(), config.getLocatorPort()));

    config.apply(this);

    List<String> command = new ArrayList<>();
    command.add("gfsh");
    command.add("start");
    command.add("server");
    command.add("--name=" + config.getServerName());
    command.add("--server-port=" + config.getProxyForwardPort());
    command.addAll(jvmArgs);

    String classpathPart = getBinds()
        .stream()
        .map(bind -> bind.getVolume().getPath())
        .collect(Collectors.joining(":"));

    if (!classpathPart.isEmpty()) {
      command.add("--classpath=" + classpathPart);
    }

    withCommand(command.toArray(new String[]{}));

    logger().info("Starting GemFire server: {}:{}", config.getServerName(),
        config.getProxyForwardPort());
  }
}
