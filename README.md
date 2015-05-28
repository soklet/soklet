## Soklet

#### What Is It?

Minimalist infrastructure for Java webapps and microservices.

#### Design Goals

* Single focus, unopinionated
* No external servlet container required
* Fast startup
* No 3rd party dependencies - uses only standard JDK APIs
* Extensible - applications can easily hook/override core functionality vi DI
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
  <version>1.1.0</version>
</dependency>
```

#### Direct Download

If you don't use Maven, you can drop [soklet-1.1.0.jar](http://central.maven.org/maven2/com/soklet/soklet/1.1.0/soklet-1.1.0.jar) directly into your project.  You'll also need [javax.inject-1.jar](http://central.maven.org/maven2/javax/inject/javax.inject/1/javax.inject-1.jar) and [javax.servlet-api-3.1.0.jar](http://central.maven.org/maven2/javax/servlet/javax.servlet-api/3.1.0/javax.servlet-api-3.1.0.jar) as dependencies.

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
  // See "Response Writers" section for details
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

  // You may specify @RequestBody on a String or InputStream parameter for easy access.
  // As elsewhere, if the request body is not required, wrap the parameter in an Optional<T>
  @POST("/api/hello")
  public ApiResponse createHello(@RequestBody String requestBody) {
    // It's up to you to parse the request body however you'd like
    HelloCreateCommand command = parse(requestBody, HelloCreateCommand.class);
    Hello hello = helloService.createHello(command);
    return new ApiResponse(201, hello);
  }

  // BinaryResponse allows you to specify arbitrary data and content type.
  // Useful for PDFs, CSVs, edge cases
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
}
```

#### Resource Method Return Types

There are 5 standard resource method return types provided by Soklet.

* ```ApiResponse``` Holds an arbitrary object that is meant to be written as an "API" response (often JSON or XML)
* ```AsyncResponse``` Signifies to Soklet that no response should be written and you plan to use Servlet 3.1 nonblocking I/O to handle it yourself.  Useful if you have an expensive computation to perform and don't want to tie up a request thread
* ```BinaryResponse``` Designed for writing arbitrary content to the response, e.g. streaming a PDF
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
      model = new HashMap<String, Object>() {
        {
          put("status", httpServletResponse.getStatus());
        }
      };
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
      model = new HashMap<String, Object>() {
        {
          put("status", httpServletResponse.getStatus());
          put("message", "An error occurred!");
        }
      };
    }

    // Write JSON to the response
    try (OutputStream outputStream = httpServletResponse.getOutputStream()) {
      objectWriter.writeValue(outputStream, model);
    }
  }  
}
```

<!--
## Customization

Coming soon

-->

## Deployment Archives

During development, you will normally launch a Soklet application via Maven or your IDE.  For test and production builds, you'll want to create a deployment archive.  This archive is a self-contained zip file which only requires Java 1.8 to run - no dependency on an external server, Maven, or any other 3rd party package.

Soklet provides an ```Archiver```, which allows you specify how to construct the zip file, similar to an [Ant](http://ant.apache.org) script.  ```Archiver``` exposes customization hooks to give you fine-grained control over how to build your archive.

The difference between archiving and just running an app is that archiving is a great opportunity to perform additional time-consuming work that you don't normally want to do during development.  Some common examples are:

* Compressing/combining JS and CSS files
* Hashing static resources (handled by Soklet; see **Hashed Files** section below)
* Pre-gzipping static resources for efficient serving (handled by Soklet)

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
    new ArchiverProcess("grunt", workingDirectory).execute("clean");
    new ArchiverProcess("grunt", workingDirectory).execute();
  };

  // Build and run our Archiver.
  // Specifying 'mavenSupport()' here means standard Maven clean, compile,
  // and dependency goals are used as part of the archiving process.
  // If you don't use Maven, it's your responsibility to compile your code
  // and include dependency JARs in the archive
  Archiver archiver =
      Archiver.forArchiveFile(archiveFile)
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
HashedUrlManifest hashedUrlManifest = new hashedUrlManifest();
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
model.put("hashedUrl", new TemplateFunction() {
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
<link href="{{#hashedUrl}}/static/css/myapp.css{{/hashedUrl}}" type="text/css" rel="stylesheet" />
```

...and then at runtime:

```html
<link href="/static/css/myapp.7EA2BAEF93DA17B978E20E0507A1F56E.css" type="text/css" rel="stylesheet" />
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
myapp.hashedUrl = function(url) {
  var hashedUrl = soklet.hashedUrls[url];
  return hashedUrl ? hashedUrl : url;
};

// Creates a tag like <img src='/static/images/cartoon.D958A21CF25246CA0ED6AA8BF0B1940E.png'/>
$("body").append("<img src='" + myapp.hashedUrl("/static/images/cartoon.png") + "'/>);
```

#### CSS Files

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

**WARNING!**

Currently, there are restrictions on CSS rewriting.  They are:

* Any static file references must be absolute, e.g. ```/static/images/cartoon.png``` is OK but ```../images/cartoon.png``` is not (Soklet attempts to resolve relative paths but success is not guaranteed)
* CSS ```@import``` URLs should be avoided (Soklet will rewrite the URLs, but the hashes may be "stale" in cases where there are chains of imports, e.g. CSS file 1 imports CSS file 2 which imports CSS file 3)

Soklet will warn you if it detects either of these conditions.

## java.util.Logging

Soklet uses ```java.util.Logging``` internally.  The usual way to hook into this is with [SLF4J](http://slf4j.org), which can funnel all the different logging mechanisms in your app through a single one, normally [Logback](http://logback.qos.ch).  Your Maven configuration might look like this:

```xml
<dependency>
  <groupId>org.slf4j</groupId>
  <artifactId>jul-to-slf4j</artifactId>
  <version>1.7.7</version>
</dependency>
```

You might have code like this which runs at startup:

```java
// Bridge all java.util.logging to SLF4J
java.util.logging.Logger rootLogger = java.util.logging.LogManager.getLogManager().getLogger("");
for (Handler handler : rootLogger.getHandlers())
  rootLogger.removeHandler(handler);

SLF4JBridgeHandler.install();
```

Don't forget to uninstall the bridge at shutdown time:

```java
// Sometime later
SLF4JBridgeHandler.uninstall();
```

Note: ```SLF4JBridgeHandler``` can impact performance.  You can mitigate that with Logback's ```LevelChangePropagator``` configuration option [as described here](http://logback.qos.ch/manual/configuration.html#LevelChangePropagator).

<!--
## FAQ

Coming soon
-->

## About

Soklet was created by [Mark Allen](http://revetkn.com) and sponsored by [Transmogrify, LLC.](http://xmog.com)