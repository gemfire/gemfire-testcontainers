package com.vmware.gemfire;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

  private final Map<String, Integer> internalPortMapping = new HashMap<>();
  private final Map<String, Integer> mappedPorts = new HashMap<>();

  public GemFireProxyContainer(List<String> serverNames) {
    super();

    for (int i = 0; i < serverNames.size(); i++) {
      int port = BASE_PORT + i;
      addExposedPort(port);
      internalPortMapping.put(serverNames.get(i), port);
    }
  }

  @Override
  public void configure() {
    withCommand("-c", "while [ ! -f " + STARTER_SCRIPT + " ]; do sleep 0.1; done; " + STARTER_SCRIPT);
  }

  @Override
  protected void containerIsStarting(InspectContainerResponse containerInfo) {
    List<String> socats = new ArrayList<>();
    for (Map.Entry<String, Integer> entry : internalPortMapping.entrySet()) {
      int internalPort = entry.getValue();
      int mappedPort = getMappedPort(internalPort);
      mappedPorts.put(entry.getKey(), mappedPort);

      socats.add(String.format("socat TCP-LISTEN:%d,fork,reuseaddr TCP:%s:%d", internalPort,
          entry.getKey(), mappedPort));
    }

    String command = "#!/bin/sh\n";
    command += String.join(" & ", socats);

    copyFileToContainer(Transferable.of(command, 0777), STARTER_SCRIPT);
  }

  public Map<String, Integer> getMappedPorts() {
    return mappedPorts;
  }

}
