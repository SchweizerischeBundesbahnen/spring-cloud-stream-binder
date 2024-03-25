# Solace PubSub+ Testcontainer

## Overview

Basic Testcontainer implementation for Solace PubSub+.

## Usage

### Updating Your Build

```xml
<dependencyManagement>
	<dependencies>
		<dependency>
			<groupId>com.solace.test.integration</groupId>
			<artifactId>solace-integration-test-support-bom</artifactId>
			<version>${solace.integration.test.support.version}</version>
			<type>pom</type>
			<scope>import</scope>
		</dependency>
	</dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>com.solace.test.integration</groupId>
        <artifactId>pubsubplus-testcontainer</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

### Using It In Your Application

```java
// Create and start the PubSub+ container
PubSubPlusContainer container = new PubSubPlusContainer();
container.start();

// Get SEMP admin credentials
container.getAdminUsername();
container.getAdminPassword();

// Get the origin for a given PubSub+ port
container.getOrigin(Port port);
```

See https://www.testcontainers.org for more info.



# Solace PubSub+ JUnit Jupiter Utility

## Overview

Utility for using Solace PubSub+ in JUnit Jupiter.

## Table of Contents
* [Updating Your Build](#updating-your-build)
* [Using the PubSub+ Extension](#using-the-pubsub-extension)
    * [PubSub+ Resource Lifecycle](#pubsub-resource-lifecycle)
    * [Basic Usage](#basic-usage)
    * [Multiple Parameters of the Same Type](#multiple-parameters-of-the-same-type)
    * [To use an External PubSub+ Broker](#to-use-an-external-pubsub-broker)
    * [Customize PubSub+ Docker Container](#customize-pubsub-docker-container)
    * [Integrating with Other Extensions](#integrating-with-other-extensions)
    * [Toxiproxy Integration](#toxiproxy-integration)
* [Other Extensions](#other-extensions)


## Updating Your Build

```xml
<dependencyManagement>
	<dependencies>
		<dependency>
			<groupId>com.solace.test.integration</groupId>
			<artifactId>solace-integration-test-support-bom</artifactId>
			<version>${solace.integration.test.support.version}</version>
			<type>pom</type>
			<scope>import</scope>
		</dependency>
	</dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>com.solace.test.integration</groupId>
        <artifactId>pubsubplus-junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

## Using the PubSub+ Extension

The `PubSubPlusExtension` is the Junit 5 extension for using Solace PubSub+.

By default a Solace PubSub+ container will be auto-provisioned only if necessary.

### PubSub+ Resource Lifecycle

The lifecycle of resources (e.g. sessions and endpoints) created through this extension are bound to the earliest JUnit context that they were defined in.

e.g.:

* If a resource was only defined as a parameter of a `@Test` method, then that resource's lifecycle is bound to the test, and will be cleaned up after the test completes.
* If a resource was only defined as a parameter of a `@BeforeAll` method, then the resource's lifecycle is bound to the class, and will be cleaned up after the test class completes.
* If you had the same resource defined as parameters of both `@BeforeAll` and `@Test`, then both methods will use the same resource, and its lifecycle would be bound to the test class (i.e. the earliest definition of the resource).

Note that the only exception to this is the PubSub+ broker container. Which, if created, is bound to JUnit's root context. i.e. it will be cleaned up with the JVM.

### Basic Usage

```java
@ExtendWith(PubSubPlusExtension.class)
public class Test {
    // At least one of these arguments must be defined on the test function for the session and broker to be
    // provisioned.
    @Test
    public void testMethod(JCSMPSession session, SempV2Api sempV2Api, Queue queue, JCSMPProperties properties) {
        // Test logic using JCSMP
    }
}
```

`JCSMPProperties` & `JCSMPSession` parameters can be annotated with `@JCSMPProperty` to override individual JCSMP properties.

### Multiple Parameters of the Same Type

If a test needs multiple parameters of the same type (e.g. 2 `JCSMPSession`s), use the `@Store` annotation. This parameter annotation defines which store the parameter's resources belongs to.

If one resource is needed to create another (e.g. `JCSMPSession` is needed to create a `Queue`), then this extension will retrieve that resource's required resource from the same store.

e.g. In the following code snippet, `defaultQueue` will be created using `defaultSession`, while `otherQueue` will be created using `otherSession`:
```java
@ExtendWith(PubSubPlusExtension.class)
public class Test {
    @Test
    public void testMethod(JCSMPSession defaultSession, @Store("other") otherSession,
                           Queue defaultQueue, @Store("other") Queue otherQueue) {
    }
}
```

