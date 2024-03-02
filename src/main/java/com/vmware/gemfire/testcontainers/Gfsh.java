/*
 *  Copyright (c) VMware, Inc. 2024. All rights reserved.
 */

package com.vmware.gemfire.testcontainers;

import static com.vmware.gemfire.testcontainers.GemFireCluster.JMX_PORT;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.images.builder.Transferable;

/**
 * Class used to configure and create an instance to execute gfsh commands. This class can be used
 * when gfsh connections require additional security or TLS/SSL options.
 */
public class Gfsh {

  private static final Logger logger = LoggerFactory.getLogger("gfsh");
  private final AbstractGemFireContainer<?> locator;
  private final String connectCommand;
  private final boolean logOutput;

  private Gfsh(AbstractGemFireContainer<?> locator, String connectCommand, boolean logOutput) {
    this.locator = locator;
    this.connectCommand = connectCommand;
    this.logOutput = logOutput;
  }

  /**
   * Execute gfsh commands
   *
   * @param commands an array of gfsh commands to run
   * @return the result of running the gfsh command(s)
   */
  public String run(String... commands) {
    return run(Arrays.asList(commands));
  }

  /**
   * Execute gfsh commands
   *
   * @param commands a list of gfsh commands to run
   * @return the result of running the gfsh command(s)
   */
  public String run(List<String> commands) {
    List<String> tmpCommands = new ArrayList<>();
    tmpCommands.add(connectCommand);
    tmpCommands.addAll(commands);

    String commandFileContents = String.join("\n", tmpCommands);
    locator.copyFileToContainer(Transferable.of(commandFileContents, 0666), "/script.gfsh");
    Container.ExecResult result;
    try {
      result = locator.execInContainer("gfsh", "-e", "run --file=/script.gfsh");

      boolean scriptError = result.getExitCode() != 0;
      if (logOutput || scriptError) {
        for (String line : result.toString().split("\n")) {
          if (scriptError) {
            logger.error(line);
          } else {
            logger.info(line);
          }
        }
      }
    } catch (Exception ex) {
      throw new RuntimeException("Error executing gfsh command: " + Arrays.asList(commands), ex);
    }

    if (result.getExitCode() != 0) {
      throw new RuntimeException(
          "Error executing gfsh command. Return code: " + result.getExitCode());
    }

    return result.toString();
  }

  /**
   * Builder used to construct Gfsh instances.
   */
  public static class Builder {
    final AbstractGemFireContainer<?> locator;
    private boolean withLogging;
    private Map<String, String> gfshOptions = new HashMap<>();

    Builder(AbstractGemFireContainer<?> locator) {
      this.locator = locator;
    }

    /**
     * Configure with username and password. Required when a {@code SecurityManager} is enabled.
     *
     * @param username username required for gfsh connect
     * @param password password required for gfsh connect
     * @return this
     */
    public Builder withCredentials(String username, String password) {
      gfshOptions.put("--username", username);
      gfshOptions.put("--password", password);
      return this;
    }

    /**
     * Configure with a keystore when TLS is enabled for JMX connections
     *
     * @param keyStoreFile     the keystore file to be used. This file will be copied to the
     *                         locator on which gfsh is execd.
     * @param keyStorePassword the keystore password
     * @return this
     * @throws IOException if there is a problem reading the file
     */
    public Builder withKeyStore(String keyStoreFile, String keyStorePassword) throws IOException {
      byte[] content = Files.readAllBytes(Paths.get(keyStoreFile));
      return withKeyStore(content, keyStorePassword);
    }

    /**
     * Configure with a keystore when TLS is enabled for JMX connections
     *
     * @param keyStoreBytes    the raw bytes of the keystore file to be used
     * @param keyStorePassword the keystore password
     * @return this
     */
    public Builder withKeyStore(byte[] keyStoreBytes, String keyStorePassword) {
      String filename = "/key-store";
      gfshOptions.put("--key-store", filename);
      gfshOptions.put("--key-store-password", keyStorePassword);
      locator.copyFileToContainer(Transferable.of(keyStoreBytes, 0666), filename);
      return this;
    }

