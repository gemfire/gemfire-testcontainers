package com.vmware.gemfire;

import static com.vmware.gemfire.GemFireClusterContainer.LOCATOR_NAME;
import static com.vmware.gemfire.GemFireClusterContainer.LOCATOR_PORT;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Bind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.utility.DockerImageName;

public class GemFireServerContainer<SELF extends GemFireServerContainer<SELF>>
    extends AbstractGemFireContainer<SELF> {

  private static final Logger LOG = LoggerFactory.getLogger(GemFireServerContainer.class);

  private static final List<String> DEFAULT_JVM_ARGS = Arrays.asList(
      "-server",
      "--add-exports=java.management/com.sun.jmx.remote.security=ALL-UNNAMED",
      "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "-Dgemfire.start-dev-rest-api=false",
      "-Dgemfire.use-cluster-configuration=true",
      "-Dgemfire.log-level=fine",
      "-Dgemfire.locator-wait-time=120",
      "-XX:OnOutOfMemoryError=kill",
      "-Dgemfire.launcher.registerSignalHandlers=true",
      "-Djava.awt.headless=true",
      "-Dsun.rmi.dgc.server.gcInterval=9223372036854775806");

  public GemFireServerContainer(MemberConfig config, String imageName) {
    this(config, DockerImageName.parse(imageName));
  }

  public GemFireServerContainer(MemberConfig config, DockerImageName image) {
    super(image);

    jvmArgs = new ArrayList<>(DEFAULT_JVM_ARGS);

    withCreateContainerCmdModifier(it -> it.withName(config.getServerName()));

    // This is just so that TC can use the mapped port for the initial wait strategy.
    withExposedPorts(config.getProxyForwardPort());

    String locator = String.format("%s[%d]", LOCATOR_NAME, LOCATOR_PORT);
    jvmArgs.add("-Dgemfire.locators=" + locator);

    config.apply(this);

    String execPart =
        "export BOOTSTRAP_JAR=$(basename /gemfire/lib/gemfire-bootstrap-*.jar); exec java ";

    String classpathPart = "-classpath /gemfire/lib/${BOOTSTRAP_JAR}";
    for (Bind bind : getBinds()) {
      classpathPart = classpathPart + ":" + bind.getVolume().getPath();
    }
    classpathPart += " ";

    String launcherPart =
        " com.vmware.gemfire.bootstrap.ServerLauncher start " + config.getServerName() +
        " --automatic-module-classpath=/gemfire/extensions/*" +
        " --server-port=" + config.getProxyForwardPort() +
        " --hostname-for-clients=localhost";
    String jvmArgsPart = String.join(" ", jvmArgs);

    withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh"));
    withCommand("-c", execPart + classpathPart + jvmArgsPart + launcherPart);
  }

  @Override
  protected void containerIsStarted(InspectContainerResponse containerInfo) {
    super.containerIsStarted(containerInfo);
    logger().info("Started GemFire server: {}", containerInfo.getName());
  }
}
