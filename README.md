## Soklet

#### What Is It?

Minimalist infrastructure for Java webapps and microservices.

#### Design Goals

* Single focus, unopinionated
* No external servlet container required
* Fast startup, under a second on modern hardware
* No 3rd party dependencies - uses only standard JDK APIs
* Extensible - applications can easily hook/override core functionality via DI
* Self-contained deployment (single zip file)
* Static resource filename hashing for efficient HTTP caching and versioning
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
  <version>1.2.0</version>
</dependency>
```

#### Direct Download

If you don't use Maven, you can drop [soklet-1.2.0.jar](http://central.maven.org/maven2/com/soklet/soklet/1.2.0/soklet-1.2.0.jar) directly into your project.  You'll also need [javax.inject-1.jar](http://central.maven.org/maven2/javax/inject/javax.inject/1/javax.inject-1.jar) and [javax.servlet-api-3.1.0.jar](http://central.maven.org/maven2/javax/servlet/javax.servlet-api/3.1.0/javax.servlet-api-3.1.0.jar) as dependencies.

<!--
## Bootstrap Your App

TODO - discussion

```
$ git clone https://github.com/soklet/soklet.git
$ cd soklet
$ mvn -q exec:exec
```
-->

## App Startup

Soklet applications are designed to launch via ```public static void main()```, just like a regular Java application.  You do not have to worry about environment and external server setup, deployment headaches, and tricky debugging.

```java
// Assumes you're using Guice as your DI framework via soklet-guice
public static void main(String[] args) throws Exception {
  Injector injector = Guice.createInjector(Modules.override(new SokletModule()).with(new AppModule()));
  Server server = injector.getInstance(Server.class);

  // Start the server
  new ServerLauncher(server).launch(StoppingStrategy.ON_KEYPRESS, () -> {
    // Some custom on-server-startup code here, if needed
  }, () -> {
    // Some custom on-server-shutdown code here, if needed
  });
}

class AppModule extends AbstractModule {
  @Provides
  @Singleton
  public Server provideServer(InstanceProvider instanceProvider) {
    // Assumes you're using Jetty as your server via soklet-jetty.
    // If you prefer Tomcat, soklet-tomcat is an alternative
    return JettyServer.forInstanceProvider(instanceProvider).port(8080).build();
  }

  // You'll likely want to override Soklet's defaults.
  // Dependency injection makes this easy.
  //
  // For example, if your API endpoints should return JSON generated by Jackson,
  // use Guice to provide your own ApiResponseWriter implementation.
  @Provides
  @Singleton
  public ApiResponseWriter provideApiResponseWriter() {
    return MyJacksonApiResponseWriter();
  }
}
```

## Resource Methods

Soklet's main job is mapping Java methods to URLs.  We refer to these methods as  _resource methods_.

Resource methods may return any type, such as ```String``` or ```UUID```, but normally you'll return special types like ```PageResponse``` and ```ApiResponse```.

#### Example Code

```java
// Any class containing URL-resource methods must have the @Resource annotation applied.
// This is a performance optimization for fast startup time. Soklet uses an annotation processor
// at compile time to create a lookup table which avoids runtime reflection
@Resource
public class HelloResource {
  // You may return arbitrary types - the object's toString() value is written
  // to the response as text/plain;charset=UTF-8
  @GET("/hello")
  public String hello() {
    return "Hello, world!";
  }  

  // Response body looks like you would expect: 100.25
  @GET("/hello-big-decimal")
  public Object helloBigDecimal() {
    return new BigDecimal(100.25);
  }

  // Path parameters, query parameters, and form parameters are easy to access via annotations.
  // By default, reflection is used to determine their names,
  // but you can override by supplying an explicit name.
  //
  // If a parameter is not required, you must wrap it in an Optional.
  // Path parameters are implicitly required as they are part of the URL itself.
  //
  // If a value cannot be converted to the declared type (for example, t=abc below)
  // or is required but missing, an exception is thrown and a 400 response is returned.
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

  // Soklet has the concept of a PageResponse, which associates a logical page name with an optional
  // map of data to be merged into it and written to the HTTP response.
  //
  // Each application will do it differently - Velocity, Freemarker, Mustache, etc.
  // You just need to provide Soklet with a PageResponseWriter implementation.
  // See "Response Writers" section for details
  //
  // Example URL: /hello-there?name=Steve
  @GET("/hello-there")
  public PageResponse helloTherePage(@QueryParameter String name) {
    return new PageResponse("hello-there", new HashMap<String, Object>() {{
      put("name", name);     
    }});
  }  

