/*
 *  Copyright (c) VMware, Inc. 2023. All rights reserved.
 */

package com.vmware.gemfire.testcontainers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

public class GemFireLocatorContainer<SELF extends GemFireLocatorContainer<SELF>>
    extends AbstractGemFireContainer<SELF> {

  private static final List<String> DEFAULT_LOCATOR_JVM_ARGS =
      Collections.unmodifiableList(Arrays.asList(
          "--J=-Dgemfire.use-cluster-configuration=true",
          "--J=-Dgemfire.jmx-manager-start=true"
      ));

  public GemFireLocatorContainer(MemberConfig config, DockerImageName image, Network network,
                                 String locatorAddresses) {
    super(config, image, network, locatorAddresses);
  }

  @Override
  public String getMemberName() {
    return config.getMemberName();
  }

  @Override
  protected String startupMessage() {
    return "Locator started on";
  }

  @Override
  protected List<String> getDefaultJvmArgs() {
    return new ArrayList<>(DEFAULT_LOCATOR_JVM_ARGS);
  }

  @Override
  protected void configure() {
    withCreateContainerCmdModifier(it -> it.withName(config.getHostname())
        .withAliases(config.getMemberName()));

    config.apply(this);

    List<String> command = new ArrayList<>();
    command.add("gfsh");
    command.add("start");
    command.add("locator");
    command.add("--name=" + config.getMemberName());
    command.add("--port=" + config.getPort());
    command.add("--locators=" + locatorAddresses);
    command.add("--J=-Dgemfire.http-service-port=" + config.getProxyHttpPublicPort());
    command.add("--hostname-for-clients=" + hostnameForClients);
    command.addAll(jvmArgs);

    String classpathPart = getBinds()
        .stream()
        .map(bind -> bind.getVolume().getPath())
        .collect(Collectors.joining(":"));

    if (!classpathPart.isEmpty()) {
      command.add("--classpath=" + classpathPart);
    }

    withCommand(command.toArray(new String[]{}));

    logger().info("Starting GemFire locator: {}:{}", config.getMemberName(),
        config.getProxyPublicPort());
  }

  @Override
  protected void containerIsCreated(String containerId) {
    config.applyPreStart(this);
  }

}
