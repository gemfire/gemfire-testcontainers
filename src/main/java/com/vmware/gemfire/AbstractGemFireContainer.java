package com.vmware.gemfire;

import java.util.List;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

public class AbstractGemFireContainer extends GenericContainer<AbstractGemFireContainer> {
  protected List<String> jvmArgs;

  protected AbstractGemFireContainer(DockerImageName image) {
    super(image);
  }

  public void addJvmArg(String arg) {
    jvmArgs.add(arg);
  }
}
