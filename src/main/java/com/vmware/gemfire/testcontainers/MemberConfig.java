/*
 *  Copyright (c) VMware, Inc. 2023. All rights reserved.
 */

package com.vmware.gemfire.testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

class MemberConfig {

  private final String serverName;
  private final List<Consumer<AbstractGemFireContainer<?>>> configConsumers = new ArrayList<>();
  private int proxyListenPort;
  private int proxyPublicPort;
  private String locatorHost;
  private int locatorPort;

  MemberConfig(String serverName) {
    this.serverName = serverName;
  }

  public String getServerName() {
    return serverName;
  }

  void addConfig(Consumer<AbstractGemFireContainer<?>> config) {
    configConsumers.add(config);
  }

  void apply(AbstractGemFireContainer<?> container) {
    configConsumers.forEach(config -> config.accept(container));
  }

  /**
   * Set the port that the proxy listens on. This is NOT the port that will be mapped externally as
   * the 'public' port.
   */
  void setProxyListenPort(int port) {
    proxyListenPort = port;
  }

  public int getProxyListenPort() {
    return proxyListenPort;
  }

  void setProxyPublicPort(int port) {
    proxyPublicPort = port;
  }

  public int getProxyPublicPort() {
    return proxyPublicPort;
  }

  public void setLocatorHostPort(String locatorHost, int locatorPort) {
    this.locatorHost = locatorHost;
    this.locatorPort = locatorPort;
  }

  public String getLocatorHost() {
    return locatorHost;
  }

  public int getLocatorPort() {
    return locatorPort;
  }
}
