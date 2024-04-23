# Spring Cloud Stream Binder for Solace 

## Overview
A Spring Cloud Stream Binder for Solace

## Table of contents
* [Spring Cloud Version Compatibility](#spring-cloud-version-compatibility)
* [Using it with Maven](#using-it-with-maven)
* [Building Locally](#building-locally)
* [Run Tests With An External Broker](#run-tests-with-an-external-broker)
* [Run Tests on an external Docker](#run-tests-on-an-external-docker)
* [Release Process](#release-process)
* [Contributing](#contributing)
* [Authors](#authors)
* [License](#license)
* [Support](#support)
---
## Spring Cloud Version Compatibility

Consult the table below to determine which version of the BOM you need to use:

| Spring Cloud | Spring Cloud Stream Binder Solace | Spring Boot | sol-jcsmp  |
|--------------|-----------------------------------|-------------|------------|
| 2023.0.1     | 4.0.0                             | 3.2.5       | 10.23.0    |

For older versions you can try at your own risk or use the original version from solace:
https://github.com/SolaceDev/solace-spring-cloud

## Using it with Maven
```xml
<dependencies>
    <dependency>
        <groupId>ch.sbb</groupId>
        <artifactId>spring-cloud-stream-binder-solace</artifactId>
        <version>4.0.0</version>
    </dependency>
</dependencies>
```

## Building Locally

To build the artifacts locally, simply clone this repository and run `mvn package` at the root of the project.
This will build everything.
```shell script
git clone https://github.com/SchweizerischeBundesbahnen/spring-cloud-stream-binder.git
cd spring-cloud-stream-binder
mvn package # or mvn install to install them locally
```
## Run Tests
To run the it-tests execute the following command:
```shell script
mvn -B verify -Dmaven.test.skip=false -P it_tests --file pom.xml --no-transfer-progress
```

### Run Tests With An External Broker

By default, the tests requires for Docker to be installed on the host machine so that they can auto-provision a PubSub+ broker. Otherwise, the following environment variables can be set to direct the tests to use an external broker:
```
SOLACE_JAVA_HOST=tcp://localhost:55555
SOLACE_JAVA_CLIENT_USERNAME=default
SOLACE_JAVA_CLIENT_PASSWORD=default
SOLACE_JAVA_MSG_VPN=default
TEST_SOLACE_MGMT_HOST=http://localhost:8080
TEST_SOLACE_MGMT_USERNAME=admin
TEST_SOLACE_MGMT_PASSWORD=admin
```

### Run Tests on an external Docker
```
DOCKER_HOST=tcp://123.123.123.123:2375
```

## Release Process

1. Update the version in the pom.xml
2. Push a branch to github
3. Create a Pullrequest 
4. Approve and merge the Pullrequest to master
5. Create a release in github and assign the same version
6. Go to https://oss.sonatype.org and release the new version


## Contributing

Please read [CONTRIBUTING.md](CONTRIBUTING.md) for details on the process for submitting pull requests to us.

## Authors

See the list of [contributors](https://github.com/SchweizerischeBundesbahnen/spring-cloud-stream-binder/graphs/contributors) who participated in this project.

## License

This project is licensed under the Apache License, Version 2.0. - See the [LICENSE](LICENSE) file for details.

## Code of Conduct
[![Contributor Covenant](https://img.shields.io/badge/Contributor%20Covenant-v1.4%20adopted-ff69b4.svg)](CODE_OF_CONDUCT.md)
Please note that this project is released with a Contributor Code of Conduct. By participating in this project you agree to abide by its terms.

## Support
Open an issue at https://github.com/SchweizerischeBundesbahnen/spring-cloud-stream-binder/issues

