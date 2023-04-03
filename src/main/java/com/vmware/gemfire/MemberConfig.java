package com.vmware.gemfire;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

class MemberConfig {

  private final String serverName;
  private List<Consumer<AbstractGemFireContainer>> configConsumers = new ArrayList<>();
  private int proxyListenPort;
  private int proxyForwardPort;

  MemberConfig(String serverName) {
    this.serverName = serverName;
  }

  public String getServerName() {
    return serverName;
  }

  void addConfig(Consumer<AbstractGemFireContainer> config) {
    configConsumers.add(config);
  }

  void apply(AbstractGemFireContainer container) {
    configConsumers.forEach(config -> config.accept(container));
  }

  void setProxyListenPort(int port) {
    proxyListenPort = port;
  }

  public int getProxyListenPort() {
    return proxyListenPort;
  }

  void setProxyForwardPort(int port) {
    proxyForwardPort = port;
  }

  public int getProxyForwardPort() {
    return proxyForwardPort;
  }

}
