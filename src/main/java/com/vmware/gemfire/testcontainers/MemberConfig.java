/*
 *  Copyright (c) VMware, Inc. 2023. All rights reserved.
 */

package com.vmware.gemfire.testcontainers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

public class MemberConfig {

  private final String memberName;
  private final String hostname;
  private final List<Consumer<AbstractGemFireContainer<?>>> configConsumers = new ArrayList<>();
  private final List<Consumer<AbstractGemFireContainer<?>>> preStartConsumers = new ArrayList<>();
  private int proxyListenPort;
  private int proxyPublicPort;
  private int port;
  private int proxyHttpListenPort;
  private int proxyHttpPublicPort;
  private AbstractGemFireContainer<?> container;

  MemberConfig(String prefix, int index, String suffix) {
    this.memberName = String.format("%s-%d", prefix, index);
    this.hostname = String.format("%s-%d-%s", prefix, index, suffix);
  }

  public String getMemberName() {
    return memberName;
  }

  public String getHostname() {
    return hostname;
  }

  public AbstractGemFireContainer<?> getContainer() {
    return container;
  }

  public void setContainer(AbstractGemFireContainer<?> container) {
    this.container = container;
  }

  void addConfig(Consumer<AbstractGemFireContainer<?>> config) {
    configConsumers.add(config);
  }

  void addPreStart(Consumer<AbstractGemFireContainer<?>> config) {
    preStartConsumers.add(config);
  }

  void apply(AbstractGemFireContainer<?> container) {
    configConsumers.forEach(consumer -> consumer.accept(container));
  }

  void applyPreStart(AbstractGemFireContainer<?> container) {
    preStartConsumers.forEach(consumer -> consumer.accept(container));
  }

  /**
   * Set the port that the proxy listens on. This is NOT the port that will be mapped externally as
   * the 'public' port.
   */
  void setProxyListenPort(int port) {
    proxyListenPort = port;
  }

  int getProxyListenPort() {
    return proxyListenPort;
  }

  void setProxyHttpListenPort(int port) {
    proxyHttpListenPort = port;
  }

  int getProxyHttpListenPort() {
    return proxyHttpListenPort;
  }

  public int getPort() {
    return port == 0 ? proxyPublicPort : port;
  }

  public void setPort(int port) {
    this.port = port;
    addConfig(container -> container
        .setPortBindings(Collections.singletonList(String.format("%d:%d", port, port))));
  }

  void setProxyPublicPort(int port) {
    proxyPublicPort = port;
  }

  public int getProxyPublicPort() {
    return proxyPublicPort;
  }

  void setProxyHttpPublicPort(int port) {
    proxyHttpPublicPort = port;
  }

  public int getProxyHttpPublicPort() {
    return proxyHttpPublicPort;
  }

}
