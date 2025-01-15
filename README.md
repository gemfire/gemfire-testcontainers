# VMware GemFire Testcontainers Integration

[![maven](https://img.shields.io/maven-central/v/dev.gemfire/gemfire-testcontainers)](https://central.sonatype.com/artifact/dev.gemfire/gemfire-testcontainers/overview)
![build](https://github.com/gemfire/gemfire-testcontainers/actions/workflows/build.yaml/badge.svg)
[![javadoc](https://javadoc.io/badge2/dev.gemfire/gemfire-testcontainers/javadoc.svg)](https://javadoc.io/doc/dev.gemfire/gemfire-testcontainers)

Testcontainers can be used to automatically instantiate and manage [VMware GemFire](https://docs.vmware.com/en/VMware-GemFire/index.html)
clusters. This is enabled using the official [Docker images](https://hub.docker.com/r/gemfire/gemfire) for GemFire.

Note that the default image is `gemfire/10` which will use the latest, published, `10.x` image.

## Dependencies

Official artifacts are published to VMware's commercial repository. Please follow the instructions
[here](https://gemfire.dev/quickstart/java/) in order to set up your repository definitions.

Dependencies for Maven can then be added with:
```xml
<dependency>
  <groupId>dev.gemfire</groupId>
  <artifactId>gemfire-testcontainers</artifactId>
  <version>2.3.2</version>
</dependency>
```

Or, for gradle:
```java
testImplementation 'dev.gemfire:gemfire-testcontainers:2.3.2'
```

_Note that from version 2.3 onwards, the group co-ordinate has changed from `com.vmware.gemfire` to
`dev.gemfire`._

## Example

Create a GemFire cluster and use it in your tests:

```java
    try (GemFireCluster cluster = new GemFireCluster()) {
        cluster.acceptLicense();
        cluster.start();
    }
```

By default, a single locator and 2 servers are created. Additional servers can be created using the
parameterized `GemFireCluster` constructor. Some API calls require targeting a specific
server; servers are named `server-N` with numbering starting at `0`. Similarly, locators are named
`locator-N`. These names are also set as network aliases in addition to the actual docker container
names.

!!! warning "EULA Acceptance"
Due to licensing restrictions you are required to accept an EULA for this container image.
To indicate that you accept the VMware GemFire image EULA, call the `acceptLicense()` method,
or place a file at the root of the classpath named `container-license-acceptance.txt`,
e.g. at `src/test/resources/container-license-acceptance.txt`. This file should contain the
line: `foo/bar` (or, if you are overriding the docker image name/tag, update accordingly).

    Please see the [`vmware-gemfire` image documentation](https://hub.docker.com/_/vmware-gemfire#environment-variables) for a link to the EULA document.

Now your tests, or any other process running on your machine, can access the cluster by creating
a connection to the _locator_:

```java
    try (
        ClientCache cache = new ClientCacheFactory()
            .addPoolLocator("localhost", cluster.getLocatorPort())
            .create()
    ) {
        Region<Integer, String> region = cache
            .<Integer, String>createClientRegionFactory(ClientRegionShortcut.PROXY)
            .create("FOO");

        region.put(1, "Hello World");

        assertThat(region.get(1)).isEqualTo("Hello World");
    }
```

## GFSH integration

The `gfsh` CLI utility is often used to configure a GemFire cluster. To facilitate this from within 
Testcontainers, a convenience method is provided to execute `gfsh` commands against the
cluster:

```java
    cluster.gfsh(true,
        "list members",
        "create region --name=ORDERS --type=REPLICATE",
        "describe region --name=ORDERS");
```

This, effectively, creates a single script and executes it on the locator instance. The output can
optionally be logged. This particular method should only be used once the cluster has started.
In order to run gfsh commands immediately at startup, you may instead use the `withGfsh` variant.
This is suitable when the lifecycle of the container is managed separately; for example using a
Junit `@Rule` annotation. For example:

```java
    @Rule
    public void GemFireCluster cluster = new GemFireCluster()
        .acceptLicense()
        .withGfsh("create region --name=ORDERS --type=PARTITION_REDUNDANT");
```
In this case, with the addition of the `@Rule` annotation, Junit would be responsible for the
lifecycle of the GemFire cluster.

If additional options for gfsh connectivity are required then using the `Gfsh.Builder` would be
appropriate. An instance of this Builder can be created using the `GemFireCluster.gfshBuilder()`
method. Using this approach allows for configuring gfsh with various security as well as TLS
options. For example if a `SecurityManager` is configured gfsh would require a username and
password to connect:

```java
    Gfsh gfsh = cluster.gfshBuilder()
        .withCredentials("username", "pa$$word")
        .withLogging(true)
        .build();
    gfsh.run("list members");
```

## Additional configuration

Locators and servers can be configured with specific GemFire parameters:

```java
    cluster.withGemFireProperty(LOCATOR_GLOB, "security-manager", SimpleSecurityManager.class.getName());
```

### Applying configuration to specific members

Most of the configuration methods will take a simple glob pattern to target specific members. For
example `server-*` would apply the configuration all servers. Several general patterns are defined
in `GemFireCluster`, namely: `ALL_GLOB`, `LOCATOR_GLOB` and `SERVER_GLOB`.

### Using a cache.xml file

If required, the cluster can be configured with a custom `cache.xml` file. The file is retrieved
as a resource and, as such, should be present on the classpath. The file is applied to
all servers on startup.

```java
    cluster.withCacheXml(SERVER_GLOB, "/test-cache.xml");
```

### Classpath additions

One or more local directories may be exposed on the classpath of each member of the cluster:

```java
    cluster.withClasspath(ALL_GLOB, "build/classes/java/main", "out/production/classes");
```

This will allow the local directory to be mounted (read-only) within each container and added to
the classpath.

### PDX

PDX can be configured by setting the regular expression of the default `ReflectionBasedAutoSerializer`
and selecting the 'read serialized' option as necessary:

```java
    cluster.withPdx("com.example.*", true);
```

More details about PDX can be found [here](https://docs.vmware.com/en/VMware-GemFire/10.0/gf/developing-data_serialization-gemfire_pdx_serialization.html)

### Copying files before startup

In some cases it may be necessary to copy files to members before GemFire starts up. For example
to supply certificate files when using TLS. This can be done using the `withPreStart` method:

```java
    String CACHE_XML = "/test-cache.xml";
    byte[] rawBytes = readAllBytes(Objects.requireNonNull(getClass().getResourceAsStream(CACHE_XML)));
    Transferable fileData = Transferable.of(new String(rawBytes));

    try (GemFireCluster cluster = new GemFireCluster()) {
      cluster.withPreStart(SERVER_GLOB, x -> x.copyFileToContainer(fileData, CACHE_XML));
      cluster.withGemFireProperty(SERVER_GLOB, "cache-xml-file", CACHE_XML);
      cluster.acceptLicense();
      cluster.start();
      ...
    }
```

### Debugging

JVM debugging can be enabled on an individual server:

```java
    cluster.withDebugPort("server-0", 5005);
```

This will expose port `5005` for debugging on server `0`. *Note that the server will wait for a
debugger to attach before continuing with start up.*

In order to log all container output to `stdout`, you can set the Java system property `-Dgemfire-testcontainers.log-container-output=true`.


## Building and Testing

In order to build and test this code, you will need to register an account on the commercial
GemFire repo: https://commercial-repo.pivotal.io/data3/gemfire-release-repo/gemfire. This will give
access to the necessary GemFire bits.

When building, the credentials must be provided via the environment variables
`COMMERCIAL_MAVEN_USERNAME` and `COMMERCIAL_MAVEN_PASSWORD`.

You will also need an account on, and login to Tanzunet / Harbor, in order to pull the GemFire image:

```shell
docker login dev.registry.pivotal.io
```

Now you will be ready to build and test:

```shell
./gradlew build
```

In order to use a different default image you can set the Java system property `gemfire.image`.
To use this when running the `gemfire-testcontainers` tests you can set this property as a Gradle
property. For example:

```shell
./gradlew test -P gemfire.image=gemfire/10.0.27
```
