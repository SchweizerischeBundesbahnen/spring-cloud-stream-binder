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

| Spring Cloud | Spring Cloud Stream Binder Solace | Spring Boot | sol-jcsmp |
|--------------|-----------------------------------|-------------|-----------|
| 2025.0.0     | 7.1.0                             | 3.5.5       | 10.28.1   |
| 2025.0.0     | 7.0.3                             | 3.5.4       | 10.27.3   |
| 2025.0.0     | 7.0.2                             | 3.5.4       | 10.27.3   |
| 2025.0.0     | 7.0.1                             | 3.5.4       | 10.27.3   |
| 2025.0.0     | 7.0.0                             | 3.5.3       | 10.27.2   |
| 2025.0.0     | 6.0.1                             | 3.5.3       | 10.27.2   |
| 2025.0.0     | 6.0.0                             | 3.5.3       | 10.27.2   |
| 2024.0.1     | 5.0.10                            | 3.4.4       | 10.27.1   |
| 2024.0.1     | 5.0.8                             | 3.4.4       | 10.26.0   |
| 2024.0.1     | 5.0.7                             | 3.4.3       | 10.25.2   |
| 2024.0.0     | 5.0.6                             | 3.4.2       | 10.25.2   |
| 2024.0.0     | 5.0.5                             | 3.4.2       | 10.25.2   |
| 2023.0.3     | 5.0.4                             | 3.3.5       | 10.25.1   |
| 2023.0.3     | 5.0.2                             | 3.3.3       | 10.24.1   |
| 2023.0.3     | 5.0.1                             | 3.3.3       | 10.24.1   |
| 2023.0.3     | 5.0.0                             | 3.3.2       | 10.24.1   |
| 2023.0.3     | 4.2.4                             | 3.3.2       | 10.24.1   |
| 2023.0.3     | 4.2.3                             | 3.3.2       | 10.24.1   |
| 2023.0.2     | 4.2.2                             | 3.3.1       | 10.24.0   |
| 2023.0.2     | 4.2.1                             | 3.3.1       | 10.23.0   |
| 2023.0.2     | 4.2.0                             | 3.3.0       | 10.23.0   |
| 2023.0.1     | 4.0.1                             | 3.2.5       | 10.23.0   |
| 2023.0.1     | 4.0.0                             | 3.2.5       | 10.23.0   |

For older versions you can try at your own risk or use the original version from solace:
https://github.com/SolaceDev/solace-spring-cloud

## Fork vs Original

Check out the difference between this fork and the original solace spring cloud stream binder
[COMPARE_WITH_SOLACE.md](COMPARE_WITH_SOLACE.md)

## Using it with Maven

```xml

<dependencies>
    <dependency>
        <groupId>ch.sbb</groupId>
        <artifactId>spring-cloud-stream-binder-solace</artifactId>
        <version>7.1.0</version>
    </dependency>
</dependencies>
```

## Documentation

Read [API.adoc](API.adoc) for a description of the properties and API.
All Changes are documented in the [Changelog](CHANGELOG.md).

## Contributing

To build/release read [DEVELOPER.md](DEVELOPER.md).

Please read [CONTRIBUTING.md](CONTRIBUTING.md) for details on the process for submitting pull requests to us.

## Authors

See the list
of [contributors](https://github.com/SchweizerischeBundesbahnen/spring-cloud-stream-binder/graphs/contributors) who
participated in this project.

## License

This project is licensed under the Apache License, Version 2.0. - See the [LICENSE](LICENSE) file for details.

## Code of Conduct

[![Contributor Covenant](https://img.shields.io/badge/Contributor%20Covenant-v1.4%20adopted-ff69b4.svg)](CODE_OF_CONDUCT.md)
Please note that this project is released with a Contributor Code of Conduct. By participating in this project you agree
to abide by its terms.

## Support

Open an issue at https://github.com/SchweizerischeBundesbahnen/spring-cloud-stream-binder/issues

## Links
[Java profiler](https://www.ej-technologies.com/jprofiler) used to improve performance: [![JProfiler](https://www.ej-technologies.com/images/product_banners/jprofiler_small.png)](https://www.ej-technologies.com/jprofiler)
