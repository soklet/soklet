![Soklet Logo](https://soklet.s3.amazonaws.com/soklet-logo-github.png)

### What Is It?

A small [HTTP 1.1 server](https://github.com/ebarlas/microhttp) and route handler for Java, well-suited for building RESTful APIs.<br/><br/>
Zero dependencies.  Dependency Injection friendly.<br/>
Optionally powered by [JEP 425: Virtual Threads, aka Project Loom](https://openjdk.org/jeps/425).

Soklet is a library, not a framework.

### Why?

The Java web ecosystem is missing a solution that is dependency-free but offers features like Loom support, DI-awareness, and annotation-based request handling.  Soklet aims to fill this void.

### Design Goals

* Main focus: route HTTP requests to Java methods 
* Near-instant startup
* No dependencies
* Plenty of hooks for customization
* Favor immutability
* Small, comprehensible codebase
* Amenable to automated testing

### Design Non-Goals

* Support for Java versions below 16
* SSL/TLS (your load balancer should provide TLS termination)
* HTTP streaming
* WebSockets
* Dictate which technologies to use (Guice vs. Dagger, Gson vs. Jackson, etc.)
* "Batteries included" authentication and authorization

### Future Work

* Servlet API compatibility layer (`javax.servlet`, `jakarta.servlet`)

### Do Zero-Dependency Libraries Interest You?

Similarly-flavored commercially-friendly OSS libraries are available.

* [Lokalized](https://www.lokalized.com) - natural-sounding translations (i18n) via expression language 
* [Pyranid](https://www.pyranid.com) - makes working with JDBC pleasant

### License

[Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0)

Soklet includes code from the following OSS projects:

* [ClassIndex](https://github.com/atteo/classindex) by [Sławek Piotrowski](https://github.com/sentinelt) - Apache 2.0 License
* [Microhttp](https://github.com/ebarlas/microhttp) by [Elliot Barlas](https://github.com/ebarlas) - MIT License

### Maven Installation

```xml
<dependency>
  <groupId>com.soklet</groupId>
  <artifactId>soklet</artifactId>
  <version>2.0.0-SNAPSHOT</version>
</dependency>
```

### Direct Download

If you don't use Maven, you can drop [soklet-2.0.0-SNAPSHOT.jar](https://repo1.maven.org/maven2/com/soklet/soklet/2.0.0-SNAPSHOT/soklet-2.0.0-SNAPSHOT.jar) directly into your project.  That's all you need!

## App Startup

Soklet applications are regular Java applications - there is no Servlet container.

```java
class App {
  public static void main(String[] args) throws Exception {
    int port = 8080;

    // Bare-bones: use built-in MicrohttpServer and don't change the default configuration
    SokletConfiguration configuration = new SokletConfiguration.Builder(
        new MicrohttpServer.Builder(port).build())
      .build();

    try (Soklet soklet = new Soklet(configuration)) {
      soklet.start();
      System.out.printf("Soklet started at http://localhost:%d\n", port);
      System.out.printf("Press [enter] to exit\n");
      System.in.read();
    }
  }
}
```

## Resources

For Soklet to be useful, one or more classes annotated with `@Resource` (hereafter _Resources_) are required, which use annotation metadata to declare how HTTP inputs - methods, URL paths, query parameters, cookies, and so forth - map to Java methods (hereafter _Resource Methods_). 

Soklet detects Resources using a compile-time annotation processor and constructs a lookup table to avoid expensive classpath scans during startup.

When an HTTP request arrives, Soklet determines the appropriate Resource Method to invoke based on HTTP method and URL path pattern matching.  Parameter values are injected using the heuristics described below.

Resource Methods may return results of any type - your [ResponseMarshaler](#response-marshaler) runs downstream and is responsible for converting the returned objects to bytes over the wire.

```java
@Resource
class ExampleResource {
  // You can name your methods whatever you like and return whatever you like (or void).
  @GET("/")
  public String index() {
    return "Hello, world!";
  }
	
  // Curly-brace syntax is used to denote path parameters.
  // All parameters in a path must have unique names and have corresponding 
  // @PathParameter-annotated Java method parameters.
  // 
  // This URL might look like /example/123
  @GET("/example/{placeholder}")
  public Response examplePlaceholder(@PathParameter Integer placeholder) {
    return new Response.Builder(204)
      .headers(Map.of("X-Placeholder-Header", Set.of(String.valueOf(placeholder))))
      .build();
  }
	
  // You may accept query parameters by using the @QueryParameter annotation.
  // Use Optional<T> if the query parameter is not required.
  // If multiple instances of the query parameter are permitted, use List<T>. 
  //
  // See the ValueConverter documentation for details on how Soklet marshals
  // strings to "complex" types like java.time.LocalDate, and how you can 
  // customize this behavior.
  // 
  // By default, parameter names are determined by reflection.
  // You may override this behavior by passing a name to the annotation,
  // e.g. @QueryParameter("value").
  //
  // This URL might look like /example/params?date=2022-09-21&value=ABC&value=123
  @GET("/example/params")
  public Response params(@QueryParameter LocalDate date) {
                         @QueryParameter("value") Optional<List<String>> values) {
    return new Response.Builder()
      .body(String.format("date=%s, values=%s", date, values))
      .build();
  }

  // The @FormParameter annotation supports application/x-www-form-urlencoded values.
  //
  // @RequestCookie exposes java.net.HttpCookie representations of cookies.
  //
  // The @RequestBody annotation can be applied to any parameter of type
  // String or byte[].
  //
  // If you specify a parameter of type com.soklet.core.Request, Soklet will
  // provide the request so you can directly examine its contents.
  //
  // For any parameter type that Soklet does not recognize, it will ask the
  // configured InstanceProvider to vend an instance.  This is particularly
  // useful if your application is built using Dependency Injection.
  //
  // Here, MyExampleJsonParser and MyExampleBackend are hypothetical types
  // in your application.
  @POST("/another/example/post")
  public Response formPost(Request request,
                           @RequestBody String requestBody, 
                           @FormParameter("attr") Optional<String> attribute,
                           @RequestCookie("gat") HttpCookie analyticsCookie,
                           MyExampleJsonParser jsonParser,
                           MyExampleBackend backend) {
    // Assemble some data to pass to our example backend
    String analyticsId = analyticsCookie.getValue();
    Locale locale = request.getLocales().stream().findFirst().get();
    MyExampleType exampleType = jsonParser.parse(requestBody, MyExampleType.class);
    
    backend.createRecord(analyticsId, locale, exampleType, attribute.orElse(null));

    // The response builder has a convenience shorthand for performing redirects.
    // You could alternatively do this "by hand" by setting HTTP status and headers appropriately.
    return new Response.Builder(RedirectType.HTTP_307_TEMPORARY_REDIRECT, "/")
      .cookies(Set.of(new HttpCookie("post-attribute-value", attribute.orElse("none"))))
      .build();
  }
}
```

## Configuration

All of Soklet's components are programmatically pluggable via the [SokletConfiguration](https://www.soklet.com/javadoc/com/soklet/SokletConfiguration.html) builder.

Components you'll likely want to customize are:

* [Server](#server) - handles HTTP 1.1 requests and responses
* [ResponseMarshaler](#response-marshaler) - turns Java objects into bytes to send over the wire
* [InstanceProvider](#instance-provider) - creates class instances on your behalf
* [LifecycleInterceptor](#lifecycle-interceptor) - provides hooks to customize phases of request/response processing
* [ValueConverters](#value-converters) - convert input strings (e.g. query parameters) to Java types (e.g. [LocalDateTime](https://docs.oracle.com/en/java/javase/18/docs/api/java.base/java/time/LocalDateTime.html))
* [CorsAuthorizer](#cors-authorizer) - determines whether to accept or reject [CORS](https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS) requests

"Experts only" components are:

* [RequestMethodResolver](https://www.soklet.com/javadoc/com/soklet/core/RequestMethodResolver.html) - determines how to map HTTP requests to Resource Methods 
* [ResourceMethodParameterProvider](https://www.soklet.com/javadoc/com/soklet/core/ResourceMethodParameterProvider.html) - determines how to inject appropriate parameter values when invoking Resource Methods

Here's an example configuration for an API that serves JSON responses.

```java
int port = 8080;

// This example uses Gson to turn Java objects into JSON - https://github.com/google/gson
Gson gson = new Gson();

SokletConfiguration configuration = new SokletConfiguration.Builder(
  // Use the default Microhttp Server
  new MicrohttpServer.Builder(port).build()
)

// Hook into lifecycle events to log/customize behavior
.lifecycleInterceptor(new LifecycleInterceptor() {
  @Override
  public void didStartRequestHandling(@Nonnull Request request,
                                      @Nullable ResourceMethod resourceMethod) {
    // Soklet received the request and figured out which Java method
    // it maps to (might be none in the case of a 404)
    System.out.printf("Received %s, which maps to %s\n", request, resourceMethod);
  }

  @Override
  public void didFinishRequestHandling(@Nonnull Request request,
                                       @Nullable ResourceMethod resourceMethod,
                                       @Nonnull MarshaledResponse marshaledResponse,
                                       @Nonnull Duration processingDuration,
                                       @Nonnull List<Throwable> throwables) {
    System.out.printf("Finished processing %s\n", request);
  }
})    
    
// Your Response Marshaler provides the response body bytes, headers, and cookies 
// that get sent back over the wire.  It's your opportunity to turn raw data into
// JSON, XML, Protocol Buffers, etc.
//
// There are other overridable methods to customize marshaling for 404s, exceptions,
// CORS, and OPTIONS handling.  The DefaultResponseMarshaler provides sensible defaults for those.
// See the Response Marshaler section for more details.
.responseMarshaler(new DefaultResponseMarshaler() {
  @Nonnull
  @Override
  public MarshaledResponse forHappyPath(@Nonnull Request request,
                                        @Nonnull Response response,
                                        @Nonnull ResourceMethod resourceMethod) {
    // Ask Gson to turn the Java response body object into JSON bytes
    Object bodyObject = response.getBody().orElse(null);    
    byte[] body = bodyObject == null ? null : gson.toJson(bodyObject).getBytes(StandardCharsets.UTF_8);

    // Tack on the appropriate Content-Type to the existing set of headers
    Map<String, Set<String>> headers = new HashMap<>(response.getHeaders());
    headers.put("Content-Type", Set.of("application/json;charset=UTF-8"));

    // This value is what is ultimately written to the HTTP response
    return new MarshaledResponse.Builder(response.getStatusCode())
      .headers(headers)
      .cookies(response.getCookies()) // Pass through any cookies as-is
      .body(body)
      .build();
  }
})

// "Wildcard" CORS authorization (don't use this in production!)
.corsAuthorizer(new AllOriginsCorsAuthorizer())
.build();

// OK, start up
try (Soklet soklet = new Soklet(configuration)) {
  soklet.start();
  System.in.read();
}
```

### Server

Soklet provides an embedded version of [Microhttp](https://github.com/ebarlas/microhttp) out-of-the-box in the form of [MicrohttpServer](https://www.soklet.com/javadoc/com/soklet/core/impl/MicrohttpServer.html).

```java
// The only required configuration is port number
Server server = new MicrohttpServer.Builder(8080 /* port */)
  // Host on which we are listening
  .host("0.0.0.0")
  // The number of connection-handling event loops to run concurrently 
  .concurrency(Runtime.getRuntime().availableProcessors())
  // How long to permit a request to process before timing out
  .requestTimeout(Duration.ofSeconds(60))
  // How long to block waiting for the socket's channel to become ready - if zero, block indefinitely
  .socketSelectTimeout(Duration.ofMillis(100))
  // How long to wait for request handler threads to complete when shutting down
  .shutdownTimeout(Duration.ofSeconds(5))
  // The biggest request we permit clients to make  
  .maximumRequestSizeInBytes(1_024 * 1_024)
  // Requests are read into a byte buffer of this size
  .socketReadBufferSizeInBytes(1_024 * 64)
  // The maximum number of pending connections on the socket (values < 1 use JVM platform default)
  .socketPendingConnectionLimit(0)
  // Handle server logging statements
  .logHandler(new LogHandler() { ... })
  // Vend an ExecutorService that is used to run our Soklet event loops
  .eventLoopExecutorServiceSupplier(() -> Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()))
  // Vend an ExecutorService that is used to service HTTP requests.
  // For Loom/Virtual Threads - it makes sense to have 1 virtual thread per request.
  // For non-Loom operation - you will likely want a fixed-size pool of native threads.
  .requestHandlerExecutorServiceSupplier(() -> Executors.newVirtualThreadPerTaskExecutor())
  .build();

// Use our custom server
SokletConfiguration configuration = new SokletConfiguration.Builder(server).build();
```

### Response Marshaler

Soklet's [ResponseMarshaler](https://www.soklet.com/javadoc/com/soklet/core/ResponseMarshaler.html) specifies how a "logical" response (Java object) is written to bytes over the wire.

Hooks are provided for these scenarios:

* "Happy path" - a non-exceptional, non-OPTIONS, non-CORS request
    * [`ResponseMarshaler::forHappyPath`](https://www.soklet.com/javadoc/com/soklet/core/ResponseMarshaler.html#forHappyPath(com.soklet.core.Request,com.soklet.core.Response,com.soklet.core.ResourceMethod))
* Uncaught exception
    * [`ResponseMarshaler::forException`](https://www.soklet.com/javadoc/com/soklet/core/ResponseMarshaler.html#forException(com.soklet.core.Request,java.lang.Throwable,com.soklet.core.ResourceMethod))    
* No matching Resource Method (HTTP 404)
    * [`ResponseMarshaler::forNotFound`](https://www.soklet.com/javadoc/com/soklet/core/ResponseMarshaler.html#forNotFound(com.soklet.core.Request))
* Method not allowed (HTTP 405)
    * [`ResponseMarshaler::forMethodNotAllowed`](https://www.soklet.com/javadoc/com/soklet/core/ResponseMarshaler.html#forMethodNotAllowed(com.soklet.core.Request,java.util.Set))    
* HTTP OPTIONS
    * [`ResponseMarshaler::forOptions`](https://www.soklet.com/javadoc/com/soklet/core/ResponseMarshaler.html#forOptions(com.soklet.core.Request,java.util.Set))
* CORS 
    * [`ResponseMarshaler::forCorsPreflightAllowed`](https://www.soklet.com/javadoc/com/soklet/core/ResponseMarshaler.html#forCorsAllowed(com.soklet.core.Request,com.soklet.core.CorsPreflightResponse))
    * [`ResponseMarshaler::forCorsPreflightRejected`](https://www.soklet.com/javadoc/com/soklet/core/ResponseMarshaler.html#forCorsRejected(com.soklet.core.Request))
    * [`ResponseMarshaler::forCorsAllowed`](https://www.soklet.com/javadoc/com/soklet/core/ResponseMarshaler.html#forCorsAllowed(com.soklet.core.Request,com.soklet.core.CorsResponse,com.soklet.core.MarshaledResponse))

Normally, you'll want to extend [DefaultResponseMarshaler](src/main/java/com/soklet/core/impl/DefaultResponseMarshaler.java) because it provides sensible defaults for things like CORS, OPTIONS, and 404s/405s.  This way you can stay focused on how your application writes happy path and exception responses.  For example:

```java
// This example uses Gson to turn Java objects into JSON - https://github.com/google/gson
Gson gson = new Gson();

SokletConfiguration configuration = new SokletConfiguration.Builder(server)
  .responseMarshaler(new DefaultResponseMarshaler() {
    @Nonnull
    @Override
    public MarshaledResponse forHappyPath(@Nonnull Request request,
                                          @Nonnull Response response,
                                          @Nonnull ResourceMethod resourceMethod) {
      // Ask Gson to turn the Java response body object into JSON bytes
      Object bodyObject = response.getBody().orElse(null);    
      byte[] body = bodyObject == null ? null : gson.toJson(bodyObject).getBytes(StandardCharsets.UTF_8);

      // Tack on the appropriate Content-Type to the existing set of headers
      Map<String, Set<String>> headers = new HashMap<>(response.getHeaders());
      headers.put("Content-Type", Set.of("application/json;charset=UTF-8"));

      // This value is what is ultimately written to the HTTP response
      return new MarshaledResponse.Builder(response.getStatusCode())
        .headers(headers)
        .cookies(response.getCookies()) // Pass through any cookies as-is
        .body(body)
        .build();
    }

    @Nonnull
    @Override
    public MarshaledResponse forException(@Nonnull Request request,
                                          @Nonnull Throwable throwable,
                                          @Nullable ResourceMethod resourceMethod) {
      int statusCode = 500;
      String message = "Internal server error";

      // Your application likely has exceptions that are designed to "bubble out", e.g.
      // input validation errors.  This is where to trap and customize your response
      if(throwable instanceof MyExampleValidationException e) {
        statusCode = 422;
        message = e.getExampleUserFriendlyErrorMessage();
      }

      // Construct an object to send as the response body
      Map<String, Object> bodyObject = new HashMap<>();
      bodyObject.put("message", message);

      // Ask Gson to turn the Java response body object into JSON bytes    
      byte[] body = gson.toJson(bodyObject).getBytes(StandardCharsets.UTF_8);

      return new MarshaledResponse.Builder(statusCode)
        .headers(Map.of("Content-Type", Set.of("application/json; charset=UTF-8")))
        .body(body)
        .build();
    }  
  })
  .build();
```

### Instance Provider

Soklet creates instances of Resource classes and other types in order to invoke methods on them on your behalf.  To do this, it delegates to the configured [InstanceProvider](https://www.soklet.com/javadoc/com/soklet/core/InstanceProvider.html).
<br/><br/>
Here's a naïve implementation that assumes the presence of a default constructor.

```java
SokletConfiguration configuration = new SokletConfiguration.Builder(server)
  .instanceProvider(new InstanceProvider() {
    @Override
    public <T> T provide(@Nonnull Class<T> instanceClass) {
      // Use vanilla JDK reflection, and create a new instance every time
      try {        
        return instanceClass.getDeclaredConstructor().newInstance();
      } catch (Exception e){
        throw new RuntimeException(e);
      }
    }
  }).build();
```

In practice, you will likely want to tie in to whatever Dependency Injection library your application uses and have
the DI infrastructure vend your instances.<br/><br/>
Here's how it might look if you use [Google Guice](https://github.com/google/guice):

```java
Injector injector = Guice.createInjector(new MyExampleAppModule());

SokletConfiguration configuration = new SokletConfiguration.Builder(server)
    .instanceProvider(injector::getInstance)
  }).build();
```

Now, your Resources are dependency-injected just like the rest of your application is:

```java
@Resource
class WidgetResource {
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

### Lifecycle Interceptor

The [LifecycleInterceptor](https://www.soklet.com/javadoc/com/soklet/core/LifecycleInterceptor.html) provides a set of well-defined hooks into request processing.
<br/>
Useful for things like:

* Logging requests and responses
* Performing authentication and authorization
* Modifying requests/responses before downstream processing occurs
* Wrapping downstream code in a database transaction 

This is similar to the [Jakarta EE Servlet Filter](https://jakarta.ee/specifications/platform/9/apidocs/jakarta/servlet/filter) concept, but provides additional functionality beyond "wrap the whole request".

```java
SokletConfiguration configuration = new SokletConfiguration.Builder(server)
  .lifecycleInterceptor(new LifecycleInterceptor() {
    @Override
    public void interceptRequest(@Nonnull Request request,
                                 @Nullable ResourceMethod resourceMethod,
                                 @Nonnull Function<Request, MarshaledResponse> requestHandler,
                                 @Nonnull Consumer<MarshaledResponse> responseHandler) {
      // Similar to a Servlet Filter...let normal request processing finish
      MarshaledResponse marshaledResponse = requestHandler.apply(request);

      // Add a snazzy header to all responses before they are sent over the wire.
      // MarshaledResponse is immutable, so we use a copy-builder to mutate
      marshaledResponse = marshaledResponse.copy()
        .headers((mutableHeaders) -> {
          mutableHeaders.put("X-Powered-By", Set.of("My Amazing API"));
        }).finish();

      // Let downstream processing finish using our modified marshaledResponse
      responseHandler.accept(marshaledResponse);
    }	

    @Override
    public void didStartRequestHandling(@Nonnull Request request,
                                        @Nullable ResourceMethod resourceMethod) {
      // Useful for tracking when a request comes in
    }

    @Override
    public void didFinishRequestHandling(@Nonnull Request request,
                                         @Nullable ResourceMethod resourceMethod,
                                         @Nonnull MarshaledResponse marshaledResponse,
                                         @Nonnull Duration processingDuration,
                                         @Nonnull List<Throwable> throwables) {
      // Useful for tracking when a request has fully finished processing.

      // Why a list of throwables?
      // For example, an exception might occur during the normal flow of execution,
      // and then another might occur when attempting to write an error response
    }    

    @Override
    public void willStartServer(@Nonnull Server server) {}

    @Override
    public void didStartServer(@Nonnull Server server) {}

    @Override
    public void willStopServer(@Nonnull Server server) {}

    @Override
    public void didStopServer(@Nonnull Server server) {}

    @Override
    public void willStartResponseWriting(@Nonnull Request request,
                                         @Nullable ResourceMethod resourceMethod,
                                         @Nonnull MarshaledResponse marshaledResponse) {}

    @Override
    public void didFinishResponseWriting(@Nonnull Request request,
                                         @Nullable ResourceMethod resourceMethod,
                                         @Nonnull MarshaledResponse marshaledResponse,
                                         @Nonnull Duration responseWriteDuration,
                                         @Nullable Throwable throwable) {}
  }).build();
```


### Value Converters

A [ValueConverter](https://www.soklet.com/javadoc/com/soklet/converter/ValueConverter.html) is how Soklet marshals one type into another - for example, a query parameter is a `String` but it's useful to declare that your Resource Method accepts a `LocalDate` instead of parsing it "by hand" every time.  For example:

```java
@Resource
class WidgetResource {
  // e.g. /widgets/123?date=2022-09-30&time=15:45
  // ValueConverters take care of String->Long, String->LocalDate, String->LocalTime
  // so you can focus on business logic.  Also simplifies testing...
  @GET("/widgets/{widgetId}")
  public Optional<Widget> widget(@PathParameter Long widgetId,
                                 @QueryParameter LocalDate date,
                                 @QueryParameter LocalTime time) {
    return widgetService.findWidget(widgetId, date, time);
  }
}
```

The [ValueConverterRegistry](https://www.soklet.com/javadoc/com/soklet/converter/ValueConverterRegistry.html) manages a set of these converters, and its default constructor provides a set of sensible defaults that is sufficient for most cases.
<br/><br/>
However, you might have special types that you'd like to have Soklet convert on your behalf.  Just supplement your `ValueConverterRegistry` with any `ValueConverter` instances you need.

```java
// A registry with useful default converters
ValueConverterRegistry valueConverterRegistry = new ValueConverterRegistry();

// Add a custom converter for a special type
valueConverterRegistry.add(new ValueConverter<String, MyExampleType>() {
  @Nullable
  @Override
  public MyExampleType convert(@Nullable String from) throws ValueConversionException {
    if(from == null)
      return null;
				
    // Whatever custom logic you need
    return MyExampleType.fromString(from);
  }

  @Nonnull
  @Override
  public Type getFromType() {
    return String.class;
  }

  @Nonnull
  @Override
  public Type getToType() {
    return MyExampleType.class;
  }
});

// Plug in your custom registry so Soklet can use it
SokletConfiguration configuration = new SokletConfiguration.Builder(server)
  .valueConverterRegistry(valueConverterRegistry)
  .build();
```

Now, your Resource Methods can enjoy custom marshaling for `MyExampleType`.

```java
@Resource
class WidgetResource {
  @GET("/widgets")
  public List<Widget> widgets(@QueryParameter MyExampleType example) {
    return widgetService.findWidgets(example);
  }
}
```

### CORS Authorizer

For [CORS requests](https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS), Soklet will consult its configured [CorsAuthorizer](https://www.soklet.com/javadoc/com/soklet/core/CorsAuthorizer.html) to determine how to respond.
<br/>
Unless configured differently, Soklet will use its [DefaultCorsAuthorizer](https://www.soklet.com/javadoc/com/soklet/core/impl/DefaultCorsAuthorizer.html), which does not respond to CORS requests.

#### All Origins (Testing Only!)

This will allow all CORS requests regardless of origin.  Useful for local development and experimentation.

```java
SokletConfiguration configuration = new SokletConfiguration.Builder(server)
  // "Wildcard" CORS authorization (don't use this in production!)
  .corsAuthorizer(new AllOriginsCorsAuthorizer())
  .build();
```

#### Whitelisted Origins

This is usually what you want in a production system - a whitelisted set of origins from which to allow requests.

```java
Set<String> allowedOrigins = Set.of("https://www.revetware.com");

SokletConfiguration configuration = new SokletConfiguration.Builder(server)
  .corsAuthorizer(new WhitelistedOriginsCorsAuthorizer(allowedOrigins))
  .build();
```

#### Custom Handling

If none of the out-of-the-box [CorsAuthorizer](https://www.soklet.com/javadoc/com/soklet/core/CorsAuthorizer.html) implementations fit your use-case, it's straightforward to roll your own.

```java
SokletConfiguration configuration = new SokletConfiguration.Builder(server)
  .corsAuthorizer(new CorsAuthorizer() {
    // Any subdomain under myexampledomain.com is permitted
    boolean originMatchesMyCustomSubdomains(Cors cors) {
      return cors.getOrigin().matches("https://(.*)\\.myexampledomain\\.com");
    }
				
    @Override
    public Optional<CorsResponse> authorize(@Nonnull Request request) {
      Cors cors = request.getCors().get();

      // Only permit according to special rules
      if (originMatchesMyCustomSubdomains(cors))
        return Optional.of(new CorsResponse.Builder(cors.getOrigin())
          .accessControlExposeHeaders(Set.of("*"))
          .build());

      return Optional.empty();
    }
		
    @Override
    public Optional<CorsPreflightResponse> authorizePreflight(Request request,
                                                              Set<HttpMethod> availableHttpMethods) {
      Cors cors = request.getCors().get();

      // Only permit according to special rules
      if (originMatchesMyCustomSubdomains(cors))
        return Optional.of(new CorsPreflightResponse.Builder(cors.getOrigin())
          .accessControlAllowMethods(availableHttpMethods)
          .accessControlAllowHeaders(Set.of("*"))
          .accessControlAllowCredentials(true)
          .accessControlMaxAge(Duration.ofMinutes(10))        
          .build());

      return Optional.empty();
    }
  })
  .build();
```

### Log Handler

If you'd like to handle any of Soklet's internal log events, you can provide your own [LogHandler](https://www.soklet.com/javadoc/com/soklet/core/LogHandler.html) implementation.

For example, if your application uses [Logback](https://logback.qos.ch/) and/or [SLF4J](https://www.slf4j.org/), you will likely want to log messages using those.

Below we present a basic implementation that writes to stdout/stderr.

```java
SokletConfiguration configuration = new SokletConfiguration.Builder(server)
  .logHandler(new LogHandler() {
    @Override
    public void logDebug(@Nonnull String message) {
      System.out.println(message);
    }

    @Override
    public void logError(@Nonnull String message) {
      System.err.println(message);
    }

    @Override
    public void logError(@Nonnull String message,
                         @Nonnull Throwable throwable) {
      System.err.println(message);
      throwable.printStackTrace();
    }
  })
  .build();
```

## Common Usage Patterns

Every system is different, but there are frequently recurring patterns.

We present how these pattern implementations might look in Soklet applications.

### Authentication and Authorization

Request headers and cookies are common ways to pass authentication information - for example, as a [JWT](https://jwt.io).

The appropriate place to handle this is with a custom [Lifecycle Interceptor](#lifecycle-interceptor).

It would also be nice for each Resource Method to be annotated with information that describes its security requirements - does the Method require authentication?  Are special roles/authorizations necessary?  And so on.

First - let's define an annotation we can apply to our Resource Methods.

```java
// Roles we have in our app
enum Role {
  REGULAR_USER,
  ADMINISTRATOR
}

// Annotation declaring "this method requires that Role"
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RoleRequired {
  Role value() default Role.REGULAR_USER;
}

// Resource Method with our new annotation
@Resource
class ExampleResource {
  @GET("/top-secret")
  @RoleRequired(Role.ADMINISTRATOR) // Use our new annotation
  public String topSecret() {
    return "Don't tell anyone!";
  }
}
```

With our annotation ready to go, we can build out authentication and authorization:

```java
SokletConfiguration configuration = new SokletConfiguration.Builder(server)
  .lifecycleInterceptor(new LifecycleInterceptor() {
    @Override
    public void interceptRequest(@Nonnull Request request,
                                 @Nullable ResourceMethod resourceMethod,
                                 @Nonnull Function<Request, MarshaledResponse> requestHandler,
                                 @Nonnull Consumer<MarshaledResponse> responseHandler) {
      // Look up account using JWT from the request
      ExampleAccount account = accountForRequest(request).orElse(null);

      // No account?  Authentication failed
      if(account == null)
        throw new MyAuthenticationException();

      // Via ResourceMethod, you have access to the Java method configured 
      // to handle the request...
      Method method = resourceMethod.getMethod();

      // ...which permits you to do things like examine its annotations
      // to apply special behavior, e.g. check authorization
      if (method != null && method.getAnnotation(RoleRequired.class) != null) {
        Role role = method.getAnnotation(RoleRequired.class).value();

        // Authenticated account doesn't have the proper role?
        // Authorization failed
        if (!account.hasRole(role))
          throw new MyAuthorizationException();
      }      

      // Normal downstream processing
      MarshaledResponse marshaledResponse = requestHandler.apply(request);
      responseHandler.accept(marshaledResponse);
    }

    // Pull the value from MyExampleJWTCookie and use it to authenticate
    Optional<ExampleAccount> accountForRequest(Request request) {
      // For sake of example, we examine cookies.
      // In practice, you might example request headers instead.     
      request.getCookies().stream()
        .filter(cookie -> cookie.getName().equals("MyExampleJWTCookie"))
        .findAny()
        .ifPresent(jwtCookie -> {
          // Wire in your authentication logic/account loading here
          return myAccountService.accountForJwt(jwtCookie.getValue());
        });

      return Optional.empty();
    }
  }).build();
```

This approach can be extended to other scenarios where it's useful to apply custom behavior to specially-annotated Resource Methods.  For example, you can imagine annotations like `@Transactional`, `@WritableMaster`, and `@ReadReplica` that specify how a Resource Method should interact with datasource[s].

### Exception Handling

### Testing

### Configuration (?)

### Custom IDs

e.g. using `ValueConverter` for seamless integration of https://github.com/Devskiller/friendly-id

### Request Context (?)

TBD: should we introduce a `Map<String, Object> userContext` (or whatever) on `Request` in which arbitrary metadata can be stuffed?

### Docker (?)