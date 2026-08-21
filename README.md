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

> **Unreleased API:** This section describes `3.6.0-SNAPSHOT`. The `3.5.1`
> artifact shown above contains the older, incompatible MCP API.

Soklet 3.6.0 targets the MCP `2026-07-28` server protocol with a dedicated,
stateless `McpServer`. MCP owns a listener and port separate from Soklet's
ordinary HTTP and SSE servers, can host multiple exact endpoint paths, and
derives each endpoint's advertised capabilities from its registered
operations.

Define endpoints with the compile-time-processed `@McpServerEndpoint`,
`@McpTool`, `@McpPrompt`, `@McpResource`, and `@McpResourceList` annotations,
or assemble the same immutable model programmatically. The public API covers:

- Java-derived tool input and output schemas, typed or JSON arguments, and
  validated structured results;
- prompts, exact and templated resources, custom resource pagination, and
  protocol cache hints;
- multi-round input requests with application- or framework-protected state;
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
  .endpointRegistry(McpEndpointRegistry.fromClasses(CatalogMcpEndpoint.class))
  .admissionController(McpAdmissionController.acceptAllInstance())
  .toolRateLimiter(McpRateLimiter.fromInMemoryDefaults())
  .build();

SokletConfig config = SokletConfig.withMcpServer(mcpServer).build();
```

`acceptAllInstance()` and the in-memory limiter are convenient development
defaults, not production authentication or fleet-wide rate limiting. Every
server requires an admission controller, and every tool-bearing server requires a
fallback tool limiter. The listener binds to `127.0.0.1` by default; configure
`host(...)`, `allowedHosts(...)`, authentication/admission, and TLS termination
deliberately before exposing it remotely.

Framework-owned catalog text - server, tool, prompt, resource, and schema
titles and descriptions - can be localized per request through a
library-neutral seam that keeps Soklet free of any translation dependency.
`McpLocalizationContext` is a Soklet-owned final value built with
`withLocale(...)`; applications provide a localization callback instead of a
custom context implementation.
The other public MCP value carriers follow the same style: final immutable
classes, named factories or builders, private constructors, and conventional
`get...` accessors. Sealed decision, localization, subscription, and metric
families keep public nested variants for typed pattern matching, but creation
is owned by factories on the sealed root.
Omitting a localizer leaves wire output byte-identical. See
[MCP localization](https://www.soklet.com/docs/mcp-localization).

Every selected application handler receives one cooperative `CancelationToken`.
Framework cancellation exposes only a fixed `StreamTerminationReason`; its
underlying cause is empty, including through
`StreamingResponseCanceledException`. On HTTP, an incoming
`notifications/cancelled` message is accepted and ignored for compatibility;
disconnect, deadline, shutdown, and response-stream failure are the signals
that cancel work. Soklet validates the open `inputResponses` wire union, but
applications still own response-key correlation, action handling, accepted
content policy, user binding, sampling limits, and filesystem containment. See
the compile-checked
[application input-security patterns](src/test/java/examples/mcp/McpInputSecurityApplicationPatternsTests.java)
and [deployment guidance](SECURITY.md#mcp-deployment-security).

Trace correlation remains default-off. Configuring a trace-correlation key
enables an exactly-once finish-time `MCP_TRACE_CORRELATION` log event carrying
the bounded pseudonymous token fields; the separate
`logRawValidatedTraceIds(true)` opt-in may add only the validated lowercase MCP
trace ID. Neither mode adds a trace value to metrics. The current snapshot has
all 65 Phase 6 owners frozen and an empty provisional inventory, but it is not
a release claim. The 1,676/0/0/4 result over 462 main and 194 test sources
remains the rate-limit identity/trusted-proxy checkpoint. The independent-
request direction-boundary checkpoint passed 1,678/0/0/4 over 462 main and 195
test sources, with its focused protocol gate at 35/35. The localization-fleet
checkpoint passed Corretto 26 core clean verify at 1,681/0/0/4 over 462 main
and 196 test sources and built the main, sources, and Javadoc artifacts; the
new fixture passes 3/3 and its
related localization regression set passes 24/24. The preceding Corretto 17
core clean-test run passed 1,659/0/0/72 before the rate-limit identity,
independent-request, and localization-fleet runtime test sources were added, so
it remains prior supported-JDK evidence rather than a current 196-source
result. The exact six-scenario smoke soak
passes 6/6 plus its strict verifier; both 39-row development conformance paths, candidate
localization, and the pinned TypeScript/Go snapshot harnesses are also green.
Current post-fix Corretto 21 validation passes 1,682/0/0/4 over the unchanged
462/196 source tree, with the focused terminal/subscription set at 32/32 and
the cross-feature smoke method at 10/10 repeated stress runs.
Subsequent pinned Corretto 21.0.12.9.1 containment revalidation also passes the
exact full suite at 1,682/0/0/4; its focused platform-plus-virtual-thread matrix
passes 30/30 and 20/20 repetitions cover 600 dynamic cases, while the affected
JDK 17 platform-thread matrix passes 15/15. The test-only synchronization waits
for exact cleanup counts without changing counts, timeouts, production, API, or
freeze state; these local results are not immutable release evidence.
Current supported-JDK revalidation on local Amazon Corretto
17.0.20.1+10-LTS passes `mvn -B -ntp clean test` at 1,667/0/0/72 over the
same 462 main and 196 test sources. The two corrected methods pass 2/2 once
and 20/20 across ten combined repetitions. Both corrections are test-only
synchronization: live transport smoke waits for the complete idle snapshot,
and observation containment waits for actual typed failure-log publication
before exact inspection. The original exact counts and timeout assertions
remain; production behavior, public API, and frozen inventories are unchanged.
A later subscription observer-scope revalidation on the same pinned Amazon
Corretto 21.0.12.9.1 toolchain (`java 21.0.12.1`) passes the exact full
`mvn -B -ntp clean test` at 1,682/0/0/4 over the unchanged 462 main and 196
test sources. The affected method passes 1/1 focused and 20/20 repeated runs;
`McpSubscriptionPublicRuntimeTests` plus
`McpSubscriptionRuntimeBoundaryTests` pass 26/26. The test-only correction
sets the per-principal subscription cap to one and holds the recovery
subscription open while the original disconnect observer's exact-once count
is asserted, preventing that recovery request's legitimate finish from
entering the first request's observation phase. No production behavior,
public API, Phase 4/5/6 freeze inventory, timeout, or asserted count changed.
This is local snapshot evidence, not an immutable release-candidate PASS
receipt.
Current JDK 17 application-execution revalidation on local Amazon Corretto
17.0.20.1+10-LTS passes the exact `mvn -B -ntp clean test` at 1,667/0/0/72
over the unchanged 462 main and 196 test sources. The affected method passes
1/1 focused and 20/20 repeated runs, and the full
`McpApplicationExecutionTests` class passes 10/10. The same affected method
also passes 1/1 on the pinned Corretto 21.0.12.9.1 toolchain. The test-only
correction uses an exact post-observer stable fence requiring both
`retainedExchanges == 1` and `queuedCleanups == 1` before inspecting the
dequeued snapshot. Existing timeout bounds and expected counts are unchanged;
production behavior, public API, and the Phase 4/5/6 freeze inventories are
unchanged. This is local snapshot evidence, not immutable release-candidate
evidence.
The checked-in fail-closed
[release validator](release/README.md) still requires reviewed downstream pins
and a checksum-matched immutable candidate; local snapshot results are not
promotion evidence.

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

Soklet's [`Simulator`](https://javadoc.soklet.com/com/soklet/Simulator.html) is available via [`Soklet`](https://javadoc.soklet.com/com/soklet/Soklet.html) to exercise full request/response flows without binding a port. Its modern `startMcpRequest(...)` methods run an asynchronous MCP POST through the real processor and lifecycle while retaining bounded JSON or exact SSE capture off-network; they do not start the configured MCP listener or change public server diagnostics.

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
security controls. The resulting public diagnostics view is immutable, but the two
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

The tenth bounded Phase 6 vertical resolved the full `AMB-003` aggregate
contract and implemented the coherent transport-boundary slice. At that
checkpoint, `McpMetricsSnapshot` had seven getters: five boxed nonnegative
`Long` values plus immutable shutdown and transport-failure maps. The new
getter/builder pairs are `getConnectionsAccepted()`/
`connectionsAccepted(Long)`, `getConnectionsRejected()`/
`connectionsRejected(Long)`, and `getTransportFailures()`/
`transportFailures(Map<MetricsCollector.TransportFailureReason, Long>)`.
The map is defensive, enum-ordered, and sparse in default snapshots.

Configured MCP collectors render the label-free counters
`soklet_mcp_connections_accepted_total` and
`soklet_mcp_connections_rejected_total`, including zeros; a directly ingested
transport event activates the same pair. At that checkpoint these joined the
four prior families for seven rendered aggregate families. MCP failures reuse the single
`soklet_transport_failures_total` family with fixed
`server_type="MCP"` and `reason="<TransportFailureReason>"` labels. HTTP, SSE,
and MCP samples share one HELP/TYPE block, and a filter rejecting every sample
leaves no orphaned metadata. Reset clears both connection counters and the
sparse failure map while configured zero families remain visible; retained
snapshots are immutable. `McpTransportMetricsAggregationTests` covers all 18
reasons, Prometheus/OpenMetrics, filtering, reset, direct ingest, and
post-quiescence concurrent ingest through
`#snapshotContractUsesBoxedConnectionCountsAndImmutableBoundedTransportFailures`,
`#defaultCollectorAggregatesRendersFiltersAndResetsTransportBoundaryFamilies`,
`#sharedTransportFamilyCombinesServerTypesWithSingleMetadataBlock`, and
`#concurrentDirectIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.

The connection events are fieldless and the failure aggregate retains only a
fixed enum reason—never a remote address, request, throwable, header, trace,
token, key, tracestate, baggage, or application-controlled label.

The eleventh bounded Phase 6 vertical implements the contract-fixed,
label-free `ServerStarted` scalar. The boxed, nonnegative
`getServerStarts()`/`serverStarts(Long)` pair brings `McpMetricsSnapshot` to
exactly eight getters and its builder to nine public methods including
`build()`: six boxed `Long` values and two immutable maps. The counter is the
eighth rendered aggregate family. The default
collector increments only the existing fieldless event emitted once per
successfully started listener generation. Failed staged starts and repeated
already-started no-ops contribute none; managed rollback retains its successful
start before the matching stop, and restart counts each fresh generation.

Configured collectors render `soklet_mcp_server_starts_total` at zero. Direct
`ServerStarted` or `ServerStopped` ingest activates the same lifecycle subset,
so a stop-only fresh collector renders zero starts plus its shutdown sample.
Filtering the start sample also removes its HELP/TYPE block. Reset clears the
cumulative count while retaining configured/event-activated zero visibility;
retained snapshots stay immutable. Starts and shutdown totals are not a
conservation pair because a running generation has not stopped. Neither the
fieldless event nor the label-free family carries request, network, endpoint,
method, outcome, throwable, header, trace, token, key, tracestate, baggage, or
application-controlled data. Exact coverage is
`McpServerStartMetricsAggregationTests#snapshotContractUsesBoxedNonnegativeServerStarts`,
`#defaultCollectorAggregatesConfiguredAndDirectServerStartsAcrossRenderFilterAndReset`,
and
`#concurrentDirectServerStartIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.

The twelfth bounded Phase 6 vertical implements the independent, label-free
`RequestAccepted` and `RequestRejected` request-boundary scalars. Boxed,
nonnegative `getRequestsAccepted()`/`requestsAccepted(Long)` and
`getRequestsRejected()`/`requestsRejected(Long)` brought
`McpMetricsSnapshot` at that checkpoint to exactly ten getters and its builder
to 11 public methods including `build()`: eight boxed `Long` values and two
immutable maps.

The accepted event becomes durable only when the bounded protocol processor
accepts `Executor.execute`; an execute rejection or throw discards its
provisional identity entry. Rejected is exact once for a complete Handler
request whose terminal wins before atomic observation-start reservation. A
request can produce both events on a terminal pre-admission path, while execute
failure can produce rejected without retained accepted, so the counters are
not complementary or conserved. They exclude early transport/Microhttp
failure, post-admission outcomes, and handler-capacity rejection.

Configured collectors render paired zero samples for
`soklet_mcp_requests_accepted_total` (`Total MCP requests accepted by the
bounded protocol processor`) and `soklet_mcp_requests_rejected_total` (`Total
MCP requests rejected before admitted semantic handling`). Either directly
ingested event activates both label-free families. Filters remove rejected
family metadata with its sample; OpenMetrics, reset, retained immutable
snapshots, and post-quiescence concurrent ingest preserve the scalar contract.
Reset clears both cumulative counts but preserves configured or event-activated
paired-zero visibility.

The fieldless sources and label-free families retain no request, network
identity, endpoint, method, code, outcome, throwable, header, trace, token,
key, tracestate, baggage, or application-controlled dimension. Exact tests are
`McpRequestAdmissionMetricsAggregationTests#snapshotContractUsesBoxedNonnegativeRequestAdmissionCounts`,
`#defaultCollectorAggregatesConfiguredAndDirectRequestAdmissionEventsAcrossRenderFilterAndReset`,
and
`#concurrentDirectRequestAdmissionIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
Authority is additionally frozen by
`McpHttpServerApplicationExecutionTests#protocol_processor_submission_records_two_accepted_then_one_rejected_outside_request_control_lock`
and
`McpPreAdmissionMetricsEventPublicRuntimeTests#acceptedMalformedRequestEmitsExactProtocolErrorThenRejectionWithoutAdmission`.

