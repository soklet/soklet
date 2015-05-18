## Soklet

#### What Is It?

Minimalist infrastructure for Java webapps and microservices.

**Note:** Soklet is under active development and will be ready for production use soon.

#### Design Goals

* Minimal, unopinionated
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

```xml
<dependency>
  <groupId>com.soklet</groupId>
  <artifactId>soklet</artifactId>
  <version>1.0.1</version>
</dependency>
```

#### Direct Download

Coming soon
<!-- [https://www.soklet.com/releases/soklet-1.0.1.jar](https://www.soklet.com/releases/soklet-1.0.1.jar) -->

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