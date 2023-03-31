package com.vmware.gemfire;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

public class GemFireLocatorContainer extends GenericContainer<GemFireLocatorContainer> {

  private static final Logger LOG = LoggerFactory.getLogger(GemFireLocatorContainer.class);

  private static final int LOCATOR_PORT = 10334;

  private static final int HTTP_PORT = 7070;

  public GemFireLocatorContainer(String imageName) {
    this(DockerImageName.parse(imageName));
  }

  public GemFireLocatorContainer(DockerImageName image) {
    super(image);

    withCreateContainerCmdModifier(it -> it.withName("locator1"));

    withLogConsumer(x -> new Slf4jLogConsumer(logger()));

    withExposedPorts(LOCATOR_PORT, HTTP_PORT);

    String command = "export BOOTSTRAP_JAR=$(basename /gemfire/lib/gemfire-bootstrap-*.jar); ";
    command += "java -server -classpath /gemfire/lib/${BOOTSTRAP_JAR} "
        + "--add-exports=java.management/com.sun.jmx.remote.security=ALL-UNNAMED "
        + "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED "
        + "--add-opens=java.base/java.lang=ALL-UNNAMED "
        + "--add-opens=java.base/java.nio=ALL-UNNAMED "
        + "-DgemfirePropertyFile=/application/server_gemfire.properties "
        + "-Dgemfire.start-dev-rest-api=false "
        + "-Dgemfire.use-cluster-configuration=true "
        + "-Dgemfire.log-level=info "
        + "-Dgemfire.http-service-port=7070 "
        + "-XX:OnOutOfMemoryError=kill "
        + "-Dgemfire.launcher.registerSignalHandlers=true "
        + "-Djava.awt.headless=true "
        + "-Dsun.rmi.dgc.server.gcInterval=9223372036854775806 "
        + "com.vmware.gemfire.bootstrap.LocatorLauncher start locator1 "
        + "--automatic-module-classpath=/gemfire/extensions/* "
        + "--port=" + LOCATOR_PORT + " "
        + "--hostname-for-clients=localhost";

    withCreateContainerCmdModifier(cmd -> {
      cmd.withEntrypoint("sh");
    });
    withCommand("-c", command);

    // The default Wait strategy waits for the first exposed (mapped) port to start listening
  }

  public int getLocatorPort() {
    return getMappedPort(LOCATOR_PORT);
  }

}