The thirteenth bounded Phase 6 vertical implements admitted-request lifecycle
aggregation. The provisional `McpMetricsSnapshot` adds boxed, nonnegative
`getActiveRequests()`, immutable `getRequests()` and
`getRequestDurations()` maps keyed by a public, thread-safe final value created
with
`RequestOutcomeKey.fromDimensions(endpointPath, jsonRpcMethod, outcome)`, and
matching builder methods. The key rejects nulls and empty routed strings but
its public factory does not validate registry membership. Its dimensions use
`getEndpointPath()`, `getJsonRpcMethod()`, and `getOutcome()`. The current surface is 13 getters and
14 public builder methods including `build()`: nine boxed `Long` values and
four immutable maps; completed counts and histograms are independent sparse
maps.

The existing exact `RequestStarted`/`RequestFinished` authority drives
`soklet_mcp_requests_active`, `soklet_mcp_requests_total`, and
`soklet_mcp_request_duration_nanos`. Completed and duration samples use only
bounded `endpoint`, `method`, and lower-snake `outcome`; there are no standalone
start/finish counters. The histogram uses 1, 2, 5, 10, 25, 50, 100, 200, 400,
800, 1,500, 3,000, 7,000, and 15,000 millisecond boundaries plus overflow.
Configured empty state renders only the active gauge at zero; sparse completed
families emit no sample or orphan HELP/TYPE metadata, including when filters
reject all samples. OpenMetrics follows the same projection.

Reset preserves the live gauge and clears completed counts/histograms; a
request crossing reset records its full original duration. Retained snapshots
remain immutable and balanced post-quiescence concurrent ingest is lossless.
This does not promise cross-field atomicity during mutation, repair unmatched
manual events, or impose a cross-map invariant. Runtime keys retain only a
registered endpoint, recognized method or `<unrecognized>`, and fixed outcome;
no request/network identity, raw method, error detail, throwable, header,
trace/token/key, tracestate, baggage, or application telemetry enters the
built-in families. Custom collectors, generic HTTP metrics, logs, application-
created events/keys, and application telemetry remain outside this claim.
Exact tests are
`McpRequestLifecycleMetricsAggregationTests#snapshotContractUsesReferenceTypedImmutableRequestLifecycleState`,
`#defaultCollectorAggregatesRendersAndFiltersRequestLifecycleFamilies`,
`#resetPreservesActiveRequestsAndLateFinishRecordsFullOriginalDuration`, and
`#concurrentBalancedRequestLifecycleIngestIsLosslessAndRetainedSnapshotsRemainImmutable`;
authority/cardinality evidence includes
`McpRequestObservationPublicRuntimeTests#admittedDiscoveryPublishesLifecycleAndMetricsWithoutInterception`,
`#admissionRejectionDoesNotPublishAdmittedRequestObservation`, and
`#distinctTraceMetadataDoesNotCreateMetricDimensionsOrLeakIntoRendering`.

The fourteenth bounded Phase 6 vertical implements request-stream lifecycle
aggregation. The provisional snapshot adds boxed, nonnegative
`getActiveRequestStreams()`, immutable `getRequestStreamDurations()`, and
matching `activeRequestStreams(Long)`/`requestStreamDurations(Map)` builders.
The new public, thread-safe
`RequestStreamTerminationKey(endpointPath, jsonRpcMethod, reason)` validates
non-null/nonempty shape but not application-created registry membership. The
current surface is 15 getters and 16 public builder methods including
`build()`: ten boxed `Long` values and five immutable maps.