  // ApiResponse is similar to PageResponse, except that it accepts an arbitrary Object
  // intended to be written to the HTTP response.
  //
  // Each application will do it differently - Jackson, GSON, XML, Protocol Buffers, etc.
  // You just need to provide Soklet with an ApiResponseWriter implementation.
  // See "Response Writers" section for details
  //
  // Example URL: /api/hello-there?name=Steve&type=FRIENDLY
  
  public static enum GreetingType {
    FRIENDLY, UNFRIENDLY
  }  
  
  @GET("/api/hello-there")
  public ApiResponse helloThereApi(@QueryParameter String name, @QueryParameter GreetingType type) {
    return new ApiResponse(new HashMap<String, Object>() {{      
      put("name", name);
      put("type", type);      
    }});
  }

  // You may specify @RequestBody on a String or InputStream parameter for easy access.
  // As elsewhere, if the request body is not required, wrap the parameter in an Optional<T>
  @POST("/api/hello")
  public ApiResponse createHello(@RequestBody String requestBody) {
    // It's up to you to parse the request body however you'd like
    HelloCreateCommand command = parse(requestBody, HelloCreateCommand.class);
    Hello hello = helloService.createHello(command);
    return new ApiResponse(201, hello);
  }

  // Currently Soklet does not have built-in multipart support (coming soon!)
  // But you can pull out form parameters if your form's content type is
  // application/x-www-form-urlencoded.
  @POST("/example-non-multipart-post")
  public void examplePost(@FormParameter Long id, @FormParameter Optional<String> name) {
    database.executeInsert("INSERT INTO account VALUES (?,?)", id, name.orElse("Anonymous"));
  }
  
  // Similar to @QueryParameter, you may use @RequestHeader and @RequestCookie to marshal
  // request data to strongly-typed parameter values.
  //
  // It's possible to have multiple values for the same name for headers and cookies, so
  // mapping as List is supported.
  //
  // For cookies, you may map to either javax.servlet.http.Cookie or other types (e.g. String)
  // if you only need the value
  @GET("/headers-and-cookies")
  public String headersAndCookies(@RequestHeader("Accept-Language") String acceptLanguage,
    @RequestCookie Cookie securityCookie, @RequestCookie Optional<List<Float>> numbers) {
    return format("Language: %s, Security: %s, Numbers: %s", acceptLanguage, securityCookie, numbers);
  }    

  // BinaryResponse allows you to specify arbitrary data and content type.
  // Useful for PDFs, CSVs, edge cases.
  //
  // Soklet will automatically close the InputStream after the response has been written.
  @GET("/hello.pdf")
  public BinaryResponse helloPdf() {
    InputStream pdfInputStream = generateMyPdf();
    return new BinaryResponse("application/pdf", pdfInputStream);
  }

  // RedirectResponse performs temporary and permanent redirects.
  //
  // Example URL: /redirect?temporary=true
  @GET("/redirect")
  public RedirectResponse redirect(@QueryParameter boolean temporary) {
    return new RedirectResponse("http://google.com",
      temporary ? RedirectResponse.Type.TEMPORARY : RedirectResponse.Type.PERMANENT);
  }  
  
  // Returning a CustomResponse signifies that you want to do your own response
  // handling and Soklet should take no action.
  @GET("/oauth/token")
  public CustomResponse oauthToken(HttpServletResponse httpServletResponse) {
    // Example of Oltu OAuth integration
    String accessToken = oauthIssuer.accessToken();
    String refreshToken = oauthIssuer.refreshToken();
		
    OAuthResponse oauthResponse = OAuthASResponse
      .tokenResponse(HttpServletResponse.SC_OK)
      .setAccessToken(accessToken)
      .setExpiresIn("3600")
      .setRefreshToken(refreshToken)
      .buildJSONMessage();
		
    httpServletResponse.setStatus(oauthResponse.getResponseStatus());
		
    PrintWriter printWriter = httpServletResponse.getWriter();
    printWriter.print(oauthResponse.getBody());
    printWriter.flush();
    printWriter.close();  
		
    return CustomResponse.instance();
  }

  // Methods with a void return type are 204s
  @GET("/no-response")
  public void noResponse() {
    out.println("I'll return a 204");
  }