    /**
     * Configure with a truststore when TLS is enabled for JMX connections
     *
     * @param trustStoreFile     the truststore file to be used. This file will be copied to the
     *                           locator on which gfsh is execd.
     * @param trustStorePassword the truststore password
     * @return this
     * @throws IOException if there is a problem reading the file
     */
    public Builder withTrustStore(String trustStoreFile, String trustStorePassword)
        throws IOException {
      byte[] content = Files.readAllBytes(Paths.get(trustStoreFile));
      return withTrustStore(content, trustStorePassword);
    }

    /**
     * Configure with a truststore when TLS is enabled for JMX connections
     *
     * @param trustStoreBytes    the raw bytes of the truststore file to be used
     * @param trustStorePassword the truststore password
     * @return this
     */
    public Builder withTrustStore(byte[] trustStoreBytes, String trustStorePassword) {
      String filename = "/trust-store";
      gfshOptions.put("--trust-store", filename);
      gfshOptions.put("--trust-store-password", trustStorePassword);
      locator.copyFileToContainer(Transferable.of(trustStoreBytes, 0666), filename);
      return this;
    }

    /**
     * The ciphers to use for JMX connections.
     *
     * @param ciphers a cipher or comma-separated list if there are multiple.
     * @return this
     */
    public Builder withCiphers(String ciphers) {
      gfshOptions.put("--ciphers", ciphers);
      return this;
    }

    /**
     * The protocols to use for JMX connections
     *
     * @param protocols a protocol or comma-separated list if there are multiple.
     * @return this
     */
    public Builder withProtocols(String protocols) {
      gfshOptions.put("--protocols", protocols);
      return this;
    }

    /**
     * Should the output from gfsh be logged.
     *
     * @param withLogging true if output should be logged
     * @return this
     */
    public Builder withLogging(boolean withLogging) {
      this.withLogging = withLogging;
      return this;
    }

    /**
     * Configure gfsh with a security properties file. The file should contain all required
     * properties to connect as, using this method, will override any other previous
     * {@code with*} calls that may already have been invoked.
     * @param securityFile file containing all security properties
     *
     * @return a Gfsh instance set up to use the provided security options
     * @throws IOException if the file cannot be read correctly
     */
    public Gfsh withSecurityProperties(String securityFile) throws IOException {
      byte[] content = Files.readAllBytes(Paths.get(securityFile));
      return writeSecurityPropertiesFile(content);
    }

    /**
     * Configure gfsh with security properties. Using this method will override any other previous
     * {@code with*} calls that may already have been invoked.
     *
     * @param securityProperties properties containing all security options required to connect
     * @return a Gfsh instance set up to use the provided security properties
     * @throws IOException if the properties cannot be processed correctly
     */
    public Gfsh withSecurityProperties(Properties securityProperties) throws IOException {
      try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
        securityProperties.store(baos, "Security Properties");
        return writeSecurityPropertiesFile(baos.toByteArray());
      }
    }

    private Gfsh writeSecurityPropertiesFile(byte[] fileBytes) {
      String filename = "/security.properties";
      List<String> options = new ArrayList<>();
      options.add("--security-properties-file=");
      locator.copyFileToContainer(Transferable.of(fileBytes, 0666), filename);

      return withConnect(options);
    }

    /**
     * Explicit list of options to use for gfsh to connect. For example: "--username=bob". The
     * options should be the literal values as would be used for a regular gfsh CLI 'connect' call.
     * Note that the option {@code --jmx-manager=localhost[1099]} will automatically be applied.
     * This method will override any other previous {@code with*} calls that may already have been
     * invoked.
     *
     * @param connectOptions a list of options
     * @return a Gfsh instance set up to use the provided connection options
     */
    public Gfsh withConnect(List<String> connectOptions) {
      String connectCommand = String.format("connect --jmx-manager=localhost[%d] ", JMX_PORT);
      connectCommand += connectOptions.stream().collect(Collectors.joining(" "));

      return new Gfsh(locator, connectCommand, withLogging);
    }

    /**
     * Create a {@link Gfsh} instance configured with the provided options.
     *
     * @return a Gfsh instance
     */
    public Gfsh build() {
      StringBuilder connectBuilder =
          new StringBuilder(String.format("connect --jmx-manager=localhost[%d] ", JMX_PORT));
      for (Map.Entry<String, String> entry : gfshOptions.entrySet()) {
        connectBuilder.append(entry.getKey()).append("=").append(entry.getValue()).append(" ");
      }

      return new Gfsh(locator, connectBuilder.toString(), withLogging);
    }
  }

}