Exact `RequestStreamOpened`/`RequestStreamClosed` delivery drives the gauge
`soklet_mcp_request_streams_active` (HELP `Currently active MCP request
streams`) and histogram `soklet_mcp_request_stream_duration_nanos` (HELP `MCP
request-stream duration in nanoseconds`). The stream transition records open
before accepted progress/keepalive observations and the single close before
terminal `RequestFinished`; this is FIFO record/enqueue order, not a universal
cross-thread total order. Histogram dimensions are only
bounded `endpoint`, `method`, and lower-snake `reason`. The ten reasons are
`completed`, `client_disconnected`, `request_canceled`, `deadline_exceeded`,
`write_failed`, `backpressure`, `server_stopped`,
`simulator_capture_item_limit_exceeded`,
`simulator_capture_byte_limit_exceeded`, and `internal_error`; the 13 buckets
are 1, 5, 10, 30, 60, 120, 300, 600, 1,800, 3,600, 7,200, and 14,400 seconds
plus overflow. No standalone open/close counters exist.

Configured collectors and either direct event activate gauge-zero visibility;
the histogram remains sparse with no orphan HELP/TYPE metadata when empty or
fully filtered. Prometheus and OpenMetrics preserve that rule. Reset preserves
the live gauge, clears histograms, and a stream crossing reset records its full
original duration. Retained snapshots are immutable and balanced concurrent
ingest is lossless after quiescence.

Built-in keys retain only registered endpoint, recognized method or
`<unrecognized>`, and fixed reason. No request/network identity, error detail,
throwable, header, trace/token/key, tracestate, baggage, or application
telemetry enters these dimensions. This does not constrain custom collectors,
generic HTTP/SSE metrics, logs, application-created events/keys, or telemetry;
promise cross-field or concurrent-reset atomicity, repair unmatched manual
events, equate metrics with diagnostics, expose a subscription breakdown,
promise canonical order, add OpenTelemetry/trace emission, or prove sustained,
simulator, privacy, release-readiness, or Phase 6 freeze. Exact tests are
`McpRequestStreamLifecycleMetricsAggregationTests#snapshotContractUsesReferenceTypedImmutableRequestStreamLifecycleState`,
`#defaultCollectorAggregatesRendersAndFiltersRequestStreamLifecycleFamilies`,
`#resetPreservesActiveRequestStreamsAndLateCloseRecordsFullOriginalDuration`,
and
`#concurrentBalancedRequestStreamLifecycleIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
Live authority is additionally covered by
`McpProgressPublicRuntimeTests#disconnectCancelsSameFeatureInstanceAndRunsCallback`
and
`McpSubscriptionPublicRuntimeTests#configuredMaximumDurationPublishesExactLifecycleAndMetrics`.

The fifteenth bounded Phase 6 vertical implements subscription lifecycle
aggregation. The provisional snapshot adds boxed, nonnegative
`getActiveSubscriptions()`, immutable `getSubscriptionDurations()`, and
matching `activeSubscriptions(Long)`/`subscriptionDurations(Map)` builders.
The new public, thread-safe
`SubscriptionTerminationKey(endpointPath, reason)` validates non-null/nonempty
shape but not application-created registry membership. The current surface is
17 getters and 18 public builder methods including `build()`: 11 boxed `Long`
values and six immutable maps.

Exact `SubscriptionOpened`/`SubscriptionClosed` delivery drives the gauge
`soklet_mcp_subscriptions_active` (HELP `Currently active MCP subscriptions`)
and histogram `soklet_mcp_subscription_duration_nanos` (HELP `MCP subscription
duration in nanoseconds`). Dimensions are only bounded `endpoint` and
lower-snake `reason`. The ten reasons are `completed`, `client_disconnected`,
`request_canceled`, `deadline_exceeded`, `write_failed`, `backpressure`,
`server_stopped`, `simulator_capture_item_limit_exceeded`,
`simulator_capture_byte_limit_exceeded`, and `internal_error`; the 13 buckets
are 1, 5, 10, 30, 60, 120, 300, 600, 1,800, 3,600, 7,200, and 14,400 seconds
plus overflow. No standalone open/close counters exist.

Produced order is `RequestStreamOpened`, `SubscriptionOpened`, then at
termination `RequestStreamClosed`, `SubscriptionClosed`, and
`RequestFinished`. This is FIFO record/enqueue order, not universal
cross-thread ordering or an atomic relationship between gauges. Configured
collectors and either direct subscription event activate gauge-zero visibility;
the histogram remains sparse without orphan HELP/TYPE metadata when empty or
fully filtered. Prometheus/OpenMetrics, reset preserving the gauge while
clearing histograms, full duration across reset, retained immutability, and
balanced post-quiescence concurrency are covered.

Built-in keys retain only registered endpoint and fixed reason—never method,
resource URI, subscription filter, request/network identity, error detail,
throwable, header, trace/token/key, tracestate, baggage, or application
telemetry. This does not constrain custom collectors, generic HTTP/SSE metrics,
logs, application-created events/keys, or telemetry; promise cross-field or
concurrent-reset atomicity, repair unmatched manual events, equate metrics with
diagnostics, promise canonical order or conservation with stream gauges, add
OpenTelemetry/trace emission, or prove sustained, simulator, comprehensive
privacy, release-readiness, or Phase 6 freeze. Exact tests are
`McpSubscriptionLifecycleMetricsAggregationTests#snapshotContractUsesReferenceTypedImmutableSubscriptionLifecycleState`,
`#defaultCollectorAggregatesRendersAndFiltersSubscriptionLifecycleFamilies`,
`#resetPreservesActiveSubscriptionsAndLateCloseRecordsFullOriginalDuration`,
and
`#concurrentBalancedSubscriptionLifecycleIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
Live authority is additionally covered by
`McpSubscriptionPublicRuntimeTests#configuredMaximumDurationPublishesExactLifecycleAndMetrics`
and `#clientDisconnectReleasesStateAndPublishesExactlyOnce`.

The sixteenth bounded Phase 6 vertical implements independent progress and
cooperative-cancelation counters. The provisional snapshot adds immutable
`Map<EndpointMethodKey, Long> getCancelationsSignaled()` and
`getProgressEmitted()`, with matching `cancelationsSignaled(Map)` and
`progressEmitted(Map)` builders. The public, thread-safe
`EndpointMethodKey(endpointPath, jsonRpcMethod)` rejects null/empty shape while
accepting arbitrary nonempty application-created values. The current surface
is 19 getters and 20 public builder methods including `build()`: 11 boxed
`Long` values and eight immutable maps.

`CancelationSignaled` drives
`soklet_mcp_cancelations_signaled_total{endpoint,method}` with HELP `Total
cooperative MCP request cancelations signaled by endpoint and method`;
`ProgressEmitted` drives
`soklet_mcp_progress_emitted_total{endpoint,method}` with HELP `Total MCP
progress notifications accepted for delivery by endpoint and method`. They are
independent counters, not complements or a conservation equation. The labeled
families remain strictly sparse: configuration alone emits no sample or
HELP/TYPE metadata, and a direct event populates only its own family. Filters
receive exactly `endpoint` and `method`, fully rejected families leave no
orphan metadata, OpenMetrics retains one EOF, and reset clears both maps.
Snapshots defensively copy and preserve explicit application zeros; retained
maps remain immutable and post-quiescence concurrent direct ingest is
lossless.

