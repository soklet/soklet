# Soklet MCP quickstart

This is the shortest complete Soklet 4.0.0 MCP server: one annotation-processed
tool, one dedicated localhost endpoint, and a standalone application lifecycle.
It targets exactly Soklet's MCP `2026-07-28` profile.

## 1. Configure the dependency and compiler

Soklet's annotation processor generates the endpoint descriptor at compile
time. Keep Java parameter names and select the processor explicitly:

```xml
<properties>
  <maven.compiler.release>17</maven.compiler.release>
</properties>

<dependencies>
  <dependency>
    <groupId>com.soklet</groupId>
    <artifactId>soklet</artifactId>
    <version>4.0.0</version>
  </dependency>
</dependencies>

<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <version>3.14.0</version>
      <configuration>
        <parameters>true</parameters>
        <annotationProcessorPaths>
          <path>
            <groupId>com.soklet</groupId>
            <artifactId>soklet</artifactId>
            <version>4.0.0</version>
          </path>
        </annotationProcessorPaths>
        <annotationProcessors>
          <annotationProcessor>com.soklet.SokletProcessor</annotationProcessor>
        </annotationProcessors>
      </configuration>
    </plugin>
  </plugins>
</build>
```

If a build shades or repackages classes, preserve the generated Soklet endpoint
provider and index resources under `META-INF`. Named Java modules must open or
export the endpoint package to Soklet; a package containing a non-public record
used for runtime conversion must be open to Soklet.

## 2. Define one tool

Save this as `src/main/java/example/CatalogMcpEndpoint.java`:

```java
package example;

import com.soklet.McpInvocationFeatures;
import com.soklet.McpRequestContext;
import com.soklet.annotation.McpServerEndpoint;
import com.soklet.annotation.McpTool;
import com.soklet.annotation.McpToolArgument;

import java.util.List;

@McpServerEndpoint(
    path = "/catalog/mcp",
    name = "catalog",
    version = "1.0.0",
    instructions = "Search the catalog")
public final class CatalogMcpEndpoint {
  @McpTool(
      name = "catalog.search",
      title = "Search the catalog",
      description = "Searches for matching catalog items")
  public SearchResult search(
      McpRequestContext request,
      @McpToolArgument(
          name = "query",
          title = "Search query",
          description = "Text to search for") String query,
      McpInvocationFeatures features) {
    return new SearchResult(List.of("Match for " + query));
  }

  public record SearchResult(List<String> matches) {}
}
```

The input and output schemas are derived from the Java declaration. Soklet
validates both and does not accept an application-supplied replacement schema.

## 3. Build and run the server

Save this as `src/main/java/example/CatalogMcpApp.java`:

```java
package example;

import com.soklet.CorsAuthorizer;
import com.soklet.McpAdmissionController;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpRateLimiter;
import com.soklet.McpServer;
import com.soklet.ShutdownTrigger;
import com.soklet.SokletApplication;
import com.soklet.SokletConfig;

import java.util.Set;

public final class CatalogMcpApp {
  public static void main(String[] args) {
    McpEndpointRegistry endpointRegistry =
        McpEndpointRegistry.fromClasses(CatalogMcpEndpoint.class);
    McpAdmissionController admissionController =
        McpAdmissionController.acceptAllInstance();

    McpServer mcpServer = McpServer.withPort(
            8081, endpointRegistry, admissionController)
        .host("127.0.0.1")
        .toolRateLimiter(McpRateLimiter.fromInMemoryDefaults())
        .corsAuthorizer(CorsAuthorizer.rejectAllInstance())
        .allowedHosts(Set.of("127.0.0.1"))
        .build();

    SokletConfig config = SokletConfig.withMcpServer(mcpServer).build();
    System.out.println(
        "Starting MCP at http://127.0.0.1:8081/catalog/mcp; "
            + "press Enter to stop once ready");
    SokletApplication.run(config, ShutdownTrigger.ENTER_KEY);
  }
}
```

Then run:

```sh
mvn -B -ntp clean package
java -cp target/classes:$HOME/.m2/repository/com/soklet/soklet/4.0.0/soklet-4.0.0.jar \
  example.CatalogMcpApp
```

The classpath separator above is for macOS/Linux; use `;` on Windows.

The anonymous admission controller and node-local in-memory limiter are
development choices. Before a remote deployment, supply application-owned
authentication and authorization, fleet-appropriate limiting, an intentional
Origin policy, allowed hosts, and TLS termination. See the worked
[OAuth resource-server pattern](release/MCP_OAUTH_RESOURCE_SERVER.md).

## 4. Connect over localhost HTTP

The endpoint accepts MCP over Streamable HTTP `POST`; it does not use the old
initialize/session/GET-SSE lifecycle. This discovery request is useful before
configuring a host:

```sh
curl --fail-with-body --silent --show-error \
  --request POST http://127.0.0.1:8081/catalog/mcp \
  --header 'Host: 127.0.0.1:8081' \
  --header 'Content-Type: application/json' \
  --header 'Accept: application/json, text/event-stream' \
  --header 'MCP-Protocol-Version: 2026-07-28' \
  --header 'Mcp-Method: server/discover' \
  --data '{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
```

For MCP Inspector 2.3.0, save this as `inspector.json`:

```json
{
  "mcpServers": {
    "soklet": {
      "type": "http",
      "url": "http://127.0.0.1:8081/catalog/mcp",
      "protocolEra": "modern"
    }
  }
}
```

List and invoke the tool:

```sh
npx --yes @modelcontextprotocol/inspector@2.3.0 --cli \
  --config ./inspector.json --server soklet \
  --method tools/list --format json

npx --yes @modelcontextprotocol/inspector@2.3.0 --cli \
  --config ./inspector.json --server soklet \
  --method tools/call --tool-name catalog.search \
  --tool-args-json '{"query":"sprocket"}' --format json
```

See the dated [client compatibility matrix](release/MCP_CLIENT_COMPATIBILITY.md)
for the exact manual-smoke record and the complete [MCP reference](MCP.md) for
capabilities, limits, simulation, and production boundaries.
