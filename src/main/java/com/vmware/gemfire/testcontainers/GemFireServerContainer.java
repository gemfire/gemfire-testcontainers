package com.vmware.gemfire.testcontainers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.github.dockerjava.api.model.Bind;
import org.testcontainers.utility.DockerImageName;

public class GemFireServerContainer<SELF extends GemFireServerContainer<SELF>>
    extends AbstractGemFireContainer<SELF> {

  private static final List<String> DEFAULT_JVM_ARGS = Arrays.asList(
      "-server",
      "--add-exports=java.management/com.sun.jmx.remote.security=ALL-UNNAMED",
      "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "-Dgemfire.start-dev-rest-api=false",
      "-Dgemfire.use-cluster-configuration=true",
      "-Dgemfire.log-level=info",
      "-Dgemfire.log-file=",
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

    String locator = String.format("%s[%d]", config.getLocatorHost(), config.getLocatorPort());
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

    logger().info("Starting GemFire server: {}:{}", config.getServerName(),
        config.getProxyForwardPort());
  }

}