Live authority is
`McpProgressPublicRuntimeTests#disconnectCancelsSameFeatureInstanceAndRunsCallback`:
two accepted progress events, one cooperative-cancelation event, serialized
collector delivery outside the reporter monitor, and no post-cancel progress.
It does not impose cancelation-before-terminal cross-thread ordering. Built-in
keys retain only registered endpoint and bounded method, never progress
token/value/total/message, cancelation reason, request/network identity,
throwable, header, trace/token/key, tracestate, baggage, or application
telemetry. This does not constrain custom collectors, generic HTTP/SSE metrics,
logs, application-created events/keys, or telemetry; promise cross-map or
concurrent-reset atomicity, canonical order, OpenTelemetry/trace emission,
comprehensive privacy, sustained/simulator evidence, release readiness, or
Phase 6 freeze. Exact tests are
`McpProgressAndCancelationMetricsAggregationTests#snapshotContractUsesSharedImmutableEndpointMethodCounterMaps`,
`#defaultCollectorAggregatesRendersAndFiltersProgressAndCancelationFamilies`,
`#resetClearsSparseProgressAndCancelationCountersWithoutLeavingFamilyMetadata`,
and
`#concurrentDirectProgressAndCancelationIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.

The seventeenth bounded Phase 6 vertical implements fieldless keep-alive
aggregation. Boxed, nonnegative `@NonNull Long getKeepAlivesEmitted()` and
matching `keepAlivesEmitted(Long)` expand the provisional snapshot to 20
getters and 21 public builder methods including `build()`: 12 boxed `Long`
values and eight immutable maps.

Each exact `KeepAliveEmitted` accepted by the shared semantic-event FIFO drives
the label-free `soklet_mcp_keep_alives_emitted_total` counter with HELP `Total
MCP keep-alive comments accepted for delivery`. Configured MCP and a direct
event both activate the family; configured and post-reset state render zero.
Prometheus/OpenMetrics filters see an empty label map, full rejection leaves no
sample or orphan HELP/TYPE metadata, and reset preserves visibility while
clearing the cumulative count. Retained snapshots remain immutable, and
post-quiescence concurrent direct ingest is lossless.

Live authority is bounded by
`McpSubscriptionPublicRuntimeTests#keepAliveAcceptanceSharesStreamTransitionWithCloseObservation`
and
`McpSubscriptionRuntimeBoundaryTests#maximumDurationIsAbsoluteAcrossKeepAlivesAndEvents`.
They freeze accepted wire-observation/stream-transition order and the
exact-one keep-alive boundary in deterministic fixtures; the metric does not
count timer attempts or prove client/intermediary receipt, and has no
conservation relationship with stream, subscription, or terminal events. The
fieldless built-in event retains no request, endpoint, method, remote identity,
duration, reason, throwable, header, trace ID/token/key, tracestate, baggage,
or application label. Exact tests are
`McpKeepAliveMetricsAggregationTests#snapshotContractUsesBoxedNonnegativeKeepAliveCount`,
`#defaultCollectorAggregatesConfiguredAndDirectKeepAlivesAcrossRenderFilterAndReset`,
and
`#concurrentDirectKeepAliveIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
This does not constrain custom collectors, generic HTTP/SSE metrics, logs, or
application telemetry; promise universal cross-thread ordering, delivery or
receipt, cross-field/concurrent-reset atomicity, OpenTelemetry/trace emission,
comprehensive privacy, sustained/simulator evidence, release readiness, or
Phase 6 freeze.

The eighteenth bounded Phase 6 production vertical completes core default MCP
aggregation with immutable `Map<Integer, Long> getProtocolErrors()` and
`Map<EndpointMethodKey, Long> getUnknownMirroredHeaders()`, plus matching
`protocolErrors(Map)` and `unknownMirroredHeaders(Map)` builder methods. The
provisional snapshot now has 22 getters and 23 public builder methods including
`build()`: 12 boxed `Long` values and ten maps. The three fuzz, dormant-
derivation, and metric-dimensionality checkpoints remain separately
unnumbered.

`DefaultMetricsCollector` renders
`soklet_mcp_protocol_errors_total{code}` with HELP `Total client-visible MCP
protocol errors by fixed code` and
`soklet_mcp_unknown_mirrored_headers_total{endpoint,method}` with HELP `Total
unknown MCP mirrored-header occurrences by endpoint and method`. Both are
strictly sparse and independent: configuration alone emits no sample or
HELP/TYPE metadata, one event populates only its own family, complete filter
rejection leaves no orphan metadata, OpenMetrics retains one EOF, and reset
removes both families. Snapshots are defensive and immutable, explicit public
zeros survive construction, and post-quiescence concurrent direct ingestion
is lossless.

Framework production is narrower than public/manual value construction. Live
protocol errors use exactly `-32700`, `-32600`, `-32601`, `-32602`, `-32603`,
`-32020`, `-32021`, `-32022`, `-31999`, and `-31998`, only after successful
client-visible encoding or accepted streamed-terminal reservation. Failed
provisional terminals are discarded; application codes, tool-result `isError`,
and empty-notification HTTP errors are excluded. Unknown-header events occur
once per occurrence under IGNORE or REJECT and retain only registered endpoint
plus a recognized core method or `<unrecognized>`, never header name/value or
raw unrecognized method. Pre-admission errors are request-free; only admitted
fixed errors use the exact admitted context for bounded delivery/failure
attribution.

The two default maps independently retain at most 8,192 keys. Public builder
maps remain uncapped value carriers and accept arbitrary non-null `Integer`
codes and structurally valid nonempty `EndpointMethodKey` values with
nonnegative counts, including explicit zero. Protocol maps use natural Integer
order; no canonical `EndpointMethodKey` order is promised. Built-in dimensions
contain no header identity, request, throwable, payload, remote identity, trace
ID/token/key material, tracestate, baggage, or generic application label.

Exact tests are
`McpProtocolAndUnknownHeaderMetricsAggregationTests#snapshotContractUsesImmutableProtocolAndUnknownHeaderCounterMaps`,
`#defaultCollectorAggregatesRendersAndFiltersProtocolAndUnknownHeaderFamilies`,
`#resetClearsSparseProtocolAndUnknownHeaderCountersWithoutLeavingFamilyMetadata`,
`#manualDimensionRetentionIsIndependentlyBoundedPerFamily`, and
`#concurrentDirectProtocolAndUnknownHeaderIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
Live authority is covered by
`McpPreAdmissionMetricsEventPublicRuntimeTests#acceptedMalformedRequestEmitsExactProtocolErrorThenRejectionWithoutAdmission`,
`#applicationCodesAreExcludedWhileAdmittedFixedErrorsRetainExactRequestContext`,
`#unknownHeaderOccurrencesAreExactRedactedAndMethodBoundedAcrossPolicies`,
`#preAdmissionQuartetDeliveryIsReentrantAndSerializedWithoutCrossRequestOrderClaim`,
`McpHttpServerApplicationExecutionTests#produced_protocol_error_metric_allowlist_is_exact_and_excludes_application_codes`,
and `#failed_stream_terminal_discards_provisional_protocol_error_metric`.
Accepted/unknown/error/rejected and admitted started/error/finished sequences
promise FIFO record/enqueue order only, not universal cross-thread ordering or
conservation.

This constrains built-in MCP event, snapshot, and default-renderer surfaces,
not arbitrary public/manual vocabulary, custom collectors, generic HTTP
callbacks, logs, `Request`, `Throwable`, or application telemetry. It adds no
structured/raw-ID emission, downstream OpenTelemetry mapping, sustained/soak,
simulator or release-candidate proof, and does not freeze Phase 6.

The nineteenth bounded Phase 6 production vertical implements the frozen
downstream metric matrix in unreleased
`com.soklet:soklet-otel:1.4.0-SNAPSHOT`, whose default core baseline is
`com.soklet:soklet:3.6.0-SNAPSHOT`. All 23 `McpMetricsEvent` variants map to
exactly 22 OpenTelemetry instruments: 21 MCP-specific instruments plus the
existing shared transport-failure counter. The mapping uses seven fixed MCP
attributes, lower-snake enum values, the exact 14 request-duration and 12
long-lived-duration finite bucket boundaries in seconds, and the shared
transport attributes `soklet.server.type="mcp"` and
`soklet.failure.reason`. It never adds `error.type` to an MCP transport
failure, and HTTP metric naming strategy choices do not rename MCP metrics.

The required 3.6 migration removes obsolete MCP request/session/SSE span
callbacks, the four legacy `soklet.mcp.sessions.*`/session-duration
instruments, three MCP span-policy knobs, and two MCP span-naming methods. At
that V19 boundary, the reviewed downstream public diff was exactly 15 removed
legacy methods plus the new `didRecordMcpMetricsEvent(McpMetricsEvent)`
callback. Modern MCP lifecycle callbacks then remained inherited no-ops, so
that metric slice emitted no replacement MCP spans; HTTP and SSE tracing
remained intact. A 1.3.1 consumer
that needs the old 3.5.1 MCP tracing model must remain on that historical
artifact pair until it deliberately migrates.

