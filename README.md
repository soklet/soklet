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

### Installation

Soklet is a single JAR, available on Maven Central.

JDK 17+ is required (or JDK 21+ for [Server-Sent Events](https://www.soklet.com/docs/server-sent-events)).

#### Maven

```xml
<dependency>
  <groupId>com.soklet</groupId>
  <artifactId>soklet</artifactId>
  <version>3.5.1</version>
</dependency>
```

#### Gradle

```groovy
repositories {
  mavenCentral()
}

dependencies {
  implementation 'com.soklet:soklet:3.5.1'
}
```

#### Direct Download

If you don't use Maven or Gradle, you can drop [soklet-3.5.1.jar](https://repo1.maven.org/maven2/com/soklet/soklet/3.5.1/soklet-3.5.1.jar) directly into your project. No other dependencies are required.

### Code Sample

Here we demonstrate building and running a single-file Soklet application with nothing but the [soklet-3.5.1.jar](https://repo1.maven.org/maven2/com/soklet/soklet/3.5.1/soklet-3.5.1.jar) and the JDK. There are no other libraries or frameworks, no Servlet container, no Maven or Gradle build process - no special setup is required.

Soklet systems can be structurally as simple as a "hello world" app.

While a real production system will have more moving parts, this demonstrates that you _can_ build server software without ceremony or dependencies.

```java
package com.soklet.example;

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
      .headers(Map.of("Content-Language", Set.of(contentLanguage)))
      .cookies(Set.of(
        ResponseCookie.withName("lastRequest")
          .value(Instant.now().toString())
          .httpOnly(true)
          .secure(true)
          .maxAge(Duration.ofMinutes(5))
          .sameSite(SameSite.LAX)
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

    try (Soklet soklet = Soklet.fromConfig(config)) {
      soklet.start();
      System.out.println("Soklet started, press [enter] to exit");
      soklet.awaitShutdown(ShutdownTrigger.ENTER_KEY);
    }
  }
}
```

Here we use raw `javac` to build and `java` to run.

This example requires JDK 17+ to be installed on your machine ([or see this example of using Docker for Soklet apps](https://github.com/soklet/barebones-app?tab=readme-ov-file#building-and-running-with-docker)). If you need a JDK, Amazon provides [Corretto](https://aws.amazon.com/corretto/) - a free-to-use-commercially, production-ready distribution of [OpenJDK](https://openjdk.org/) that includes long-term support.

#### Build

```shell
javac -parameters -cp soklet-3.5.1.jar -processor com.soklet.SokletProcessor -d build src/com/soklet/example/App.java
```

#### Run

```shell
java -cp soklet-3.5.1.jar:build com/soklet/example/App
```

#### Test

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
  Map<String, Set<String>> queryParameters = request.getQueryParameters();
  // Shorthand for plucking the first query param value by name
  Optional<String> queryParameter = request.getQueryParameter("test");
  // Header values by name (names are case-insensitive)
  Map<String, Set<String>> headers = request.getHeaders();
  // Shorthand for plucking the first header value by name (case-insensitive)
  Optional<String> header = request.getHeader("Accept-Language");
  // Parsed W3C trace context from traceparent/tracestate, if present
  Optional<TraceContext> traceContext = request.getTraceContext();
  // Request cookies by name (names are case-insensitive)
  Map<String, Set<String>> cookies = request.getCookies();
  // Shorthand for plucking the first cookie value by name (case-insensitive)
  Optional<String> cookie = request.getCookie("cookie-name");
  // Form parameters by name (application/x-www-form-urlencoded)
  Map<String, Set<String>> fps = request.getFormParameters();
  // Shorthand for plucking the first form parameter value by name
  Optional<String> fp = request.getFormParameter("fp-name");
  // Is this a multipart request?
  boolean multipart = request.isMultipart();
  // Multipart fields by name
  Map<String, Set<MultipartField>> mpfs = request.getMultipartFields();
  // Shorthand for plucking the first multipart field by name
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
    return Optional.of(GSON.fromJson(
      request.getBodyAsString().orElseThrow(),
      requestBodyType
    ));
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
([`ResourceMethodHandler`](https://javadoc.soklet.com/com/soklet/ResponseMarshaler.ResourceMethodHandler.html) and
[`ThrowableHandler`](https://javadoc.soklet.com/com/soklet/ResponseMarshaler.ThrowableHandler.html)) to Soklet as shown below.

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
  Map<String, Set<String>> headers = new HashMap<>(response.getHeaders());
  headers.put("Content-Type", Set.of("application/json;charset=UTF-8"));

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
  switch (throwable) {
    // Soklet throws this exception, a specific subclass of BadRequestException
    case IllegalQueryParameterException e -> {
      message = String.format("Illegal value '%s' for parameter '%s'",
        e.getQueryParameterValue().orElse("[not provided]"),
        e.getQueryParameterName());
      statusCode = 400;
    }

    // Generically handle other BadRequestExceptions
    case BadRequestException ignored -> {
      message = "Your request was improperly formatted.";
      statusCode = 400;
    }

    // Something else?  Fall back to a 500
    default -> {
      message = "An unexpected error occurred.";
      statusCode = 500;
    }
  }

  // Turn response body into JSON bytes with Gson.
  // Note: real systems should expose richer error constructs
  // than an object with a single message field
  byte[] body = GSON.toJson(Map.of("message", message))
    .getBytes(StandardCharsets.UTF_8);

  // Specify our headers
  Map<String, Set<String>> headers = new HashMap<>();
  headers.put("Content-Type", Set.of("application/json;charset=UTF-8"));

  return MarshaledResponse.withStatusCode(statusCode)
    .headers(headers)
    .body(body)
    .build();
};

// Supply our custom handlers to the standard response marshaler
SokletConfig config = SokletConfig.withHttpServer(
  HttpServer.fromPort(8080)
).responseMarshaler(ResponseMarshaler.builder()
  .resourceMethod(resourceMethodHandler)
  .throwable(throwableHandler)
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
      "Content-Type", Set.of("image/png")
    ))
    .build();
}
```

[`MarshaledResponse`](https://javadoc.soklet.com/com/soklet/MarshaledResponse.html) supports known-length byte-array, file, file-channel, and [`ByteBuffer`](https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/nio/ByteBuffer.html) bodies. The standard HTTP server can write file-backed responses without first loading the whole file into heap memory. If you already selected a trusted file and want file-response semantics like validators and byte ranges, use [`MarshaledResponse::withFile`](<https://javadoc.soklet.com/com/soklet/MarshaledResponse.html#withFile(java.nio.file.Path,com.soklet.Request)>); its builder can set `Content-Type`, `Content-Encoding`, cache headers, validators, and range behavior. For safe static roots, use [`StaticFiles`](https://javadoc.soklet.com/com/soklet/StaticFiles.html) instead of hand-rolled path joins; it handles root containment, validators, optional content-hash ETags, access policy, single byte ranges, MIME defaults, and `GET`/`HEAD` behavior. Standard HTTP can also opt into gzip compression for finalized in-memory byte-array and [`ByteBuffer`](https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/nio/ByteBuffer.html) responses with [`HttpServer.Builder::responseGzipPolicy`](<https://javadoc.soklet.com/com/soklet/HttpServer.Builder.html#responseGzipPolicy(com.soklet.ResponseGzipPolicy)>), including [`ResponseGzipPolicy::fromDefaultsWithMinimumBodySizeInBytes`](<https://javadoc.soklet.com/com/soklet/ResponseGzipPolicy.html#fromDefaultsWithMinimumBodySizeInBytes(java.lang.Integer)>) for common text-like response media types.

##### Streaming Responses

[`MarshaledResponse`](https://javadoc.soklet.com/com/soklet/MarshaledResponse.html) also supports streaming response bodies when the final byte length is not known up front. Streaming is intentionally a marshaled-response feature, like file-backed output: the resource method is taking direct control of what Soklet writes to the HTTP response.

```java
@GET("/tokens")
public MarshaledResponse tokens(TokenService tokenService) {
  return MarshaledResponse.withStatusCode(200)
    .headers(Map.of(
      "Content-Type", Set.of("text/plain; charset=UTF-8"),
      "Cache-Control", Set.of("no-transform")
    ))
    .stream(StreamingResponseBody.fromWriter((output, context) -> {
      try (AutoCloseable ignored = context.onCancel(tokenService::stop)) {
        tokenService.generate(token -> {
          context.throwIfCanceled();
          output.write(token.getBytes(StandardCharsets.UTF_8));
          output.flush();
        });
      }
    }))
    .build();
}
```

Streaming responses use HTTP/1.1 chunked transfer encoding. Soklet owns `Transfer-Encoding`, rejects caller-supplied `Content-Length`, and gives the producer a [`StreamingResponseContext`](https://javadoc.soklet.com/com/soklet/StreamingResponseContext.html) so upstream work can be canceled when the client disconnects, the server shuts down, or a streaming timeout fires. That context also exposes the originating [`Request`](https://javadoc.soklet.com/com/soklet/Request.html), so producers can use [`Request::getId`](<https://javadoc.soklet.com/com/soklet/Request.html#getId()>) for correlation without ambient thread-local state.

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
These builders let you configure host, read/write/handler timeouts, handler concurrency/queueing, request size limits, request decompression, and connection caps; you
can also plug in custom [`IdGenerator`](https://javadoc.soklet.com/com/soklet/IdGenerator.html) and
[`MultipartParser`](https://javadoc.soklet.com/com/soklet/MultipartParser.html) instances.
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
      .clientInitializer(unicaster -> {
        unicaster.unicastEvent(SseEvent.withEvent("hello")
          .data("welcome")
          .build());
      })
      .build();
  }

  @POST("/chat")
  public void postMessage(@RequestBody ChatMessage message,
                          SseServer sseServer) {
    SseBroadcaster broadcaster = sseServer
      .acquireBroadcaster(ResourcePath.fromPath("/chat"))
      .orElseThrow();

    broadcaster.broadcastEvent(SseEvent.withEvent("message")
      .data(message.message())
      .build());
  }
}
```

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
import org.junit.Assert;
import org.junit.Test;

@Test
public void sseTest() {
  SokletConfig config = SokletConfig.withHttpServer(HttpServer.fromPort(0).build())
    .sseServer(SseServer.fromPort(0))
    .resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(ChatResource.class)))
    .build();

  List<SseEvent> events = new ArrayList<>();

  Soklet.runSimulator(config, simulator -> {
    Request request = Request.fromPath(HttpMethod.GET, "/chat");
    SseRequestResult result = simulator.performSseRequest(request);

    if (result instanceof SseRequestResult.HandshakeAccepted accepted) {
      accepted.registerEventConsumer(events::add);

      SseBroadcaster broadcaster = config.getSseServer().orElseThrow()
        .acquireBroadcaster(ResourcePath.fromPath("/chat")).orElseThrow();
      broadcaster.broadcastEvent(SseEvent.withEvent("message")
        .data("hello")
        .build());
    } else {
      throw new IllegalStateException("SSE handshake failed: " + result);
    }
  });

  Assert.assertEquals("hello", events.get(0).getData().orElse(null));
}
```

#### Model Context Protocol (MCP)

Soklet 3.6.0 implements the MCP `2026-07-28` server protocol through
[`McpServer`](https://javadoc.soklet.com/com/soklet/McpServer.html). An MCP
server always listens on its own port, independent of Soklet's standard HTTP
and SSE servers. The protocol is stateless: a client may make
`server/discover` its first request, there is no initialization or session
lifecycle, and every request carries its own protocol metadata and client
capabilities. Soklet derives the capabilities it advertises from the endpoint's
registered operations. The complete current behavior and operational contract
are documented in the [MCP guide](MCP.md).

> **Unreleased API:** this section describes `3.6.0-SNAPSHOT`. The 3.5.1
> installation shown above contains the older, incompatible MCP API.

Complete compile-checked programmatic and annotation-driven applications are
kept outside the source repository in the project-root
`mcp/examples/phase-4` workspace. They each register a tool, prompt, and
resource and start MCP on a dedicated port. The annotation-driven form runs
[`SokletProcessor`](https://javadoc.soklet.com/com/soklet/SokletProcessor.html)
with parameter names retained and loads its generated endpoint through
`McpHandlerResolver.fromClasses(...)`; the programmatic form builds the same
immutable endpoint and registration model directly.

Programmatic tools use a staged builder so their schema and handler cannot
drift apart:

- `types(arguments, result)` derives and enforces input and output schemas and
  adapts an ordinary Java result to a complete MCP result.
- `argumentType(arguments)` derives typed inputs for an advanced handler that
  returns `McpOperationResult` directly.
- `jsonArguments()` supplies immutable `McpJsonObject` arguments and publishes
  the fixed `{"type":"object"}` input schema.

Typed derivation is the only application-facing schema-authoring path in
3.6.0. It uses the closed Soklet MCP Tool Schema Profile 1, based on JSON Schema
Draft 2020-12. Soklet rejects unsupported Java shapes and schema constructs at
registration or compilation; it does not expose hand-authored schemas and does
not claim complete Draft 2020-12 support.

MCP's three application primitives serve different purposes:

- **Tools** perform actions. Soklet validates and converts their JSON arguments,
  invokes the selected application handler, and validates structured output.
- **Prompts** are named, discoverable templates that turn string arguments into
  ordered user/assistant content messages.
- **Resources** expose application data by exact URI or bounded RFC 6570 Level 1
  URI template. With no custom `resources/list` handler, exact registrations
  form one static page and templates are listed separately. A custom
  [`McpResourceListHandler`](https://javadoc.soklet.com/com/soklet/McpResourceListHandler.html)
  is the sole authority for every page; Soklet passes its opaque cursor through
  but does not create, sign, persist, or interpret it.

Important operational defaults and boundaries:

- Every server requires an admission policy. A tool-bearing server must also
  resolve a tool limiter; a request-wide limiter is optional. `McpRateLimiter`
  is a thread-safe application SPI, so a deployment may use Redis or another
  distributed system instead of the finite in-JVM convenience implementation.
- The MCP listener binds to `127.0.0.1` by default. Containers that need a
  reachable listener must set `host(...)` deliberately and retain an explicit
  Host allowlist. TLS termination and network exposure belong at the proxy or
  load-balancer boundary.
- A present `Origin` is rejected by default through the shared
  [`CorsAuthorizer`](https://javadoc.soklet.com/com/soklet/CorsAuthorizer.html);
  an absent Origin is allowed by default. Host validation is independent and
  runs before protocol parsing or application work.
- Handlers are synchronous. The defaults permit 32 active handlers and 128
  queued requests with a 60-second absolute request timeout. A custom executor
  does not bypass those bounds. Disconnect, deadline, shutdown, and stream
  backpressure signal the handler's cooperative `CancelationToken`, but cannot
  forcibly stop non-cooperative application code. Bounded shutdown reports
  `RESIDUAL_HANDLERS` while any application-supplied MCP request-processing
  execution remains and rejects restart until that work actually exits.
- [`McpHandlerInterceptor`](https://javadoc.soklet.com/com/soklet/McpHandlerInterceptor.html)
  wraps every application-owned tool, prompt, resource-read, and custom
  resource-list handler. Framework-owned discovery and static catalogs bypass
  it. MCP server/request observation reuses Soklet's existing
  `LifecycleObserver` and `MetricsCollector` hosts.
- MCP responses carry protocol cache hints while transport responses remain
  `Cache-Control: no-store`. Application-owned resource-list cursors are bounded
  by UTF-8 size and must themselves preserve integrity, authorization,
  snapshot, and fleet-portability semantics.

Every application handler can retrieve its request-scoped cancelation token;
a progress reporter is available only when the request carries a valid MCP
progress token and no conditional-capability decision requires the response to
remain uncommitted:

```java
CancelationToken cancelation =
    features.require(CancelationToken.class);

features.find(McpProgressReporter.class).ifPresent(reporter ->
    reporter.report(McpProgressUpdate.withProgress(50.0d)
        .total(100.0d)
        .message("Halfway")
        .build()));

cancelation.throwIfCanceled();
```

Soklet echoes string and integer progress tokens exactly, coalesces equal
progress values, rejects decreases while the invocation is active, and writes
accepted updates synchronously through the bounded request SSE queue. Progress
never extends the absolute request deadline. Annotated tool, prompt, resource,
and resource-list methods may directly inject `CancelationToken` and
`Optional<McpProgressReporter>`; those are the same instances exposed through
`McpInvocationFeatures`. A bare progress-reporter parameter is invalid because
the feature is conditional. Accepted progress and cancelation signals also
reach the shared metrics host with bounded endpoint-path and JSON-RPC-method
dimensions.

Tools, prompts, and resource reads may return `McpInputRequiredResult` after
declaring their possible client requests with `mayRequestInput(...)` (or
`@McpMayRequestInput`). Retries expose the client's `inputResponses` through
`McpRequestContext`. An operation may also select one request-state contract:

- `APPLICATION_PROTECTED` passes a nonempty opaque string through exactly;
  the application owns its confidentiality, integrity, expiry, authorization
  binding, replay policy, and fleet portability.
- `FRAMEWORK_PROTECTED` lets the application work with `McpJsonValue` state
  while Soklet canonicalizes, binds, protects, expires, and round-limits its
  wire representation. It requires `McpServer.Builder.protectionConfig(...)`
  using a production key ring, explicit development-ephemeral protection, or
  a thread-safe custom `McpRequestStateProtector`.

Framework-protected state can continue on another server instance when both
instances share production protection material and admission resolves the
retry to the same authorization partition. Wrong material under the same key
ID or a different partition fails before application observation. Development-
ephemeral state is intentionally process-local.

See the [MCP guide](MCP.md#multi-round-trip-input-and-request-state) for the
declaration, protection, error, and retry-cache contracts. Progress reporting,
cooperative cancelation, and resource-subscription delivery are implemented
Phase 5 slices. Deterministic MRTR termination, cross-instance protected-state
continuation, and residual-shutdown recovery are implemented as well. Resource
subscriptions use framework-owned listen streams and an application-owned
local or distributed broadcast publisher. Nine bounded Phase 6 verticals are
implemented: shutdown observation, handler-capacity metrics, handler-capacity
diagnostics, live stream/subscription diagnostics, protection/trace
diagnostics, serialized semantic-event delivery, and bounded pre-admission
metrics, followed by connection/transport metric delivery and admitted-request
trace-token capture. Every successfully
started listener generation emits exactly one
matching clean/residual shutdown metric, and server-wide handler execution,
admitted-queue depth, and queue-full rejection transitions feed three label-
free default metric families. Immutable server diagnostics expose the
configured handler bounds, current active/queued counts, open request streams
and subscription subset, effective request-state protection mode, custom-
protector presence, and secret-free production-ring and trace-configuration
fingerprints. The diagnostics add no metric, event, or wire dimension. A
separate bounded Phase 6 MCP fuzz-registration checkpoint now covers five new
Jazzer methods with 21 synthetic seeds and expands the nightly matrix to 15
total one-method slots; it remains an unnumbered checkpoint. The internal
trace-correlation derivation checkpoint is likewise unnumbered; the subsequent
admitted-request capture integration is the ninth production vertical. A
third unnumbered metric-dimensionality checkpoint freezes the exact 23-event
schema, four-field MCP snapshot, 17 ignored nonaggregated variants, and
default-render cardinality under 16 distinct trace-metadata inputs. The
unresolved aggregate families and `AMB-003`, structured-log emission and raw-
ID opt-in, broader privacy and sustained-cardinality work, MCP simulation,
scheduled/manual coverage-guided and sustained fuzz gates, and
release-candidate and Phase 6 review/freeze work remain open; applications
must not advertise or depend on those remaining behaviors yet.

The exact pinned 39-scenario MCP suite has completed one clean controlled
profile-observation run against the packaged fixture: 147 successful outcomes,
two reviewed skips for truthfully unadvertised mutable prompt/tool lists, one
reviewed informational JSON-versus-optional-SSE outcome, and no warning,
failure, or wire-harness error. This is profile-acquisition evidence only. The
observation did not itself activate the Phase 5 profiles or freeze the API. The
bounded Phase 5 cross-feature soak/resource-delta gate is green: full Maven
smoke runs pass on JDK 21 and JDK 26, and the full JDK 21 nightly profile also passes, with the
strict verifier requiring four scenarios and three Surefire suites. The later
atomic closeout activates all 39 exact profiles, freezes the Phase 5 API, and
passes a fresh 39-scenario development-candidate verify with all 39 goldens and
no bad outcome, standard-error output, or non-clean exit. It remains
development evidence, not release-candidate provenance.

The focused metric-dimensionality and trace-cardinality checkpoint run passes
95/0/0/0.
The prior focused five-target fuzz run remains 28/0/0/0 and was not rerun for
this checkpoint;
the prior deterministic full fuzz corpus replay on both JDKs remains
127/0/0/0 and was likewise not rerun. Exact-source full main suites on JDK 21
and JDK 26 each report 1,467/0/0/4. The JDK 21 enforced static-analysis profile
is green without counting advisory warnings; SpotBugs is green. Exact API-
freeze evidence remains unchanged at 556 incompatibilities, 206 reviewed
owners, 1,049 Phase 4
records, and 195 Phase 5 records with the prior hashes. Candidate main,
source, and Javadoc packages plus standalone Javadoc are green using
offline-link resolution. All 167 API-sketch sources compile for Java 17 and
pass Javadoc doclint on JDK 26. All 104 files from pinned JSON Schema commit
`0c7b65dc16dd8eaa7bd83e21099c76610c3b246a` validate. No scheduled or manual
coverage-guided nightly fuzz run occurred; deterministic seed replay is not
sustained, coverage, corpus-saturation, privacy, security, release-readiness,
or Phase 6 freeze proof. The remaining Phase 6 aggregate families and
`AMB-003`, structured-log carrier/emission, raw-ID opt-in,
  broader privacy, sustained cardinality, and redaction work, simulator,
coverage-guided and sustained fuzz gates, broader
CI/provenance and release-candidate work, and Phase 6 review/freeze remain open.
Phase 6 remains provisional and unfrozen.

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
  MultipartField document,
  // ...but is useful if you need to massage the name.
  @Multipart(name="extra") List<MultipartField> supplements,
  // If you specify type byte[] for a @Multipart field,
  // you'll get just its binary data injected
  @Multipart(optional=true) byte[] photo
) {
  // Let's demonstrate the functionality MultipartField provides.

  // Form field name, always available, e.g. "document"
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

HTTP Server Start/Stop: execute code immediately before and after [`HttpServer`](https://javadoc.soklet.com/com/soklet/HttpServer.html) startup and shutdown.

```java
SokletConfig config = SokletConfig.withHttpServer(
  HttpServer.fromPort(8080)
).lifecycleObserver(new LifecycleObserver() {
  @Override
  public void willStartHttpServer(@NonNull HttpServer httpServer) {
    // Perform startup tasks required prior to server launch
    MyPayrollSystem.INSTANCE.startLengthyWarmupProcess();
  }

  @Override
  public void didStartHttpServer(@NonNull HttpServer httpServer) {
    // HTTP server has fully started up and is listening
    System.out.println("HTTP server started.");
  }

  @Override
  public void willStopHttpServer(@NonNull HttpServer httpServer) {
    // Perform shutdown tasks required prior to server teardown
    MyPayrollSystem.INSTANCE.destroy();
  }

  @Override
  public void didStopHttpServer(@NonNull HttpServer httpServer) {
    // HTTP server has fully shut down
    System.out.println("HTTP server stopped.");
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
// Special scoped value so anyone can access the current Locale.
// For Java < 21, use ThreadLocal instead
public static final ScopedValue<Locale> CURRENT_LOCALE;

// Spin up the ScopedValue (or ThreadLocal)
static {
  CURRENT_LOCALE = ScopedValue.newInstance();
}

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
    Locale locale = request.getLocales().get(0);

    // ...by binding it to a ScopedValue (or ThreadLocal).
    ScopedValue.where(CURRENT_LOCALE, locale).run(() -> {
      // You must call this so downstream processing can proceed
      requestProcessor.accept(request);
    });
  }
}).build();

// Then, elsewhere in your code while a request is being processed:

class ExampleService {
  void accessCurrentLocale() {
    // You now have access to the Locale bound to the logical scope
    // (or Thread) without having to pass it down the call stack
    Locale locale = CURRENT_LOCALE.orElse(Locale.getDefault());
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
      mutableHeaders.put("X-Powered-By", Set.of("Soklet"));
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
  public void willStartResponseWriting(
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
  public void didFinishResponseWriting(
    @NonNull Request request,
    @Nullable ResourceMethod resourceMethod,
    @NonNull MarshaledResponse marshaledResponse,
    @NonNull Duration responseWriteDuration,
    @Nullable Throwable throwable
  ) {
    long millis = responseWriteDuration.toMillis();
    System.out.printf("Took %dms to write response\n", millis);

    // You have access to the throwable that might have occurred
    // while writing the response.  This is useful to, for example,
    // determine trends in unexpected client disconnect rates
    if(throwable != null) {
      System.err.println("Exception occurred while writing response");
      throwable.printStackTrace();
    }
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
  .corsAuthorizer(WhitelistedOriginsCorsAuthorizer.fromOrigins(allowedOrigins))
  .build();
```

...or be dynamic:

```java
SokletConfig config = SokletConfig.withHttpServer(server)
  .corsAuthorizer(WhitelistedOriginsCorsAuthorizer.fromAuthorizer(
    (origin) -> origin.equals("https://www.revetware.com")
  ))
  .build();
```

Custom CORS logic:

```java
SokletConfig config = SokletConfig.withHttpServer(server)
  .corsAuthorizer(new CorsAuthorizer() {
    // Any subdomain under soklet.com is permitted
    boolean originMatchesValidSubdomain(@NonNull Cors cors) {
      return cors.getOrigin().matches("https://(.+)\\.soklet\\.com");
    }

    @NonNull
    @Override
    public Optional<CorsPreflightResponse> authorizePreflight(
      @NonNull Request request,
      @NonNull Map<HttpMethod, ResourceMethod> availableResourceMethodsByHttpMethod
    ) {
      // Requests here are guaranteed to have the Cors value set
      Cors cors = request.getCors().orElseThrow();

      // Only greenlight our soklet.com subdomains
      if (originMatchesValidSubdomain(cors))
        return Optional.of(
          CorsPreflightResponse.withAccessControlAllowOrigin(cors.getOrigin())
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
    public Optional<CorsResponse> authorize(@NonNull Request request) {
      // Requests here are guaranteed to have the Cors value set
      Cors cors = request.getCors().orElseThrow();

      // Only greenlight our soklet.com subdomains
      if (originMatchesValidSubdomain(cors))
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
    return numbers.reversed();
  }

  // Reverse the input and set custom headers/cookies
  @POST("/reverse-again")
  public Response reverseAgain(@RequestBody List<Integer> numbers) {
    Integer largest = Collections.max(numbers);
    Instant lastRequest = Instant.now();

    return Response.withStatusCode(200)
      .headers(Map.of("X-Largest", Set.of(String.valueOf(largest))))
      .cookies(Set.of(
        ResponseCookie.with("lastRequest", lastRequest.toString()).build()
      ))
      .body(numbers.reversed())
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

Soklet's [`Simulator`](https://javadoc.soklet.com/com/soklet/Simulator.html) is available via [`Soklet`](https://javadoc.soklet.com/com/soklet/Soklet.html) to exercise full request/response flows without binding a port.

```java
@Test
public void basicIntegrationTest() {
  // Just use your app's existing configuration
  SokletConfig config = obtainMySokletConfig();

  // Instead of running in a real HTTP server that listens on a port,
  // a simulator is provided against which you can issue requests
  // and receive responses.
  Soklet.runSimulator(config, (simulator -> {
    // Construct a request
    Request request = Request.withPath(HttpMethod.GET, "/hello")
      .queryParameters(Map.of("name", Set.of("Mark")))
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
  }));
}
```

#### Metrics Collection

Soklet includes a [`MetricsCollector`](https://javadoc.soklet.com/com/soklet/MetricsCollector.html) hook for collecting HTTP, SSE, and MCP telemetry. Use metrics collectors for low-cardinality counters, gauges, and histograms; use [`LifecycleObserver`](https://javadoc.soklet.com/com/soklet/LifecycleObserver.html) for per-request tracing and audit hooks. The default in-memory
collector is enabled automatically, but you can replace or disable it:

```java
SokletConfig config = SokletConfig.withHttpServer(
  HttpServer.fromPort(8080)
).metricsCollector(
  MetricsCollector.defaultInstance()
  // or MetricsCollector.disabledInstance()
).build();
```

Use [`MetricsCollector.SnapshotTextOptions`](https://javadoc.soklet.com/com/soklet/MetricsCollector.SnapshotTextOptions.html) and
[`MetricsCollector.MetricsFormat`](https://javadoc.soklet.com/com/soklet/MetricsCollector.MetricsFormat.html) to control text output.

`McpServer.getDiagnostics()` provides an immutable point-in-time view of MCP
handler capacity, live request streams, protection, and trace configuration
even when metrics are disabled. `McpServerDiagnostics` declares exactly 12
zero-argument methods: `getStatus()` and `getBoundAddress()`, plus all ten
implemented diagnostic getters. Six are boxed `@NonNull Integer` values:
`getRequestHandlerConcurrency()`, `getRequestHandlerQueueCapacity()`,
`getActiveHandlerExecutions()`, `getQueuedRequests()`,
`getActiveRequestStreams()`, and `getActiveSubscriptions()`. The other four are
`getProtectionMode()`, boxed
`@NonNull Boolean isApplicationRequestStateProtectorConfigured()`,
`getProtectionKeyRingFingerprint()`, and
`getTraceCorrelationConfigurationFingerprint()`; both fingerprint accessors
return non-null `Optional` values with non-null payloads.

The configured numeric values are positive and stable before start and across
stop/restart. Lifecycle status, bound address, configured bounds, handler
counts, and the paired stream/subscription counts form one runtime-owned atomic
tuple. The four security fields form a separate atomic tuple owned by the
security controls. The resulting public record is immutable, but the two
tuples do not claim a shared global linearization point. Handler counts are
nonnegative and bounded by their corresponding configuration, queued work
implies all handler slots are occupied, and
`0 <= activeSubscriptions <= activeRequestStreams`.

An ordinary request-scoped SSE stream produces pair `1/0`. An isolated
resource subscription enters both counts when its acknowledgment stream opens,
producing `1/1` without claiming client receipt. Opening both produces the
server-wide pair `2/1`. Disconnecting the subscription returns the pair to
`1/0`, and disconnecting the ordinary stream returns it to `0/0`.

After a completed clean stop both live counts are zero. A completed residual
stop has queue depth zero but continues to report a non-cooperative handler as
active until its actual late exit. A transient residual snapshot captured
during unexpected-failure cleanup may retain the actual bounded queue depth;
cleanup drains it without promoting work, and a queue-full rejection does not
change either live handler count. Completed clean and residual-handler stops
both report stream pair `0/0`, even while a residual handler remains active.
During internal `FAILED` cleanup, public residual status may transiently retain
an open subscription pair `1/1`; completed cleanup reports `STOPPED` with
`0/0`.

The protection mode and custom-protector flag are construction-time values and
remain stable across listener lifecycle. The flag is `true` exactly for
`CUSTOM_PROTECTOR`; it reports selection of a custom application-owned
`McpRequestStateProtector`, not `APPLICATION_PROTECTED` operation selection.
Application-protected opaque state requires no framework protector and bypasses
one even when configured. The protection fingerprint is present exactly for a
live `PRODUCTION_KEY_RING`; development-ephemeral, custom, and unconfigured
modes return empty. The trace fingerprint is independent of protection mode
and is present exactly when trace correlation was enabled at construction.
Successful live rotations update only fresh snapshots, survive listener
stop/restart, and never mutate retained snapshots.

The fingerprints are deterministic operational comparison metadata, not
authentication or token-derivation inputs. Diagnostics expose no raw key
material, key IDs, per-key fingerprint tags, custom-provider identity,
request-state cursors or epochs, or trace tokens. Strong operator key entropy
remains required; equality is observable and rotation may create high-
cardinality values, so fingerprints should not be metric labels or per-request
log fields. These diagnostics add no metric family, event type, wire field,
label, or other observation dimension, and collector reset cannot alter them.

For MCP handler capacity, `McpMetricsSnapshot` exposes boxed, nonnegative
`Long` values from `getActiveHandlerExecutions()`, `getHandlerQueueDepth()`,
and `getHandlerCapacityRejections()`. The corresponding
`activeHandlerExecutions(Long)`, `handlerQueueDepth(Long)`, and
`handlerCapacityRejections(Long)` builder methods also use boxed values. A
configured MCP server renders these exact label-free families, including zero
values:

- `soklet_mcp_handler_executions_active` (gauge);
- `soklet_mcp_handler_queue_depth` (gauge); and
- `soklet_mcp_handler_capacity_rejections_total` (counter).

Only a full admitted handler queue increments the rejection counter. Deadline,
disconnect, cancelation, and shutdown removal of queued work decrement queue
depth without counting a rejection. Reset preserves the two live gauges while
clearing cumulative rejections. A non-cooperative residual handler remains
active after bounded shutdown until it actually exits, at which point the
active gauge returns to zero; retained snapshots do not change.

The sixth bounded Phase 6 vertical established one context-aware, server-wide
deferred FIFO for the first 16 semantic event variants produced by the runtime:
the five handler transitions,
`ServerStopped`, admitted `RequestStarted`, `RequestFinished`,
`RequestStreamOpened`, `RequestStreamClosed`, `SubscriptionOpened`,
`SubscriptionClosed`, `CancelationSignaled`, `ProgressEmitted`, and
`KeepAliveEmitted`, plus exactly one `ServerStarted` for each successfully
started listener generation. Failed starts leave no phantom `ServerStarted`.
Direct restart orders the old `ServerStopped` before the new `ServerStarted`,
while managed startup rollback orders `ServerStarted` before `ServerStopped`.

The seventh vertical extended the same FIFO to the 20 variants produced at
that checkpoint with
`RequestAccepted`, `RequestRejected`, `ProtocolError`, and
`UnknownMirroredHeader`. A successful bounded-processor submission emits
`RequestAccepted`; executor rejection removes that provisional event and emits
only `RequestRejected` before the fixed empty HTTP 503. Malformed requests
order accepted, fixed protocol error, then rejected. Strict unknown-header and
unresolved-method requests additionally emit one unknown-header event per
occurrence before their fixed protocol error and rejection. Unknown events use
only the endpoint path and a bounded method or `<unrecognized>`—never a header
name, value, or raw method—and are independent of optional name-diagnostic
quota. Application-owned error codes are excluded from protocol-error metrics.

`ProtocolError` uses exactly the fixed codes `-32700`, `-32600`, `-32601`,
`-32602`, `-32603`, `-32020`, `-32021`, `-32022`, `-31999`, and `-31998`,
after successful response encoding. A streamed error remains provisional until
its terminal message is accepted and is discarded on failed reservation.
Collector callbacks drain after the relevant dispatcher, progress-reporter,
stream-transition, request-control, runtime, server, and Soklet lifecycle locks
or monitors are released, with nonwaiting request-transition deferral
preserving reentrant liveness. Pre-admission events are request-free. Only an
admitted fixed protocol error retains its exact request context for bounded
delivery and failure attribution; that context is never rendered. Collector
failures are contained and do not stall the FIFO. This guarantees FIFO metric
record/enqueue order, not a universal cross-thread causal or per-request total
order between independently racing producers.

The eighth bounded vertical adds `ConnectionAccepted`, `ConnectionRejected`,
and `TransportFailure`, so the same FIFO now produces and delivers all 23
declared event variants. `ConnectionAccepted` follows operating-system accept
and capacity reservation but precedes registration and request processing; a
later setup failure can therefore follow it. `ConnectionRejected` means only
that an accepted socket encountered the configured maximum-connection bound.
Accept-loop and setup faults are typed transport failures, never capacity
rejections.

Every `TransportFailure` is request-free and carries only one of the exact 18
bounded reasons: `REQUEST_READ_TIMEOUT`, `REQUEST_TOO_LARGE`,
`MALFORMED_REQUEST`, `READ_ERROR`, `WRITE_ERROR`,
`RESPONSE_WRITE_IDLE_TIMEOUT`, `RESPONSE_READY_ERROR`,
`REQUEST_READ_TIMEOUT_ERROR`, `RESPONSE_WRITE_IDLE_TIMEOUT_ERROR`,
`ACCEPT_LOOP_ERROR`, `CONNECTION_SETUP_ERROR`, `TASK_ERROR`,
`TIMEOUT_TASK_ERROR`, `SELECTION_KEY_ERROR`, `REGISTER_ERROR`, `WRITE_TIMEOUT`,
`EVENT_LOOP_TERMINATED`, or `UNKNOWN`. Neither the event nor collector-failure
logging retains a remote address, raw request, request context, throwable,
payload, trace token, or another unbounded dimension.

Typed provisional failure scopes and a coalescing single-daemon-worker drain
keep collector callbacks off connection threads and retry a signal that races
executor rejection. Lifecycle deferral safely adopts pending delivery, so a
fatal restart orders old `EVENT_LOOP_TERMINATED`, old `ServerStopped`, then new
`ServerStarted` before returning. A partial request timeout is recorded while
a byte-free idle close is quiet; malformed HTTP remains distinct from a
complete malformed JSON-RPC request. A winning request-SSE write-idle expiry
records one `WRITE_TIMEOUT` before its terminals, while a losing/generic close
records no `WRITE_TIMEOUT` and does not manufacture `WRITE_ERROR`. Fatal-loop
recording precedes stop/wake and remains scoped through sibling cleanup. These
are FIFO
record/enqueue-order guarantees, not universal cross-thread causal ordering.

Separate from the first eight production observability and diagnostics
verticals,
the bounded Phase 6 MCP fuzz-registration and hardening checkpoint adds
`McpJsonRpcEnvelopeCodecFuzzTest#decodeClassifiesOrRejectsOnlyWithTypedWireFailure`,
`McpMirroredHeaderCodecFuzzTest#decodeStringOnlyRejectsWithRedactedIllegalArgumentException`,
`McpToolSchemaProfileFuzzTest#compileAndEvaluateRemainTypedAndBounded`,
`McpCursorValidatorFuzzTest#cursorValidationIsUtf8ExactAndTotal`, and
`McpRequestStatePlaintextCodecFuzzTest#decodeOnlyRejectsWithUniformRedactedIllegalArgumentException`.
Twenty-one checked-in synthetic text seeds cover those five new Jazzer methods,
and the nightly workflow declares 15 total one-method slots, five of them new.
This fuzz checkpoint remains unnumbered; it is not the ninth production
vertical described below.

The targets classify a production-limited JSON-RPC envelope or accept only a
typed `McpWireDecodingException` without requiring unconditional re-encoding;
bound mirrored-header decoding to its production default and uniform redacted
`IllegalArgumentException`; and cap Profile 1 schema/instance input at 64 KiB
while requiring typed compilation or production-bounded evaluation outcomes.
The cursor target caps input at 64 KiB and cross-checks decoded UTF-8 and raw
UTF-16 projections with the JDK UTF-8 encoder in `REPORT` mode at a derived
1-to-256-byte limit. The request-state plaintext target uses a fixed binding,
clock, request ID, 4,096-byte size, 15-minute lifetime, and three-round limit;
accepted input re-encodes byte-exactly and rejection stays uniform and
redacted, with terminal-LF copying limited to at most 4,097 input bytes. The
cursor validator exposed for this target is package-private and internal, is
shared by incoming and outgoing cursor checks, and adds no public API.

An unnumbered internal trace-correlation derivation checkpoint implements the
frozen token construction. Trace correlation is disabled by default, and
disabled controls capture no token. Enabled controls
snapshot one complete active key ID and key-material pair under the shared
security lock, derive after releasing it with HMAC-SHA-256 over UTF-8
`soklet-mcp-trace-correlation-v1\0` plus the decoded 16-byte trace ID, truncate
to the first 16 digest bytes, and encode an unpadded 22-character Base64URL
token. `TraceContext` rejects invalid and all-zero trace IDs before derivation;
same key/trace inputs agree, changed key or trace inputs differ, and rotation
exposes only coherent old or new `(keyId, token)` pairs. Copied key material
and explicit derivation buffers are zeroed. The internal carrier retains only
the nonsecret key ID and token and redacts the token from rendering.

The ninth bounded production vertical now captures one token carrier exactly
once for each admitted semantic request before lifecycle and handler
observation. It derives only from a valid MCP `_meta.traceparent`; disabled
correlation, invalid or all-zero MCP trace context, absent metadata, and a
physical HTTP trace header without valid MCP metadata produce no carrier. The
lifecycle observer, interceptor, handler, and terminal callback share the same
immutable request context and carrier. A request captured before key rotation
retains its old `(keyId, token)` through terminal observation, while a later
request adopts the new pair. Raw validated trace-ID opt-in neither enables nor
changes token derivation. The hidden final carrier retains only nonsecret key
ID and token, not raw trace context or key material, and redacts its token from
rendering.

At that point, following the ninth vertical, the prior fuzz and dormant
derivation checkpoints remained unnumbered. `SOK-TRACE-001`, `SOK-TRACE-002`,
and `SOK-TRACE-003` were COMPLETE; `SOK-TRACE-004` and `SOK-TRACE-005` were
PLANNED; and `SOK-PRIV-001` was PARTIAL. No public API or API-sketch source
changed. There is no
structured-log carrier, field, emission point, cadence, or new `LogEventType`,
and raw trace-ID logging remains unimplemented. No metric, event, diagnostics/
snapshot field, aggregate, label, or wire dimension was added. Tokens remain
pseudonymous high-cardinality operational metadata, not anonymization,
authentication, or authorization inputs. The carrier is not cleared at finish
and has no GC or application-reference lifetime guarantee; an application-
retained request context naturally retains it, while core controls retain only
the current key and expose no history API. This is not comprehensive trace/
baggage redaction, cardinality, privacy/security, aggregate/`AMB-003`,
simulator, release-readiness, or Phase 6 freeze evidence.

A third unnumbered Phase 6 checkpoint is covered by
`McpObservabilityPublicApiTests#metricSchemaHasExactFiniteNonTraceDimensions`
and
`McpRequestObservationPublicRuntimeTests#distinctTraceMetadataDoesNotCreateMetricDimensionsOrLeakIntoRendering`.
It freezes exactly 23 event records, including 11 fieldless variants. The
remaining components are limited to endpoint path, bounded method, fixed
outcome, reason or protocol code, and nonnegative duration. Production emits
registered endpoints, recognized methods or `<unrecognized>`, the fixed ten
codes, and fixed enums; public constructors still permit arbitrary
application-created nonempty endpoints/methods and non-null codes. The MCP
snapshot remains three boxed `Long` values and one immutable shutdown map.
`DefaultMetricsCollector` aggregates only five handler variants and
`ServerStopped`, ignoring and retaining none of the other 17 variants.

Sixteen sequential real requests carrying distinct valid MCP and HTTP trace
IDs, tracestate, baggage, derived tokens, and key canaries leave no value in
built-in MCP events, snapshot state, metric names or labels, Prometheus,
OpenMetrics, filter-observed samples, or reset output. The exact pre-reset MCP
sample set is three label-free handler samples plus the clean shutdown outcome;
post-reset it is exactly the three label-free samples. Nine production
verticals remain nine; fuzz registration, dormant derivation, and metric
dimensionality are the three unnumbered checkpoints. `SOK-TRACE-001/002/003`
remain COMPLETE, `SOK-TRACE-004` remains PLANNED, `SOK-TRACE-005` is PARTIAL
for metric-dimension inventory/default-collector evidence only, and
`SOK-PRIV-001` remains PARTIAL. `SOK-METRIC-001` and `SOK-METRIC-004`
remain PARTIAL; `AMB-003` remains AMBIGUOUS.

This test-only checkpoint changes no production source, public API, API sketch,
owner/signature inventory, family, label, event variant, or wire behavior. It
does not cover custom collectors; generic HTTP `MetricsCollector` callbacks
receiving `Request`, request-target, or `Throwable` values; `LogEvent`,
application callbacks or handler telemetry; arbitrary application event
vocabulary; structured logging or raw-ID emission; future aggregates;
comprehensive trace/baggage redaction; sustained cardinality, fuzz or soak;
simulation, migration, release-candidate provenance, review, or Phase 6
freeze.

For MCP shutdowns, `snapshot().getMcpMetrics().getShutdowns()` is an immutable,
enum-ordered `Map<McpShutdownOutcome, Long>`. The default collector omits
unobserved outcomes, returns the map to empty on reset, and emits only
`soklet_mcp_shutdowns_total{outcome="clean"}` or
`soklet_mcp_shutdowns_total{outcome="residual_handlers"}`. Default aggregation
remains limited to `ServerStopped` and the five handler variants. Unresolved
aggregate families and `AMB-003`, structured-log carrier/emission, raw-ID
opt-in, broader privacy, sustained cardinality, and redaction work, simulator
integration, fuzz and
sustained gates, release-candidate work, and Phase 6 review/freeze remain open.
Here, the remaining fuzz work means scheduled/manual coverage-guided and
sustained execution, not the completed registration and deterministic corpus
replay checkpoint. No such coverage-guided nightly run has occurred, and the
replay is not sustained, coverage, corpus-saturation, privacy, security,
release-readiness, or freeze proof.
The seventh through ninth verticals add no public API, snapshot field, aggregate
family, label, event variant, or wire dimension. Phase 6 remains provisional
and unfrozen.

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
    .headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
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
