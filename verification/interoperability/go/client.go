package main

import (
	"context"
	"fmt"
	"net/url"
	"os"
	"slices"
	"sort"
	"strconv"
	"time"

	"github.com/modelcontextprotocol/go-sdk/mcp"
)

func main() {
	endpoint := os.Getenv("SOKLET_INTEROP_URL")
	protocolVersion := os.Getenv("SOKLET_INTEROP_PROTOCOL_VERSION")
	expectedTool := os.Getenv("SOKLET_INTEROP_EXPECTED_TOOL")
	parsedEndpoint, err := url.Parse(endpoint)
	if err != nil || parsedEndpoint.Scheme != "http" || parsedEndpoint.Hostname() != "127.0.0.1" ||
		parsedEndpoint.Path != "/mcp" || parsedEndpoint.RawQuery != "" || parsedEndpoint.Fragment != "" ||
		parsedEndpoint.User != nil {
		panic("interoperability environment is invalid")
	}
	if port, err := strconv.Atoi(parsedEndpoint.Port()); err != nil || port < 1 || port > 65535 ||
		protocolVersion != "2026-07-28" || expectedTool != "test_simple_text" {
		panic("interoperability environment is invalid")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	client := mcp.NewClient(&mcp.Implementation{
		Name:    "soklet-go-interoperability",
		Version: "1.0.0",
	}, nil)
	session, err := client.Connect(ctx, &mcp.StreamableClientTransport{Endpoint: endpoint}, nil)
	if err != nil {
		panic(err)
	}
	defer session.Close()
	if session.InitializeResult().ProtocolVersion != protocolVersion {
		panic("unexpected negotiated protocol version")
	}

	listing, err := session.ListTools(ctx, nil)
	if err != nil {
		panic(err)
	}
	toolNames := make([]string, 0, len(listing.Tools))
	for _, tool := range listing.Tools {
		toolNames = append(toolNames, tool.Name)
	}
	sort.Strings(toolNames)
	expectedTools := []string{
		"json_schema_2020_12_tool",
		"test_audio_content",
		"test_custom_header",
		"test_embedded_resource",
		"test_error_handling",
		"test_image_content",
		"test_multiple_content_types",
		"test_simple_text",
		"test_tool_with_progress",
	}
	if !slices.Equal(toolNames, expectedTools) {
		panic("fixture returned an unexpected tool catalog")
	}

	result, err := session.CallTool(ctx, &mcp.CallToolParams{
		Name:      expectedTool,
		Arguments: map[string]any{},
	})
	if err != nil {
		panic(err)
	}
	if result.IsError {
		panic("tool unexpectedly returned an application error")
	}
	if len(result.Content) != 1 {
		panic("tool returned unexpected content cardinality")
	}
	text, ok := result.Content[0].(*mcp.TextContent)
	if !ok || text.Text != "This is a simple text response for testing." {
		panic("tool returned unexpected text")
	}
	fmt.Printf("SOKLET_INTEROP_PASS %s go\n", protocolVersion)
}