For framework-produced events, the integration adds no dedicated attribute
for a trace or raw request ID, progress token/value, header name/value, request
object, throwable, operation/resource URI, principal/address, tracestate,
baggage, or generic label bag. Manual public event dimensions remain
application-controlled and may contain sensitive text; applications own their
confidentiality and cardinality, and OpenTelemetry SDK series retention
remains SDK-owned. This slice does not claim default-
snapshot/reset/filter/OpenMetrics parity, an SDK series cap, cross-instrument
atomicity or conservation, structured-log emission, modern MCP spans,
sustained cardinality, simulator/release evidence, or Phase 6 freeze.

Exact tests are
`OpenTelemetryMetricsCollectorTests#allTwentyThreeMcpEventsMapToExactTwentyTwoInstrumentsAndTransitions`,
`#mcpInstrumentContractUsesExactKindsUnitsAttributesAndBuckets`,
`#mcpEnumAndManualDimensionsUseExactTypedVocabularyWithoutSensitiveAttributes`,
`#mcpSchemaIgnoresHttpNamingStrategyRemovesLegacySessionsAndPreservesFailureBoundary`,
`#handlesConcurrentMcpMetricEventsWithoutLoss`, and
`OpenTelemetryLifecycleObserverTests#legacyMcpSessionTracingSurfacesRemainAbsentAndModernRequestCallbacksAreImplemented`.
At that point, the complete module suite passed 28/0/0/0 on both JDK 21 and
JDK 26, and main,
sources, Javadoc, and standalone Javadoc packaging is green. Core inventories
remain unchanged: 23/23 event variants and 22 text families, 22 snapshot
getters and 23 builder methods, the exact 31/12 cardinality projection, and
the 32-entry provisional/210-owner union. At that V19 boundary, modern
`McpRequestContext` span parenting, naming, policy, and terminal behavior were
the next contract slice.

The twentieth bounded Phase 6 production vertical implements those modern
admitted-request spans in the same unreleased
`com.soklet:soklet-otel:1.4.0-SNAPSHOT` against
`com.soklet:soklet:3.6.0-SNAPSHOT`. Boxed
`SpanPolicy.recordMcpRequestSpans()` and its builder method default to `true`.
The additive default
`SpanNamingStrategy.mcpRequestSpanName(McpRequestContext)` preserves existing
three-method implementations. Default names are `MCP <method>` for the exact
ten core methods; every other raw context method is `<unrecognized>` in the
name and `rpc.method`, with no original-method attribute. Custom naming remains
application-owned and may inspect the raw context method.

One SERVER span covers each admitted request or notification through any
request stream or subscription to its exact terminal lifecycle callback. Its
only remote parent is validated MCP `_meta.traceparent`/`tracestate`; physical
HTTP headers, ambient OpenTelemetry context, and baggage never backfill it.
Start attributes are exactly `soklet.server.type="mcp"`,
`rpc.system.name="jsonrpc"`, bounded `rpc.method`, and
`soklet.mcp.endpoint`. Existing `client.address` and `soklet.request.id`
controls remain off by default and, when enabled, use only the physical server
request—not the JSON-RPC ID.

Finish always records lower-snake `soklet.mcp.request.outcome`. A non-null
JSON-RPC error records string `rpc.response.status_code` and `error.type` as
the same decimal code and marks ERROR. Without an error, rejected,
application/protocol/internal errors, deadline exceeded, and write failure are
ERROR with lower-snake outcome `error.type`; complete, input-required,
canceled, and client-disconnected remain UNSET without it. Lifecycle
throwables produce no exception event, status, attribute, message, data, or
stack material. Exact duration controls the end timestamp, with plain-end
fallback for overflowing manual duration arithmetic.

Disabled policy emits nothing. Missing/late finishes are no-ops; duplicate
direct starts plainly end the older state, close plainly drains active states,
and a post-publication closed recheck removes and ends the exact state that
raced close. Failures are contained and concurrent contexts stay isolated.
Built-in projection excludes JSON-RPC IDs, request metadata, operation/path/
capability/admission data, baggage, physical HTTP trace headers, error
message/data, throwables, and exception events, except for the intentional MCP
parent and explicitly opted-in physical address/request ID. This adds no
session, stream, or subscription span and proves no custom-namer safety,
structured logging, raw-ID emission, comprehensive privacy, sustained
cardinality, simulator/release readiness, or Phase 6 freeze.

Exact V20 tests are
`OpenTelemetryMcpLifecycleObserverTests#mcpMetadataTraceContextIsTheOnlyRemoteParentAndPreservesTraceState`,
`#mcpSpanUsesExactDefaultAndCustomNamesAttributesAndTerminalSemantics`,
`#allMcpRequestOutcomesMapToExactStatusAndErrorVocabulary`,
`#mcpRequestSpanStaysOpenUntilTerminalFinishAcrossStreamAndSubscriptionLifetimes`,
`#mcpPolicyAndNamingAreModernAdditiveAndLegacySessionControlsRemainAbsent`,
`#mcpTelemetryFailuresAreContainedAndReleaseStateExactlyOnce`,
`#concurrentMcpSpansRemainContextIsolatedAndCloseDrainsEveryState`,
`#mcpSpanProjectionExcludesSensitiveContextAndHttpFallbackCanaries`, and
`OpenTelemetryLifecycleObserverTests#legacyMcpSessionTracingSurfacesRemainAbsentAndModernRequestCallbacksAreImplemented`.
Core authority is
`McpRequestObservationPublicRuntimeTests#successfulToolSharesOneContextAndFinishesExactlyOnce`,
`#traceCaptureUsesOnlyValidMcpMetadataWithoutHttpFallback`,
`#handlerFailurePublishesExactInternalErrorAndImmutableThrowable`,
`#unsupportedNotificationRetainsRawLifecycleMethodAndBoundsMetrics`,
`#throwingObservationCallbacksAreContainedLoggedAndPartitioned`,
`McpRequestPropagationTests#validatedMetadataReachesAdmissionAndToolHandlersInsteadOfHttpTraceHeaders`,
`#invalidOrMistypedMetadataIsOmittedWithoutFallingBackToHttpHeaders`,
`#baggageParsingIsBoundedDecodedAndImmutable`,
`McpSubscriptionPublicRuntimeTests#configuredMaximumDurationPublishesExactLifecycleAndMetrics`,
and `#clientDisconnectReleasesStateAndPublishesExactlyOnce`.

At the V20 boundary, the core 23/23 variants, 22 text families, 22 snapshot getters,
23 builder methods, 12 boxed `Long` values, ten maps, 31/12 canary projection,
32 provisional owners, and 210-owner union were unchanged. V20 added five
declared downstream methods relative to V19; the reviewed historical-to-current
diff was 13 removals/four additions. `MCP-BASE-026` was COMPLETE. `AMB-003` was
RESOLVED CONTRACT 2026-08-10 / CORE IMPLEMENTATION COMPLETE / DOWNSTREAM
METRIC IMPLEMENTATION COMPLETE; `SOK-METRIC-001`, `SOK-METRIC-004`, metric-
only `SOK-TRACE-005`, and `SOK-PRIV-001` remained PARTIAL; and
`SOK-TRACE-004` remained PLANNED. MCP simulator
integration was next.

The twenty-first bounded Phase 6 production vertical implements that simulator
integration through the existing shared `Simulator` host. Its two abstract
`startMcpRequest(...)` methods return a thread-safe `McpSimulation`; seven new
top-level simulation types and `McpSimulationOptions.Builder` define immutable
responses, completion, exact SSE items, body/item enums, and positive capture
bounds. Defaults are 128 pending SSE items and 10,485,760 cumulative bytes.

The simulation is asynchronous and off-network but uses the real MCP
processor, application, stream/subscription, lifecycle, metrics, and terminal
authority. It binds no socket, leaves listener status `STOPPED`, bound address
empty and diagnostics zero, and emits no server/connection/transport event.
The supplied Host, Origin, headers, and body are not repaired. Effective policy
uses the configured host and literal port, so port `0` requires an authority
such as `127.0.0.1:0`; no Host is synthesized.

Repeatable response/completion waits and destructive FIFO item reads expose
defensive JSON/empty-body copies, exact unchunked SSE frames, and immutable
completion. A captured terminal JSON frame consumes one ordinary item and is
also available as completion `terminalMessage` at no second cost. Item capacity
is checked before cumulative bytes; equality is allowed, an offending frame is
excluded, dequeue refunds only a queue slot, and bytes never refund. JSON or
pre-response SSE overflow retains the response head and exact item/byte reason.
The admitted request finishes `CANCELED` with coarse token reason
`SIMULATOR_LIMIT_EXCEEDED`, not a protocol or transport failure.

