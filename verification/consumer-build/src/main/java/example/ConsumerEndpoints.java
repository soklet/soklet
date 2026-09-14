package example;

import com.soklet.McpPromptMessage;
import com.soklet.McpPromptOutput;
import com.soklet.annotation.GET;
import com.soklet.annotation.McpPrompt;
import com.soklet.annotation.McpPromptArgument;
import com.soklet.annotation.McpServerEndpoint;
import com.soklet.annotation.McpTool;
import com.soklet.annotation.McpToolArgument;

import java.util.List;
import java.util.Optional;

/** Consumer-owned declarations; no core test classes or runtime dependencies. */
@McpServerEndpoint(path = "/catalog/mcp", name = "catalog", version = "1.0.0")
public final class ConsumerEndpoints {
  @GET("/hello")
  public String hello() {
    return "consumer-ok";
  }

  @McpTool(name = "catalog.search")
  public SearchResult search(@McpToolArgument String query) {
    return new SearchResult(List.of("Match for " + query));
  }

  @McpPrompt(name = "catalog.prompt")
  public McpPromptOutput prompt(@McpPromptArgument String query,
      @McpPromptArgument Optional<String> suffix) {
    return McpPromptOutput.fromMessages(McpPromptMessage.fromUserText(
        query + suffix.orElse("!")));
  }

  public record SearchResult(List<String> matches) {}
}
