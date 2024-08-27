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

### Why?

The Java web ecosystem is missing a server solution that is dependency-free but offers support for [Virtual Threads](https://openjdk.org/jeps/444), hooks for dependency injection, and annotation-based request handling. Soklet aims to fill this void.

Soklet provides the basic plumbing to build "transactional" REST APIs that exchange small amounts of data with clients.
It does not make technology choices on your behalf (but [an example of how to build a full-featured API is available](https://github.com/soklet/toystore-app)). It does not natively support [Reactive Programming](https://en.wikipedia.org/wiki/Reactive_programming) or similar methodologies.  It _does_ give you the foundation to build your system, your way.

Soklet is [commercially-friendly Open Source Software](/docs/licensing), proudly powering production systems since 2015.

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

### Direct Download

If you don't use Maven, you can drop [soklet-2.0.0-SNAPSHOT.jar](https://repo1.maven.org/maven2/com/soklet/soklet/2.0.0-SNAPSHOT/soklet-2.0.0-SNAPSHOT.jar) directly into your project.  That's all you need!

### Example App

Soklet applications are regular Java applications - there is no Servlet container.

```java
class App {
  @Resource
  public static class ExampleResource {
    @GET("/")
    public String index() {
      return "Hello, world!";
    }

    @GET("/test-input")
    public Response testInput(@QueryParameter Integer input) {
      return new Response.Builder()
        .headers(Map.of("Content-Type", Set.of("application/json; charset=UTF-8")))
        // A real application would not construct JSON in this manner
        .body(String.format("{\"input\": %d}", input))
        .build();
    }
  }

  public static void main(String[] args) throws Exception {
    int port = 8080;

    // Bare-bones: use built-in DefaultServer and don't change the default configuration
    SokletConfiguration configuration = new SokletConfiguration.Builder(
      new DefaultServer.Builder(port).build()
    ).build();

    try (Soklet soklet = new Soklet(configuration)) {
      soklet.start();
      System.out.printf("Soklet started at http://localhost:%d\n", port);
      System.out.printf("Press any key to exit\n");
      System.in.read(); // or Thread.currentThread().join() in containers
    }
  }
}
```

You can use `javac` to build and `java` to run.  Soklet systems can be structurally as simple as a "hello world" app.

Requires JDK 16+ to be installed on your machine.  If you need one, Amazon provides [Corretto](https://aws.amazon.com/corretto/) - a free-to-use-commercially, production-ready distribution of [OpenJDK](https://openjdk.org/) that includes long-term support.

#### Build

```shell
javac -parameters -cp soklet-2.0.0-SNAPSHOT.jar -d build src/com/soklet/example/App.java 
```

#### Run

```shell
java --enable-preview -cp soklet-2.0.0-SNAPSHOT.jar:build com/soklet/example/App
```

#### **Test**

```shell
# Hello, world
% curl -i 'http://localhost:8080/'
HTTP/1.1 200 OK
Content-Length: 13
Content-Type: text/plain; charset=UTF-8

Hello, world!
```

### Credits

Soklet stands on the shoulders of giants.  Internally, it embeds code from the following OSS projects:

* [Microhttp](https://github.com/ebarlas/microhttp) by [Elliot Barlas](https://github.com/ebarlas) - MIT License
* [ClassIndex](https://github.com/atteo/classindex) by [Sławek Piotrowski](https://github.com/sentinelt) - Apache 2.0 License
* [Selenium](https://github.com/SeleniumHQ/selenium) - Apache 2.0 License
* [Apache Commons FileUpload](https://commons.apache.org/proper/commons-fileupload/) - Apache 2.0 License
* [The Spring Framework](https://spring.io/) - Apache 2.0 License