Cancel, close, and scope exit publish `CLIENT_DISCONNECTED` only if they win
the shared terminal reservation. Cleanup is bounded and idempotent; residual
noncooperative work blocks new simulation and live start until release, while
escaped handles stay readable. Waits reject null/negative values, support zero
polling and overflow-safe large durations, and preserve interruption without
canceling. Accepted JSON, malformed/rejected requests, streams,
subscriptions, keep-alives, and a distinct-ID two-request `input_required`
continuation use the same path. Ordering is FIFO per request, not a universal
cross-request order.

Request headers/body and completion Throwables remain caller-sensitive.
Collections and byte arrays are immutable/defensively copied and carrier
rendering is redacted, but accessors do not establish confidentiality.
Representative exact citations from the full 46-test simulator/API gate are
`McpSimulationPublicApiTests#simulationSurfaceHasExactReferenceNullabilityAndClosedEnums`,
`McpPublicApiReflectionContractTests#phaseSixSimulatorInventoryAndSharedHostDescriptorsAreExact`,
`McpSimulatorPublicRuntimeTests#startMcpRequestRejectsMissingServerConfiguration`,
`#defaultLoopbackHostPolicyRequiresLiteralConfiguredPortZero`,
`#multiRoundTripSimulationContinuesInputRequiredStateToDistinctCompletedRequest`,
`#subscriptionReplayPreservesAcknowledgmentEventAndCancelationOrder`,
`#mcpSimulationCompletionRetainsStreamCaptureFailures`,
`#noncooperativeSimulationCleanupIsBoundedAndPreservesSuppression`,
`#waitOperationsHandleZeroTimeoutInterruptionAndCompletionIdempotently`, and
`McpSimulationCaptureRuntimeTests#cancelAndTerminalRacePublishesOneCoherentFirstWinner`.

At the V21 boundary, `phase-6.includes` held 15 owners, the provisional
inventory held 32, and the reviewed union was 219. The compatibility set was
558 records with
SHA-256
`d40004fa92cc5d095404de2133cf04fcd2b5574e9326eb680f571a017ef33671`;
frozen Phase 4/5 counts and hashes were unchanged. Core metrics remained 23/23
events into 22 families, 22 snapshot getters/23 builder methods, 12 boxed
`Long` values plus ten maps, and the 31/12 canary projection.

At the V21 checkpoint, `SOK-SIM-001` was COMPLETE BOUNDED PHASE 6
IMPLEMENTATION EVIDENCE while every-operation simulator coverage, the
39-scenario suite through simulation, stress/soak, sustained fuzz,
live-network fidelity, comprehensive privacy/security, release provenance,
and Phase 6 freeze remained open. Phase 6 has since frozen after the
localization program; actual scheduled and sustained runs, release-candidate
provenance/conformance, and the other release gates remain open as recorded in
the MCP conformance matrix.

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
changed. At that checkpoint there was no structured-log carrier, field,
emission point, cadence, or new `LogEventType`, and raw trace-ID logging was
unimplemented. No metric, event, diagnostics/
snapshot field, aggregate, label, or wire dimension was added. Tokens remain
pseudonymous high-cardinality operational metadata, not anonymization,
authentication, or authorization inputs. The carrier is not cleared at finish
and has no GC or application-reference lifetime guarantee; an application-
retained request context naturally retains it, while core controls retain only
the current key and expose no history API. This is not comprehensive trace/
baggage redaction, cardinality, privacy/security, aggregate/`AMB-003`,
simulator, release-readiness, or Phase 6 freeze evidence.

A third unnumbered Phase 6 checkpoint was covered by
`McpObservabilityPublicApiTests#metricSchemaHasExactFiniteNonTraceDimensions`
and
`McpRequestObservationPublicRuntimeTests#distinctTraceMetadataDoesNotCreateMetricDimensionsOrLeakIntoRendering`.
It freezes exactly 23 public final event variants, including 11 fieldless
variants. Conventional getters expose only endpoint path, bounded method,
fixed outcome, reason or protocol code, and nonnegative duration. Production
emits registered endpoints, recognized methods or `<unrecognized>`, the fixed
ten codes, and fixed enums; named outer factories still permit arbitrary
application-created nonempty endpoints/methods and non-null codes. Variant
constructors are private, while the nested types remain public for typed
pattern matching. At that
checkpoint, the MCP snapshot was three boxed `Long` values and one immutable
shutdown map. `DefaultMetricsCollector` aggregated only five handler variants
and `ServerStopped`, ignoring and retaining none of the other 17 variants.

Sixteen sequential real requests carrying distinct valid MCP and HTTP trace
IDs, tracestate, baggage, derived tokens, and key canaries leave no value in
built-in MCP events, snapshot state, metric names or labels, Prometheus,
OpenMetrics, filter-observed samples, or reset output. At that checkpoint, the
exact pre-reset MCP sample set was three label-free handler samples plus the
clean shutdown outcome; post-reset it was exactly the three label-free samples.
The production-vertical count remained nine; fuzz registration, dormant
derivation, and metric
dimensionality were the three unnumbered checkpoints. `SOK-TRACE-001/002/003`
were COMPLETE, `SOK-TRACE-004` was PLANNED, `SOK-TRACE-005` was PARTIAL
for metric-dimension inventory/default-collector evidence only, and
`SOK-PRIV-001` was PARTIAL. `SOK-METRIC-001` and `SOK-METRIC-004`
remained PARTIAL; `AMB-003` remained AMBIGUOUS.

That test-only checkpoint changed no production source, public API, API sketch,
owner/signature inventory, family, label, event variant, or wire behavior. It
did not cover custom collectors; generic HTTP `MetricsCollector` callbacks
receiving `Request`, request-target, or `Throwable` values; `LogEvent`,
application callbacks or handler telemetry; arbitrary application event
vocabulary; structured logging or raw-ID emission; future aggregates;
comprehensive trace/baggage redaction; sustained cardinality, fuzz or soak;
simulation, migration, release-candidate provenance, review, or Phase 6
freeze.

Transport aggregation is the tenth production vertical, server-start is the
eleventh, request-boundary aggregation is the twelfth, admitted-request
lifecycle aggregation is the thirteenth, request-stream lifecycle aggregation
is the fourteenth, subscription lifecycle aggregation is the fifteenth,
progress/cancelation aggregation is the sixteenth, keep-alive aggregation is
the seventeenth, and protocol/error-header aggregation is the eighteenth; the
downstream OpenTelemetry metric migration is the nineteenth, modern
admitted-request spans are the twentieth, and bounded off-network MCP
simulation is the twenty-first. The three earlier
checkpoints remain unnumbered. The snapshot remains at 22 getters
and 23 public builder methods including `build()`: 12 boxed `Long` values and
ten maps. The default collector aggregates the full 23/23 event variants across 22 text
families, leaving zero core events unaggregated. The nonsubscription 16-request
gate remains exactly 31 MCP-prefixed samples before reset and 12 after reset
because both final map families are sparse on that clean path. The MCP failure map is empty
on that clean path,
and the built-in MCP plus shared transport rendering continues to exclude every
trace/tracestate/baggage/token/key canary.

The final resolved aggregate subset now implements a fixed-code protocol-error
map and endpoint/method unknown-header map with no header identity. It defines no
standalone start/finish/open/close counters. Configured scalars render zero,
maps/histograms are sparse, and reset preserves five live gauges while clearing
cumulative/map/histogram state. The downstream implementation now maps the
same 23 transitions to 22 OpenTelemetry instruments without changing this
core snapshot or text contract.

At that checkpoint, `SOK-TRACE-005` remained PARTIAL for metric-only evidence;
`SOK-PRIV-001`, `MCP-HTTP-020`, `SOK-METRIC-001`, and `SOK-METRIC-004`
remained PARTIAL; and `SOK-METRIC-002`, `SOK-METRIC-003`, and `SOK-SHUT-002`
were COMPLETE. `AMB-003` was RESOLVED CONTRACT 2026-08-10 / CORE
IMPLEMENTATION COMPLETE / DOWNSTREAM METRIC IMPLEMENTATION COMPLETE,
`MCP-BASE-026` was COMPLETE, and `SOK-TRACE-004` remained PLANNED because
modern spans did not implement structured trace-log emission. That vertical
did not constrain custom collectors or
application telemetry, promise an atomic cross-field snapshot during active
concurrent mutation, add structured-log or raw-ID emission, complete
privacy/cardinality work, or prove every-operation simulation, sustained,
release-readiness, review, or the later Phase 6 freeze.

