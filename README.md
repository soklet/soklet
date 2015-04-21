## Soklet

#### What Is It?

Minimalist infrastructure for Java webapps and microservices.

It's under active development and will be available for production use soon.

#### Design Goals

* Minimalist, unopinionated
* No external servlet container required
* Fast startup
* No 3rd party dependencies - uses only standard JDK APIs
* Extensible - applications can easily hook/override core functionality
* Deployment artifact is a single zip file
* Java 8+, Servlet 3.1+

#### Design Non-Goals

* Dictation of what libraries and versions to use (GSON vs. Jackson, Mustache vs. Velocity, etc.)
* Baked-in authentication and authorization
* Database support

#### License

[Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0)

#### Maven Installation

Coming soon
<!--
```xml
<dependency>
  <groupId>com.pyranid</groupId>
  <artifactId>pyranid</artifactId>
  <version>1.0.0</version>
</dependency>
```
-->
#### Direct Download

Coming soon
<!-- [https://www.pyranid.com/releases/pyranid-1.0.0.jar](https://www.pyranid.com/releases/pyranid-1.0.0.jar) -->
## Configuration

## Example Code

```java
// Any class containing URL-resource methods must have the @Resource annotation applied.
// This is a performance optimization for fast startup time.
@Resource
public class HelloResource {
  // You can return whatever you like from resource methods.
  // It's up to you to implement your own ResponseHandler to do something meaningful with the value.
  // For example, you might write a JSON API response or render an HTML template.
  // By default, Soklet writes the toString() value to the servlet response (or 204 for voids).
  @GET("/hello")
  public String hello() {
    return "Hello, world!";
  }
  
  // 
  // Example URL: /hello/everyone?t=10
  @GET("/hello/{target}")
  public String hello(@PathParameter String target, @QueryParameter("t") Optional<Integer> times) {
    if (times.isPresent())
      return format("Saying %d hellos to %s!", times.get(), target);

    return format("Not saying hello to %s!", target);
  }
  
  // The same method can handle multiple URLs
  @GET("/twins")
  @GET("/triplets")
  @POST("/quadruplets")
  public String hello() {
    return "Hello, everyone!";
  }  
}
```

#### Error Handling

```java
@Resource
public class UserResource {
  @GET("/users/{userId}")
  public String user(@PathParameter UUID userId) {
    return format("User ID %s", userId);
  }
}
```

The return value for a request to `/users/eccee47d-b0b3-47eb-ac9a-8e9d5da4b208` would be

    User ID eccee47d-b0b3-47eb-ac9a-8e9d5da4b208

For a request to `/users/junk`, an `IllegalPathParameterException` would bubble out

    IllegalPathParameterException: Illegal value 'junk' was specified for path parameter 'userId' (was expecting a value convertible to class java.util.UUID)

#### Startup

```java
public static void main(String[] args) throws Exception {
  // Bootstrap Guice
  Injector injector = createInjector(new SokletModule(), new AppModule());
  Server server = injector.getInstance(Server.class);
  server.start();
  System.in.read(); // Wait for keypress
  server.stop();
}

// If you're using Guice
public static class AppModule extends AbstractModule {
  @Inject
  @Provides
  @Singleton
  public Server provideServer(InstanceProvider instanceProvider) {
    return new JettyServer(JettyServerConfiguration.builder(instanceProvider).port(8080).build());
  }
}
```