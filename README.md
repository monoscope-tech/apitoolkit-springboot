<div align="center">

![APItoolkit's Logo](https://github.com/apitoolkit/.github/blob/main/images/logo-white.svg?raw=true#gh-dark-mode-only)
![APItoolkit's Logo](https://github.com/apitoolkit/.github/blob/main/images/logo-black.svg?raw=true#gh-light-mode-only)

## Springboot SDK

[![APItoolkit SDK](https://img.shields.io/badge/APItoolkit-SDK-0068ff?logo=spring)](https://github.com/topics/apitoolkit-sdk) [![Join Discord Server](https://img.shields.io/badge/Chat-Discord-7289da)](https://apitoolkit.io/discord?utm_campaign=devrel&utm_medium=github&utm_source=sdks_readme) [![APItoolkit Docs](https://img.shields.io/badge/Read-Docs-0068ff)](https://apitoolkit.io/docs/sdks/java/springboot?utm_campaign=devrel&utm_medium=github&utm_source=sdks_readme)

APIToolkit Springboot SKD is a middleware that can be used to monitor HTTP requests. It is provides additional functionalities on top of the open telemetry instrumentation which creates a custom span for each request capturing details about the request including request and response bodies.

</div>

---

## Table of Contents

- [Installation](#installation)
- [Setup Open Telemetry](#setup-open-telemetry)
- [APItoolkit SDK Configuration](#apitoolkit-sdk-configuration)
- [Contributing and Help](#contributing-and-help)
- [License](#license)

---

## Installation

To install the SDK, kindly add the following dependency to your `pom.xml` file within the `<dependencies>` section like so:

```xml
<dependency>
    <groupId>io.apitoolkit.springboot</groupId>
    <artifactId>apitoolkit-springboot</artifactId>
    <version>2.0.10</version>
</dependency>
```

## Setup Open Telemetry

Setting up open telemetry allows you to send traces, metrics and logs to the APIToolkit platform.
To setup open telemetry, you need to install the opentelemetry-javaagent.jar file.

```sh
curl -L -O https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar
```

### Setup Open Telemetry Variables

The environment variables include your API key and the endpoint to send the data to, this allows you to send data to the APIToolkit platform.

```sh
export OTEL_EXPORTER_OTLP_ENDPOINT="http://otelcol.apitoolkit.io:4317"
export OTEL_SERVICE_NAME="my-service" # Specifies the name of the service.
export OTEL_RESOURCE_ATTRIBUTES=at-project-key="{ENTER_YOUR_API_KEY_HERE}" # Adds your API KEY to the resource.
export OTEL_EXPORTER_OTLP_PROTOCOL="grpc" #Specifies the protocol to use for the OpenTelemetry exporter.
```

> [!NOTE]
>
> The `{ENTER_YOUR_API_KEY_HERE}` demo string should be replaced with the [API key](https://apitoolkit.io/docs/dashboard/settings-pages/api-keys?utm_campaign=devrel&utm_medium=github&utm_source=sdks_readme) generated from the APItoolkit dashboard.

<br />

You can then run the application with opentelemetry instrumented using the following command:

```sh
java -javaagent:<PATH-TO>/opentelemetry-javaagent.jar -jar target/your_app.jar
```

## APItoolkit SDK Configuration

The apitoolkit sdk can be configured using the following optional properties:

```sh
apitoolkit.captureRequestBody=true
apitoolkit.captureResponseBody=true
apitoolkit.serviceName=my-service

# ...
```

Then, initialize the SDK like so:

```java
package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
// Import APItoolkit annotation
import io.apitoolkit.springboot.annotations.EnableAPIToolkit;
import org.springframework.web.bind.annotation.*;

@SpringBootApplication
// Add APIToolkit custom annotation
@EnableAPIToolkit
@RestController
public class DemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}
	@GetMapping("/greet/{name}")
	public String getUser(@PathVariable String name) {
		return "Hello, " + name;
	}
}
```

<br />

> [!IMPORTANT]
>
> To learn more configuration options (redacting fields, error reporting, outgoing requests, etc.), please read this [SDK documentation](https://apitoolkit.io/docs/sdks/java/springboot?utm_campaign=devrel&utm_medium=github&utm_source=sdks_readme).

## Contributing and Help

To contribute to the development of this SDK or request help from the community and our team, kindly do any of the following:

- Read our [Contributors Guide](https://github.com/apitoolkit/.github/blob/main/CONTRIBUTING.md).
- Join our community [Discord Server](https://apitoolkit.io/discord?utm_campaign=devrel&utm_medium=github&utm_source=sdks_readme).
- Create a [new issue](https://github.com/apitoolkit/apitoolkit-springboot/issues/new/choose) in this repository.

## License

This repository is published under the [MIT](LICENSE) license.

---

<div align="center">

<a href="https://apitoolkit.io?utm_campaign=devrel&utm_medium=github&utm_source=sdks_readme" target="_blank" rel="noopener noreferrer"><img src="https://github.com/apitoolkit/.github/blob/main/images/icon.png?raw=true" width="40" /></a>

</div>