  // Methods that return nulls are 204s as well
  @GET("/another-no-response")
  public Object anotherNoResponse() {
    out.println("I'll also return a 204");
    return null;
  }  

  // The same resource method can handle multiple URLs
  @GET("/twins")
  @GET("/triplets")
  @POST("/quadruplets")
  public String multiples() {
    return "Multiples work as expected";
  }
  
  // If your resource method accepts arguments Soklet doesn't recognize, Soklet
  // asks your DI mechanism to provide them.
  //
  // Note: UserContext is not a Soklet construct, but it is a useful concept for many apps
  @GET("/widgets")
  public PageResponse widgets(UserContext userContext, WidgetService widgetService) {  
    User currentUser = userContext.currentUser();
    List<Widget> widgets = widgetService.findWidgetsForUser(currentUser);
    
    return new PageResponse("widgets", new HashMap<String, Object>() {{
      put("widgets", widgets);
    }});
  }
}
```

#### Resource Method Return Types

There are 5 standard resource method return types provided by Soklet.

* ```ApiResponse``` Holds an arbitrary object that is meant to be written as an "API" response (often JSON or XML)
* ```BinaryResponse``` Designed for writing arbitrary content to the response, e.g. streaming a PDF
* ```CustomResponse``` Indicates Soklet should take no action - you are responsible for writing the response yourself
* ```PageResponse``` Holds a logical page template name and optional model data to merge with it, meant to be written as an HTML page response. Some popular templating technologies are Velocity, Freemarker, and Mustache  
* ```RedirectResponse``` Performs standard 301 and 302 redirects

Returning ```void``` or ```null``` will result in a ```204``` with an empty response body.

Returning types other than those listed above (e.g. ```UUID``` or ```Double``` or ```MyCustomType```) will invoke Soklet's default behavior of writing their ```toString()``` value to the response with content type ```text/plain;charset=UTF-8```.

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
      Optional<PageResponse> pageResponse, Optional<Route> route, Optional<Exception> exception) throws IOException {
    // Make sure our content type is correct
    httpServletResponse.setContentType("text/html;charset=UTF-8");

    // Keep track of what to write to the response
    String name = null;
    Map<String, Object> model = null;

    if (pageResponse.isPresent()) {
      // Happy path - resource method completed successfully and returned a value
      name = pageResponse.get().name();
      model = pageResponse.get().model().orElse(null);
    } else {
      // There was a problem - render an error page
      name = "error";
      model = new HashMap<String, Object>() {{
        put("status", httpServletResponse.getStatus());
      }};
    }

    // Create a mustache instance and write the merged output to the response
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
      Optional<ApiResponse> apiResponse, Optional<Route> route, Optional<Exception> exception) throws IOException {
    // Make sure our content type is correct
    httpServletResponse.setContentType("application/json;charset=UTF-8");

    // Keep track of what to write to the response
    Map<String, Object> model = null;

    if (apiResponse.isPresent()) {
      // Happy path - resource method completed successfully and returned a value
      model = apiResponse.get().model().orElse(null);
    } else {
      // There was a problem - render an error response
      model = new HashMap<String, Object>() {{
        put("status", httpServletResponse.getStatus());
        put("message", "An error occurred!");        
      }};
    }

    // Write JSON to the response
    try (OutputStream outputStream = httpServletResponse.getOutputStream()) {
      objectMapper.writeValue(outputStream, model);
    }
  }  
}
```

## Error Handling

When an exception is thrown by a resource method, it's up to your ```ExceptionStatusMapper``` to determine the appropriate HTTP status code and your ```ResponseWriter``` implementations to figure out how to communicate details back to the user (for example, render a custom error page or a special JSON for your API).


#### Standard Exception Types

Soklet provides these exceptions out of the box, but any exception your code throws will work.  By default, other exception types will return a 500 status, but you can customize this behavior - see the **Customizing Status Codes** section below. 

* ```BadRequestException``` - 400
* ```AuthenticationException``` - 401
* ```AuthorizationException``` - 403
* ```NotFoundException``` - 404
* ```MethodNotAllowedException``` - 405

#### Example Resource Method

```java
// Example URL: /users/ba19be82-5d90-4b3b-b78f-284c5b86ae11
@GET("/users/{userId}")
public ApiResponse user(@PathParameter UUID userId) {
  Optional<User> user = userService.find(userId);

  if(!user.isPresent())
    throw new NotFoundException(format("No user was found with ID %s", userId));
    
  if(user.get().isTopSecret() && !currentContext.isAdministrator())
    throw new MyCustomException("You can't see this top-secret user!");
  
  return new ApiResponse(user); 
}
```

