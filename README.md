# VMware GemFire

Testcontainers can be used to automatically instantiate and manage [VMware GemFire](https://docs.vmware.com/en/VMware-GemFire/index.html)
clusters. This is enabled using the official [Docker images](https://hub.docker.com/v/vmware-gemfire) for GemFire.

## Example

Create a GemFire cluster and use it in your tests:

```java
    try (GemFireClusterContainer<?> cluster = new GemFireClusterContainer<>(IMAGE)) {
        cluster.acceptLicense();
        cluster.start();
    }
```

By default, a single locator and 2 servers are created. Additional servers can be created using the
parameterized `GemFireClusterContainer` constructor. Some API calls require targeting a specific
server; servers are numbered starting at `0`.

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
    public void GemFireClusterContainer<?> cluster = new GemFireClusterContainer<>()
        .acceptLicense()
        .withGfsh("create region --name=ORDERS --type=PARTITION_REDUNDANT");
```
In this case, with the addition of the `@Rule` annotation, Junit would be responsible for the
lifecycle of the GemFire cluster.

## Additional configuration

Servers can be configured with specific GemFire parameters:

```java
    cluster.withGemFireProperty("security-manager", SimpleSecurityManager.class.getName());
```

### Using a cache.xml file

If required, the cluster can be configured with a custom `cache.xml` file. The file is retrieved
as a resource and, as such, should be present on the classpath. The file is applied to
all servers on startup.

```java
    cluster.withCacheXml("/test-cache.xml");
```

### Classpath additions

One or more local directories may be exposed on the classpath of each member of the cluster:

```java
    cluster.withClasspath("build/classes/java/main", "out/production/classes");
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

### Debugging

JVM debugging can be enabled on an individual server:

```java
    cluster.withDebugPort(1, 5005);
```

This will expose port `5005` for debugging on server `1`. *Note that the server will wait for a
debugger to attach before continuing with start up.*

## Building:

In order to build and test this code, you will need to register an account on the commercial
GemFire repo: https://commercial-repo.pivotal.io/data3/gemfire-release-repo/gemfire. This will give
access to the necessary GemFire bits.

Add your credentials to the placeholder properties in `gradle.properties`.

You will also need to login to Tanzunet / Harbor in order to pull the GemFire image:

```shell
docker login dev.registry.pivotal.io
```

Now you will be ready to build, test and publish:

```shell
./gradlew build publishToMavenLocal
```
