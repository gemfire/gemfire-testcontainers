package com.vmware.gemfire;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

public class GemFireServerContainer extends GenericContainer<GemFireServerContainer> {

  private static final Logger LOG = LoggerFactory.getLogger(GemFireServerContainer.class);

  public GemFireServerContainer(String name, int port, String imageName) {
    this(name, port, DockerImageName.parse(imageName));
  }

  public GemFireServerContainer(String name, int port, DockerImageName image) {
    super(image);

    withCreateContainerCmdModifier(it -> it.withName(name));

    // This is just so that TC can use the mapped port for the initial wait strategy.
    withExposedPorts(port);

//    withLogConsumer(x -> {
//      String message = x.getUtf8String();
//      if (!message.isBlank()) {
//        LOG.info(x.getUtf8String());
//      }
//    });

    String command = "export BOOTSTRAP_JAR=$(basename /gemfire/lib/gemfire-bootstrap-*.jar); ";
    command += "java -server -classpath /gemfire/lib/${BOOTSTRAP_JAR} "
        + "--add-exports=java.management/com.sun.jmx.remote.security=ALL-UNNAMED "
        + "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED "
        + "--add-opens=java.base/java.lang=ALL-UNNAMED "
        + "--add-opens=java.base/java.nio=ALL-UNNAMED "
        + "-DgemfirePropertyFile=/application/server_gemfire.properties "
        + "-Dgemfire.name=" + name + " "
        + "-Dgemfire.start-dev-rest-api=false "
        + "-Dgemfire.use-cluster-configuration=true "
        + "-Dgemfire.log-level=fine "
        + "-Dgemfire.locators=locator1[10334] "
        + "-Dgemfire.locator-wait-time=120 "
        + "-XX:OnOutOfMemoryError=kill "
        + "-Dgemfire.launcher.registerSignalHandlers=true "
        + "-Djava.awt.headless=true "
        + "-Dsun.rmi.dgc.server.gcInterval=9223372036854775806 "
        + "com.vmware.gemfire.bootstrap.ServerLauncher start server1 "
        + "--automatic-module-classpath=/gemfire/extensions/* "
        + "--server-port=" + port + " "
        + "--hostname-for-clients=localhost";

    withCreateContainerCmdModifier(cmd -> {
      cmd.withEntrypoint("sh");
    });
    withCommand("-c", command);
  }

}
