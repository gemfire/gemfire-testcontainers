/*
 *  Copyright (c) VMware, Inc. 2023. All rights reserved.
 */

package com.vmware.gemfire.testcontainers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.swing.text.html.Option;

import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

public class GemFireServerContainer<SELF extends GemFireServerContainer<SELF>>
    extends AbstractGemFireContainer<SELF> {

  private static final List<String> DEFAULT_SERVER_JVM_ARGS =
      Collections.unmodifiableList(Arrays.asList(
          "--J=-Dgemfire.use-cluster-configuration=true",
          "--J=-Dgemfire.locator-wait-time=120"
      ));

  public GemFireServerContainer(MemberConfig config, DockerImageName image, Network network,
                                String locatorAddresses) {
    super(config, image, network, locatorAddresses);
  }

  @Override
  public String getMemberName() {
    return config.getMemberName();
  }

  @Override
  protected String startupMessage() {
    return "Server " + config.getMemberName() + " startup completed";
  }

  @Override
  protected List<String> getDefaultJvmArgs() {
    return new ArrayList<>(DEFAULT_SERVER_JVM_ARGS);
  }

  @Override
  protected void configure() {
    withCreateContainerCmdModifier(it -> it.withName(config.getHostname())
        .withAliases(config.getMemberName()));

    config.apply(this);

    List<String> command = new ArrayList<>();
    command.add("gfsh");
    command.add("start");
    command.add("server");
    command.add("--name=" + config.getMemberName());
    command.add("--server-port=" + config.getPort());
    command.add("--locators=" + locatorAddresses);
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

    logger().info("Starting GemFire server: {}:{}", config.getMemberName(),
        config.getProxyPublicPort());
  }

  @Override
  protected void containerIsCreated(String containerId) {
    config.applyPreStart(this);
  }

}
