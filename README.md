## Soklet

### What Is It?

A small HTTP 1.1 server and route handler for Java, well-suited for building RESTful APIs.<br/>
Zero dependencies.  Dependency Injection friendly.<br/>
Optionally powered by [JEP 425: Virtual Threads, aka Project Loom](https://openjdk.org/jeps/425).

Soklet is a library, not a framework.

### Design Goals

* Main focus: route HTTP requests to Java methods 
* Near-instant startup
* No dependencies
* Small but expressive API
* Deep control over request and response processing
* Be immutable where reasonable
* Small and comprehensible codebase

### Design Non-Goals

* SSL/TLS (your load balancer should be providing TLS termination...)
* HTTP streaming
* WebSockets
* Dictate which technologies to use (Guice vs. Dagger, Gson vs. Jackson, etc.)
* "Batteries included" authentication and authorization

### Future Work

* Servlet API compatibility layer to support legacy libraries

### Do Zero-Dependency Libraries Interest You?

Similarly-flavored commercially-friendly OSS libraries are available.

* [Lokalized](https://www.lokalized.com) - natural-sounding translations (i18n) via expression language 
* [Pyranid](https://www.pyranid.com) - makes working with JDBC pleasant

### License

[Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0)

Soklet includes code from the following:

* [ClassIndex](https://github.com/atteo/classindex) - Apache 2.0 License
* [Microhttp](https://github.com/ebarlas/microhttp) - MIT License

### Maven Installation

```xml
<dependency>
  <groupId>com.soklet</groupId>
  <artifactId>soklet</artifactId>
  <version>2.0.0-SNAPSHOT</version>
</dependency>
```

### Direct Download

If you don't use Maven, you can drop [soklet-2.0.0-SNAPSHOT.jar](http://central.maven.org/maven2/com/soklet/soklet/2.0.0-SNAPSHOT/soklet-2.0.0-SNAPSHOT.jar) directly into your project.  That's all you need!

## App Startup

Soklet applications are regular Java applications - no Servlet container required.

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

For Soklet to be useful, create one or more `@Resource`-annotated classes (hereafter _Resources_), which use annotation metadata
to declare how HTTP inputs (methods, paths, query parameters, cookies, etc.) map to Java methods. 

Soklet detects Resources using a compile-time annotation processor and constructs a lookup table to avoid expensive classpath scans during startup.

```java
@Resource
class ExampleResource {
  // You can name your methods whatever you like and return whatever you like (or void).
  // It's up to your ResponseMarshaler to take what you return and convert it into bytes.
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
  // strings to "complex" types like LocalDate, and how you can customize this
  // behavior.
  // 
  // By default, parameter names are determined by reflection.
  // You may override this behavior by passing a name to the annotation,
  // e.g. @QueryParameter("value").
  //
  //
  // This URL might look like /example/params?date=2022-09-21&value=ABC&value=123
  @GET("/example/params")
  public Response params(@QueryParameter LocalDate date) {
                         @QueryParameter("value") Optional<List<String>> values) {
    return new Response.Builder()
      .body(String.format("date=%s, values=%s", date, values))
      .build();
  }	

  // The @FormParameter annotation supports application/x-www-form-urlencoded
  // values.
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
  @POST("/another/example/post")
  public Response formPost(Request request,
                           @RequestBody String requestBody, 
                           @FormParameter("attr") Optional<String> attribute,
                           @RequestCookie("gat") HttpCookie analyticsCookie,
                           MyExampleJsonParser jsonParser,
                           MyExampleBackend backend) {
    // Assemble some data to pass to our example backend
    String analyticsCookie.getValue();
    Locale locale = request.getLocales().stream().findFirst().get();
    MyExampleType exampleType = jsonParser.parse(requestBody, MyExampleType.class);
    
    backend.createRecord(exampleType, locale, analyticsCookie.getValue(), attribute.orElse(null));

    // The response builder has a convenience shorthand for performing redirects.
    // You could alternatively do this "by hand" by setting HTTP status and headers appropriately.
    return new Response.Builder(RedirectType.HTTP_307_TEMPORARY_REDIRECT, "/")
      .cookies(Set.of(new HttpCookie("post-attribute-value", attribute.orElse("none"))))
      .build();
  }
}
```

TBD: more info here?

## Configuration

All of Soklet's components are programmatically pluggable via the [SokletConfiguration](https://www.soklet.com/javadoc/com/soklet/SokletConfiguration.html) builder.

The components you'll likely want to customize are:

* [Server](#server) - handles HTTP 1.1 requests and responses
* [Response Marshaler](#response-marshaler) - turns Java objects into bytes to send over the wire
* [Instance Provider](#instance-provider) - creates class instances on your behalf
* [Lifecycle Interceptor](#lifecycle-interceptor) - provides hooks to customize phases of request/response processing
* [Value Converters](#value-converters) - convert input strings (e.g. query parameters) to Java types (e.g. [LocalDateTime](https://docs.oracle.com/en/java/javase/18/docs/api/java.base/java/time/LocalDateTime.html))
* [CORS Authorizer](#cors-authorizer) - handles [CORS](https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS)

The "experts only" components are:

* [Request Method Resolver](#request-method-resolver-experts-only) - determines how to map HTTP requests to Java Resource methods 
* [Resource Method Parameter Provider](#resource-method-parameter-provider-experts-only) - determines how to inject appropriate parameter values when invoking Java Resource methods

Here's an example that demonstrates a basic setup for an API that serves JSON responses.

TODO: move the detailed stuff to individual sections.

```java
int port = 8080;

SokletConfiguration sokletConfiguration = new SokletConfiguration.Builder(
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
  public void interceptRequest(@Nonnull Request request,
                               @Nullable ResourceMethod resourceMethod,
                               @Nonnull Function<Request, MarshaledResponse> requestHandler,
                               @Nonnull Consumer<MarshaledResponse> responseHandler) {
    // Similar to a Servlet Filter, here you have the opportunity to:
    //
    // * Modify the request before downstream processing occurs
    // * Modify the response before downstream processing occurs
    // * Inject application-specific logic, e.g. authorizing a request or
    //   wrapping downstream code in a database transaction
    
    // Let normal request processing finish
    MarshaledResponse marshaledResponse = requestHandler.apply(request);

    // Add a snazzy header to all responses before they are sent over the wire.
    marshaledResponse = marshaledResponse.copy()
      .headers((mutableHeaders) -> {
        mutableHeaders.put("X-Powered-By", Set.of("My Amazing API"));
      }).finish();

    // Let downstream processing finish
    responseHandler.accept(marshaledResponse);
  }	

  @Override
  public void didFinishRequestHandling(@Nonnull Request request,
                                       @Nullable ResourceMethod resourceMethod,
                                       @Nonnull MarshaledResponse marshaledResponse,
                                       @Nonnull Duration processingDuration,
                                       @Nonnull List<Throwable> throwables) {
    // Totally done processing the request
    System.out.printf("Finished processing %s\n", request);
  }
})    
    
//
.responseMarshaler(new DefaultResponseMarshaler() {
@Nonnull
@Override
public MarshaledResponse toDefaultMarshaledResponse(Request request,
		Response response,
		ResourceMethod resourceMethod) {
		Object bodyObject = response.getBody().orElse(null);
		byte[] body = bodyObject == null ? null : gson.toJson(bodyObject).getBytes(StandardCharsets.UTF_8);

		Map<String, Set<String>> headers = new HashMap<>(response.getHeaders());
		headers.put("Content-Type", Set.of("application/json;charset=UTF-8"));

		return new MarshaledResponse.Builder(response.getStatusCode())
		.headers(headers)
		.cookies(response.getCookies())
		.body(body)
		.build();
		}
		})
		.corsAuthorizer(new AllOriginsCorsAuthorizer())
		.instanceProvider(injector::getInstance)
		.build();
```

### Server

Soklet provides an embedded version of [Microhttp](https://github.com/ebarlas/microhttp) out-of-the-box in the form of [MicrohttpServer](https://www.soklet.com/javadoc/com/soklet/core/impl/MicrohttpServer.html).

```java
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
  // The biggest request headers we permit clients to send  
  .maximumHeaderSizeInBytes(1_024 * 8)    
  // Requests are read into a byte buffer of this size
  .socketReadBufferSizeInBytes(1_024 * 64)
  // The maximum number of pending connections on the socket (values < 1 use JVM platform default)
  .socketPendingConnectionLimit(0)
  // Handle server logging statements
  .logHandler(new LogHandler() { ... })
  // Vend an ExecutorService that is used to run our event loops
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

TBD

### Instance Provider

Soklet creates instances of Resource classes so it can invoke methods on them on your behalf.<br/>
Here's a naïve implementation that assumes the presence of a default constructor.

```java
SokletConfiguration configuration = new SokletConfiguration.Builder(server)
  .instanceProvider(new InstanceProvider() {
    @Override
    public <T> T provide(@Nonnull Class<T> instanceClass) {
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
Here's how it might look if you use [Guice](https://github.com/google/guice):

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

TBD

### Value Converters

TBD

### CORS Authorizer

TBD

#### All Origins (Testing Only!)

#### Whitelisted Origins

### Log Handler

TBD

### Request Method Resolver (experts only!)

TBD

### Resource Method Parameter Provider (experts only!)

TBD

## Common Usage Patterns

Every system is different, but there are frequently recurring patterns.

We present how these pattern implementations might look in Soklet applications.

### Authentication and Authorization

Request headers and cookies are common ways to pass authentication information - for example, as a [JWT](https://jwt.io).

The appropriate place to handle this is with a custom [Lifecycle Interceptor](#lifecycle-interceptor).

```java
SokletConfiguration configuration = new SokletConfiguration.Builder(server)
  .lifecycleInterceptor(new LifecycleInterceptor() {
    @Override
    public void interceptRequest(@Nonnull Request request,
                                 @Nullable ResourceMethod resourceMethod,
                                 @Nonnull Function<Request, MarshaledResponse> requestHandler,
                                 @Nonnull Consumer<MarshaledResponse> responseHandler) {
      // Pull the value from MyExampleJWTCookie and use it to authenticate
      request.getCookies().stream()
        .filter(cookie -> cookie.getName().equals("MyExampleJWTCookie"))
        .findAny()
        .ifPresent(jwtCookie -> {
          // Your authentication logic here
        });

      // Normal downstream processing
      MarshaledResponse marshaledResponse = requestHandler.apply(request);
      responseHandler.accept(marshaledResponse);
    }		
  }).build();
```

### Relational Database Transaction Management

TBD

### Exception Handling

### Testing

### Configuration (?)

### Request Context (?)

### Docker (?)