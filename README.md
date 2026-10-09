<a href="https://www.soklet.com">
    <picture>
        <source media="(prefers-color-scheme: dark)" srcset="https://cdn.soklet.com/soklet-gh-logo-dark-v2.png">
        <img alt="Soklet" src="https://cdn.soklet.com/soklet-gh-logo-light-v2.png" width="300" height="101">
    </picture>
</a>

[![Maven Central](https://img.shields.io/maven-central/v/com.soklet/soklet.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/com.soklet/soklet)
[![CI](https://github.com/soklet/soklet/actions/workflows/ci.yml/badge.svg)](https://github.com/soklet/soklet/actions/workflows/ci.yml)
[![Javadoc](https://javadoc.io/badge2/com.soklet/soklet/javadoc.svg)](https://javadoc.soklet.com)
[![Changelog](https://img.shields.io/badge/changelog-view-blue)](CHANGELOG.md)

### What Is It?

A small [HTTP/1.1 server](https://github.com/ebarlas/microhttp) and route handler for Java, well-suited for building RESTful APIs, broadcasting [Server-Sent Events](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events), and exposing dedicated [Model Context Protocol](https://modelcontextprotocol.io/) servers.<br/><br/>
Zero dependencies. Dependency Injection friendly.<br/>
Optionally powered by [JEP 444: Virtual Threads](https://openjdk.org/jeps/444).

Soklet codes like a library, not a framework.

**Note: this README provides a high-level overview of Soklet.**<br/>
**For details, please refer to the official documentation at [https://www.soklet.com](https://www.soklet.com).**

### Why?

The Java web ecosystem is missing an HTTP server solution that is dependency-free but offers support for [Server-Sent Events (SSE)](https://www.soklet.com/docs/server-sent-events) along with hooks for dependency injection and annotation-based request handling. Soklet aims to fill this void.

Soklet provides the plumbing to build "transactional" REST APIs as well as systems that vend results via [HTTP response streaming](https://www.soklet.com/docs/response-writing#streaming-responses) or [SSE](https://www.soklet.com/docs/server-sent-events).
It does not make technology choices on your behalf (but [an example of how to build a full-featured API is available](https://www.soklet.com/docs/toystore-app)). It does not natively support [Reactive Programming](https://en.wikipedia.org/wiki/Reactive_programming) or similar methodologies. It _does_ give you the foundation to build your system, your way.

Soklet is [commercially-friendly Open Source Software](https://www.soklet.com/docs/licensing), proudly powering production systems since 2015.

### Design Goals

- Main focus: routing HTTP/1.1 requests to Java methods
- Near-instant startup
- Zero dependencies
- Immutability/thread-safety
- Small, comprehensible codebase - auditable end-to-end by a human or AI agent
- No runtime classpath scanning or autoconfiguration (explicit behavior, statically analyzable)
- Contract/interface-driven: bring your own implementations for almost anything
- Thorough, high-quality documentation
- Extensive support for [automated unit and integration testing](https://www.soklet.com/docs/testing)
- Fine-grained [telemetry and metrics collection](https://www.soklet.com/docs/metrics-collection)
- Best-in-class support for [Server-Sent Events](https://www.soklet.com/docs/server-sent-events)
- [Servlet Integration](https://www.soklet.com/docs/servlet-integration) for legacy code

### Design Non-Goals

- SSL/TLS (your load balancer should provide TLS termination)
- HTTP/2, HTTP/3 (also handled by your load balancer)
- WebSockets
- Dictate which technologies to use (Guice vs. Dagger, Gson vs. Jackson, etc.)
- "Batteries included" authentication and authorization

### Do Zero-Dependency Libraries Interest You?

Similarly-flavored commercially-friendly OSS libraries are available.

- [Pyranid](https://www.pyranid.com) - makes working with JDBC pleasant
- [Lokalized](https://www.lokalized.com) - natural-sounding translations (i18n) via expression language

### License

[Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0)

Redistributed-source attributions are retained in [`NOTICE`](NOTICE); the
4.0.0 review is recorded in the [third-party audit](release/THIRD_PARTY_AUDIT.md).

### Installation

Soklet is a single JAR, available on Maven Central.

JDK 17+ is required (or JDK 21+ for [Server-Sent Events](https://www.soklet.com/docs/server-sent-events)).

Upgrading from 3.5.1? Read the [4.0.0 migration guide](MIGRATING_TO_4_0.md).
Building an MCP server? Start with the copy/paste [MCP quickstart](MCP_QUICKSTART.md).

For the required annotation processor configuration, see [Building and Running](#building-and-running).

#### Maven

```xml
<dependency>
  <groupId>com.soklet</groupId>
  <artifactId>soklet</artifactId>
  <version>4.0.0</version>
</dependency>
```

#### Gradle

```groovy
dependencies {
  implementation 'com.soklet:soklet:4.0.0'
}
```

#### Direct Download

If you don't use Maven or Gradle, you can drop [soklet-4.0.0.jar](https://repo1.maven.org/maven2/com/soklet/soklet/4.0.0/soklet-4.0.0.jar) directly into your project. No other dependencies are required.

### Code Sample

Here we demonstrate building and running a single-file Soklet application with nothing but the [soklet-4.0.0.jar](https://repo1.maven.org/maven2/com/soklet/soklet/4.0.0/soklet-4.0.0.jar) and the JDK. There are no other libraries or frameworks, no Servlet container, no Maven or Gradle build process - no special setup is required.

Soklet systems can be structurally as simple as a "hello world" app.

While a real production system will have more moving parts, this demonstrates that you _can_ build server software without ceremony or dependencies.

```java
package com.soklet.example;

import com.soklet.*;
import com.soklet.annotation.*;

import java.time.*;
import java.util.*;

public class App {
  // Canonical example
  @GET("/")
  public String index() {
    return "Hello, world!";
  }

  // Echoes back the path parameter, which must be a LocalDate
  @GET("/echo/{date}")
  public LocalDate echo(@PathParameter LocalDate date) {
    return date;
  }

  // Formats request body locale for display and customizes the response.
  // Example: fr-CA ⇒ francês (Canadá)
  @POST("/language")
  public Response languageFor(@RequestBody Locale locale) {
    Locale systemLocale = Locale.forLanguageTag("pt-BR");
    String contentLanguage = systemLocale.toLanguageTag();

    return Response.withStatusCode(200)
      .body(locale.getDisplayName(systemLocale))
      .headers(Map.of("Content-Language", List.of(contentLanguage)))
      .cookies(List.of(
        ResponseCookie.withName("lastRequest")
          .value(Instant.now().toString())
          .httpOnly(true)
          .secure(true)
          .maxAge(Duration.ofMinutes(5))
          .sameSite(ResponseCookie.SameSite.LAX)
          .build()
      ))
      .build();
  }

  // Start the server and listen on :8080
  public static void main(String[] args) throws Exception {
    // Use out-of-the-box defaults
    SokletConfig config = SokletConfig.withHttpServer(
      HttpServer.fromPort(8080)
    ).build();

    System.out.println("Starting Soklet; press [enter] to stop once ready");
    SokletApplication.run(config, ShutdownTrigger.ENTER_KEY);
  }
}
```

The static [`run(SokletConfig)`](<https://javadoc.soklet.com/com/soklet/SokletApplication.html#run(com.soklet.SokletConfig)>) and
[`run(SokletConfig, ShutdownTrigger...)`](<https://javadoc.soklet.com/com/soklet/SokletApplication.html#run(com.soklet.SokletConfig,com.soklet.ShutdownTrigger...)>) methods on
[`SokletApplication`](https://javadoc.soklet.com/com/soklet/SokletApplication.html) cover the usual standalone-process lifecycle. If
the runner also owns bounded application-resource cleanup, configure one
one-shot application and supply that cleanup for its run:

```java
ShutdownResult result = SokletApplication.fromConfig(config).run(
    ShutdownCleanup.fromTimeoutAndAction(
        Duration.ofSeconds(5),
        shutdownResult -> applicationResources.close()),
    ShutdownTrigger.ENTER_KEY);
```

The configured [`SokletApplication`](https://javadoc.soklet.com/com/soklet/SokletApplication.html)
is one-shot. After any run attempt begins,
it cannot be run a second time or concurrently. The cleanup action is eligible
only after Soklet has proven core shutdown complete; an incomplete core
shutdown skips it.

#### Building and Running

Here we use raw `javac` to build and `java` to run.

This example requires JDK 17+ to be installed on your machine ([or see this example of using Docker for Soklet apps](https://github.com/soklet/barebones-app?tab=readme-ov-file#building-and-running-with-docker)). If you need a JDK, Amazon provides [Corretto](https://aws.amazon.com/corretto/) - a free-to-use-commercially, production-ready distribution of [OpenJDK](https://openjdk.org/) that includes long-term support.

##### Build

```shell
javac -parameters -cp soklet-4.0.0.jar -processor com.soklet.SokletProcessor -d build src/com/soklet/example/App.java
```

##### Run

```shell
java -cp soklet-4.0.0.jar:build com/soklet/example/App
```

##### Test

```shell
# Hello, world
% curl -i 'http://localhost:8080/'
HTTP/1.1 200 OK
Content-Length: 13
Content-Type: text/plain; charset=UTF-8
Date: Sun, 21 Mar 2024 16:19:01 GMT

Hello, world!
```

```shell
# Acceptable path parameter
% curl -i 'http://localhost:8080/echo/2024-12-31'
HTTP/1.1 200 OK
Content-Length: 10
Content-Type: text/plain; charset=UTF-8
Date: Sun, 21 Mar 2024 16:19:01 GMT

2024-12-31
```

```shell
# Illegal path parameter
% curl -i 'http://localhost:8080/echo/abc'
HTTP/1.1 400 Bad Request
Content-Length: 21
Content-Type: text/plain; charset=UTF-8
Date: Sun, 21 Mar 2024 16:19:01 GMT

HTTP 400: Bad Request
```

```shell
# Language request body
% curl -i -X POST 'http://localhost:8080/language' -d 'fr-CA'
HTTP/1.1 200 OK
Content-Language: pt-BR
Content-Length: 18
Content-Type: text/plain; charset=UTF-8
Date: Sun, 21 Mar 2024 16:19:01 GMT
Set-Cookie: lastRequest=2024-04-21T16:19:01.115336Z; Max-Age=300; Secure; HttpOnly; SameSite=Lax

francês (Canadá)
```

##### Maven build configuration

Also configure annotation processing in your POM's `build/plugins` section.
This generates HTTP/SSE routes and MCP endpoint descriptors; declaring only
the dependency is not sufficient on JDK 23 and later:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <version>3.16.0</version>
  <configuration>
    <parameters>true</parameters>
    <annotationProcessorPaths>
      <path>
        <groupId>com.soklet</groupId>
        <artifactId>soklet</artifactId>
        <version>4.0.0</version>
      </path>
    </annotationProcessorPaths>
    <annotationProcessors>
      <annotationProcessor>com.soklet.SokletProcessor</annotationProcessor>
    </annotationProcessors>
  </configuration>
</plugin>
```

Merge this configuration with your existing compiler-plugin configuration.
The explicit processor path replaces compile-classpath discovery, and the named
processor list selects which processors run. Preserve every other processor
your build needs (such as Lombok or MapStruct) by keeping its artifact in
`annotationProcessorPaths` and its processor class name in `annotationProcessors`
alongside Soklet.

##### Gradle build configuration

```groovy
plugins {
  id 'java'
}

repositories {
  mavenCentral()
}

dependencies {
  implementation 'com.soklet:soklet:4.0.0'
  annotationProcessor 'com.soklet:soklet:4.0.0'
}

tasks.withType(JavaCompile).configureEach {
  options.compilerArgs += ['-parameters']
}
```

Gradle discovers processors through `annotationProcessor`, not `implementation`.
If test sources declare annotated routes or endpoints, also configure
`testAnnotationProcessor 'com.soklet:soklet:4.0.0'`.

##### Packaging and static analysis

The optional `@NonNull` and `@Nullable` annotations used in advanced examples
come from `org.jspecify.annotations`. If you use them in application source,
declare `org.jspecify:jspecify:1.0.1` as a compile-time dependency; Soklet's
provided annotation dependency is not inherited by consumers.

Preserve generated classes and `META-INF/soklet` indexes when shading or
repackaging either build.

The class files retain compile-time annotation references. Static tools such as
`jdeps` may need those annotation JARs on their analysis classpath, or
`jdeps --ignore-missing-deps` after verifying that only those annotation types
are missing. That option is not a `jlink` option; the automatic module name
does not make the Soklet JAR directly linkable. See the
[dependency audit](release/THIRD_PARTY_AUDIT.md) for the scope of this distinction.

### Building Real-World Apps

Of course, real-world apps have more moving parts than a "hello world" example.

[The Toy Store App](https://www.soklet.com/docs/toystore-app) showcases how you might build a robust production system with Soklet.

Feature highlights include:

- Authentication and role-based authorization
- Basic CRUD operations
- Dependency injection via [Google Guice](https://github.com/google/guice)
- Relational database integration via [Pyranid](https://www.pyranid.com)
- Context-awareness via [ScopedValue (JEP 481)](https://openjdk.org/jeps/481)
- Internationalization via the JDK and [Lokalized](https://www.lokalized.com)
- JSON requests/responses via [Gson](https://github.com/google/gson)
- Logging via [SLF4J](https://slf4j.org/) / [Logback](https://logback.qos.ch/)
- Metrics collection via [`MetricsCollector`](https://javadoc.soklet.com/com/soklet/MetricsCollector.html)
- Automated unit and integration tests via [JUnit](https://junit.org)
- Ability to run in [Docker](https://www.docker.com/)

### What Else Does It Do?

#### Request Handling

Soklet maps HTTP requests to plain Java methods known as Resource Methods
([`ResourceMethod`](https://javadoc.soklet.com/com/soklet/ResourceMethod.html)).
Annotate them with [`@GET`](https://javadoc.soklet.com/com/soklet/annotation/GET.html),
[`@POST`](https://javadoc.soklet.com/com/soklet/annotation/POST.html),
[`@PUT`](https://javadoc.soklet.com/com/soklet/annotation/PUT.html),
[`@PATCH`](https://javadoc.soklet.com/com/soklet/annotation/PATCH.html),
[`@DELETE`](https://javadoc.soklet.com/com/soklet/annotation/DELETE.html),
[`@HEAD`](https://javadoc.soklet.com/com/soklet/annotation/HEAD.html),
[`@OPTIONS`](https://javadoc.soklet.com/com/soklet/annotation/OPTIONS.html), or
[`@SseEventSource`](https://javadoc.soklet.com/com/soklet/annotation/SseEventSource.html) for SSE.
Soklet discovers them at compile time via the
[`SokletProcessor`](https://javadoc.soklet.com/com/soklet/SokletProcessor.html) annotation processor, avoiding
classpath scans at startup. See the [Request Handling](https://www.soklet.com/docs/request-handling) docs for details.

#### Access To Request Data

Resource Methods ([`ResourceMethod`](https://javadoc.soklet.com/com/soklet/ResourceMethod.html)) can accept a
[`Request`](https://javadoc.soklet.com/com/soklet/Request.html) parameter and inspect
[`HttpMethod`](https://javadoc.soklet.com/com/soklet/HttpMethod.html) values.

```java
@GET("/example")
public void example(Request request /* param name is arbitrary */) {
  // Here, it would be HttpMethod.GET
  HttpMethod httpMethod = request.getHttpMethod();
  // Just the path, e.g. "/example"
  String path = request.getPath();
  // The raw path and query, e.g. "/example?test=123"
  String rawPathAndQuery = request.getRawPathAndQuery();
  // Request body as bytes, if available
  Optional<byte[]> body = request.getBody();
  // Request body marshaled to a string, if available.
  // Charset defined in "Content-Type" header is used to marshal.
  // If not specified, UTF-8 is assumed
  Optional<String> bodyAsString = request.getBodyAsString();
  // Query parameter values by name
  Map<String, List<String>> queryParameters = request.getQueryParameters();
  // Convenience for a query parameter when at most one value is expected;
  // throws IllegalQueryParameterException if multiple values are present
  Optional<String> queryParameter = request.getQueryParameter("test");
  // Header values by name (names are case-insensitive)
  Map<String, List<String>> headers = request.getHeaders();
  // Convenience for a header when at most one value is expected
  // (case-insensitive name); throws if multiple values are present
  Optional<String> header = request.getHeader("Accept-Language");
  // Parsed W3C trace context from traceparent/tracestate, if present
  Optional<TraceContext> traceContext = request.getTraceContext();
  // Request cookies by case-sensitive cookie name
  Map<String, List<String>> cookies = request.getCookies();
  // Convenience for a cookie when at most one value is expected;
  // throws IllegalRequestCookieException if multiple values are present
  Optional<String> cookie = request.getCookie("cookie-name");
  // Form parameters by name (application/x-www-form-urlencoded)
  Map<String, List<String>> fps = request.getFormParameters();
  // Convenience for a form parameter when at most one value is expected;
  // throws IllegalFormParameterException if multiple values are present
  Optional<String> fp = request.getFormParameter("fp-name");
  // Is this a multipart request?
  boolean multipart = request.isMultipart();
  // Multipart fields by name
  Map<String, List<MultipartField>> mpfs = request.getMultipartFields();
  // Convenience for a multipart name when at most one field is expected;
  // throws IllegalMultipartFieldException if multiple fields are present
  Optional<MultipartField> mpf = request.getMultipartField("file-input");
  // CORS information, if available
  Optional<Cors> cors = request.getCors();
  // Ordered locales via Accept-Language parsing
  List<Locale> locales = request.getLocales();
  // Ordered media ranges via Accept parsing; empty means no Accept preference
  List<MediaRange> mediaRanges = request.getMediaRanges();
  // Charset as specified by "Content-Type" header, if available
  Optional<Charset> charset = request.getCharset();
  // Content type component of "Content-Type" header, if available
  Optional<String> contentType = request.getContentType();
}
```

#### Value Conversions

Soklet converts textual request inputs to Java types using a
[`ValueConverterRegistry`](https://javadoc.soklet.com/com/soklet/converter/ValueConverterRegistry.html) populated with
[`ValueConverter<F,T>`](https://javadoc.soklet.com/com/soklet/converter/ValueConverter.html).
Conversions are applied to parameters annotated with
[`@QueryParameter`](https://javadoc.soklet.com/com/soklet/annotation/QueryParameter.html),
[`@PathParameter`](https://javadoc.soklet.com/com/soklet/annotation/PathParameter.html),
[`@RequestHeader`](https://javadoc.soklet.com/com/soklet/annotation/RequestHeader.html),
[`@RequestCookie`](https://javadoc.soklet.com/com/soklet/annotation/RequestCookie.html),
[`@FormParameter`](https://javadoc.soklet.com/com/soklet/annotation/FormParameter.html), and
[`@Multipart`](https://javadoc.soklet.com/com/soklet/annotation/Multipart.html).
Supply your own registry (or additional converters) via
[`SokletConfig`](https://javadoc.soklet.com/com/soklet/SokletConfig.html) to support custom types.

#### Request Body Parsing

Configure a [`RequestBodyMarshaler`](https://javadoc.soklet.com/com/soklet/RequestBodyMarshaler.html) however you like - here we accept JSON:

```java
SokletConfig config = SokletConfig.withHttpServer(
  HttpServer.fromPort(8080)
).requestBodyMarshaler(new RequestBodyMarshaler() {
  // This example uses Google's GSON
  static final Gson GSON = new Gson();

  @NonNull
  @Override
  public Optional<Object> marshalRequestBody(
    @NonNull Request request,
    @NonNull ResourceMethod resourceMethod,
    @NonNull Parameter parameter,
    @NonNull Type requestBodyType
  ) {
    // Let GSON turn the request body into an instance
    // of the specified type.
    //
    // Note that this method has access to all runtime information
    // about the request, which provides the opportunity to, for example,
    // examine annotations on the method/parameter which might
    // inform custom marshaling strategies.
    String body = request.getBodyAsString()
      .filter(value -> !value.isBlank())
      .orElseThrow(() -> new IllegalRequestBodyException(
        "Request body must contain JSON."
      ));

    try {
      Object value = GSON.fromJson(body, requestBodyType);

      if (value == null)
        throw new IllegalRequestBodyException(
          "Request body must contain a non-null JSON value."
        );

      return Optional.of(value);
    } catch (JsonParseException e) {
      // Expected parse failures are client errors. Keep request data and
      // the parser's input-bearing cause out of the public diagnostic.
      throw new IllegalRequestBodyException(
        "Request body is not valid JSON."
      );
    }
  }
}).build();
```

Then, apply:

```java
public record Employee (
  UUID id,
  String name
) {}

// Accepts a JSON-formatted Record type as input
@POST("/employees")
public void createEmployee(@RequestBody Employee employee) {
  System.out.printf("TODO: create %s\n", employee.name());
}
```

#### Response Writing

To control how response data is surfaced to clients (e.g. JSON), provide handler functions
([`ResourceMethodHandler`](https://javadoc.soklet.com/com/soklet/ResponseMarshaler.Builder.ResourceMethodHandler.html) and
[`ThrowableHandler`](https://javadoc.soklet.com/com/soklet/ResponseMarshaler.Builder.ThrowableHandler.html)) to Soklet as shown below.

Alternatively, you can provide your own implementation of [`ResponseMarshaler`](https://javadoc.soklet.com/com/soklet/ResponseMarshaler.html) for full control.

```java
// Let's use Gson to write response body data
// See https://github.com/google/gson
final Gson GSON = new Gson();

// The request was matched to a Resource Method and executed non-exceptionally
ResourceMethodHandler resourceMethodHandler = (
  @NonNull Request request,
  @NonNull Response response,
  @NonNull ResourceMethod resourceMethod
) -> {
  // Turn response body into JSON bytes with Gson
  Object bodyObject = response.getBody().orElse(null);
  byte[] body = bodyObject == null
    ? null
    : GSON.toJson(bodyObject).getBytes(StandardCharsets.UTF_8);

  // To be a good citizen, set the Content-Type header
  Map<String, List<String>> headers = new HashMap<>(response.getHeaders());
  headers.put("Content-Type", List.of("application/json;charset=UTF-8"));

  // Tell Soklet: "OK - here is the final response data to send"
  return MarshaledResponse.withResponse(response)
    .headers(headers)
    .body(body)
    .build();
};

// Function to create responses for exceptions that bubble out
ThrowableHandler throwableHandler = (
  @NonNull Request request,
  @NonNull Throwable throwable,
  @Nullable ResourceMethod resourceMethod
) -> {
  // Keep track of what to write to the response
  String message;
  int statusCode;

  // Examine the exception that bubbled out and determine what
  // the HTTP status and a user-facing message should be.
  // Note: real systems should localize these messages
  // Soklet throws this exception, a specific subclass of BadRequestException.
  if (throwable instanceof IllegalQueryParameterException e) {
    message = String.format("Illegal value '%s' for parameter '%s'",
      e.getQueryParameterValue().orElse("[not provided]"),
      e.getQueryParameterName());
    statusCode = 400;
  } else if (throwable instanceof BadRequestException) {
    // Generically handle other BadRequestExceptions.
    message = "Your request was improperly formatted.";
    statusCode = 400;
  } else {
    // Something else? Fall back to a 500.
    message = "An unexpected error occurred.";
    statusCode = 500;
  }

  // Turn response body into JSON bytes with Gson.
  // Note: real systems should expose richer error constructs
  // than an object with a single message field
  byte[] body = GSON.toJson(Map.of("message", message))
    .getBytes(StandardCharsets.UTF_8);

  // Specify our headers
  Map<String, List<String>> headers = new HashMap<>();
  headers.put("Content-Type", List.of("application/json;charset=UTF-8"));

  return MarshaledResponse.withStatusCode(statusCode)
    .headers(headers)
    .body(body)
    .build();
};

// Supply our custom handlers to the standard response marshaler
SokletConfig config = SokletConfig.withHttpServer(
  HttpServer.fromPort(8080)
).responseMarshaler(ResponseMarshaler.builder()
  .resourceMethodHandler(resourceMethodHandler)
  .throwableHandler(throwableHandler)
  .build()
).build();
```

##### Zero-Copy Responses

Already know exactly what you want to send over the wire? Use [`MarshaledResponse`](https://javadoc.soklet.com/com/soklet/MarshaledResponse.html) to skip additional processing.

```java
@GET("/example-image.png")
public MarshaledResponse exampleImage() {
  Path imageFile = Path.of("/home/user/test.png");

  // Serve a known-length file response over the wire.
  // Soklet sets Content-Length from the file size; Content-Type remains explicit.
  return MarshaledResponse.withStatusCode(200)
    .body(imageFile)
    .headers(Map.of(
      "Content-Type", List.of("image/png")
    ))
    .build();
}
```

[`MarshaledResponse`](https://javadoc.soklet.com/com/soklet/MarshaledResponse.html) supports known-length byte-array, file, file-channel, and [`ByteBuffer`](https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/nio/ByteBuffer.html) bodies. The standard HTTP server can write file-backed responses without first loading the whole file into heap memory. If you already selected a trusted file and want file-response semantics like validators and byte ranges, use [`MarshaledResponse::withFile`](<https://javadoc.soklet.com/com/soklet/MarshaledResponse.html#withFile(java.nio.file.Path,com.soklet.Request)>); its builder can set `Content-Type`, `Content-Encoding`, cache headers, validators, and range behavior. For safe static roots, use [`StaticFiles`](https://javadoc.soklet.com/com/soklet/StaticFiles.html) instead of hand-rolled path joins; it handles root containment, validators, optional content-hash ETags, access policy, single byte ranges, MIME defaults, and `GET`/`HEAD` behavior.

##### Response Compression

Standard HTTP can opt into compression for finalized in-memory byte-array and [`ByteBuffer`](https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/nio/ByteBuffer.html) responses with [`HttpServer.Builder::responseCompressor`](<https://javadoc.soklet.com/com/soklet/HttpServer.Builder.html#responseCompressor(com.soklet.ResponseCompressor)>). The provided factory selects gzip for common text-like media types at or above the supplied body-size threshold:

```java
HttpServer httpServer = HttpServer.withPort(8080)
  .responseCompressor(
    ResponseCompressor.fromDefaultsWithMinimumBodySizeInBytes(1_024)
  )
  .build();
```

Compression is disabled unless configured; passing `null` or [`ResponseCompressor::disabledInstance`](<https://javadoc.soklet.com/com/soklet/ResponseCompressor.html#disabledInstance()>) restores that default. A custom [`ResponseCompressor`](https://javadoc.soklet.com/com/soklet/ResponseCompressor.html) returns [`ResponseCompressionPlan::none`](<https://javadoc.soklet.com/com/soklet/ResponseCompressionPlan.html#none()>) or a plan selecting a [`ResponseCompressionCodec`](https://javadoc.soklet.com/com/soklet/ResponseCompressionCodec.html). Soklet supplies `ResponseCompressionCodec.gzipInstance()` using JDK gzip; applications can provide other codecs without adding a codec dependency to core.

Plans can wrap Soklet's lazy compression supplier with an application-owned cache. There is no built-in cache across responses: applications own cache keys, bounds, eviction, and thread safety, while Soklet owns encoding acceptance, `Vary`, validators, and response framing. `HEAD` selects the plan using the uncompressed representation but invokes neither the codec nor the cache callback. File, streaming, range, and already-encoded responses remain outside dynamic compression. See [Response Compression](https://www.soklet.com/docs/response-writing#response-compression) for caching and codec contracts, or [the 4.0.0 migration](MIGRATING_TO_4_0.md#response-compression) when replacing `ResponseGzipPolicy`.

##### Streaming Responses

[`MarshaledResponse`](https://javadoc.soklet.com/com/soklet/MarshaledResponse.html) also supports streaming response bodies when the final byte length is not known up front. Streaming is intentionally a marshaled-response feature, like file-backed output: the resource method is taking direct control of what Soklet writes to the HTTP response.

```java
@GET("/tokens")
public MarshaledResponse tokens(TokenService tokenService) {
  return MarshaledResponse.withStatusCode(200)
    .headers(Map.of(
      "Content-Type", List.of("text/plain; charset=UTF-8"),
      "Cache-Control", List.of("no-transform")
    ))
    .stream(responseStream -> {
      CancelationToken cancelationToken = responseStream.getCancelationToken();
      try (CallbackRegistration callbackRegistration = cancelationToken.onCancel(tokenService::stop)) {
        tokenService.generate(token -> {
          cancelationToken.throwIfCanceled();
          responseStream.write(token.getBytes(StandardCharsets.UTF_8));
          responseStream.flush();
        });
      }
    })
    .build();
}
```

`TokenService` is an application-defined, per-request provider in this example.
Its `generate` method invokes a checked token callback synchronously on the
producer thread; that callback can throw `Exception`. Its `stop` method must be
safe to call concurrently to unblock generation. An asynchronous provider must
hand events back to the producer thread before writing to `ResponseStream`.

Streaming responses use HTTP/1.1 chunked transfer encoding. Soklet owns `Transfer-Encoding`, rejects caller-supplied `Content-Length`, and gives the producer one [`ResponseStream`](https://javadoc.soklet.com/com/soklet/ResponseStream.html) for output and runtime metadata. Its `getCancelationToken()` allows upstream work to react when Soklet observes a client disconnect, forced shutdown begins after the graceful budget, or a streaming timeout fires. Indefinite HTTP feeds should check `ResponseStream.isGracefulShutdownRequested()` and finish cooperatively during graceful shutdown; the cancelation token is not canceled by that advisory signal. `getRequest()`, `getDeadline()`, and `getIdleTimeout()` expose the originating request and timing policy; producers can use [`Request::getId`](<https://javadoc.soklet.com/com/soklet/Request.html#getId()>) for correlation without ambient thread-local state. Closing a [`CallbackRegistration`](https://javadoc.soklet.com/com/soklet/CallbackRegistration.html) removes the cancelation callback if it has not already been claimed.

An HTTP/1.0 request that produces a streaming response receives a bodyless `505 HTTP Version Not Supported` with `Connection: close`. The producer/factory is never invoked, and its original stream handle terminates once with `PROTOCOL_UNSUPPORTED`. The handler and interceptors have already run, so their side effects are not undone. `willWriteResponse` sees the logical candidate; write and request-finish callbacks and metrics see the finite 505 replacement. Configure the reverse proxy's connection to Soklet to use HTTP/1.1; for nginx, set [`proxy_http_version 1.1;`](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_http_version). Buffered HTTP/1.0 responses and normal HEAD body omission still work. The simulator has no HTTP wire version and does not exercise this rejection.

Ordinary HTTP preserves input half-close and buffered pipelining: a client can stop sending while continuing to read its response. TCP FIN alone cannot distinguish that from a full peer close. A reset or recognized remote write failure cancels delivery with `CLIENT_DISCONNECTED`; routine remote closure does not emit a `WRITE_ERROR` transport diagnostic. An idle feed with no next write may retain its lifecycle slot until the idle or total streaming timeout. If both timeouts are disabled and no further write occurs, FIN alone supplies no cleanup bound. Keep a finite timeout or use application heartbeats for long-lived feeds.

`ResponseStream` also owns producer resources. `open(factory)` coordinates one close attempt for normal cleanup or cancelation; use it only when the provider supports close racing consumption. `open(factory, aborter)` uses a separate provider abort operation and final close. `own(resource)` finalizes on the producer thread and is suitable for resources whose close writes trailing response bytes. Both `using(factory, consumer)` and `using(factory, aborter, consumer)` close their resource and nested acquisitions before the block returns. Normal cleanup runs in reverse ownership order; Soklet seals successful output only after root cleanup finishes. Do not also close transferred resources yourself. Output and ownership operations are confined to the producer thread.

Encode text explicitly with `write(string.getBytes(StandardCharsets.UTF_8))`, use `write(bytes, offset, length)` for array slices, or use `asOutputStream()` with a `Writer` for sustained text or other Java I/O libraries. Each output view has its own closed state; closing one flushes shared staging without ending the response. Transfer encoder ownership so Soklet writes its trailer before completing the response:

```java
return MarshaledResponse.withStatusCode(200)
    .stream(responseStream -> {
        var zipOutputStream = responseStream.own(
            new java.util.zip.ZipOutputStream(responseStream.asOutputStream()));
        zipOutputStream.putNextEntry(new java.util.zip.ZipEntry("report.txt"));
        zipOutputStream.write(reportBytes);
        zipOutputStream.closeEntry();
    })
    .build();
```

Publisher bodies also retain pending asynchronous subscription acquisition and entered provider calls. A subscription delivered after cancelation is canceled without requesting data, and its lifecycle slot remains occupied until that cancel attempt finishes. If the first subscription never arrives, shutdown reports the retained obligation. See the [publisher lifecycle contract](MIGRATING_TO_4_0.md#http-streaming-callbacks-and-sources) for failed-acquisition and protocol requirements.

Configure streaming admission and cleanup on `HttpServer.Builder` with `streamingLifecycleCapacity(...)`, `streamingCallbackConcurrency(...)`, and `streamingCleanupTimeout(...)`. Their defaults are 256 admitted lifetimes, four cancelation/rejection workers, and five seconds of cleanup grace. Outstanding physical work retains admission capacity after cleanup expiry; exhausted admission returns HTTP 503 before starting a producer. These settings accept `null` to restore their defaults and are validated together at `build()`. Simulators derived from a built-in HTTP server inherit all three settings.

Effective streaming total and idle timeouts must be nonnegative and representable in nanoseconds (at most `Duration.ofNanos(Long.MAX_VALUE)`, about 292 years). `build()` rejects larger values, including an oversized idle timeout inherited from `requestBodyTimeout`. Zero disables a timeout; `null` restores the total timeout’s disabled default or the idle timeout’s effective request-body default.

Ordinary HTTP simulation runs streaming producers on the caller thread, materializes successful output, and waits for the admitted termination observer before returning. It does not apply HTTP streaming total or idle timeouts; `ResponseStream.getDeadline()` and `getIdleTimeout()` are empty. Cleanup supervision, scope shutdown and output limits still apply. A producer failure that wins termination throws `IllegalStateException` with the original cause without returning partial bytes; an application `Error` is rethrown when it wins that outcome. Use a real HTTP fixture to test response deadlines or committed partial delivery. In HTTP, the already committed status remains and producer failure aborts the body. Both runtimes report `PRODUCER_FAILED` with the original cause for that outcome; an earlier elected cancelation still wins.

Exhausted HTTP streaming admission in simulation returns the same built-in finite `503` as HTTP: `Content-Type: text/plain; charset=UTF-8`, `Connection: close`, and body `HTTP 503: Service Unavailable`. The result clears the rejected logical response and retains the resource method. `didWriteResponse` and `didFinishRequestHandling` observers and metrics describe that finite response. `willWriteResponse` sees the original stream before admission, as it does in HTTP. The producer or source factory is never acquired; a bounded asynchronous rejection notification retains the original streaming descriptor and reports `BACKPRESSURE`, without delaying the finite result. An admitted simulator call still waits for its own termination observer.

In built-in HTTP streaming and simulation, owned-resource close/abort and publisher cancel failures are reported as `RESPONSE_STREAM_CLOSE_FAILED` log events with the original exception, request, optional resource method and original streaming response. Framework supervision failures and cleanup-deadline expiry use `SERVER_INTERNAL_ERROR`. Diagnostic delivery is asynchronous and bounded to the first diagnostic claimed for an admitted lifetime; it does not replace a previously elected stream termination. A failed finalizer can also cause a `PRODUCER_FAILED` termination. Blocked log observers retain physical work and admission capacity through shutdown. Applications choose which event fields to log; context and exceptions can contain application data.

Admitted termination notifications and diagnostics use separate executors, each allowing at most one observation per admitted lifetime and at most `streamingLifecycleCapacity` workers. Workers grow with outstanding work, are reused and expire when idle. A blocked observation retains its own slot while other admitted streams can deliver their observations and retire. These observers can run more concurrently than `streamingCallbackConcurrency`, which bounds cancelation batches and unadmitted rejection observers. Blocking that pool can still queue later cancelation batches and retain their slots. If every lifecycle slot contains blocked application work, admission still returns 503. Keep application hooks short; Soklet cannot forcibly stop them.

Redirects (via [`Response`](https://javadoc.soklet.com/com/soklet/Response.html)):

```java
@GET("/example-redirect")
public Response exampleRedirect() {
  // Response has a convenience builder for performing redirects.
  // You could alternatively do this "by hand" by setting HTTP status
  // and headers appropriately.
  return Response.withRedirect(
    RedirectType.HTTP_307_TEMPORARY_REDIRECT, "/other-url"
  ).build();
}
```

#### HTTP Server Configuration

Soklet ships with an embedded HTTP/1.1 [`HttpServer`](https://javadoc.soklet.com/com/soklet/HttpServer.html), a dedicated
[`SseServer`](https://javadoc.soklet.com/com/soklet/SseServer.html), and a dedicated
[`McpServer`](https://javadoc.soklet.com/com/soklet/McpServer.html). Each server owns
its listener and port; MCP is never mounted inside the standard HTTP or SSE
server.
These builders expose transport-specific host, timeout, concurrency, request-size,
and connection controls. The HTTP and SSE builders also accept custom
[`IdGenerator`](https://javadoc.soklet.com/com/soklet/IdGenerator.html) and
[`MultipartParser`](https://javadoc.soklet.com/com/soklet/MultipartParser.html) instances;
the MCP builder does not. MCP uses the default generator for its underlying HTTP
`Request` ID. Its JSON-RPC request ID and privacy-preserving trace-correlation
tokens are separate concepts, not overrides of that HTTP identity.
Standard HTTP request-body decompression is disabled by default; enable [`HttpServer.Builder::requestDecompressionPolicy`](<https://javadoc.soklet.com/com/soklet/HttpServer.Builder.html#requestDecompressionPolicy(com.soklet.RequestDecompressionPolicy)>) with [`RequestDecompressionPolicy::fromDefaults`](<https://javadoc.soklet.com/com/soklet/RequestDecompressionPolicy.html#fromDefaults()>) or a custom policy to accept single-coding `Content-Encoding: gzip`/`x-gzip` request bodies with decompression-bomb limits. Handlers receive the decompressed bytes through [`Request::getBody`](<https://javadoc.soklet.com/com/soklet/Request.html#getBody()>), while [`Request::getEncodedBodySizeInBytes`](<https://javadoc.soklet.com/com/soklet/Request.html#getEncodedBodySizeInBytes()>) retains the pre-decompression payload size for telemetry.
Provide the configured servers via [`SokletConfig`](https://javadoc.soklet.com/com/soklet/SokletConfig.html) and see the
[Server Configuration](https://www.soklet.com/docs/server-configuration) docs for the full option matrix.

#### Server-Sent Events (SSE)

SSE endpoints are declared with [`@SseEventSource`](https://javadoc.soklet.com/com/soklet/annotation/SseEventSource.html) and return a
[`SseHandshakeResult`](https://javadoc.soklet.com/com/soklet/SseHandshakeResult.html), served from a dedicated
[`SseServer`](https://javadoc.soklet.com/com/soklet/SseServer.html) port (separate from your standard HTTP server port).

```java
public record ChatMessage(String message) {}

public class ChatResource {
  @SseEventSource("/chat")
  public SseHandshakeResult chat() {
    return SseHandshakeResult.Accepted.builder()
      .clientInitializer(sseUnicaster -> {
        sseUnicaster.unicastEvent(SseEvent.withEvent("hello")
          .data("welcome")
          .build());
      })
      .build();
  }

  @POST("/chat")
  public void postMessage(@RequestBody ChatMessage message,
                          SseServer sseServer) {
    SseEvent event = SseEvent.withEvent("message")
      .data(message.message())
      .build();
    sseServer.acquireBroadcaster(ResourcePath.fromPath("/chat"))
      .ifPresent(broadcaster -> broadcaster.broadcastEvent(event));
  }
}
```

Client-initializer writes are buffered until the SSE connection becomes active
and are hard-bounded by `SseServer.Builder.connectionQueueCapacity(...)` (128
application writes by default). Size the queue for the largest catch-up page
plus headroom for live broadcasts that may arrive before that page drains, and
paginate larger `Last-Event-ID` replays. Exceeding the bound throws
`IllegalStateException` and terminates the connection with `BACKPRESSURE`, even
if the initializer catches the exception. The optional framework
connection-verification heartbeat does not use an application queue slot.

`clientInitializer(...)` accepts the checked `SseClientInitializer` callback.
Use it only for synchronous, bounded setup or catch-up work; queued events are
delivered after it returns successfully and before broadcaster events. Do not
retain its `SseUnicaster` or use it as an ongoing event callback. Successful
unicast accepts a queued write; it does not acknowledge delivery. Use
[`SseBroadcaster`](https://javadoc.soklet.com/com/soklet/SseBroadcaster.html)
for ongoing delivery to connected clients. Broadcasts published before this
client joins the broadcaster are not buffered for it; initializer ordering
alone does not make `Last-Event-ID` replay gap-free. Applications needing that
guarantee must coordinate the replay-to-live handoff themselves.

`SseServer.Builder.streamingLifecycleCapacity(...)` separately limits admitted
SSE connections to 256 by default. Exhausted admission returns HTTP 503 before
accepted handshake headers or initializer invocation. Passing `null` restores
the default. Simulators derived from a built-in SSE server inherit this setting
and its connection queue capacity.

The lifecycle limit is independent of `concurrentConnectionLimit(...)`, whose
default is 8,192; together the defaults admit at most 256 SSE connections.
Increase lifecycle capacity when the application needs more clients, accounting
for their payloads. The queue bounds event count, not bytes.

Because this example exposes both an SSE event source and a regular `POST /chat`
resource method, it needs both servers:

```java
SokletConfig config = SokletConfig.withHttpServer(
  HttpServer.fromPort(8080)
).sseServer(
  SseServer.fromPort(8081)
).resourceMethodResolver(
  ResourceMethodResolver.fromClasses(Set.of(ChatResource.class))
).build();
```

If your application only exposes SSE event source methods, you can omit the regular
HTTP server and start with [`SokletConfig::withSseServer`](<https://javadoc.soklet.com/com/soklet/SokletConfig.html#withSseServer(com.soklet.SseServer)>) instead.

SSE test via the [`Simulator`](https://javadoc.soklet.com/com/soklet/Simulator.html)
(see [`SseRequestResult`](https://javadoc.soklet.com/com/soklet/SseRequestResult.html)):

```java
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@Test
public void sseTest() {
  List<SseEvent> events = new ArrayList<>();

  SokletSimulator.run(config, simulator -> {
    Request request = Request.fromPath(HttpMethod.GET, "/chat");
    SseRequestResult result = simulator.performSseRequest(request);

    if (result instanceof SseRequestResult.HandshakeAccepted accepted) {
      try (accepted) {
        accepted.registerEventConsumer(events::add);

        simulator.getSseServer().orElseThrow()
          .acquireBroadcaster(ResourcePath.fromPath("/chat"))
          .ifPresent(broadcaster -> broadcaster.broadcastEvent(
            SseEvent.withEvent("message").data("hello").build()));
      }
    } else {
      throw new IllegalStateException("SSE handshake failed: " + result);
    }
  });

  Assertions.assertEquals(List.of("welcome", "hello"), events.stream()
    .map(event -> event.getData().orElse(null))
    .toList());
}
```

The simulated accepted result is `AutoCloseable`. Its unchecked, idempotent
`close()` simulates `CLIENT_DISCONNECTED`; simulator teardown terminates
remaining connections with `SERVER_STOPPING`, including connections with no
registered consumers. The first termination reason wins. Closing rejects later
writes and consumer registration while cleanup remains supervised.

The [streaming documentation fixture](src/test/java/com/soklet/StreamingDocumentationExamplesTests.java)
compiles the HTTP, ZIP, SSE, and settings snippets directly from these docs and
exercises them through the simulator, including ZIP finalization and SSE
initializer delivery.

#### Model Context Protocol (MCP)

For a complete buildable endpoint, compiler configuration, application start,
and localhost/Inspector recipe, use the [MCP quickstart](MCP_QUICKSTART.md).

The MCP examples in this section use Soklet `4.0.0`.

##### Recommended MCP setup

Soklet 4.0.0 supports MCP `2026-07-28`. It uses a
dedicated, stateless [`McpServer`](https://javadoc.soklet.com/com/soklet/McpServer.html).
It also supports explicitly selected `2025-06-18` and
`2025-11-25` compatibility for synchronous tools, ordinary prompts/resources,
argument completion, request-scoped POST progress, and framework static catalog
pagination, plus explicitly enabled 2025 sessions with remembered public client
metadata and active-request cancellation, leased GET opening, and verified DELETE
retirement, session-owned URI grants, and resource/catalog invalidations over GET.
Client support depends on the selected revision and features; see the dated
[client compatibility matrix](release/MCP_CLIENT_COMPATIBILITY.md) for tested
host versions and limitations. Endpoints and
operations name their exact `McpProtocolVersion` values, with no implicit
"latest" default. See [the MCP guide](MCP.md#exact-protocol-revisions).
MCP owns a listener and port separate from Soklet's
ordinary HTTP and SSE servers, can host multiple exact endpoint paths, and
derives each endpoint's advertised capabilities from its registered
operations.

Define endpoints with the compile-time-processed
[`@McpServerEndpoint`](https://javadoc.soklet.com/com/soklet/annotation/McpServerEndpoint.html),
[`@McpTool`](https://javadoc.soklet.com/com/soklet/annotation/McpTool.html),
[`@McpPrompt`](https://javadoc.soklet.com/com/soklet/annotation/McpPrompt.html),
[`@McpResource`](https://javadoc.soklet.com/com/soklet/annotation/McpResource.html), and
[`@McpResourceList`](https://javadoc.soklet.com/com/soklet/annotation/McpResourceList.html) annotations,
or assemble the same immutable model programmatically. The public API covers:

- Java-derived tool input and output schemas, typed or JSON arguments, and
  validated structured results;
- prompts, exact and templated resources, custom resource pagination, and
  protocol cache hints;
- multi-round input requests with application- or framework-protected state;
- durable Tasks for tool calls through an application-owned manager, including
  polling, input, cooperative cancelation, and optional status notifications;
- request-scoped progress, cooperative cancelation, and resource
  subscriptions;
- admission, rate limiting, bounded handler execution, interception, output
  sanitization, and Host/Origin policy; and
- lifecycle and metrics hooks, downstream OpenTelemetry integration, and
  bounded off-network simulation.

A minimal loopback configuration for an annotation-driven, tool-bearing
endpoint looks like this:

```java
McpServer mcpServer = McpServer.withPort(8081)
  .toolRateLimiter(McpRateLimiter.fromInMemoryDefaults())
  .build();

SokletConfig config = SokletConfig.withMcpServer(mcpServer).build();
```

The built-in accept-all admission policy and the in-memory limiter are
convenient development choices, not production authentication or fleet-wide
rate limiting. Every tool-bearing server requires a fallback tool limiter. The
listener binds to `127.0.0.1` by default; configure
[`McpServer.Builder::host`](<https://javadoc.soklet.com/com/soklet/McpServer.Builder.html#host(java.lang.String)>),
[`McpServer.Builder::allowedHosts`](<https://javadoc.soklet.com/com/soklet/McpServer.Builder.html#allowedHosts(java.util.Set)>),
authentication/admission, and TLS termination deliberately
before exposing it remotely.

##### Argument completion

MCP `completion/complete` suggests up to 100 values for a declared prompt
argument or resource-template variable. Configure a completer on a prompt or
URI-template registration to advertise `completions` for that endpoint's
selected revision; exact resources cannot have completers. A resource reference is the literal
registered template (for example, `catalog://products/{sku}`), not an expanded
resource URI. The handler receives the partial value and other supplied
arguments in `McpCompletionContext`. Treat those values as untrusted input and
authorize each suggestion, including any sensitive identifier, before
returning it. Soklet does not match, translate, deduplicate, or cache returned
suggestions.

Completion supports explicitly selected `2025-06-18`, `2025-11-25`, and
`2026-07-28` revisions through the same handlers. Select completer revisions
within the owning prompt/template and endpoint revisions; a modern-only
completer is not exposed to a 2025 request. On a Completion-enabled 2025
endpoint/revision, a visible target and declared argument without an enabled
completer return empty suggestions.
Unknown or hidden targets and undeclared arguments fail with invalid params.
The 2025 implementation defaults to stateless operation. Explicit session
selection requires both endpoint revisions and server ownership/bounds; see
[2025 sessions](MCP.md#explicitly-enabled-2025-sessions). Named legacy host checks
and exact-candidate qualification remain pending.

For a programmatic registration, attach the callback to the prompt or template
builder and install a server-wide request limiter:

```java
McpPromptRegistration prompt = McpPromptRegistration.withName("code_review",
        Set.of(McpProtocolVersion.V2026_07_28))
  .handler(promptHandler)
  .arguments(List.of(McpPromptArgumentDeclaration.withName("language").build()))
  .completionHandler((requestContext, completionContext, invocationFeatures) ->
    McpArgumentCompletionResult.fromValues(List.of("java")),
    Set.of(McpProtocolVersion.V2026_07_28))
  .build();

McpServer mcpServer = McpServer.withPort(8081)
  .endpointRegistry(registryContainingPrompt)
  .requestRateLimiter(requestLimiter)
  .build();
```

`promptHandler`, `registryContainingPrompt`, and `requestLimiter` above are
application-provided. For an annotated endpoint, declare the companion method
beside its `@McpPrompt(name = "code_review", ...)` method and configure the
same request-wide limiter on the server:

```java
@McpPromptCompletion(name = "code_review",
    protocolVersions = {McpProtocolVersion.V2026_07_28})
@NonNull
public McpArgumentCompletionResult completeCodeReviewArgument(
    @NonNull McpRequestContext requestContext,
    McpCompletionContext.@NonNull Prompt completionContextPrompt,
    @NonNull McpInvocationFeatures invocationFeatures) {
  return McpArgumentCompletionResult.fromValues(List.of("java"));
}

McpServer mcpServer = McpServer.withPort(8081)
  .requestRateLimiter(requestLimiter)
  .build();
```

Use `@McpResourceCompletion(uri = "catalog://products/{sku}",
protocolVersions = {McpProtocolVersion.V2026_07_28})` and
`McpCompletionContext.Resource` for a template method. Each annotated target
must be registered in the same endpoint. Server construction fails when any
completer is configured without `requestRateLimiter`. This limiter is charged
once through the normal request stage for *all* admitted MCP methods—not only
Completion—and is not the tool limiter. The application must choose a
meaningful caller/tenant budget and coordinate it across nodes where needed;
`McpRateLimitContext.getOperationType()` can select a tighter Completion
budget. A callback alone does not establish an effective policy.

##### Operation classification

Application policy and handler interceptors can branch on the semantic
[`McpOperationType`](https://javadoc.soklet.com/com/soklet/McpOperationType.html)
instead of comparing JSON-RPC method strings:

```java
if (context.getOperationType() == McpOperationType.TOOLS_CALL) {
  // Apply tool-call-specific policy.
}
```

[`McpRequestContext::getOperationType`](<https://javadoc.soklet.com/com/soklet/McpRequestContext.html#getOperationType()>),
[`McpAdmissionContext::getOperationType`](<https://javadoc.soklet.com/com/soklet/McpAdmissionContext.html#getOperationType()>),
and
[`McpRateLimitContext::getOperationType`](<https://javadoc.soklet.com/com/soklet/McpRateLimitContext.html#getOperationType()>)
provide the same classification. `OTHER` covers an unrecognized, future, or
extension method, while
[`McpRequestContext::getJsonRpcMethod`](<https://javadoc.soklet.com/com/soklet/McpRequestContext.html#getJsonRpcMethod()>)
retains the exact validated wire value for diagnostics and extension-aware
code. Keep a default branch when switching over operation types because Soklet
may recognize additional operations in later MCP profiles.

Task methods use
[`McpOperationType.TASKS_GET`](https://javadoc.soklet.com/com/soklet/McpOperationType.html#TASKS_GET),
[`TASKS_UPDATE`](https://javadoc.soklet.com/com/soklet/McpOperationType.html#TASKS_UPDATE),
and
[`TASKS_CANCEL`](https://javadoc.soklet.com/com/soklet/McpOperationType.html#TASKS_CANCEL);
their operation name is the validated task ID.

After admission, static `tools/list` and `prompts/list` catalogs are immutable
and caller-neutral; Soklet does not authorization-filter their descriptors. A
registered tool remains listed when it declares a required client capability,
but the matching call can receive `-32021` after successful admission and before execution when that capability
is absent. These list responses retain private, zero-TTL protocol cache hints
and HTTP `Cache-Control: no-store`; this list/call distinction is not an
authorization boundary or a promise of ETag-based dynamic catalogs.

Framework-owned catalog text - server, tool, prompt, resource, and schema
titles and descriptions - can be localized per request through a
library-neutral seam that keeps Soklet free of any translation dependency.
[`McpLocalizationContext`](https://javadoc.soklet.com/com/soklet/McpLocalizationContext.html)
is a Soklet-owned final value built with
[`McpLocalizationContext::withLocale`](<https://javadoc.soklet.com/com/soklet/McpLocalizationContext.html#withLocale(java.util.Locale,com.soklet.McpLocalizationLookup)>);
applications provide the named, thread-safe
[`McpLocalizationLookup`](https://javadoc.soklet.com/com/soklet/McpLocalizationLookup.html)
callback instead of a custom context
implementation.
The other public MCP value carriers follow the same style: final immutable
classes, named factories or builders, private constructors, and conventional
`get...` accessors. Sealed decision, localization, subscription, and metric
families keep public nested variants for typed pattern matching, but creation
is owned by factories on the sealed root.
Omitting a localizer leaves wire output byte-identical. When a localizer is
configured, Soklet clamps every emitted MCP cache hint to private scope with a
zero TTL. MCP cache hints have no locale key comparable to HTTP `Vary`, so a
client otherwise could treat, for example, English and French representations
as the same cache entry. This is a final safety rule, not merely a default:
positive per-result TTL overrides on
[`McpResourceOutput`](https://javadoc.soklet.com/com/soklet/McpResourceOutput.html)
and
[`McpResourcePage`](https://javadoc.soklet.com/com/soklet/McpResourcePage.html)
cannot weaken it. See
[MCP localization](https://www.soklet.com/docs/mcp-localization) for the full
MCP and HTTP behavior.

Every selected application handler receives one cooperative
[`CancelationToken`](https://javadoc.soklet.com/com/soklet/CancelationToken.html).
Programmatic MCP handlers obtain it from
[`McpInvocationFeatures::getCancelationToken`](<https://javadoc.soklet.com/com/soklet/McpInvocationFeatures.html#getCancelationToken()>)
and obtain request-scoped progress, when available, from
[`McpInvocationFeatures::getProgressReporter`](<https://javadoc.soklet.com/com/soklet/McpInvocationFeatures.html#getProgressReporter()>).
The generic
[`McpInvocationFeatures::find`](<https://javadoc.soklet.com/com/soklet/McpInvocationFeatures.html#find(java.lang.Class)>)
and
[`McpInvocationFeatures::require`](<https://javadoc.soklet.com/com/soklet/McpInvocationFeatures.html#require(java.lang.Class)>)
methods remain available for extension feature types.
Framework cancellation exposes only a fixed
[`StreamTerminationReason`](https://javadoc.soklet.com/com/soklet/StreamTerminationReason.html);
its
underlying cause is empty, including through
[`StreamingResponseCanceledException`](https://javadoc.soklet.com/com/soklet/StreamingResponseCanceledException.html).
On HTTP, an incoming
`notifications/cancelled` message is accepted and ignored for compatibility;
deadline, forced shutdown after the graceful-drain budget, and
response-stream failure are signals that cancel work, subject to the legacy
writer-detachment rules below. Graceful shutdown
fences new MCP work while preserving already-admitted finite unary and
request-scoped progress responses; indefinite subscriptions complete promptly.
On either selected 2025 revision, a valid progress token enables the same
reporter: the first update commits POST SSE, while no update returns JSON.
A committed legacy SSE disconnect or lost-writer write failure detaches delivery,
wakes blocked reporters, and discards later output without itself canceling the handler. The deadline
and physical worker reservation remain; finite/uncommitted or queued legacy
requests and modern requests retain disconnect cancellation. Legacy streams
have no event IDs, priming event, polling, replay, or lost-result recovery.
Named-host and exact-candidate progress qualification remain pending. See
[Progress and cooperative cancelation](MCP.md#progress-and-cooperative-cancelation).
Soklet validates the open `inputResponses` wire union, but
applications still own response-key correlation, action handling, accepted
content policy, user binding, and side-effect authorization. See
the compile-checked
[application input-security patterns](src/test/java/examples/mcp/McpInputSecurityApplicationPatternsTests.java),
[durable-handle and prompt-security patterns](src/test/java/examples/mcp/McpDurableHandlePromptApplicationPatternsTests.java),
and [resource and cursor-security patterns](src/test/java/examples/mcp/McpResourceCursorApplicationPatternsTests.java).
The separate [localized cursor fleet pattern](src/test/java/examples/mcp/McpLocalizedCursorFleetApplicationPatternsTests.java)
shows one application-owned way to move the exact opaque cursor across two
independently configured nodes while retaining snapshot, locale, catalog and
localization revisions, expiry, page position, and authorization binding.
These examples demonstrate application-owned repositories, semantic
authorization, canonical containment, delivery-intent URI policy, and stable
cursor snapshots; Soklet does not supply the replicated repository, key
distribution, or other deployment policies. See also
[deployment guidance](SECURITY.md#mcp-deployment-security).

##### Durable Tasks

Soklet implements the MCP Tasks extension for `tools/call`. Configure one
application-wide
[`McpTaskManager`](https://javadoc.soklet.com/com/soklet/McpTaskManager.html)
through
[`McpServer.Builder::taskManager`](<https://javadoc.soklet.com/com/soklet/McpServer.Builder.html#taskManager(com.soklet.McpTaskManager)>).
Also select `V2026_07_28` in that endpoint's `taskProtocolVersions`;
the manager alone does not advertise Tasks:

```java
// The endpoint uses taskProtocolVersions =
// {McpProtocolVersion.V2026_07_28} in @McpServerEndpoint,
// or .taskProtocolVersions(Set.of(McpProtocolVersion.V2026_07_28))
// on its programmatic builder.
McpServer mcpServer = McpServer.withPort(8081)
    .taskManager(taskManager)
    .toolRateLimiter(toolRateLimiter)
    .build();
```

An annotated tool that always creates a task returns
[`McpTaskCreatedResult<R>`](https://javadoc.soklet.com/com/soklet/McpTaskCreatedResult.html)
and can accept one unannotated
[`McpTaskCreationContext`](https://javadoc.soklet.com/com/soklet/McpTaskCreationContext.html):

```java
@McpTool(name = "reports.generate",
    protocolVersions = {McpProtocolVersion.V2026_07_28})
public McpTaskCreatedResult<GeneratedReport> generateReport(
    McpTaskCreationContext taskCreationContext) {
  String ownerKey = deriveTaskOwnerKey(taskCreationContext.getRequestContext());
  String taskId = reportJobs.persistAndPublish(
      ownerKey, taskCreationContext.getTaskOrigin());
  return McpTaskCreatedResult.fromTaskId(taskId);
}
```

`reportJobs` represents application infrastructure. It must synchronously
derive a stable authorization binding from the request, then atomically persist
its work description and the complete opaque
[`McpTaskOrigin`](https://javadoc.soklet.com/com/soklet/McpTaskOrigin.html)
before publishing recoverable work. Do not retain the request context or its
request-scoped cancelation token as work. Before returning the handle, Soklet
requires the configured manager to resolve the task and matching origin. A
later completed result receives the current server's sanitizer and size-limit
checks plus the persisted original typed-output schema.

`tasks/get` polling is authoritative. `tasks/update` supplies partial,
idempotently consumed input responses, and `tasks/cancel` records cooperative
durable cancelation intent without promising that it wins a race with
completion. An optional
[`McpTaskEventPublisher`](https://javadoc.soklet.com/com/soklet/McpTaskEventPublisher.html)
lets `subscriptions/listen` project fresh authorized snapshots as
`notifications/tasks`; delayed, duplicate, or lost events never replace
polling. Graceful shutdown stops new requests but does not cancel application
tasks.

For development and tests,
[`McpTaskManager::fromInMemoryDefaults`](<https://javadoc.soklet.com/com/soklet/McpTaskManager.html#fromInMemoryDefaults()>)
creates a bounded
[`McpInMemoryTaskManager`](https://javadoc.soklet.com/com/soklet/McpInMemoryTaskManager.html).
It has no worker, durable storage, replication, outbox, leases, fencing,
failover, or crash recovery; its state disappears at JVM shutdown. Production
systems provide their own manager and worker infrastructure shared by every
eligible node and should assume at-least-once execution. See
[Durable Tasks in the complete MCP guide](MCP.md#durable-tasks) for the full
manager, authorization, wire-routing, notification, reconnect, simulator, and
distributed-operation contract.

##### Protocol scope and unsupported features

An endpoint and operation explicitly select exact MCP revisions.
Soklet neither selects an automatic
"latest" profile nor falls back to another revision.
Soklet does not implement
MCP Roots, Sampling, or Logging. Pass file or directory information through
explicit tool parameters, resource URIs, or server configuration, and integrate
directly with a model provider when needed. Use application logging and
Soklet's existing observability and OpenTelemetry integrations.
Dynamic Client Registration and
deprecated standalone legacy HTTP+SSE transport are reviewed N/A; current SSE response
streaming is not that legacy transport.

##### Trace correlation

Trace correlation remains default-off. Configuring a trace-correlation key
enables an exactly-once finish-time
[`LogEventType.MCP_TRACE_CORRELATION`](https://javadoc.soklet.com/com/soklet/LogEventType.html#MCP_TRACE_CORRELATION)
log event carrying
the bounded pseudonymous token fields; the separate
[`McpServer.Builder::logRawValidatedTraceIds`](<https://javadoc.soklet.com/com/soklet/McpServer.Builder.html#logRawValidatedTraceIds(java.lang.Boolean)>)
opt-in may add only the validated lowercase MCP
trace ID. Neither mode adds a trace value to metrics.

See the [complete MCP guide](https://www.soklet.com/docs/mcp) for endpoint
authoring, configuration, protocol behavior, security, observability, testing,
and a map of the public API.

#### Form Handling

Frontend:

```html
<form
  enctype="application/x-www-form-urlencoded"
  action="https://example.soklet.com/form?id=123"
  method="POST"
>
  <!-- User can type whatever text they like -->
  <input type="number" name="numericValue" />
  <!-- Multiple values for the same name are supported -->
  <input type="hidden" name="multi" value="1" />
  <input type="hidden" name="multi" value="2" />
  <!-- Names with special characters can be remapped -->
  <textarea name="long-text"></textarea>
  <!-- Note: browsers send "on" string to indicate "checked" -->
  <input type="checkbox" name="enabled" />
  <input type="submit" />
</form>
```

Backend:

Backend parameters can use [`@QueryParameter`](https://javadoc.soklet.com/com/soklet/annotation/QueryParameter.html) and
[`@FormParameter`](https://javadoc.soklet.com/com/soklet/annotation/FormParameter.html).

```java
@POST("/form")
public String form(
  @QueryParameter Long id,
  @FormParameter Integer numericValue,
  @FormParameter(optional=true) List<String> multi,
  @FormParameter(name="long-text") String longText,
  @FormParameter String enabled
) {
  // Echo back the inputs
  return List.of(id, numericValue, multi, longText, enabled).stream()
    .map(Object::toString)
    .collect(Collectors.joining("\n"));
}
```

Test:

```shell
% curl -i -X POST 'https://example.soklet.com/form?id=123' \
   -H 'Content-Type: application/x-www-form-urlencoded' \
   -d 'numericValue=456&multi=1&multi=2&long-text=long%20multiline%20text&enabled=on'
HTTP/1.1 200 OK
Content-Length: 37
Content-Type: text/plain; charset=UTF-8
Date: Sun, 21 Mar 2024 16:19:01 GMT

123
456
[1, 2]
long multiline text
on
```

#### Multipart Handling

Frontend:

```html
<form
  enctype="multipart/form-data"
  action="https://example.soklet.com/multipart?id=123"
  method="POST"
>
  <!-- User can type whatever text they like -->
  <input type="text" name="freeform" />
  <!-- Multiple values for the same name are supported -->
  <input type="hidden" name="multi" value="1" />
  <input type="hidden" name="multi" value="2" />
  <!-- Prompt user to upload a file -->
  <p>Please attach your document: <input name="doc" type="file" /></p>
  <!-- Multiple file uploads are supported -->
  <p>
    Supplement 1: <input name="extra" type="file" /> Supplement 2:
    <input name="extra" type="file" />
  </p>
  <!-- An optional file -->
  <p>Optionally, attach a photo: <input name="photo" type="file" /></p>
  <input type="submit" value="Upload" />
</form>
```

Backend:

Backend parameters can use [`@Multipart`](https://javadoc.soklet.com/com/soklet/annotation/Multipart.html) and
[`MultipartField`](https://javadoc.soklet.com/com/soklet/MultipartField.html).

```java
@POST("/multipart")
public Response multipart(
  @QueryParameter Long id,
  // Multipart fields work like other Soklet params
  // with support for Optional<T>, List<T>, custom names, ...
  @Multipart(optional=true) String freeform,
  @Multipart(name="multi") List<Integer> numbers,
  // The MultipartField type allows access to additional data,
  // like filename and content type (if available).
  // The @Multipart annotation is optional
  // when your parameter is of type MultipartField...
  @Multipart(name="doc") MultipartField document,
  // ...but is useful if you need to massage the name.
  @Multipart(name="extra") List<MultipartField> supplements,
  // If you specify type byte[] for a @Multipart field,
  // you'll get just its binary data injected
  @Multipart(optional=true) byte[] photo
) {
  // Let's demonstrate the functionality MultipartField provides.

  // Form field name, always available, e.g. "doc"
  String name = document.getName();
  // Browser may provide this for files, e.g. "test.pdf"
  Optional<String> filename = document.getFilename();
  // Browser may provide this for files, e.g. "application/pdf"
  Optional<String> contentType = document.getContentType();
  // Field data as bytes, if available
  Optional<byte[]> data = document.getData();
  // Field data as a string, if available
  Optional<String> dataAsString = document.getDataAsString();

  // Apply the standard redirect-after-POST pattern
  return Response.withRedirect(
    RedirectType.HTTP_307_TEMPORARY_REDIRECT, "/thanks"
  ).build();
}
```

#### Dependency Injection

In practice, you will likely want to tie in to whatever Dependency Injection library your application uses and have the DI infrastructure vend your instances.

Soklet integrates via an [`InstanceProvider`](https://javadoc.soklet.com/com/soklet/InstanceProvider.html).
The single provider configured on
[`SokletConfig`](https://javadoc.soklet.com/com/soklet/SokletConfig.html)
creates annotation-backed
HTTP/SSE resources, generated MCP endpoint classes, and injectable application
parameter values. Soklet may call it concurrently, so custom providers must
support concurrent invocation.

Here's how it might look if you use [Google Guice](https://github.com/google/guice):

```java
// Standard Guice setup
Injector injector = Guice.createInjector(new MyExampleAppModule());

SokletConfig config = SokletConfig.withHttpServer(
  HttpServer.fromPort(8080)
).instanceProvider(new InstanceProvider() {
  @NonNull
  @Override
  public <T> T provide(@NonNull Class<T> instanceClass) {
    // Have Soklet ask the Guice Injector for the instance
    return injector.getInstance(instanceClass);
  }
}).build();
```

Now, your Resources are dependency-injected just like the rest of your application is:

```java
public class WidgetResource {
  private WidgetService widgetService;

  @Inject
  public WidgetResource(WidgetService widgetService) {
    this.widgetService = widgetService;
  }

  @GET("/widgets")
  public List<Widget> widgets() {
    return widgetService.findWidgets();
  }
}
```

#### Lifecycle Handling and Interception

Implement [`LifecycleObserver`](https://javadoc.soklet.com/com/soklet/LifecycleObserver.html) and
[`RequestInterceptor`](https://javadoc.soklet.com/com/soklet/RequestInterceptor.html) to hook into server and request lifecycles.
Use [`SokletConfig.Builder::lifecycleObservers`](<https://javadoc.soklet.com/com/soklet/SokletConfig.Builder.html#lifecycleObservers(java.util.Collection)>) when you want multiple observers, for example an audit observer plus an OpenTelemetry tracing observer.

HTTP Server Start/Stop: observe [`HttpServer`](https://javadoc.soklet.com/com/soklet/HttpServer.html)
startup and shutdown. Transition callbacks are asynchronous, best-effort
observations: they cannot veto, delay, or change lifecycle. Do not put required
readiness work or required resource cleanup in them; keep that work in
application startup/ownership and the standalone runner's cleanup hook.

```java
SokletConfig config = SokletConfig.withHttpServer(
  HttpServer.fromPort(8080)
).lifecycleObserver(new LifecycleObserver() {
  @Override
  public void didStartHttpServer(@NonNull HttpServer httpServer) {
    // Observe that the HTTP server has started and is listening
    System.out.println("HTTP server started.");
  }

  @Override
  public void didStopHttpServer(@NonNull HttpServer httpServer,
      @NonNull ShutdownComponentResult result) {
    // Observe the HTTP server's immutable terminal evidence
    System.out.println("HTTP server stopped: " + result.getShutdownComponentDisposition());
  }
}).build();
```

Request Handling: these methods are fired at the very start of [`Request`](https://javadoc.soklet.com/com/soklet/Request.html) processing and the very end, respectively.

```java
SokletConfig config = SokletConfig.withHttpServer(
  HttpServer.fromPort(8080)
).lifecycleObserver(new LifecycleObserver() {
  @Override
  public void didStartRequestHandling(
    @NonNull ServerType serverType,
    @NonNull Request request,
    @Nullable ResourceMethod resourceMethod
  ) {
    System.out.printf("Received request: %s\n", request);

    // If there was no resourceMethod matching the request, expect a 404
    if(resourceMethod != null)
      System.out.printf("Request to be handled by: %s\n", resourceMethod);
    else
      System.out.println("This will be a 404.");
  }

  @Override
  public void didFinishRequestHandling(
    @NonNull ServerType serverType,
    @NonNull Request request,
    @Nullable ResourceMethod resourceMethod,
    @NonNull MarshaledResponse marshaledResponse,
    @NonNull Duration processingDuration,
    @NonNull List<Throwable> throwables
  ) {
    // We have access to a few things here...
    // * marshaledResponse is what was ultimately sent
    //    over the wire
    // * processingDuration is how long everything took,
    //    including sending the response to the client
    // * throwables is the ordered list of exceptions
    //    thrown during execution (if any)
    long millis = processingDuration.toMillis();
    System.out.printf("Entire request took %dms\n", millis);
  }
}).build();
```

Request Wrapping: wraps around the whole "outside" of an entire [`Request`](https://javadoc.soklet.com/com/soklet/Request.html) handling flow.

Request wrapping runs before Soklet resolves which [`ResourceMethod`](https://javadoc.soklet.com/com/soklet/ResourceMethod.html) should handle the request. If you want to rewrite the HTTP method or path, return a modified [`Request`](https://javadoc.soklet.com/com/soklet/Request.html) via the consumer and Soklet will route using the wrapped request. You must call `requestProcessor.accept(...)` exactly once before returning; otherwise Soklet logs an error and returns a 500 response.

```java
// Java 17-compatible request-local storage.
public static final ThreadLocal<Locale> CURRENT_LOCALE = new ThreadLocal<>();

SokletConfig config = SokletConfig.withHttpServer(
  HttpServer.fromPort(8080)
).requestInterceptor(new RequestInterceptor() {
  @Override
  public void wrapRequest(
    @NonNull ServerType serverType,
    @NonNull Request request,
    @NonNull Consumer<Request> requestProcessor
  ) {
    // Make the locale accessible by other code during this request...
    Locale locale = request.getLocales().stream()
      .findFirst()
      .orElse(Locale.getDefault());

    // Bind it for downstream code and always clear the pooled worker thread.
    CURRENT_LOCALE.set(locale);
    try {
      // You must call this so downstream processing can proceed
      requestProcessor.accept(request);
    } finally {
      CURRENT_LOCALE.remove();
    }
  }
}).build();

// Then, elsewhere in your code while a request is being processed:

class ExampleService {
  void accessCurrentLocale() {
    // You now have access to the Locale bound to this request thread.
    Locale locale = Optional.ofNullable(CURRENT_LOCALE.get())
      .orElse(Locale.getDefault());
  }
}
```

Request Intercepting (via [`RequestInterceptor`](https://javadoc.soklet.com/com/soklet/RequestInterceptor.html)): provides programmatic control over two processing steps.

1. Invoking the appropriate [`ResourceMethod`](https://javadoc.soklet.com/com/soklet/ResourceMethod.html) to acquire a [`MarshaledResponse`](https://javadoc.soklet.com/com/soklet/MarshaledResponse.html)
2. Sending the [`MarshaledResponse`](https://javadoc.soklet.com/com/soklet/MarshaledResponse.html) over the wire to the client

You must call `responseWriter.accept(...)` exactly once before returning; otherwise Soklet logs an error and returns a 500 response.

```java
SokletConfig config = SokletConfig.withHttpServer(
  HttpServer.fromPort(8080)
).requestInterceptor(new RequestInterceptor() {
  @Override
  public void interceptRequest(
    @NonNull ServerType serverType,
    @NonNull Request request,
    @Nullable ResourceMethod resourceMethod,
    @NonNull Function<Request, MarshaledResponse> responseGenerator,
    @NonNull Consumer<MarshaledResponse> responseWriter
  ) {
    // Here's where you might start a DB transaction.
    // (MyDatabase is a hypothetical construct)
    MyDatabase.INSTANCE.beginTransaction();

    // Step 1: Invoke the Resource Method and acquire its response
    MarshaledResponse response = responseGenerator.apply(request);

    // Commit the DB transaction before sending the response
    // to reduce contention by keeping "open" time short
    MyDatabase.INSTANCE.commitTransaction();

    // Set a special header on the response via mutable copy
    response = response.copy().headers((mutableHeaders) -> {
      mutableHeaders.put("X-Powered-By", List.of("Soklet"));
    }).finish();

    // Step 2: Send the finalized response over the wire
    responseWriter.accept(response);
  }
}).build();
```

Response Writing: monitor the response writing process for each [`MarshaledResponse`](https://javadoc.soklet.com/com/soklet/MarshaledResponse.html) - sending bytes over the wire - which may terminate exceptionally (e.g. unexpected client disconnect).

```java
SokletConfig config = SokletConfig.withHttpServer(
  HttpServer.fromPort(8080)
).lifecycleObserver(new LifecycleObserver() {
  @Override
  public void willWriteResponse(
    @NonNull ServerType serverType,
    @NonNull Request request,
    @Nullable ResourceMethod resourceMethod,
    @NonNull MarshaledResponse marshaledResponse
  ) {
    // Access to marshaledResponse here lets us see exactly
    // what will be going over the wire
    Long bodyLength = marshaledResponse.getBodyLength();
    System.out.printf("About to start writing response with " +
      "a %d-byte body...\n", bodyLength);
  }

  @Override
  public void didWriteResponse(
    @NonNull ServerType serverType,
    @NonNull Request request,
    @Nullable ResourceMethod resourceMethod,
    @NonNull MarshaledResponse marshaledResponse,
    @NonNull Duration responseWriteDuration
  ) {
    long millis = responseWriteDuration.toMillis();
    System.out.printf("Took %dms to write response\n", millis);
  }

  @Override
  public void didFailToWriteResponse(
    @NonNull ServerType serverType,
    @NonNull Request request,
    @Nullable ResourceMethod resourceMethod,
    @NonNull MarshaledResponse marshaledResponse,
    @NonNull Duration responseWriteDuration,
    @NonNull Throwable throwable
  ) {
    System.err.printf("Response write failed after %dms\n",
      responseWriteDuration.toMillis());
    throwable.printStackTrace();
  }
}).build();
```

#### CORS Support

CORS is handled by [`CorsAuthorizer`](https://javadoc.soklet.com/com/soklet/CorsAuthorizer.html) using
[`Cors`](https://javadoc.soklet.com/com/soklet/Cors.html) metadata and returns
[`CorsPreflightResponse`](https://javadoc.soklet.com/com/soklet/CorsPreflightResponse.html) /
[`CorsResponse`](https://javadoc.soklet.com/com/soklet/CorsResponse.html) as needed.

Authorize All Origins:

```java
SokletConfig config = SokletConfig.withHttpServer(server)
  // "Wildcard" (*) CORS authorization. Don't use this in production!
  .corsAuthorizer(CorsAuthorizer.acceptAllInstance())
  .build();
```

Authorize Whitelisted Origins:

```java
Set<String> allowedOrigins = Set.of("https://www.revetware.com");

SokletConfig config = SokletConfig.withHttpServer(server)
  .corsAuthorizer(CorsAuthorizer.fromWhitelistedOrigins(allowedOrigins))
  .build();
```

...or be dynamic:

```java
SokletConfig config = SokletConfig.withHttpServer(server)
  .corsAuthorizer(CorsAuthorizer.fromWhitelistAuthorizer(
    (origin) -> origin.equals("https://www.revetware.com")
  ))
  .build();
```

Custom CORS logic:

```java
SokletConfig config = SokletConfig.withHttpServer(server)
  .corsAuthorizer(new CorsAuthorizer() {
    // Any subdomain under soklet.com is permitted
    boolean originMatchesValidSubdomain(@NonNull String origin) {
      return origin.matches("https://(.+)\\.soklet\\.com");
    }

    @NonNull
    @Override
    public Optional<CorsPreflightResponse> authorizePreflight(
      @NonNull Request request,
      @NonNull CorsPreflight corsPreflight,
      @NonNull Map<HttpMethod, ResourceMethod> availableResourceMethodsByHttpMethod
    ) {
      // Only greenlight our soklet.com subdomains
      if (originMatchesValidSubdomain(corsPreflight.getOrigin()))
        return Optional.of(
          CorsPreflightResponse.withAccessControlAllowOrigin(
              corsPreflight.getOrigin())
            .accessControlAllowMethods(availableResourceMethodsByHttpMethod.keySet())
            .accessControlAllowHeaders(Set.of("*"))
            .accessControlAllowCredentials(true)
            .accessControlMaxAge(Duration.ofMinutes(10))
            .build()
        );

      return Optional.empty();
    }

    @NonNull
    @Override
    public Optional<CorsResponse> authorize(
      @NonNull Request request,
      @NonNull Cors cors
    ) {
      // Only greenlight our soklet.com subdomains
      if (originMatchesValidSubdomain(cors.getOrigin()))
        return Optional.of(
          CorsResponse.withAccessControlAllowOrigin(cors.getOrigin())
            .accessControlExposeHeaders(Set.of("*"))
            .build()
        );

      return Optional.empty();
    }
  })
  .build();
```

#### Unit Testing

First, define something to test:

```java
public class ReverseResource {
  // Reverse the input
  @POST("/reverse")
  public List<Integer> reverse(@RequestBody List<Integer> numbers) {
    List<Integer> reversed = new ArrayList<>(numbers);
    Collections.reverse(reversed);
    return reversed;
  }

  // Reverse the input and set custom headers/cookies
  @POST("/reverse-again")
  public Response reverseAgain(@RequestBody List<Integer> numbers) {
    Integer largest = Collections.max(numbers);
    Instant lastRequest = Instant.now();

    return Response.withStatusCode(200)
      .headers(Map.of("X-Largest", List.of(String.valueOf(largest))))
      .cookies(List.of(
        ResponseCookie.with("lastRequest", lastRequest.toString()).build()
      ))
      .body(reverse(numbers))
      .build();
  }
}
```

Perform tests:

```java
import org.junit.Assert;
import org.junit.Test;

@Test
public void reverseUnitTest() {
  // Your Resource is a Plain Old Java Object, no Soklet dependency
  ReverseResource resource = new ReverseResource();

  List<Integer> input = List.of(1, 2, 3);
  List<Integer> expected = List.of(3, 2, 1);
  List<Integer> actual = resource.reverse(input);

  Assert.assertEquals("Reverse failed", expected, actual);
}

@Test
public void reverseAgainUnitTest() {
  ReverseResource resource = new ReverseResource();
  List<Integer> input = List.of(1, 2, 3);

  // Set expectations
  List<Integer> expectedBody = List.of(3, 2, 1);
  Integer expectedCode = 200;
  Integer expectedLargest = Collections.max(input);
  Instant lastRequestAfter = Instant.now();

  Response response = resource.reverseAgain(input);

  // Extract actuals
  Integer actualCode = response.getStatusCode();
  List<Integer> actualBody = (List<Integer>) response.getBody().orElseThrow();

  Integer actualLargest = response.getHeaders().get("X-Largest").stream()
    .findAny()
    .map(value -> Integer.valueOf(value))
    .orElseThrow();

  Instant actualLastRequest = response.getCookies().stream()
    .filter(responseCookie -> responseCookie.getName().equals("lastRequest"))
    .findAny()
    .map(responseCookie -> Instant.parse(responseCookie.getValue().orElseThrow()))
    .orElseThrow();

  // Verify expectations vs. actuals
  Assert.assertEquals("Bad status code", expectedCode, actualCode);
  Assert.assertEquals("Reverse failed", expectedBody, actualBody);
  Assert.assertEquals("Largest header failed", expectedLargest, actualLargest);
  Assert.assertTrue("Last request too early", actualLastRequest.isAfter(lastRequestAfter));
}
```

#### Integration Testing

First, define something to test:

```java
public class HelloResource {
  // Hypothetical service that performs business logic
  private HelloService helloService;

  public HelloResource(HelloService helloService) {
    this.helloService = helloService;
  }

  // Respond with a 'hello' message, e.g. Hello, Mark
  @GET("/hello")
  public String hello(@QueryParameter String name) {
    return this.helloService.sayHelloTo(name);
  }
}
```

Perform tests:

[`SokletSimulator`](https://javadoc.soklet.com/com/soklet/SokletSimulator.html)
creates one fresh, transport-isolated off-network graph and supplies a
[`Simulator`](https://javadoc.soklet.com/com/soklet/Simulator.html) to exercise
full request/response flows without binding a port. Its
[`Simulator::startMcpRequest`](<https://javadoc.soklet.com/com/soklet/Simulator.html#startMcpRequest(com.soklet.Request)>)
methods run asynchronous MCP HTTP requests (POST, session-enabled 2025
GET/DELETE, and OPTIONS preflight) through the real
processor and lifecycle while retaining bounded JSON or exact SSE capture
off-network; they do not start a configured network listener or change public
server diagnostics.

The usual entry point is
[`SokletSimulator::run(SokletConfig, Simulation)`](<https://javadoc.soklet.com/com/soklet/SokletSimulator.html#run(com.soklet.SokletConfig,com.soklet.SokletSimulator.Simulation)>).
It derives fresh simulated counterparts for the HTTP, SSE, and MCP transports
present in the application configuration. The simulator call never starts,
claims, or changes the source transport objects. Explicitly configured
application collaborators such as resolvers, interceptors, and metrics
collectors are reused by identity; configuration-dependent defaults are derived
again for the simulated graph. Tests remain responsible for isolating any
mutable state held by those application-owned collaborators.

This is transport isolation, not a deep copy of the application object graph.
Soklet does not inspect or rebind an
[`InstanceProvider`](https://javadoc.soklet.com/com/soklet/InstanceProvider.html),
service, or other collaborator that captured a source transport; that object
continues to reference the source transport. Supply a test-scoped collaborator
when necessary. Derivation also preserves every transport type present in the
source configuration. Use the standalone
[`SimulatorConfig::builder`](<https://javadoc.soklet.com/com/soklet/SimulatorConfig.html#builder()>)
form when a test must omit one.

Use
[`SimulatorConfig::fromSokletConfig`](<https://javadoc.soklet.com/com/soklet/SimulatorConfig.html#fromSokletConfig(com.soklet.SokletConfig)>)
when a completed single-use simulator configuration is useful, or
[`SimulatorConfig::withSokletConfig`](<https://javadoc.soklet.com/com/soklet/SimulatorConfig.html#withSokletConfig(com.soklet.SokletConfig)>)
when a test needs overrides before building. An imported MCP server is rebuilt
from its construction settings with fresh framework-owned listener and runtime
state. Application-supplied MCP collaborators, including rate limiters, are
reused by identity; replace them through
[`SimulatorConfig.Builder::configureMcpServer`](<https://javadoc.soklet.com/com/soklet/SimulatorConfig.Builder.html#configureMcpServer(java.util.function.Consumer)>)
when a test needs isolated collaborator state. For a standalone test without
an application configuration, that method supplies a fresh MCP builder whose
logical port defaults to `0`. Override the port and add any test-specific
settings in that callback when needed. The builder uses the same
classpath-discovery and accept-all defaults as
[`McpServer::withPort`](<https://javadoc.soklet.com/com/soklet/McpServer.html#withPort(java.lang.Integer)>),
including its requirement to configure a fallback tool rate limiter when any
discovered endpoint has a tool. Supply an explicit endpoint registry or
admission controller through the MCP builder when a test must override either
default:

```java
SimulatorConfig simulatorConfig = SimulatorConfig.builder()
    .configureMcpServer(mcpServerBuilder -> mcpServerBuilder
        .port(8082)
        .endpointRegistry(testEndpointRegistry)
        .admissionController(testAdmissionController)
        .toolRateLimiter(McpRateLimiter.fromInMemoryDefaults()))
    .build();
```

The application-level integration test remains direct:

```java
@Test
public void basicIntegrationTest() {
  SokletConfig applicationConfig = obtainMySokletConfig();

  // Soklet derives a fresh simulated transport graph from the application
  // configuration; none of its live transport instances are reused.
  SokletSimulator.run(applicationConfig, simulator -> {
    // Construct a request
    Request request = Request.withPath(HttpMethod.GET, "/hello")
      .queryParameters(Map.of("name", List.of("Mark")))
      .build();

    // Perform the request and get a handle to the response
    HttpRequestResult httpRequestResult = simulator.performHttpRequest(request);
    MarshaledResponse marshaledResponse = httpRequestResult.getMarshaledResponse();

    // Verify status code
    Integer expectedCode = 200;
    Integer actualCode = marshaledResponse.getStatusCode();
    Assert.assertEquals("Bad status code", expectedCode, actualCode);

    // Verify response body
    MarshaledResponseBody body = marshaledResponse.getBody().orElse(null);
    if (body instanceof MarshaledResponseBody.Bytes bytesBody) {
      String expectedBody = "Hello, Mark";
      String actualBody = new String(bytesBody.getBytes(), StandardCharsets.UTF_8);
      Assert.assertEquals("Bad response body", expectedBody, actualBody);
    } else {
      Assert.fail("No byte-array-backed response body");
    }
  });
}
```

#### Metrics Collection

Soklet provides a [`MetricsCollector`](https://javadoc.soklet.com/com/soklet/MetricsCollector.html)
for HTTP, SSE and MCP counters, gauges and histograms. Use
[`LifecycleObserver`](https://javadoc.soklet.com/com/soklet/LifecycleObserver.html)
for request tracing and audit hooks. The default in-memory collector is enabled
automatically; replace or disable it through `SokletConfig`:

```java
SokletConfig config = SokletConfig.withHttpServer(
  HttpServer.fromPort(8080)
).metricsCollector(
  MetricsCollector.defaultInstance()
  // or MetricsCollector.disabledInstance()
).build();
```

Pair `com.soklet:soklet:4.0.0` with `com.soklet:soklet-otel:2.0.0` for the
official OpenTelemetry integration.

##### Snapshots and export

Collectors that support snapshots return an immutable
[`MetricsCollector.Snapshot`](https://javadoc.soklet.com/com/soklet/MetricsCollector.Snapshot.html).
Its `getMcpMetrics()` attachment provides
[`McpMetricsSnapshot`](https://javadoc.soklet.com/com/soklet/McpMetricsSnapshot.html)
with boxed counts, immutable dimensioned maps and duration histograms. Use
[`SnapshotTextOptions`](https://javadoc.soklet.com/com/soklet/MetricsCollector.SnapshotTextOptions.html)
and [`MetricsFormat`](https://javadoc.soklet.com/com/soklet/MetricsCollector.MetricsFormat.html)
to select Prometheus/OpenMetrics output and filter individual samples.

Configured MCP scalar counters and live gauges render at zero; labeled maps
and histograms remain sparse. Empty or fully filtered families emit no orphan
HELP/TYPE metadata. Transport failures share one HTTP/SSE/MCP family.
`reset()` clears cumulative counters, maps and histograms while preserving
live request, handler, handler-queue, request-stream and subscription gauges.
A lifetime spanning reset contributes its full original duration at finish.
Retained snapshots never change, but independent aggregates are not one atomic
transaction.

MCP request-duration buckets extend from one millisecond through 15, 30, 60,
120 and 300 seconds, followed by overflow. Request-stream and subscription
histograms extend from one second through four hours. Default snapshots/text
use nanoseconds; OpenTelemetry uses seconds and corresponding bucket advice,
which SDK views can override. Histogram sums use `Double`; large totals can
lose integer precision. Recheck exact `le` filters and dashboards when
migrating. See the [histogram migration](MIGRATING_TO_4_0.md#histogram-sums-and-snapshot-values).

##### MCP observation and diagnostics

MCP uses `MetricsCollector.didRecordMcpMetricsEvent(...)` for immutable semantic
events. The default collector covers listener and connection outcomes,
request admission and lifetime, handler capacity, request streams,
subscriptions and maintenance, accepted progress/keep-alive delivery,
cooperative cancelation, protocol errors and unknown mirrored-header
occurrences. Maintenance counts include coalescing and stale-result discards;
accepted delivery is not proof of client receipt.

Collectors must be thread-safe, nonblocking and avoid I/O. Delivery is
asynchronous with at most 4,096 pending records. Overflow omits ordinary
records while preserving listener start/stop evidence; aggregates can then
be incomplete. [`McpServer.getDiagnostics()`](<https://javadoc.soklet.com/com/soklet/McpServer.html#getDiagnostics()>)
provides an immutable instantaneous view of status, effective bind address,
handler capacity, physical work, live streams/subscriptions and secret-free
security fingerprints, even when metrics are disabled. Runtime and security
tuples have independent linearization boundaries.

Coordinated shutdown projects the configured MCP component's immutable
`ShutdownComponentResult`, including `NOT_STARTED`. Metric delivery can follow
result publication; tests must await observation before inspecting counters.
Residual handlers remain physically active until exit, and their eventual exit
does not change the owner's published shutdown result. A new lifecycle needs
fresh transports.

Built-in MCP dimensions use registered endpoints, bounded methods and fixed
enum/code values. They omit session IDs, principals, operation/resource names,
header identity, arguments, results, request IDs and trace data. Dimensioned
default maps have an 8,192-key capacity with approximate LRU eviction; new
dimensions can evict older aggregates. Manually created events, custom
collectors, exact callback values and telemetry storage remain
application-owned confidentiality/cardinality surfaces.

The OpenTelemetry integration records the same semantic events and admitted
request spans, including their stream/subscription lifetime. Parent context
comes only from validated MCP metadata. HTTP trace headers and ambient context
are not fallbacks; error messages, data and exact throwables are not exported
by the built-in MCP span projection. SDK retention/export and application
custom naming remain separately owned.

See [MCP lifecycle and metrics](MCP.md#lifecycle-and-metrics) for exact families,
labels, diagnostics, reset/precision behavior, trace logging and the task-work
instrumentation boundary. The website's
[metrics guide](https://www.soklet.com/docs/metrics-collection) covers the shared
HTTP/SSE/MCP collector, filtering and export APIs.

You can expose a `/metrics` endpoint by injecting [`MetricsCollector`](https://javadoc.soklet.com/com/soklet/MetricsCollector.html)
into a [`ResourceMethod`](https://javadoc.soklet.com/com/soklet/ResourceMethod.html):

```java
@GET("/metrics")
public MarshaledResponse getMetrics(@NonNull MetricsCollector metricsCollector) {
  SnapshotTextOptions options = SnapshotTextOptions
    .withMetricsFormat(MetricsFormat.PROMETHEUS)
    .histogramFormat(HistogramFormat.FULL_BUCKETS)
    .includeZeroBuckets(false)
    .build();

  String body = metricsCollector.snapshotText(options).orElse(null);

  if (body == null)
    return MarshaledResponse.fromStatusCode(204);

  return MarshaledResponse.withStatusCode(200)
    .headers(Map.of("Content-Type", List.of("text/plain; charset=UTF-8")))
    .body(body.getBytes(StandardCharsets.UTF_8))
    .build();
}
```

#### Servlet Integration

Optional support is available for both legacy [`javax.servlet`](https://github.com/soklet/soklet-servlet-javax) and current [`jakarta.servlet`](https://github.com/soklet/soklet-servlet-jakarta) specifications. Just add the appropriate JAR to your project and you're good to go.

The Soklet website has in-depth [Servlet integration documentation](https://www.soklet.com/docs/servlet-integration).

### Learning More

Please refer to the official Soklet website [https://www.soklet.com](https://www.soklet.com) for detailed documentation.

### Credits

Soklet stands on the shoulders of giants. Internally, it embeds code from the following OSS projects:

- [Microhttp](https://github.com/ebarlas/microhttp) by [Elliot Barlas](https://github.com/ebarlas) - MIT License
- [Selenium](https://github.com/SeleniumHQ/selenium) - Apache 2.0 License
- [Apache Commons FileUpload](https://commons.apache.org/proper/commons-fileupload/) - Apache 2.0 License
- [The Spring Framework](https://spring.io/) - Apache 2.0 License