### To use an External PubSub+ Broker

First, implement the `PubSubPlusExtension.ExternalProvider` interface. e.g.:

```java
public class OtherExternalProvider PubSubPlusExtension.ExternalProvider {
	@Override
	public boolean isValid(ExtensionContext extensionContext) {
		// Return true to inform the PubSub+ extension if it can use this external
		// provider to get its test broker
	}
	
	@Override
	public void init(ExtensionContext extensionContext) {
		// Initialize this external provider. Is only invoked once
	}
	
	@Override
	public JCSMPProperties createJCSMPProperties(ExtensionContext extensionContext) {
		// Create a new JCSMPProperties instance pointing to your externally managed broker
	}
	
	@Override
	public SempV2Api createSempV2Api(ExtensionContext extensionContext) {
		// Create a new SempV2API instance pointing to your externally managed broker
	}
}
```

Then add the `META-INF/services/com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension$ExternalProvider` resource file to configure external PubSub+ providers:

```
com.test.OtherExternalProvider
com.solace.test.integration.junit.jupiter.extension.pubsubplus.provider.PubSubPlusFileProvider
```

Note: Providers are resolved in order of top-to-bottom.

By default, `PubSubPlusFileProvider` is enabled as the only external provider.

### Customize PubSub+ Docker Container

Either extend `SimpleContainerProvider` or implement `PubSubPlusExtension.ContainerProvider`. e.g.:
```java
public class OtherContainerProvider implements PubSubPlusExtension.ContainerProvider {
	@Override
	public Supplier<PubSubPlusContainer> containerSupplier(ExtensionContext extensionContext) {
		// return a supplier which creates a new PubSubPlusContainer
		return PubSubPlusContainer::new;
	}
	
	@Override
	public void containerPostStart(ExtensionContext extensionContext, PubSubPlusContainer container) {
		// optionally do something after the container starts
	}
	
	@Override
	public JCSMPProperties createJcsmpProperties(ExtensionContext extensionContext, PubSubPlusContainer container) {
		// create a new JCSMPProperties instance for the given PubSub+ container
	}
	
	@Override
	public SempV2Api createSempV2Api(ExtensionContext extensionContext, PubSubPlusContainer container) {
		// create a new SempV2API  instance for the given PubSub+ container
	}
}
```

Then add the `META-INF/services/com.solace.test.integration.junit.jupiter.extension.PubSubPlusExtension$ContainerProvider` resource file that contains the full reference to your new `ContainerProvider` class:

```
com.test.OtherContainerProvider
```

Note: Only one container provider is supported. If multiple are detected, the first found provider will be used.

By default, `SimpleContainerProvider` is enabled as the container provider.

### Integrating with Other Extensions

To integrate this extension into other extensions, use this extension's `static` getters. e.g.:

```java
public class SomeNewExtension implements ParameterResolver {
	public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
		...
	}
	
	public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
		JCSMPSession jcsmpSession = PubSubPlusExtension.getJCSMPSession(extensionContext);
	}
}

```

Note: Resources created through these methods follows the [PubSub+ Resource Lifecycle](#pubsub-resource-lifecycle).

### Toxiproxy Integration

To retrieve a proxied JCSMP session, annotate your `JCSMPSession` parameter with `@JCSMPProxy`. To get the proxy itself, add the `ToxiproxyContext` parameter that's also annotated with `@JCSMPProxy`:

```java
@ExtendWith(PubSubPlusExtension.class)
public class Test {
    @Test
    public void testMethod(@JCSMPProxy JCSMPSession jcsmpSession, @JCSMPProxy ToxiproxyContext jcsmpProxyContext) {
        // add a toxic to the JCSMP proxy
        Latency toxic = jcsmpProxyContext.getProxy().toxics()
                .latency("lag", ToxicDirection.UPSTREAM, TimeUnit.SECONDS.toMillis(5));

        // get a host that a container within the docker network can use to access the proxy
        String toxicJCSMPNetworkHost = String.format("tcp://%s:%s", jcsmpProxyContext.getDockerNetworkAlias(),
                jcsmpProxyContext.getProxy().getOriginalProxyPort())

        // Test logic using toxic JCSMP session.
        // Is already preconfigured to use the proxy since the parameter is annotated by @JCSMPProxy.
    }
}
```

## Other Extensions

This project provides a number of JUnit extensions:

* [ExecutorServiceExtension](src/main/java/com/solace/test/integration/junit/jupiter/extension/ExecutorServiceExtension.java)
* [LogCaptorExtension](src/main/java/com/solace/test/integration/junit/jupiter/extension/LogCaptorExtension.java)

