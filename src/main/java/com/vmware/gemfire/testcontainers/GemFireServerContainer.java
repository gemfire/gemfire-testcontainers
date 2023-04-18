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
      "-Dgemfire.use-cluster-configuration=true",
      "-Dgemfire.log-file=",
      "-Dgemfire.locator-wait-time=120",
      "-Dgemfire.standard-output-always-on=true"
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

    String locator = String.format("%s[%d]", config.getLocatorHost(), config.getLocatorPort());
    jvmArgs.add("-Dgemfire.locators=" + locator);

    config.apply(this);

    String classpathPart = getBinds()
        .stream()
        .map(bind -> bind.getVolume().getPath())
        .collect(Collectors.joining(":"));

    String jvmArgsPart = String.join(" ", jvmArgs);

    addEnv("CLASSPATH", classpathPart);
    addEnv("JVM_ARGS", jvmArgsPart);

    withCommand(
        "server",
        config.getServerName(),
        "--server-port=" + config.getProxyForwardPort(),
        "--hostname-for-clients=localhost"
    );

    logger().info("Starting GemFire server: {}:{}", config.getServerName(),
        config.getProxyForwardPort());
  }
}
