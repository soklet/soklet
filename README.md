<a href="https://www.soklet.com">
    <picture>
        <source media="(prefers-color-scheme: dark)" srcset="https://cdn.soklet.com/soklet-gh-logo-dark-v2.png">
        <img alt="Soklet" src="https://cdn.soklet.com/soklet-gh-logo-light-v2.png" width="300" height="101">
    </picture>
</a>

### What Is It?

A small [HTTP 1.1 server](https://github.com/ebarlas/microhttp) and route handler for Java, well-suited for building RESTful APIs and broadcasting [Server-Sent Events](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events).<br/><br/>
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
* Support for [Server-Sent Events](https://www.soklet.com/docs/server-sent-events)

### Design Non-Goals

* SSL/TLS (your load balancer should provide TLS termination)
* HTTP streaming
* WebSockets
* Dictate which technologies to use (Guice vs. Dagger, Gson vs. Jackson, etc.)
* "Batteries included" authentication and authorization

### Do Zero-Dependency Libraries Interest You?

Similarly-flavored commercially-friendly OSS libraries are available.

* [Pyranid](https://www.pyranid.com) - makes working with JDBC pleasant
* [Lokalized](https://www.lokalized.com) - natural-sounding translations (i18n) via expression language

### License

[Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0)

### Installation

Soklet is a single JAR, available on Maven Central.

Java 17+ is required.

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
package com.soklet.example;

public class App {
  // Handle HTTP requests
  @Resource
  public static class ExampleResource {
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
          ResponseCookie.with("lastRequest", Instant.now().toString())
            .httpOnly(true)
            .secure(true)
            .maxAge(Duration.ofMinutes(5))
            .sameSite(SameSite.LAX)
            .build()
        ))        
        .build();
    }
  }

  // Start the server and listen on :8080
  public static void main(String[] args) throws IOException {
    // Use out-of-the-box defaults
    SokletConfiguration config = SokletConfiguration.withServer(
      DefaultServer.withPort(8080).build()
    ).build();

    try (Soklet soklet = new Soklet(config)) {
      soklet.start();
      System.out.println("Soklet started, press [enter] to exit");
      System.in.read(); // or Thread.currentThread().join() in containers
    }
  }
}
```

Here we use raw `javac` to build and `java` to run.

This example requires JDK 17+ to be installed on your machine ([or see this example of using Docker for Soklet apps](https://github.com/soklet/barebones-app?tab=readme-ov-file#building-and-running-with-docker)).  If you need a JDK, Amazon provides [Corretto](https://aws.amazon.com/corretto/) - a free-to-use-commercially, production-ready distribution of [OpenJDK](https://openjdk.org/) that includes long-term support.

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
# Acceptable path parameter
% curl -i 'http://localhost:8080/echo/2024-12-31' 
HTTP/1.1 200 OK
Content-Length: 10
Content-Type: text/plain; charset=UTF-8

2024-12-31
```

```shell
# Illegal path parameter
% curl -i 'http://localhost:8080/echo/abc'
HTTP/1.1 400 Bad Request
Content-Length: 21
Content-Type: text/plain; charset=UTF-8

HTTP 400: Bad Request
```

```shell
# Language request body
% curl -i -X POST 'http://localhost:8080/language' -d 'fr-CA'
HTTP/1.1 200 OK
Content-Language: pt-BR
Content-Length: 18
Content-Type: text/plain; charset=UTF-8
Set-Cookie: lastRequest=2024-04-20T16:19:02.115336Z; Max-Age=300; Secure; HttpOnly; SameSite=Lax

francês (Canadá)
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

### What Else Does It Do?

#### Access To Request Data

```java
@GET("/example")
public void example(Request request /* param name is arbitrary */) {
  // Here, it would be HttpMethod.GET
  HttpMethod httpMethod = request.getHttpMethod();
  // Just the path, e.g. "/example"
  String path = request.getPath(); 
  // The path and query parameters, e.g. "/example?test=123"
  String uri = request.getUri();
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
  // Charset as specified by "Content-Type" header, if available
  Optional<Charset> charset = request.getCharset();
  // Content type component of "Content-Type" header, if available
  Optional<String> contentType = request.getContentType();
}
```
#### Request Body Parsing

Configure however you like - here we accept JSON:

```java
SokletConfiguration config = SokletConfiguration.withServer(
  DefaultServer.withPort(8080).build()
).requestBodyMarshaler(new RequestBodyMarshaler() {
  // This example uses Google's GSON
  static final Gson GSON = new Gson();

  @Nonnull
  @Override
  public Optional<Object> marshalRequestBody(
    @Nonnull Request request,
    @Nonnull ResourceMethod resourceMethod,
    @Nonnull Parameter parameter,
    @Nonnull Type requestBodyType
  ) {
    // Let GSON turn the request body into an instance
    // of the specified type.
    //
    // Note that this method has access to all runtime information 
    // about the request, which provides the opportunity to, for example,
    // examine annotations on the method/parameter which might
    // inform custom marshaling strategies.
    return Optional.of(GSON.fromJson(
      request.getBodyAsString().get(), 
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

"Happy Path": a non-exceptional, non-OPTIONS, non-404 request.

```java
SokletConfiguration config = SokletConfiguration.withServer(
  DefaultServer.withPort(8080).build()
).responseMarshaler(new DefaultResponseMarshaler() {
  // Let's use Gson to write response body data
  // See https://github.com/google/gson
  static final Gson GSON = new Gson();

  @Nonnull
  @Override
  public MarshaledResponse forHappyPath(
    @Nonnull Request request,
    @Nonnull Response response,
    @Nonnull ResourceMethod resourceMethod
  ) {
    // Turn response body into JSON bytes with Gson
    Object bodyObject = response.getBody().orElse(null);
    byte[] body = bodyObject == null 
      ? null 
      : GSON.toJson(bodyObject).getBytes(StandardCharsets.UTF_8);

    // To be a good citizen, set the Content-Type header
    Map<String, Set<String>> headers = new HashMap<>(response.getHeaders());
    headers.put("Content-Type", Set.of("application/json;charset=UTF-8"));

    // Tell Soklet: "OK - here is the final response data to send"
    return MarshaledResponse.withStatusCode(response.getStatusCode())
      .headers(headers)
      .cookies(response.getCookies())
      .body(body)
      .build();
  }
}).build();
```

Exceptions:

```java
SokletConfiguration config = SokletConfiguration.withServer(
  DefaultServer.withPort(8080).build()
).responseMarshaler(new DefaultResponseMarshaler() {
  // Let's use Gson to write response body data
  // See https://github.com/google/gson
  static final Gson GSON = new Gson();

  @Nonnull
  @Override
  public MarshaledResponse forThrowable(
    @Nonnull Request request,
    @Nonnull Throwable throwable,
    @Nullable ResourceMethod resourceMethod
  ) {
    // Keep track of what to write to the response
    String message;
    int statusCode;

    // Examine the exception that bubbled out and determine what 
    // the HTTP status and a user-facing message should be.
    // Note: real systems should localize these messages
    switch (throwable) {
      // Soklet throws this exception, a specific subclass
      // of BadRequestException
      case IllegalQueryParameterException ex -> {
        message = String.format("Illegal value '%s' for parameter '%s'",
          ex.getQueryParameterValue().orElse("[not provided]"),
          ex.getQueryParameterName());
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
  }
}).build();
```

Writing bytes to the response:

```java
@GET("/example-image.png")
public MarshaledResponse exampleImage() throws IOException {
  Path imageFile = Path.of("/home/user/test.png");
  byte[] image = Files.readAllBytes(imageFile);
  
  // Use MarshaledResponse to serve "final" bytes over the wire
  return MarshaledResponse.withStatusCode(200)
    .headers(Map.of(
      "Content-Type", Set.of("image/png"),
      "Content-Length", Set.of(String.valueOf(image.length))
    ))
    .body(image)
    .build();
}
```

Redirects:

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

#### Form Handling

Frontend:

```html
<form 
  enctype="application/x-www-form-urlencoded"
  action="https://example.soklet.com/form?id=123"
  method="POST">
  <!-- User can type whatever text they like -->
  <input type="number" name="numericValue" />
  <!-- Multiple values for the same name are supported -->
  <input type="hidden" name="multi" value="1" />
  <input type="hidden" name="multi" value="2" />
  <!-- Names with special characters can be remapped -->
  <textarea name="long-text"></textarea>
  <!-- Note: browsers send "on" string to indicate "checked" -->
  <input type="checkbox" name="enabled"/>
  <input type="submit"/>
</form>
```

Backend:

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
  method="POST">
  <!-- User can type whatever text they like -->
  <input type="text" name="freeform" />
  <!-- Multiple values for the same name are supported -->
  <input type="hidden" name="multi" value="1" />
  <input type="hidden" name="multi" value="2" />
  <!-- Prompt user to upload a file -->
  <p>
    Please attach your document: <input name="doc" type="file" />
  </p>
  <!-- Multiple file uploads are supported -->
  <p>
    Supplement 1: <input name="extra" type="file" />
    Supplement 2: <input name="extra" type="file" />
  </p>  
  <!-- An optional file -->
  <p>
    Optionally, attach a photo: <input name="photo" type="file" />
  </p>  
  <input type="submit" value="Upload" />
</form>
```

Backend:

```java
@POST("/multipart")
public Response multipart(
  @QueryParam Long id,
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

Here's how it might look if you use [Google Guice](https://github.com/google/guice):

```java
// Standard Guice setup
Injector injector = Guice.createInjector(new MyExampleAppModule());

SokletConfiguration config = SokletConfiguration.withServer(
  DefaultServer.withPort(8080).build()
).instanceProvider(new InstanceProvider() {
  @Nonnull
  @Override  
  public <T> T provide(@Nonnull Class<T> instanceClass) {
    // Have Soklet ask the Guice Injector for the instance
    return injector.getInstance(instanceClass);     
  }
}).build();
```

Now, your Resources are dependency-injected just like the rest of your application is:

```java
@Resource
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

Server Start/Stop: execute code immediately before and after server startup and shutdown.

```java
SokletConfiguration config = SokletConfiguration.withServer(
  DefaultServer.withPort(8080).build()
).lifecycleInterceptor(new LifecycleInterceptor() {
  @Override
  public void willStartServer(@Nonnull Server server) {
    // Perform startup tasks required prior to server launch
    MyPayrollSystem.INSTANCE.startLengthyWarmupProcess();
  }

  @Override
  public void didStartServer(@Nonnull Server server) {
    // Server has fully started up and is listening
    System.out.println("Server started.");
  }

  @Override
  public void willStopServer(@Nonnull Server server) {
    // Perform shutdown tasks required prior to server teardown
    MyPayrollSystem.INSTANCE.destroy();    
  }

  @Override
  public void didStopServer(@Nonnull Server server) {
    // Server has fully shut down
    System.out.println("Server stopped.");
  }
}).build();
```

Request Handling: these methods are fired at the very start of request processing and the very end, respectively.

```java
SokletConfiguration config = SokletConfiguration.withServer(
  DefaultServer.withPort(8080).build()
).lifecycleInterceptor(new LifecycleInterceptor() {
  @Override
  public void didStartRequestHandling(
    @Nonnull Request request,
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
    @Nonnull Request request,
    @Nullable ResourceMethod resourceMethod,
    @Nonnull MarshaledResponse marshaledResponse,
    @Nonnull Duration processingDuration,
    @Nonnull List<Throwable> throwables
  ) {
    // We have access to a few things here...
    // * marshaledResponse is what was ultimately sent
    //    over the wire
    // * processingDuration is how long everything took, 
    //    including sending the response to the client
    // * throwables is the ordered list of exceptions
    //    thrown during execution (if any)
    long millis = processingDuration.toNanos() / 1_000_000.0;
    System.out.printf("Entire request took %dms\n", millis);
  }
}).build();                  
```

Request Wrapping: wraps around the whole "outside" of an entire request-handling flow.

```java
// Special scoped value so anyone can access the current Locale.
// For Java < 21, use ThreadLocal instead
public static final ScopedValue<Locale> CURRENT_LOCALE;

// Spin up the ScopedValue (or ThreadLocal)
static {
  CURRENT_LOCALE = ScopedValue.newInstance();
}

SokletConfiguration config = SokletConfiguration.withServer(
  DefaultServer.withPort(8080).build()
).lifecycleInterceptor(new LifecycleInterceptor() {
  @Override
  public void wrapRequest(
    @Nonnull Request request,
    @Nullable ResourceMethod resourceMethod,
    @Nonnull Consumer<Request> requestProcessor
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

Request Intercepting: provides programmatic control over two processing steps.

1. Invoking the appropriate Resource Method to acquire a response
2. Sending the response over the wire to the client

```java
SokletConfiguration config = SokletConfiguration.withServer(
  DefaultServer.withPort(8080).build()
).lifecycleInterceptor(new LifecycleInterceptor() {
  @Override
  public void interceptRequest(
    @Nonnull Request request,
    @Nullable ResourceMethod resourceMethod,
    @Nonnull Function<Request, MarshaledResponse> responseProducer,
    @Nonnull Consumer<MarshaledResponse> responseWriter
  ) {
    // Here's where you might start a DB transaction.
    // (MyDatabase is a hypothetical construct)
    MyDatabase.INSTANCE.beginTransaction();

    // Step 1: Invoke the Resource Method and acquire its response
    MarshaledResponse response = responseProducer.apply(request);

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

Response Writing: monitor the response writing process - sending bytes over the wire - which may terminate exceptionally (e.g. unexpected client disconnect).

```java
SokletConfiguration config = SokletConfiguration.withServer(
  DefaultServer.withPort(8080).build()
).lifecycleInterceptor(new LifecycleInterceptor() {
  @Override
  public void willStartResponseWriting(
    @Nonnull Request request,
    @Nullable ResourceMethod resourceMethod,
    @Nonnull MarshaledResponse marshaledResponse
  ) {
    // Access to marshaledResponse here lets us see exactly
    // what will be going over the wire
    byte[] body = marshaledResponse.getBody().orElse(new byte[] {});
    System.out.printf("About to start writing response with " + 
      "a %d-byte body...\n", body.length);
  }

  @Override
  public void didFinishResponseWriting(
    @Nonnull Request request,
    @Nullable ResourceMethod resourceMethod,
    @Nonnull MarshaledResponse marshaledResponse,
    @Nonnull Duration responseWriteDuration,
    @Nullable Throwable throwable
  ) {
    long millis = processingDuration.toNanos() / 1_000_000.0;
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

Authorize All Origins:

```java
SokletConfiguration config = SokletConfiguration.withServer(server)
  // "Wildcard" (*) CORS authorization. Don't use this in production!
  .corsAuthorizer(new AllOriginsCorsAuthorizer())
  .build();
```

Authorize Whitelisted Origins:

```java
Set<String> allowedOrigins = Set.of("https://www.revetware.com");

SokletConfiguration config = SokletConfiguration.withServer(server)
  .corsAuthorizer(new WhitelistedOriginsCorsAuthorizer(allowedOrigins))
  .build();
```

...or be dynamic:

```java
SokletConfiguration config = SokletConfiguration.withServer(server)
  .corsAuthorizer(new WhitelistedOriginsCorsAuthorizer(
    (origin) -> origin.equals("https://www.revetware.com")
  ))
  .build();
```

Custom CORS logic:

```java
SokletConfiguration config = SokletConfiguration.withServer(server)
  .corsAuthorizer(new CorsAuthorizer() {
    // Any subdomain under soklet.com is permitted
    boolean originMatchesValidSubdomain(@Nonnull Cors cors) {
      return cors.getOrigin().matches("https://(.+)\\.soklet\\.com");
    }

    @Nonnull
    @Override
    public Optional<CorsPreflightResponse> authorizePreflight(
      @Nonnull Request request,
      @Nonnull Map<HttpMethod, ResourceMethod> availableResourceMethodsByHttpMethod
    ) {
      // Requests here are guaranteed to have the Cors value set
      Cors cors = request.getCors().get();

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

    @Nonnull
    @Override
    public Optional<CorsResponse> authorize(@Nonnull Request request) {
      // Requests here are guaranteed to have the Cors value set
      Cors cors = request.getCors().get();

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
@Resource
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
  List<Integer> actualBody = (List<Integer>) response.getBody().get();

  Integer actualLargest = response.getHeaders().get("X-Largest").stream()
    .findAny()
    .map(value -> Integer.valueOf(value))
    .get();

  Instant actualLastRequest = response.getCookies().stream()
    .filter(responseCookie -> responseCookie.getName().equals("lastRequest"))
    .findAny()
    .map(responseCookie -> Instant.parse(responseCookie.getValue().get()))
    .get();

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
@Resource
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

```java
@Test
public void basicIntegrationTest() {
  // Build your app's configuration however you like
  SokletConfiguration config = obtainMySokletConfig();

  // Instead of running in a real HTTP server that listens on a port,
  // a simulator is provided against which you can issue requests
  // and receive responses.
  Soklet.runSimulator(config, (simulator -> {
    // Construct a request.
    // You may alternatively specify query parameters directly in the URI
    // as a query string, e.g. "/hello?name=Mark"
    Request request = Request.with(HttpMethod.GET, "/hello")
      .queryParameters(Map.of("name", Set.of("Mark")))
      .build();

    // Perform the request and get a handle to the response
    MarshaledResponse marshaledResponse = simulator.performRequest(request);
    
    // Verify status code
    Integer expectedCode = 200;
    Integer actualCode = response.getStatusCode();
    Assert.assertEquals("Bad status code", expectedCode, actualCode);

    // Verify response body
    marshaledResponse.getBody().ifPresentOrElse(body -> {
      String expectedBody = "Hello, Mark";
      String actualBody = new String(body, StandardCharsets.UTF_8);
      Assert.assertEquals("Bad response body", expectedBody, actualBody);
    }, () -> {
      Assert.fail("No response body");
    });
  }));
}
```

#### Servlet Integration

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
