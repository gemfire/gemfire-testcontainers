package com.vmware.gemfire;

import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.utility.DockerImageName;

public class GemFireLocatorContainer extends AbstractGemFireContainer {

  private static final Logger LOG = LoggerFactory.getLogger(GemFireLocatorContainer.class);

  protected static final String LOCATOR_NAME = "locator-1";

  protected static final int LOCATOR_PORT = 10334;

  private static final int HTTP_PORT = 7070;

  public GemFireLocatorContainer(String imageName) {
    this(DockerImageName.parse(imageName));
  }

  public GemFireLocatorContainer(DockerImageName image) {
    super(image);

    withCreateContainerCmdModifier(it -> it.withName(LOCATOR_NAME));

    withExposedPorts(LOCATOR_PORT, HTTP_PORT);

    List<String> args = Arrays.asList(
        "export BOOTSTRAP_JAR=$(basename /gemfire/lib/gemfire-bootstrap-*.jar);",
        "exec java -server -classpath /gemfire/lib/${BOOTSTRAP_JAR}",
        "--add-exports=java.management/com.sun.jmx.remote.security=ALL-UNNAMED",
        "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "-DgemfirePropertyFile=/application/server_gemfire.properties",
        "-Dgemfire.start-dev-rest-api=false",
        "-Dgemfire.use-cluster-configuration=true",
        "-Dgemfire.log-level=info",
        "-Dgemfire.http-service-port=7070",
        "-XX:OnOutOfMemoryError=kill",
        "-Dgemfire.launcher.registerSignalHandlers=true",
        "-Djava.awt.headless=true",
        "-Dsun.rmi.dgc.server.gcInterval=9223372036854775806",
        "com.vmware.gemfire.bootstrap.LocatorLauncher start " + LOCATOR_NAME,
        "--automatic-module-classpath=/gemfire/extensions/*",
        "--port=" + LOCATOR_PORT,
        "--hostname-for-clients=localhost");

    withCreateContainerCmdModifier(cmd -> {
      cmd.withEntrypoint("sh");
    });
    withCommand("-c", String.join(" ", args));

    // The default Wait strategy waits for the first exposed (mapped) port to start listening
  }

  public int getLocatorPort() {
    return getMappedPort(LOCATOR_PORT);
  }

}
