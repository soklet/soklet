## Soklet

### What Is It?

A minimal HTTP 1.1 server and routing library for Java, well-suited for building RESTful APIs.<br/>
Zero dependencies.  Dependency Injection friendly.<br/>
Powered by [JEP 425: Virtual Threads, aka Project Loom](https://openjdk.org/jeps/425).

### Design Goals

* Single focus: route HTTP requests to Java methods 
* Near-instant startup
* No dependencies 
* Small featureset; big impact
* Provide control sufficient to support esoteric workflows
* Minimize mutability
* Keep codebase small enough to read and understand it all

### Design Non-Goals

* SSL/TLS (your load balancer should be providing TLS termination...)
* HTTP streaming
* WebSockets
* Dictate which technologies to use (Guice vs. Dagger, Gson vs. Jackson, etc.)
* "Batteries included" authentication and authorization

### Future Work

* Servlet API compatibility layer to support legacy libraries

### Do Zero-Dependency Libraries Interest You?

Similarly-flavored commercial-friendly OSS libraries are available.

* [Lokalized](https://www.lokalized.com) - natural-sounding translations (i18n) via expression language 
* [Pyranid](https://www.pyranid.com) - makes working with JDBC pleasant

### License

[Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0)

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

    // Bare-bones: use built-in MicroHttpServer and don't change the default configuration
    SokletConfiguration configuration = new SokletConfiguration.Builder(
        new MicroHttpServer.Builder(port).build())
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

Soklet detects Resources using a compile-time annotation processor and constructs a lookup table to avoid expensive classpath scans during app startup. 

```java
@Resource
class ExampleResource {
  // You can name your methods whatever you like and return whatever you like (or void).
  // It's up to your ResponseMarshaler to take what you return and convert it into bytes.
  @GET("/")
  public String index() {
    return "Hello, world!";
  }
	
  // Curly-brace syntax is used to denote path placeholders.
  // All placeholders in a path must have unique names and have corresponding 
  // @Placeholder-annotated Java method parameters.
  @GET("/example/{placeholder}")
  public Response examplePlaceholder(@PathParameter Integer placeholder) {
    // 
    return new Response.Builder(200)
      .body(String.format("Placeholder=%s", placeholder))
      .headers(Map.of("Custom-Header", Set.of("123")))
      .cookies(Set.of(new HttpCookie("redirected", "true")))
      .build();
  }

  @POST("/another/example/post")
  public Response formPost(Request request,
                           @RequestBody String requestBody, 
                           @FormParameter("attr") Optional<String> attribute) {
	
		
    return new Response.Builder(RedirectType.HTTP_307_TEMPORARY_REDIRECT, "/")
      .cookies(Set.of(new HttpCookie("post-attribute-value", attribute.orElse("none"))))
      .build();
  }
}
```

TBD: more info here?

## Configuration

TBD

### MicroHttpServer

```java
Server server = new MicroHttpServer.Builder(8080 /* port */)
  // Host on which we are listening
  .host("0.0.0.0")
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
  // Vend an ExecutorService that is used to run the event loop (we only need one thread)
  .eventLoopExecutorServiceSupplier(() -> Executors.newSingleThreadExecutor())
  // Vend an ExecutorService that is used to service HTTP requests.
  // For Loom/Virtual Threads - it makes sense to have 1 virtual thread per request.
  // For non-Loom operation - you will likely want a fixed-size pool of native threads.
  .requestHandlerExecutorServiceSupplier(() -> Executors.newVirtualThreadPerTaskExecutor())
  .build();

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
the DI infrastructure vend your instances.<br/>
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

TBD 

### Request Context

TBD 