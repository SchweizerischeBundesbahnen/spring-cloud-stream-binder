# Spring Cloud Stream Binder for Solace 

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
