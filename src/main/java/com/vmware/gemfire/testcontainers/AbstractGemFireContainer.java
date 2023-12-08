/*
 *  Copyright (c) VMware, Inc. 2023. All rights reserved.
 */

package com.vmware.gemfire.testcontainers;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.utility.DockerImageName;

public abstract class AbstractGemFireContainer<SELF extends AbstractGemFireContainer<SELF>>
    extends GenericContainer<SELF> implements Consumer<OutputFrame> {

  protected static boolean logContainerOutputToStdout =
      Boolean.getBoolean("gemfire-testcontainers.log-container-output");

  protected static final int DEFAULT_STARTUP_TIMEOUT = 120;

  protected List<String> jvmArgs;

  private final CountDownLatch startupLatch = new CountDownLatch(1);

  protected final MemberConfig config;
  protected final String locatorAddresses;

  public AbstractGemFireContainer(MemberConfig config, DockerImageName image, Network network,
      String locatorAddresses) {
    super(image);
    this.config = config;
    this.locatorAddresses = locatorAddresses;
    jvmArgs = getDefaultJvmArgs();

    withNetwork(network);
    withLogConsumer(this);

    if (logContainerOutputToStdout) {
      withLogConsumer(x -> System.out.print(x.getUtf8String()));
    }
  }

  protected abstract String startupMessage();

  protected abstract String getMemberName();

  protected abstract List<String> getDefaultJvmArgs();

  public void waitToStart() {
    waitToStart(DEFAULT_STARTUP_TIMEOUT);
  }

  public void waitToStart(int secondsToWait) {
    boolean started = true;
    try {
      started = startupLatch.await(secondsToWait, TimeUnit.SECONDS);
    } catch (InterruptedException ignored) {
    } finally {
      if (!started) {
        System.out.println("=================== Container logs for " + getContainerName() +
            " ===================");
        System.out.println(getLogs());
        System.out.println("===================================================");
        throw new RuntimeException("Timed out waiting for member '" + getContainerName()
            + "'to start");
      }
    }
  }

  protected void addJvmArg(String arg) {
    jvmArgs.add(arg);
  }

  @Override
  public void accept(OutputFrame frame) {
    if (startupLatch.getCount() == 1 && frame.getUtf8String().contains(startupMessage())) {
      startupLatch.countDown();
    }
  }
}
