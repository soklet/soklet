## Soklet

#### What Is It?

Minimalist infrastructure for Java webapps and microservices.

#### Design Goals

* Minimal, unopinionated
* No external servlet container required
* Fast startup
* No 3rd party dependencies - uses only standard JDK APIs
* Extensible - applications can easily hook/override core functionality vi DI
* Deployment artifact is a single zip file
* Java 8+, Servlet 3.1+

#### Design Non-Goals

* Dictation of what libraries and versions to use (GSON vs. Jackson, Mustache vs. Velocity, etc.)
* Baked-in authentication and authorization
* Database support (you can bring your own with [Pyranid](http://www.pyranid.com))

#### License

[Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0)

#### Maven Installation

```xml
<dependency>
  <groupId>com.soklet</groupId>
  <artifactId>soklet</artifactId>
  <version>1.0.3</version>
</dependency>
```

#### Direct Download

If you don't use Maven, you can drop [soklet-1.0.3.jar](http://central.maven.org/maven2/com/soklet/soklet/1.0.3/soklet-1.0.3.jar) directly into your project.  You'll also need [javax.inject-1.jar](http://central.maven.org/maven2/javax/inject/javax.inject/1/javax.inject-1.jar) and [javax.servlet-api-3.1.0.jar](http://central.maven.org/maven2/javax/servlet/javax.servlet-api/3.1.0/javax.servlet-api-3.1.0.jar) as dependencies.

#### Getting Started

```
$ git clone https://github.com/soklet/soklet.git
$ cd soklet
$ mvn -q exec:exec
```

## Example Code

#### Resource Endpoints

```java
// Any class containing URL-resource methods must have the @Resource annotation applied.
// This is a performance optimization for fast startup time. Soklet uses an annotation processor
// at compile time to create a lookup table which avoids runtime reflection
@Resource
public class HelloResource {
  // Use BinaryResponse to write whatever you'd like to the response.
  // Normally this is useful for binary data like PDFs and CSVs but here we write a string
  @GET("/hello")
  public BinaryResponse hello() {
    return new BinaryResponse("text/plain", new ByteArrayInputStream("Hello, World!".getBytes(UTF_8)));
  }

  // Methods with a void return type are 204s
  @GET("/no-response")
  public void noResponse() {
    out.println("I'll return a 204");
  }

  // Methods that return nulls are 204s
  @GET("/another-no-response")
  public Object anotherNoResponse() {
    out.println("I'll also return a 204");
    return null;
  }  

  // Path parameters and query parameters are easy to access via annotations.
  // By default, reflection is used to determine their names,
  // but you can override by supplying an explicit name.
  //
  // If a parameter is not required, you must wrap it in an Optional.
  // Path parameters are implicitly required as they are part of the URL itself.
  //
  // If a value cannot be converted to the declared type (for example, t=abc below)
  // or is required but missing, an appropriate exception is thrown and a 400 response is returned.
  //
  // Value conversion strategies are customizable - Soklet supports many standard Java types
  // out of the box, but you can add more or override as needed via a custom ValueConverterRegistry.
  // See the "Customization" section for details
  //
  // Example URL: /hello/everyone?t=10
  @GET("/hello/{target}")
  public void hello(@PathParameter String target, @QueryParameter("t") Optional<Integer> times) {
    if (times.isPresent())
      out.println(format("Saying %d hellos to %s!", times.get(), target));
    else
      out.println(format("Not saying hello to %s!", target));
  }

  //
  // Example URL: /users/d276e191-eed6-4ee2-b2ed-7e9ae4b5f05b?=10
  @GET("/users/{userId}")
  public String user(@PathParameter UUID userId, @QueryParameter BigDecimal amount) {
    return format("User ID is %s", userId);
  }

  // The same method can handle multiple URLs
  @GET("/twins")
  @GET("/triplets")
  @POST("/quadruplets")
  public void multiples() {
    out.println("Multiples work as expected");
  }

  // Soklet has the concept of a PageResponse, which associates a logical page name with an optional
  // map of data to be merged into it and written to the HTTP response.
  //
  // Each application will do it differently - Velocity, Freemarker, Mustache, etc.
  // You just need to provide Soklet with a PageResponseWriter implementation.
  //
  // Example URL: /hello-there?name=Steve
  @GET("/hello-there")
  public PageResponse helloTherePage(@QueryParameter String name) {
    return new PageResponse("hello-there", new HashMap<String, Object>() {
      {
        put("name", name);
      }
    });
  }  

  // ApiResponse is similar to PageResponse, except that it accepts an arbitrary Object
  // intended to be written to the HTTP response.
  //
  // Each application will do it differently - Jackson, GSON, XML, Protocol Buffers, etc.
  // You just need to provide Soklet with an ApiResponseWriter implementation.
  //
  // Example URL: /api/hello-there?name=Steve
  @GET("/api/hello-there")
  public ApiResponse helloThereApi(@QueryParameter String name) {
    return new ApiResponse(new HashMap<String, Object>() {
      {
        put("name", name);
      }
    });
  }
}
```

#### Default Response Types

* ```ApiResponse```
* ```AsyncResponse``` signifies to Soklet that no response should be written and you plan to use Servlet 3.1 nonblocking I/O to handle it yourself
* ```BinaryResponse``` is designed for writing arbitrary content to the response, e.g. streaming a PDF
* ```PageResponse```
* ```RedirectResponse``` performs standard 301 and 302 redirects

#### Response Writers

You might implement a [Mustache.java](https://github.com/spullara/mustache.java) ```PageResponseWriter``` like this:

```java
class MustachePageResponseWriter implements PageResponseWriter {
  private final MustacheFactory mustacheFactory;

  MustachePageResponseWriter() {
    // Mustache templates live in the "pages" directory
    this.mustacheFactory = new DefaultMustacheFactory(Paths.get("pages").toFile());
  }

  @Override
  public void writeResponse(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse,
      Optional<PageResponse> response, Optional<Route> route, Optional<Exception> exception) throws IOException {
    // Keep track of what to write to the response
    String name = null;
    Map<String, Object> model = null;

    if (response.isPresent()) {
      // Happy path
      name = response.get().name();
      model = response.get().model().orElse(null);
    } else {
      // There was a problem - render an error page
      name = "error";
      model = new HashMap<String, Object>() {
        {
          put("status", httpServletResponse.getStatus());
        }
      };
    }

    httpServletResponse.setContentType("text/html;charset=UTF-8");

    // Finally, create a mustache instance and write the merged output to the response
    Mustache mustache = this.mustacheFactory.compile(format("%s.html", name));

    try (OutputStream outputStream = httpServletResponse.getOutputStream()) {
      mustache.execute(new OutputStreamWriter(outputStream, UTF_8), model).flush();
    }
  }  
}
```

You might implement a [Jackson](https://github.com/FasterXML/jackson) ```ApiResponseWriter``` like this:

```java
class JacksonApiResponseWriter implements ApiResponseWriter {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public void writeResponse(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse,
      Optional<ApiResponse> response, Optional<Route> route, Optional<Exception> exception) throws IOException {
    // Keep track of what to write to the response
    Map<String, Object> model = null;

    if (response.isPresent()) {
      // Happy path
      model = response.get().model().orElse(null);
    } else {
      // There was a problem - render an error response
      model = new HashMap<String, Object>() {
        {
          put("status", httpServletResponse.getStatus());
          put("message", "An error occurred!");
        }
      };
    }

    httpServletResponse.setContentType("application/json;charset=UTF-8");

    // Finally, write JSON to the response
    try (OutputStream outputStream = httpServletResponse.getOutputStream()) {
      objectWriter.writeValue(outputStream, model);
    }
  }  
}
```

#### Startup

```java
// Assumes you're using Guice as your DI framework via soklet-guice
public static void main(String[] args) throws Exception {
  Injector injector = createInjector(Modules.override(new SokletModule()).with(new AppModule()));
  Server server = injector.getInstance(Server.class);
  server.start();
  System.in.read(); // Wait for keypress
  server.stop();
}

class AppModule extends AbstractModule {
  @Inject
  @Provides
  @Singleton
  public Server provideServer(InstanceProvider instanceProvider) {
    return JettyServer.forInstanceProvider(instanceProvider).port(8080).build();
  }
}
```

#### Customization

Coming soon

#### Deployment

Coming soon