#### Customizing Status Codes

```java
public static void main(String[] args) throws Exception {
  Injector injector = Guice.createInjector(Modules.override(new SokletModule()).with(new AppModule()));
  Server server = injector.getInstance(Server.class);
  new ServerLauncher(server).launch(StoppingStrategy.ON_KEYPRESS);
}

class AppModule extends AbstractModule {
  // Override Soklet's default ExceptionStatusMapper
  @Provides
  @Singleton
  public ExceptionStatusMapper provideExceptionStatusMapper() {
    return new DefaultExceptionStatusMapper() {
      @Override
      public int statusForException(Exception exception) {
        // Special status for this exception 
        if(exception instanceof MyCustomException)
          return 403;
          
        // Fall back to defaults for others
        return super.statusForException(exception);
      }
    };
  }
}
```

## App Configuration

#### Server Setup

There's no need for a `web.xml` file.  Your server is configured in code.  You just need to pick a ```Server``` implementation.

* Jetty support is provided by [soklet-jetty](https://github.com/soklet/soklet-jetty)
* Experimental Tomcat support is provided by [soklet-tomcat](https://github.com/soklet/soklet-tomcat) 

Jetty is recommended unless you have special requirements.

```java
public static void main(String[] args) throws Exception {
  Injector injector = Guice.createInjector(Modules.override(new SokletModule()).with(new AppModule()));
  Server server = injector.getInstance(Server.class);
  new ServerLauncher(server).launch(StoppingStrategy.ON_KEYPRESS);
}

class AppModule extends AbstractModule {
  @Provides
  @Singleton
  public Server provideServer(InstanceProvider instanceProvider) {
    // Assumes you're using Jetty as your server via soklet-jetty.
    // If you prefer soklet-tomcat, the only change is specifying
    // "TomcatServer" instead of "JettyServer"
    
    // Listen on a specific IP - default is "0.0.0.0", which listens on anything
    String host = "127.0.0.1";
    int port = 8080;
    
    // Tells Jetty about your static files (CSS, JS, etc.)
    //
    // First parameter: static file URL pattern
    // Second parameter: static file root directory on disk
    // Third parameter: special cache-header handling (default, cache-never, or cache-forever)
    //
    // Static files are served using Jetty's DefaultServlet for efficiency.
    // For even more better performance in production, you can instead serve these with nginx
    StaticFilesConfiguration staticFilesConfiguration =
      new StaticFilesConfiguration("/static/*", Paths.get("web/public"), CacheStrategy.DEFAULT);

    // In general, Soklet prefers mapping URLs to regular Java methods and sidestepping
    // traditional Servlets and Filters. However, there is a large existing ecosystem of useful
    // Servlets and Filters, so it's often useful to incorporate a few into your app.
    //
    // Soklet uses your dependency injection framework to instantiate Servlets and Filters.
    
    // Standard Jetty CrossOriginFilter (CORS) configuration.
    // These security options are unsafe, but may be useful for development
    FilterConfiguration corsFilter = new FilterConfiguration(CrossOriginFilter.class, "/*",
      new HashMap<String, String>() {{
        put(CrossOriginFilter.ALLOWED_METHODS_PARAM, "GET,POST,PUT,DELETE");
        put(CrossOriginFilter.ALLOWED_ORIGINS_PARAM, "*");
        put(CrossOriginFilter.ALLOWED_HEADERS_PARAM, "*");
      }}));
    
    // Captcha servlet configuration
    ServletConfiguration captchaServlet = new ServletConfiguration(MyCaptchaServlet.class, "/captcha");

    // WebSocket configuration.
    // See "WebSockets" section below for an example of how you might implement one
    WebSocketConfiguration leaderboardWebSocket = new WebSocketConfiguration(LeaderboardWebSocket.class);
    
    // Finally, build the server instance
    return JettyServer.forInstanceProvider(instanceProvider)
      .host(host)
      .port(port)
      .staticFilesConfiguration(staticFilesConfiguration)
      .servletConfigurations(singletonList(captchaServlet))
      .filterConfigurations(singletonList(corsFilter))
      .webSocketConfigurations(singletonList(leaderboardWebSocket))
      .build();
  }
}
```

### WebSockets

Oracle provides a nice explanation of WebSockets in its <a href="http://docs.oracle.com/middleware/1213/wls/WLPRG/websockets.htm" target="_blank">WebLogic documentation</a>.  Here's an important quote:

> As opposed to servlets, WebSocket endpoints are instantiated multiple times. The container creates one instance of an endpoint for each connection to its deployment URI. Each instance is associated with one and only one connection. This behavior facilitates keeping user state for each connection and simplifies development because only one thread is executing the code of an endpoint instance at any given time.

Like Servlets and Filters, Soklet will use your dependency injection library to provide WebSocket instances.  All you have to do is build your WebSockets using standard JSR-356 annotations like `@ServerEndpoint`, `@OnOpen`, `@OnMessage`, `@OnClose`, and `@OnError`.  The `@ServerEndpoint` annotation is required for the WebSocket to function.

A common implementation pattern is for a WebSocket to listen for events from some other system component using a Listener pattern or event bus and, when system state changes, data is written to the client.

```java
// Other imports elided
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

// Example of a WebSocket that listens for events from the backend
// and sends notifications down to the client.
@ServerEndpoint(value = "/websockets/leaderboard")
public class LeaderboardWebSocket implements MyLeaderboardServiceListener {
  // WebSocket session
  private Session session;
  // Hypothetical backend service
  private MyLeaderboardService leaderboardService;

  @Inject
  public LeaderboardWebSocket(MyLeaderboardService leaderboardService) {
    this.leaderboardService = leaderboardService;
  }

  @OnOpen
  public void onWebSocketConnect(Session session) {
    // Hold a reference to our session - this is how we communicate with the client
    this.session = session;

    // Listen for events from our backend
    leaderboardService.registerListener(this);
  }

  @OnMessage
  public void onWebSocketText(String message) {
    out.println("WebSocket received a message: " + message);
  }

  @OnClose
  public void onWebSocketClose(CloseReason closeReason) {
    out.println("WebSocket closed. Reason: " + closeReason.getCloseCode());

    // Do some cleanup.  Be careful if your service holds strong references to
    // its listeners - this could cause memory leaks
    leaderboardService.unregisterListener(this);

    this.session = null;
  }

  @OnError
  public void onWebSocketError(Throwable throwable) {
    out.println("WebSocket encountered an error: " + throwable.getMessage());
  }

  // Implements our hypothetical MyLeaderboardServiceListener.
  // If the backend tells us data has changed, write some data to the client
  @Override
  public void onLeaderboardChanged() {
    if(session == null)
      return;

    try {
      MyLeaderboard latestLeaderboard = leaderboardService.findLeaderboard();
      session.getBasicRemote().sendText(MyJsonUtils.toJson(latestLeaderboard));
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
```

It is important to be careful of memory leaks.  Suppose your backend maintains a collection of strong references to its WebSocket Listeners.  If your WebSockets don't deregister themselves correctly, they will never be deallocated.  A good strategy here is to store listeners using weak references, like this:

```java
// Other imports elided
import javax.inject.singleton;

@Singleton
public class MyLeaderboardService {
  // This Set automatically purges itself of "expired" weak references thanks to WeakHashMap
  private final Set<MyLeaderboardServiceListener> listeners =
    Collections.synchronizedSet(Collections.newSetFromMap(
      new WeakHashMap<MyLeaderboardServiceListener, Boolean>()));

  public void registerListener(MyLeaderboardServiceListener listener) {
    listeners.add(listener);
  }

  public void unregisterListener(MyLeaderboardServiceListener listener) {
    listeners.remove(listener);
  }

  protected void notifyListeners() {
    synchronized (listeners) {
      for(MyLeaderboardServiceListener listener : listeners) {
        // A real implementation might invoke this method via an ExecutorService.
        // You don't want to block waiting for lots of WebSockets to finish processing
        listener.onLeaderboardChanged();
      }
    }
  }

  // Rest of implementation elided
}
```

#### Interceptors

Resource method interceptors are an alternative to traditional Servlet Filters.  DI frameworks normally provide interceptor functionality on which you can build.

Examples of common interceptors follow. Note that Soklet does not provide any interceptors, database access, or security features out of the box - these examples are for illustration only.

```java
public static void main(String[] args) throws Exception {
  Injector injector = Guice.createInjector(Modules.override(new SokletModule()).with(new AppModule()));
  Server server = injector.getInstance(Server.class);
  new ServerLauncher(server).launch(StoppingStrategy.ON_KEYPRESS);
}

class AppModule extends AbstractModule {
  @Override
  protected void configure() {
    // These interceptors are executed any time a resource method is invoked
            
    // 1. Perform the resource method in the context of a database transaction 
    bindInterceptor(Matchers.annotatedWith(Resource.class),
      SokletMatchers.httpMethodMatcher(), new TransactionInterceptor(new Database()));
      
    // 2. Verify the user is who she says she is!
    bindInterceptor(Matchers.annotatedWith(Resource.class),
      SokletMatchers.httpMethodMatcher(), new SecurityInterceptor(new SecurityService()));           
  }
  
  // Rest of module would follow
}

// Guice interceptor that wraps each resource method in a database transaction
class TransactionInterceptor implements MethodInterceptor {
  private final Database database;
  
  TransactionInterceptor(Database database) {
    this.database = requireNonNull(database);
  }
  
  @Override
  public Object invoke(MethodInvocation methodInvocation) throws Throwable {
    // Note: Database is not part of Soklet, this is for illustration only.
    // If you want simple JDBC functionality, check out http://pyranid.com
    return this.database.transaction(() -> {
      return methodInvocation.proceed();
    });  
  }  
}

// Guice interceptor that performs security checks
class SecurityInterceptor implements MethodInterceptor {
  private final SecurityService securityService;
  private final Provider<RequestContext> requestContextProvider;
  
  SecurityInterceptor(SecurityService securityService, Provider<RequestContext> requestContextProvider) {
    this.securityService = requireNonNull(securityService);
    this.requestContextProvider = requireNonNull(requestContextProvider);
  }
  
  @Override
  public Object invoke(MethodInvocation methodInvocation) throws Throwable {
    // Special use of Soklet's RequestContext to get at current request and route information
    RequestContext requestContext = requestContextProvider.get();    
    Optional<Route> route = requestContext.route();
    
    // If a route matched the URL, get the Java method that should be executed
    // and examine its @RoleRequired annotation to see what access requirements are (if any).
    // Note: SecurityService, @RoleRequired, and Role are not part of Soklet, they are for illustration only
    if(route.isPresent()) {
      String authorization = requestContext.httpServletRequest().getHeader("Authorization");
    
      Method resourceMethod = route.get().resourceMethod();            
      RoleRequired roleRequired = resourceMethod.getAnnotation(RoleRequired.class);
      Role[] requiredRoles = roleRequired == null ? null : roleRequired.value();
      
      // Do some kind of security check
      this.securityService.authorize(authorization, requiredRoles);
    }
  }  
}
```


## Deployment Archives

During development, you will normally launch a Soklet application via Maven or your IDE.  For test and production builds, you'll want to create a deployment archive.  This archive is a self-contained zip file which only requires Java 1.8 to run - no dependency on an external server, Maven, or any other 3rd party package.

Soklet provides an ```Archiver```, which allows you specify how to construct the zip file, similar to an [Ant](http://ant.apache.org) script.  ```Archiver``` exposes customization hooks to give you fine-grained control over how to build your archive.

The difference between archiving and just running an app is that archiving is a great opportunity to perform additional time-consuming work that you don't normally want to do during development.  Some common examples are:

* Compressing/combining JS and CSS files
* Hashing static resources (handled by Soklet; see **Hashed Files** section below)
* Pre-gzipping static resources for efficient serving (handled by Soklet)

Note that archiving is done in a temporary sandbox directory, so your current working directory is untouched.

```java
public static void main(String[] args) throws Exception {
  // Archive file to create
  Path archiveFile = Paths.get("my-app.zip");

  // Copy these directories and files into the archive.
  // If you specify a directory, its contents are copied to the destination.
  // If you specify a single file, it is copied to the destination.
  // If no destination is specified, it is assumed to be identical to the source
  Set<ArchivePath> archivePaths = new HashSet<ArchivePath>() {
    {
      // Directories
      add(ArchivePaths.get(Paths.get("web")));
      add(ArchivePaths.get(Paths.get("config")));

      // Class and JAR directories, automatically populated by mavenSupport() below
      add(ArchivePaths.get(Paths.get("target/dependency"), Paths.get("lib")));
      add(ArchivePaths.get(Paths.get("target/classes"), Paths.get("classes")));

      // Single files
      add(ArchivePaths.get(Paths.get("scripts/start"), Paths.get(".")));
      add(ArchivePaths.get(Paths.get("scripts/stop"), Paths.get(".")));
    }
  };

  // The archiver will create copies of static files, embedding a hash of the file contents
  // in the filename.  See "Hashed Files" section for more about this
  StaticFileConfiguration staticFileConfiguration =
      StaticFileConfiguration.forRootDirectory(Paths.get("web/public/static"))
        .hashedUrlManifestJsFile(Paths.get("js/hashed-urls.js")).build();

  // You may optionally alter files in-place - for example, here we compress JS and CSS files.
  // The Archiver works in its own sandbox, so any alterations performed are written
  // to a temporary file, leaving the original untouched
  FileAlterationOperation fileAlterationOperation = (archiver, workingDirectory, file) -> {
    String filename = file.getFileName().toString().toLowerCase(ENGLISH);

    // Compression implementations are left to your imagination
    if (filename.endsWith(".js"))
      return Optional.of(compressJavascriptFile(file));
    if (filename.endsWith(".css"))
      return Optional.of(compressCssFile(file));

    // Returning empty means the file should not be altered
    return Optional.empty();
  };

  // Maybe we use grunt to do some extra build-time processing (LESS -> CSS, for example).
  // You can launch arbitrary processes using ArchiverProcess
  ArchiveSupportOperation preProcessOperation = (archiver, workingDirectory) -> {
    // The working directory is Soklet's temporary archive-building sandbox directory
    new ArchiverProcess("/usr/local/bin/grunt", workingDirectory).execute("clean");
    new ArchiverProcess("/usr/local/bin/grunt", workingDirectory).execute();
  };

  // Build and run our Archiver.
  // Specifying 'mavenSupport()' here means standard Maven clean, compile,
  // and dependency goals are used as part of the archiving process.
  // If you don't use Maven, it's your responsibility to compile your code
  // and include dependency JARs in the archive
  Archiver archiver = Archiver.forArchiveFile(archiveFile)
    .archivePaths(archivePaths)
    .staticFileConfiguration(staticFileConfiguration)
    .fileAlterationOperation(fileAlterationOperation)
    .preProcessOperation(preProcessOperation)
    .mavenSupport().build();

  archiver.run();
}
```

## Hashed Files

Soklet's archive process will create copies of your static files and embed a content-based hash in the copied filename.  Further, a manifest is created which maps original URL paths to hashed URL paths for use at runtime. 

For example

```
static/js/jquery.js
```

Might have a hashed copy like

```
static/js/jquery.D1F585EEEC4308D432181FF88068830A.js
```

#### Why Is This Important?

The hashing process and corresponding manifest is useful because:

* You never have to worry about browsers using outdated files in a local cache
* You can send "cache forever" HTTP headers when serving files with embedded hashes
* Hashing by file content ensures browser cache misses only occur when files themselves change, in contrast to other strategies like ```/file?version=1.1```
* Embedding hash in filename instead of as a query parameter can result in better proxy performance

#### Manifest Creation

The archive process will create a hashed URL manifest file at the root of the archive named ```hashedUrlManifest```.
Its format is not formally defined and is subject to change, but for illustration purposes it might look like this:

```json
{
  "/static/images/cartoon.png": "/static/images/cartoon.D958A21CF25246CA0ED6AA8BF0B1940E.png",
  "/static/js/jquery.js": "/static/js/jquery.D1F585EEEC4308D432181FF88068830A.js",
  "/static/js/hashed-urls.js": "/static/js/hashed-urls.5EA2BAEF93DA17B978E20E0507A1F56E.js"
}
```

You can use it in Java code like this:

```java
// Default ctor loads manifest from file named ```hashedUrlManifest``` in working directory
HashedUrlManifest hashedUrlManifest = new HashedUrlManifest();
Optional<String> hashedUrl = hashedUrlManifest.hashedUrl("/static/js/jquery.js");

// Output is "Optional[/static/js/jquery.D1F585EEEC4308D432181FF88068830A.js]"
out.println(hashedUrl);

String failsafeHashedUrl = hashedUrlManifest.hashedUrlWithFallback("/static/js/fake.js");

// Output is "/static/js/fake.js"
out.println(failsafeHashedUrl);
```

Archiving also creates a JavaScript version of the manifest (configured to be ```/static/js/hashed-urls.js``` above), useful for when JavaScript must load up static resources - for example, creating an ```img``` tag in code, or dynamically loading a script.

The content of the JavaScript version of the manifest might look like this:

```js
soklet.hashedUrls = {
  "/static/images/cartoon.png": "/static/images/cartoon.D958A21CF25246CA0ED6AA8BF0B1940E.png",
  "/static/js/jquery.js": "/static/js/jquery.D1F585EEEC4308D432181FF88068830A.js",
  "/static/js/hashed-urls.js": "/static/js/hashed-urls.5EA2BAEF93DA17B978E20E0507A1F56E.js"
};
```

#### Hashing Example: HTML templates

If you use [Mustache.java](https://github.com/spullara/mustache.java) to render your HTML, you might configure it to support hashed URLs as follows:

```java
// Define a custom Mustache TemplateFunction
model.put("hashedUrl", new Function<String, String>() {
  @Override
  public String apply(String url) {
    return hashedUrlManifest.hashedUrlWithFallback(url);
  }
});

// Later on...
Mustache mustache = mustacheFactory.compile("...");
mustache.execute(writer, model).flush();
```

Your Mustache markup might look like this:

```html
<link href="{{#hashedUrl}}/static/css/my-app.css{{/hashedUrl}}" type="text/css" rel="stylesheet" />
```

...and then at runtime:

```html
<link href="/static/css/my-app.7EA2BAEF93DA17B978E20E0507A1F56E.css" type="text/css" rel="stylesheet" />
```

#### Hashing Example: API Responses

This server-side code

```java
@GET("/api/movies")
public ApiResponse movies() {
  String baseUrl = "http://example.website.com";
  String imageUrl = baseUrl + hashedUrlManifest.hashedUrlWithFallback("/static/images/clue.png");

  return new ApiResponse(new ArrayList<Map<String, Object>>() {
    {
      put("id", 123);
      put("title", "Clue");
      put("imageUrl", imageUrl);
    }
  });
}
```

Might render

```json
{
  "id" : 123,
  "title" : "clue",
  "imageUrl" : "http://example.website.com/static/images/clue.A1F585EEEC4308D432181FF88068830A.png"
}
```

#### Hashing Example: JavaScript

```js
myApp.hashedUrl = function(url) {
  var hashedUrl = soklet.hashedUrls[url];
  return hashedUrl ? hashedUrl : url;
};

// Creates a tag like <img src='/static/images/cartoon.D958A21CF25246CA0ED6AA8BF0B1940E.png'/>
$("body").append("<img src='" + myApp.hashedUrl("/static/images/cartoon.png") + "'/>");
```

#### CSS File Hashing

During the archive process, Soklet will automatically detect and rewrite references to hashed URLs in your CSS files.

For example, this CSS rule:

```css
body {
  background-image: url("/static/images/cartoon.png");
}
```

Might be rewritten to this:

```css
body {
  background-image: url("/static/images/cartoon.D958A21CF25246CA0ED6AA8BF0B1940E.png");
}
```

Relative paths are automatically rewritten as well:

```css
body {
  background-image: url("images/cartoon.png");
}

.example {
  background-image: url("../images/cartoon.png");
}
```

**WARNING!**

Currently, there are restrictions on CSS rewriting.  They are:

* URLs cannot contain inner ```..``` and ```.``` values.  For example, ```../images/cartoon.png``` is OK but ```../images/../cartoon.png``` is not
* CSS ```@import``` URLs should be avoided (Soklet will rewrite the URLs, but the hashes may be "stale" in cases where there are chains of imports, e.g. CSS file 1 imports CSS file 2 which imports CSS file 3)

Soklet will warn you if it detects either of these conditions.

## java.util.logging

Soklet uses ```java.util.logging``` internally.  The usual way to hook into this is with [SLF4J](http://slf4j.org), which can funnel all the different logging mechanisms in your app through a single one, normally [Logback](http://logback.qos.ch).  Your Maven configuration might look like this:

```xml
<dependency>
  <groupId>ch.qos.logback</groupId>
  <artifactId>logback-classic</artifactId>
  <version>1.1.9</version>
</dependency>
<dependency>
  <groupId>org.slf4j</groupId>
  <artifactId>jul-to-slf4j</artifactId>
  <version>1.7.22</version>
</dependency>
```

Because it is such a common operation, Soklet provides an optional facility for configuring Logback.
You might have code like this which runs at startup:

```java
// Configures Logback; also bridges java.util.Logging calls
LoggingUtils.initializeLogback(Paths.get("config/logback.xml"));
```

## About

Soklet was created by [Mark Allen](http://revetkn.com) and sponsored by [Transmogrify, LLC.](http://xmog.com)