For MCP shutdowns, `snapshot().getMcpMetrics().getShutdowns()` is an immutable,
enum-ordered `Map<McpShutdownOutcome, Long>`. The default collector omits
unobserved outcomes, returns the map to empty on reset, and emits only
`soklet_mcp_shutdowns_total{outcome="clean"}` or
`soklet_mcp_shutdowns_total{outcome="residual_handlers"}`. Default aggregation
now covers `ServerStarted`, `ServerStopped`, `RequestAccepted`,
`RequestRejected`, `RequestStarted`, `RequestFinished`,
`RequestStreamOpened`, `RequestStreamClosed`, the five handler variants,
`SubscriptionOpened`, `SubscriptionClosed`, `CancelationSignaled`,
`ProgressEmitted`, `KeepAliveEmitted`, `ProtocolError`,
`UnknownMirroredHeader`, and the transport trio.
The later `MCP_TRACE_CORRELATION` implementation completes the bounded
pseudonymous-token and separately opted-in raw-ID structured-log contract;
operator retention, custom collectors/application telemetry, broader privacy
and redaction review, and sustained cardinality/drain evidence remain open.
Phase 6 review and freeze are complete for all 65 owners, and the provisional
inventory is empty.
Here, the remaining fuzz work means scheduled/manual coverage-guided and
sustained execution, not the completed registration and deterministic corpus
replay checkpoint. No such coverage-guided nightly run has occurred, and the
replay is not sustained, coverage, corpus-saturation, privacy, security,
release-readiness, or freeze proof.
The seventh through ninth verticals added no public API, snapshot field,
aggregate family, label, event variant, or wire dimension. The tenth added
three provisional snapshot getters and three matching builder methods; the
eleventh adds one getter/builder pair, the twelfth adds two, the thirteenth adds
three plus `RequestOutcomeKey`, and the fourteenth adds two plus
`RequestStreamTerminationKey`; the fifteenth adds two plus
`SubscriptionTerminationKey`; and the sixteenth adds two plus
`EndpointMethodKey`; the seventeenth adds one provisional getter/builder pair;
and the eighteenth adds two provisional map getter/builder pairs.
The nineteenth changes only the downstream `soklet-otel` artifact and adds no
core event variant, snapshot member, owner, label, or wire dimension.
The twentieth also changes only that downstream artifact and adds five declared
methods relative to V19, with no core inventory change.
The twenty-first adds seven top-level public simulation types,
`McpSimulationOptions.Builder`, and two abstract methods to `Simulator`, while
leaving the metric/snapshot/canary inventories unchanged.
Those Vxx counts remain historical. The current API inventory is 133/36/65
Phase 4/5/6 owners (234 total), all three phases are frozen, and
`api/mcp/provisional.includes` is empty. The release-validation workflow and
fail-closed evidence assembler are implemented, but no immutable candidate run
is claimed. The last full pre-typed-state local evidence was green at core
clean verify 1,671/0/0/4 over 464 main and 193 test sources, JDK 21 static-
analysis `BUILD SUCCESS`, SpotBugs 0, Javadocs, fuzz replay 139/139, and smoke
soak 6/6 plus verifier. After the no-alias greenfield typed-state amendment,
fresh Corretto 26 clean verify passes 1,673/0/0/4 over 462 main and 193 test
sources and builds the main, sources, and Javadoc artifacts. Focused request-
state/runtime tests pass 50/50, reflection/inventory contracts pass 24/24, and
the aggregate API gate verifies 565 incompatibilities, 234 owners, and
1,048/179/422 records. The maintained 179-source API sketch passes Java 17
compilation, Javadoc doclint, and its localization smoke contract. That 1,673
result remains the typed-request-state amendment checkpoint. The 1,676/0/0/4
result over 462 main and 194 test sources remains the rate-limit identity/
trusted-proxy checkpoint. The independent-request direction-boundary result
remains 1,678/0/0/4 over 462 main and 195 test sources, with its focused
protocol gate at 35/35. The localization-fleet checkpoint passed Corretto 26
clean verify at 1,681/0/0/4 over 462 main and 196 test sources and built the
main, sources, and Javadoc artifacts; the new localization fleet fixture
passes 3/3 and its related
localization regression set passes 24/24. The preceding Corretto 17 clean-test
run passed 1,659/0/0/72 before the rate-limit identity, independent-request,
and localization-fleet runtime test sources were added, so it remains prior
supported-JDK evidence rather than a current 196-source result. Other carried-
forward local evidence remains green for
candidate localization, artifact-backed simulator 39/39, pinned live official
CLI 39/39, the website's offline clean-install, lint, and 33-route SSG build,
and OpenTelemetry 36/36. TypeScript and Go are checksum-pinned, `READY`, and
green against the local snapshot. The six reviewed downstream change sets
remain uncommitted local work. They are therefore unpublished and unpinned, so
the manifest continues to carry its old public commits. All four servlet legs
pass 158/158 locally: the default 3.1.1 and 3.6.0-SNAPSHOT legs for both javax
and Jakarta. ToyStore's local 3.6 MCP migration passes 14/14, including six MCP
tests. Its per-request credential proof accepts a valid request, then returns 401 for malformed,
missing, expired, and wrong-audience credentials and 403 for an
insufficient-scope credential; no prior request identity or authorization is
inherited. Its old manifest pin remains blocked until its reviewed local changes
are committed and published, the resulting commit is pinned, and the
immutable-candidate/JDK-25 validation passes.
Barebones compiles and its exact live probes pass locally on a reserved
ephemeral IPv4 loopback port without disturbing the unrelated Docker listener
on port 8080. The port is supplied
through `SOKLET_BAREBONES_LOOPBACK_PORT`. Its source and validator changes
remain uncommitted and unpinned, so its old public pin remains blocked. A
same-version macOS arm64 Corretto 21.0.12.9.1 run passed the full core
`clean test` at 1,681/0/0/4 at the initial JDK 21 gate checkpoint; static
analysis reports `BUILD SUCCESS` with
the existing advisory inventory after the `SelfAssignment` fix, and SpotBugs
reports zero bugs and errors. The checksum-pinned Corretto 21.0.12.9.1
toolchain now drives the `core-jdk-21`, `static-analysis`, and `spotbugs`
release gates. The format-v2 release contract now enumerates exactly 29
ordered gates. Eighteen are dispatch-configured, while five remain
`BLOCKED_HARNESS_MISSING` and the six downstreams remain
`BLOCKED_UNCOMMITTED_LOCAL_MIGRATION`, leaving 11 fail-closed blockers;
`READY` means configured, never passed. The matrix-closure hook is `READY`, but
its checked-in registry deliberately reports `FAILED` while 20 rows remain
`UNRESOLVED`. Scheduled/nightly and sustained history, release scans,
benchmarks, published downstream pins, and immutable-candidate provenance
remain open. `release-scans` remains blocked on its exact scanner/toolchain,
severity-policy, and retained-report contract.
Candidate Javadoc generation/completeness is a configured gate;
deployment of those Javadocs remains post-validation publication work. The
bounded two-listener localization fixture is the Soklet-owned fleet gate;
production multi-host coordination remains application/deployment-owned rather
than an unimplemented core release gate.

The preceding 2026-08-20 protocol/capability golden checkpoint passed 9/9 on
local Corretto 17 and the pinned Corretto 21, 86/86 across the broader
Corretto 17 protocol/capability gate, and both runner and local-simulator self-
tests. Full Corretto 17 clean verify passed 1,671/0/0/72 over 462 main and 196
test sources and built all three JARs. The manifest bound 43 production-
derived messages; expanded-corpus final-tag Ajv validation remained with
candidate conformance because the pinned official-suite checkout was not
locally available. This is local snapshot evidence, not immutable-candidate
evidence.

