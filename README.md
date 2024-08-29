<a href="https://www.soklet.com">
    <picture>
        <source media="(prefers-color-scheme: dark)" srcset="https://cdn.soklet.com/soklet-gh-logo-dark-v2.png">
        <img alt="Soklet" src="https://cdn.soklet.com/soklet-gh-logo-light-v2.png" width="300" height="101">
    </picture>
</a>

### What Is It?

A small [HTTP 1.1 server](https://github.com/ebarlas/microhttp) and route handler for Java, well-suited for building RESTful APIs.<br/><br/>
Zero dependencies.  Dependency Injection friendly.<br/>
Optionally powered by [JEP 444: Virtual Threads](https://openjdk.org/jeps/444).

Soklet is a library, not a framework.

**Note: this README provides a high-level overview of Soklet.**<br/>
**For details, please refer to the official documentation at [https://www.soklet.com](https://www.soklet.com).**

### Why?

The Java web ecosystem is missing a server solution that is dependency-free but offers support for virtual threads, hooks for dependency injection, and annotation-based request handling. Soklet aims to fill this void.

Soklet provides the basic plumbing to build "transactional" REST APIs that exchange small amounts of data with clients.
It does not make technology choices on your behalf (but [an example of how to build a full-featured API is available](https://www.soklet.com/docs/toy-store-app)). It does not natively support [Reactive Programming](https://en.wikipedia.org/wiki/Reactive_programming) or similar methodologies.  It _does_ give you the foundation to build your system, your way.

Soklet is [commercially-friendly Open Source Software](https://www.soklet.com/docs/licensing), proudly powering production systems since 2015.

### Design Goals

* Main focus: routing HTTP requests to Java methods
* Near-instant startup
* Zero dependencies
* Immutability/thread-safety
* Small, comprehensible codebase
* Support for automated unit and integration testing
* Emphasis on configurability

### Design Non-Goals

* SSL/TLS (your load balancer should provide TLS termination)
* HTTP streaming
* WebSockets
* Dictate which technologies to use (Guice vs. Dagger, Gson vs. Jackson, etc.)
* "Batteries included" authentication and authorization

### Do Zero-Dependency Libraries Interest You?

Similarly-flavored commercially-friendly OSS libraries are available.

* [Lokalized](https://www.lokalized.com) - natural-sounding translations (i18n) via expression language 
* [Pyranid](https://www.pyranid.com) - makes working with JDBC pleasant

### License

[Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0)

### Installation

Soklet is a single JAR, available on Maven Central.

Java 16+ is required.

#### Maven

```xml
<dependency>
  <groupId>com.soklet</groupId>
  <artifactId>soklet</artifactId>
  <version>2.0.0-SNAPSHOT</version>
</dependency>
```

#### Gradle

```groovy
repositories {
  mavenCentral()
}

dependencies {
  implementation 'com.soklet:soklet:2.0.0-SNAPSHOT'
}
```

#### Direct Download

If you don't use Maven or Gradle, you can drop [soklet-2.0.0.jar](https://repo1.maven.org/maven2/com/soklet/soklet/2.0.0/soklet-2.0.0.jar) directly into your project.  No other dependencies are required.

### Code Sample

Here we demonstrate building and running a single-file Soklet application with nothing but the [soklet-2.0.0.jar](https://repo1.maven.org/maven2/com/soklet/soklet/2.0.0/soklet-2.0.0.jar) and the JDK.  There are no other libraries or frameworks, no Servlet container, no Maven or Gradle build process - no special setup is required.

Soklet systems can be structurally as simple as a "hello world" app.

While a real production system will have more moving parts, this demonstrates that you _can_ build server software without ceremony or dependencies.

```java
// Visit https://www.soklet.com to learn how to build a real app
public class App {
  @Resource
  public static class ExampleResource {
    @GET("/")
    public String index() {
      return "Hello, world!";
    }

    @GET("/test-input")
    public Response testInput(@QueryParameter Integer input) {
      return Response.withStatusCode(200)
        .headers(Map.of("Content-Type", Set.of("application/json; charset=UTF-8")))
        // A real application would not construct JSON in this manner
        .body(String.format("{\"input\": %d}", input))
        .build();
    }
  }

  public static void main(String[] args) throws Exception {
    // Use default configuration
    SokletConfiguration config = SokletConfiguration.withServer(
      DefaultServer.withPort(8080).build()
    ).build();

    try (Soklet soklet = new Soklet(config)) {
      soklet.start();
      System.out.println("Soklet started. Press enter key to exit");
      System.in.read(); // or Thread.currentThread().join() in containers
    }
  }
}
```

Here, we use raw `javac` to build and `java` to run.

This example requires JDK 16+ to be installed on your machine ([or use Docker](https://github.com/soklet/barebones-app?tab=readme-ov-file#building-and-running-with-docker)).  If you need a JDK, Amazon provides [Corretto](https://aws.amazon.com/corretto/) - a free-to-use-commercially, production-ready distribution of [OpenJDK](https://openjdk.org/) that includes long-term support.

#### Build

```shell
javac -parameters -cp soklet-2.0.0-SNAPSHOT.jar -d build src/com/soklet/example/App.java 
```

#### Run

```shell
java -cp soklet-2.0.0-SNAPSHOT.jar:build com/soklet/example/App
```

#### Test

```shell
# Hello, world
% curl -i 'http://localhost:8080/'
HTTP/1.1 200 OK
Content-Length: 13
Content-Type: text/plain; charset=UTF-8

Hello, world!
```

```shell
# Query parameter
% curl -i 'http://localhost:8080/test-input?input=123'
HTTP/1.1 200 OK
Content-Length: 14
Content-Type: application/json; charset=UTF-8

{"input": 123}
```

```shell
# Bad input
% curl -i 'http://localhost:8080/test-input?input=abc'
HTTP/1.1 400 Bad Request
Content-Length: 21
Content-Type: text/plain; charset=UTF-8

HTTP 400: Bad Request
```

### Building Real-World Apps

Of course, real-world apps have more moving parts than a "hello world" example.

[The Toy Store App](https://www.soklet.com/docs/toy-store-app) showcases how you might build a robust production system with Soklet.

Feature highlights include:

* Authentication and role-based authorization
* Basic CRUD operations
* Dependency injection via [Google Guice](https://github.com/google/guice)
* Relational database integration via [Pyranid](https://www.pyranid.com)
* Context-awareness via [ScopedValue (JEP 481)](https://openjdk.org/jeps/481)
* Internationalization via the JDK and [Lokalized](https://www.lokalized.com)
* JSON requests/responses via [Gson](https://github.com/google/gson)
* Logging via [SLF4J](https://slf4j.org/) / [Logback](https://logback.qos.ch/)
* Automated unit and integration tests via [JUnit](https://junit.org)
* Ability to run in [Docker](https://www.docker.com/)

### Servlet Integration

Optional support is available for both legacy [`javax.servlet`](https://github.com/soklet/soklet-servlet-javax) and current [`jakarta.servlet`](https://github.com/soklet/soklet-servlet-jakarta) specifications.  Just add the appropriate JAR to your project and you're good to go.

The Soklet website has in-depth [Servlet integration documentation](https://www.soklet.com/docs/servlet-integration).

### Learning More

Please refer to the official Soklet website [https://www.soklet.com](https://www.soklet.com) for detailed documentation.

### Credits

Soklet stands on the shoulders of giants.  Internally, it embeds code from the following OSS projects:

* [Microhttp](https://github.com/ebarlas/microhttp) by [Elliot Barlas](https://github.com/ebarlas) - MIT License
* [ClassIndex](https://github.com/atteo/classindex) by [Sławek Piotrowski](https://github.com/sentinelt) - Apache 2.0 License
* [Selenium](https://github.com/SeleniumHQ/selenium) - Apache 2.0 License
* [Apache Commons FileUpload](https://commons.apache.org/proper/commons-fileupload/) - Apache 2.0 License
* [The Spring Framework](https://spring.io/) - Apache 2.0 License
