/*
 *  Copyright (c) VMware, Inc. 2023. All rights reserved.
 */

package com.vmware.gemfire.testcontainers;

import static com.vmware.gemfire.testcontainers.GemFireCluster.HTTP_PORT;

import java.util.ArrayList;
import java.util.List;

import com.github.dockerjava.api.command.InspectContainerResponse;
import org.testcontainers.containers.SocatContainer;
import org.testcontainers.images.builder.Transferable;

/**
 * This proxy provides the ability for internal GemFire server ports to dynamically match the
 * exposed, ephemeral ports. This enables the GemFire single-hop mechanism and avoids the need to
 * expose hardcoded 1:1 port mappings from internal servers to external mappings.
 */
public class GemFireProxyContainer extends SocatContainer {

  private static final String STARTER_SCRIPT = "/gemfire_proxy_start.sh";

  private static final int BASE_PORT = 2000;

  private final List<MemberConfig> serverConfigs;

  private final List<MemberConfig> locatorConfigs;

  public GemFireProxyContainer(List<MemberConfig> locatorConfigs, List<MemberConfig> serverConfigs) {
    super();
    this.locatorConfigs = locatorConfigs;
    this.serverConfigs = serverConfigs;

    int port = BASE_PORT;
    // Set up the locator ports - both regular and http
    for (int i = 0; i < locatorConfigs.size(); i++) {
      addExposedPort(port);
      locatorConfigs.get(i).setProxyListenPort(port);
      port++;
      addExposedPort(port);
      locatorConfigs.get(i).setProxyHttpListenPort(port);
      port++;
    }

    for (int i = 0; i < serverConfigs.size(); i++) {
      addExposedPort(port);
      serverConfigs.get(i).setProxyListenPort(port);
      port++;
      addExposedPort(port);
      serverConfigs.get(i).setProxyHttpListenPort(port);
      port++;
    }
  }

  @Override
  public void configure() {
    withCommand("-c",
        "while [ ! -f " + STARTER_SCRIPT + " ]; do sleep 0.1; done; " + STARTER_SCRIPT);
  }

  @Override
  protected void containerIsStarting(InspectContainerResponse containerInfo) {
    List<String> socats = new ArrayList<>();
    for (MemberConfig config : locatorConfigs) {
      String socatCommand = generateSocatCommand(config);
      socats.add(socatCommand);
      logger().info("  " + socatCommand);
      String socatHttpCommand = generateHttpSocatCommand(config);
      socats.add(socatHttpCommand);
      logger().info("  " + socatHttpCommand);
    }

    for (MemberConfig config : serverConfigs) {
      String socatCommand = generateSocatCommand(config);
      socats.add(socatCommand);
      logger().info("  " + socatCommand);
      String socatHttpCommand = generateHttpSocatCommand(config);
      socats.add(socatHttpCommand);
      logger().info("  " + socatHttpCommand);
    }

    String command = "#!/bin/sh\n";
    command += String.join(" & ", socats);

    copyFileToContainer(Transferable.of(command, 0777), STARTER_SCRIPT);
  }

  private String generateSocatCommand(MemberConfig memberConfig) {
    int internalPort = memberConfig.getProxyListenPort();
    int mappedPort = getMappedPort(internalPort);
    memberConfig.setProxyPublicPort(mappedPort);

    return String.format("socat TCP-LISTEN:%d,fork,reuseaddr TCP:%s:%d",
        internalPort,
        memberConfig.getHostname(),
        mappedPort
    );
  }

  private String generateHttpSocatCommand(MemberConfig memberConfig) {
    int internalPort = memberConfig.getProxyHttpListenPort();
    int mappedPort = getMappedPort(internalPort);
    memberConfig.setProxyHttpPublicPort(mappedPort);

    return String.format("socat TCP-LISTEN:%d,fork,reuseaddr TCP:%s:%d",
        internalPort,
        memberConfig.getHostname(),
        HTTP_PORT
    );
  }
}