Those 1,681-test results remain the localization-fleet and initial JDK 21 gate
checkpoints. On the current post-fix tree, the same Corretto 21 release passes
core `clean test` at 1,682/0/0/4 over the unchanged 462 main and 196 test
sources. The focused terminal/subscription regression set passes 32/32, a clean
smoke soak passes 6/6 with its strict verifier and verifier self-test, and the
cross-feature smoke method passes 10/10 repeated stress runs. A fast inline
application stream that has reserved its terminal response now retains
transport-callback ownership when the protocol task returns, so the terminal
write and exact request/stream cleanup cannot be preempted; no timeout or
expected-count bound was relaxed. The subscription activation regression now
checks acknowledgment-then-notification wire order directly instead of
treating asynchronous metric delivery as an activation barrier. Runtime
ordering is unchanged: the acknowledgment is queued, the subscription is
activated, and only then is the response handed to the transport callback.
Current supported-JDK revalidation on local Amazon Corretto
17.0.20.1+10-LTS passes `mvn -B -ntp clean test` at 1,667/0/0/72 over the
same 462 main and 196 test sources. The two corrected methods pass 2/2 once
and 20/20 across ten combined repetitions. Both corrections are test-only
synchronization: live transport smoke waits for the complete idle snapshot,
and observation containment waits for actual typed failure-log publication
before exact inspection. The original exact counts and timeout assertions
remain; production behavior, public API, and frozen inventories are unchanged.

A subsequent containment revalidation on the pinned Amazon Corretto
21.0.12.9.1 toolchain (`java 21.0.12.1`) passes the exact
`mvn -B -ntp clean test` at 1,682/0/0/4 over 462 main and 196 test sources.
The focused platform-plus-virtual-thread containment matrix passes 30/30, and
20/20 complete repetitions cover 600 dynamic cases; the affected JDK 17
platform-thread matrix also passes 15/15. This is test-only synchronization:
containment waits now include the exact expected cleanup count before returning.
Expected cleanup counts, timeout bounds, and assertions are unchanged;
production behavior, public API, and frozen inventories are unchanged. These
local snapshot checks are not immutable-candidate release evidence.

A later subscription observer-scope revalidation on the same pinned Amazon
Corretto 21.0.12.9.1 toolchain (`java 21.0.12.1`) passes the exact full
`mvn -B -ntp clean test` at 1,682/0/0/4 over the unchanged 462 main and 196
test sources. The affected method passes 1/1 focused and 20/20 repeated runs;
`McpSubscriptionPublicRuntimeTests` plus
`McpSubscriptionRuntimeBoundaryTests` pass 26/26. The test-only correction
sets the per-principal subscription cap to one and holds the recovery
subscription open while the original disconnect observer's exact-once count
is asserted, preventing that recovery request's legitimate finish from
entering the first request's observation phase. No production behavior,
public API, Phase 4/5/6 freeze inventory, timeout, or asserted count changed.
This is local snapshot evidence, not an immutable release-candidate PASS
receipt.

Current JDK 17 application-execution revalidation on local Amazon Corretto
17.0.20.1+10-LTS passes the exact `mvn -B -ntp clean test` at 1,667/0/0/72
over the unchanged 462 main and 196 test sources. The affected method passes
1/1 focused and 20/20 repeated runs, and the full
`McpApplicationExecutionTests` class passes 10/10. The same affected method
also passes 1/1 on the pinned Corretto 21.0.12.9.1 toolchain. The test-only
correction uses an exact post-observer stable fence requiring both
`retainedExchanges == 1` and `queuedCleanups == 1` before inspecting the
dequeued snapshot. Existing timeout bounds and expected counts are unchanged;
production behavior, public API, and the Phase 4/5/6 freeze inventories are
unchanged. This is local snapshot evidence, not immutable release-candidate
evidence.

The previous policy/error reconciliation checkpoint was test- and golden-only.
Its exact slice passes 27/27 on the pinned local Corretto 17 and Corretto 21
toolchains, and the adjacent policy regression set passes 59/59 on each.
`McpFinalTagGoldenWireProductionTests` passes 11/11 on each JDK, and the
manifest now binds 48 production-derived messages. An unsigned Corretto 17
`clean verify` passes 1,677/0/0/72 over 462 main and 197 test sources and
builds the main, sources, and Javadoc JARs. No production behavior, public API,
or Phase 4/5/6 freeze inventory changed. At that checkpoint, the canonical
matrix was deliberately `FAILED`: 95 rows were `CORE_COMPLETE`, 116 were
`RELEASE_GATED`, four were `APPLICATION_OWNED`, 18 were `NOT_APPLICABLE`, and
29 remained `UNRESOLVED`. Final-tag Ajv validation of the expanded 48-message
corpus had not been rerun locally and remained owned by candidate conformance.
These are local snapshot checks, not immutable-candidate evidence.

The preceding five-row compatibility reconciliation closed the core rows for
admitted identity versus client self-report, unknown client-extension
fallback, Bearer challenge transport, authorization/CORS response-head
behavior, and legacy session/replay-header containment. A real listener keeps
credential-selected identity authoritative despite forged client metadata.
Valid unknown extension settings remain opaque admission input without
inventing or advertising core support; malformed settings fail before
admission. A safe Bearer challenge can carry an absolute `resource_metadata`
URI and operation scopes, but the application owns their meaning and standards
compliance. The independent CORS goldens cover `Authorization`, modern and
registered MCP headers, `WWW-Authenticate` exposure, exact order and
multiplicity, and fail-closed legacy-header rejection.

The focused compatibility slice passed 33/33 on the pinned local Corretto 17
and Corretto 21 toolchains. The separate authorization/CORS HTTP-head manifest
at `conformance/golden-http-head/authorization-cors/manifest.sha256` binds
three raw production response-head fixtures.
`McpAuthorizationIntegrationTests` contains two test methods: one reads and
verifies those goldens, while the other asserts request and notification
challenge semantics. This separate corpus does not alter the final-schema
corpus, which remains 48 JSON messages with 11 focused
golden tests. An unsigned Corretto 17 `clean verify` passed 1,685/0/0/72 over
462 main and 201 test sources and built the main, sources, and Javadoc JARs.
The only production change was an internal policy-response denylist for legacy
MCP session/replay headers; a negative
production-source inventory confines those names to that denylist. Public API,
signatures, and the Phase 4/5/6 freeze inventories were unchanged. At that
checkpoint, the canonical matrix remained deliberately `FAILED`: 100 rows
were `CORE_COMPLETE`, 116 were `RELEASE_GATED`, four were
`APPLICATION_OWNED`, 18 were `NOT_APPLICABLE`, and 24 were `UNRESOLVED`.
Final-tag Ajv validation of the
expanded 48-message corpus was not rerun locally and remains owned by candidate
conformance. These are local snapshot checks, not immutable-candidate evidence.

The current four-row HTTP-contract reconciliation closes modern-only
`initialize` rejection diagnostics, unsupported classified-notification
handling, universal MCP HTTP `no-store`, and exact request/notification
validation precedence. The separate
`conformance/golden-http-contract/precedence-no-store/manifest.sha256` binds 21
path-sorted canonical complete-response hex fixtures and has SHA-256
`ec1bd3f13c70bec100b18e774bfbdf2d9e574c1d8df99f2acc4b36e85f51702c`.
Four contract tests—three production-listener golden tests and one exhaustive
response-authority inventory—cover compound request first-failure winners,
the separate notification/preflight order, overload/SSE, and every production
response authority. Four initialize-diagnostic tests
cover decoder method preservation, a positive all-stage control, 23 readable-
initialize rejection cases, and the negative diagnostic boundary. Every
golden response carries exactly one `Cache-Control: no-store`, and an
application attempt to author a cacheable replacement fails closed.

The new suites pass 8/8, and the adjacent contract group passes 108/108, on
local Amazon Corretto 17.0.20.1 and local Amazon Corretto 21.0.11. Full clean
test passes 1,693/0/0/72 on Corretto 17 and 1,708/0/0/4 on Corretto 21 over
462 main and 203 test sources; the JDK 21 total includes 15 additional virtual-
thread containment cases. These are local snapshot results, not immutable-
candidate evidence or results from the release-pinned Corretto 21.0.12.9.1
toolchain. A subsequent local Corretto 17 package validation built the main,
sources, and Javadoc JARs after allowing the configured external Javadoc links.
The new corpus is separate from both the unchanged official 48-
message/11-test final-schema JSON corpus and the unchanged three-head/two-test
authorization/CORS corpus. The narrow internal production change preserves a
readable `initialize` method through valid-JSON envelope/ID failures and
attaches only the modern diagnostic; it does not implement initialization or a
handshake. Public API, signatures, and the Phase 4/5/6 freeze inventories are
unchanged. The current canonical matrix remains deliberately `FAILED`: 104
rows are `CORE_COMPLETE`, 116 are `RELEASE_GATED`, four are
`APPLICATION_OWNED`, 18 are `NOT_APPLICABLE`, and 20 remain `UNRESOLVED`.
Final-tag Ajv validation of the 48-message corpus was not rerun locally and
remains owned by candidate conformance.

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
