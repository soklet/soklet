/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet;

import com.soklet.annotation.McpToolArgument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.concurrent.ThreadSafe;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused deterministic catalog extraction and canonical slot-plan tests. */
@ThreadSafe
class McpLocalizationCatalogExtractionTests {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String WIRE_PATH = "/localization/wire";
	private static final String JSON_MEDIA_TYPE = "application/json";
	private static final Duration SIMULATOR_WAIT = Duration.ofSeconds(5);

	@Test
	void extractsEveryProgrammaticSurfaceAndPreservesCanonicalObjects() {
		McpEndpoint endpoint = endpoint(false);
		McpEndpointRegistry registry = McpEndpointRegistry.fromEndpoints(
				List.of(endpoint));

		McpCanonicalLocalizationPlan plan =
				DefaultMcpLocalizationCatalogExtractor.plan(registry, 100);
		List<McpLocalizableText> texts = plan.texts();
		List<String> externalKeys = texts.stream()
				.map(text -> text.getCoordinate().toExternalKey()).toList();
		List<String> sortedKeys = externalKeys.stream().sorted().toList();

		assertEquals(sortedKeys, externalKeys);
		assertEquals(externalKeys.size(), new HashSet<>(externalKeys).size());
		assertEquals(Set.of(
				"Server title", "Server description", "Endpoint instructions",
				"Tool title", "Tool description", "Annotation title",
				"Input title", "Input description", "Output title",
				"Prompt title", "Prompt description", "Topic title",
				"Topic description", "Resource title", "Resource description",
				"Template title", "Template description"),
				new HashSet<>(texts.stream()
						.map(McpLocalizableText::getDefaultText).toList()));

		McpCanonicalLocalizationPlan.EndpointPlan endpointPlan =
				plan.endpoints().get(0);
		assertEquals("/catalog/mcp", endpointPlan.endpointPath());
		assertEquals(5, endpointPlan.responses().size());
		McpCanonicalLocalizationPlan.ResponsePlan discovery = endpointPlan
				.response(McpCanonicalLocalizationPlan.ResponseKind.DISCOVERY)
				.orElseThrow();
		assertEquals(List.of(
				"/_meta/io.modelcontextprotocol~1serverInfo/title",
				"/_meta/io.modelcontextprotocol~1serverInfo/description",
				"/instructions"), discovery.slots().stream()
				.map(McpCanonicalLocalizationPlan.Slot::targetPointer).toList());
		McpCanonicalLocalizationPlan.ResponsePlan tools = endpointPlan
				.response(McpCanonicalLocalizationPlan.ResponseKind.TOOLS_LIST)
				.orElseThrow();
		assertTrue(tools.slots().stream().anyMatch(slot -> slot.targetPointer()
				.equals("/tools/0/inputSchema/properties/query~1text/title")));
		McpLocalizableText inputTitle = tools.slots().stream()
				.filter(slot -> slot.targetPointer().endsWith(
						"/properties/query~1text/title"))
				.map(McpCanonicalLocalizationPlan.Slot::text)
				.findFirst().orElseThrow();
		assertEquals("/inputSchema/properties/query~1text/title",
				inputTitle.getCoordinate().getMemberPath());
		assertSame(inputTitle, texts.stream()
				.filter(inputTitle::equals).findFirst().orElseThrow());

		assertSame(endpoint.getTools().get(0).getInputSchema().getDocument(),
				endpoint.getTools().get(0).getInputSchema().getDocument());
		assertEquals("Input title", schemaProperty(endpoint, "query/text")
				.find("title").map(McpJsonString.class::cast)
				.map(McpJsonString::getValue).orElseThrow());
		assertThrows(UnsupportedOperationException.class,
				() -> texts.clear());
		assertThrows(UnsupportedOperationException.class,
				() -> tools.slots().clear());
	}

	@Test
	void customListOwnsExactDescriptorsButNotStaticTemplates() {
		McpCanonicalLocalizationPlan plan =
				DefaultMcpLocalizationCatalogExtractor.plan(
						McpEndpointRegistry.fromEndpoints(List.of(endpoint(true))), 100);

		assertTrue(plan.endpoints().get(0)
				.response(McpCanonicalLocalizationPlan.ResponseKind.RESOURCES_LIST)
				.isEmpty());
		assertTrue(plan.endpoints().get(0)
				.response(McpCanonicalLocalizationPlan.ResponseKind
						.RESOURCE_TEMPLATES_LIST).isPresent());
		Set<McpTextCoordinate.Kind> kinds = plan.texts().stream()
				.map(text -> text.getCoordinate().getKind())
				.collect(java.util.stream.Collectors.toSet());
		assertFalse(kinds.contains(McpTextCoordinate.Kind.RESOURCE));
		assertTrue(kinds.contains(McpTextCoordinate.Kind.RESOURCE_TEMPLATE));
	}

	@Test
	void rejectsConstructionOverBudgetAndUnequalCoordinateCollision() {
		McpEndpointRegistry registry = McpEndpointRegistry.fromEndpoints(
				List.of(endpoint(false)));

		IllegalStateException budget = assertThrows(IllegalStateException.class,
				() -> DefaultMcpLocalizationCatalogExtractor.plan(registry, 2));
		assertTrue(budget.getMessage().contains("callback limit"));
		assertThrows(IllegalArgumentException.class,
				() -> DefaultMcpLocalizationCatalogExtractor.plan(registry, 0));
		assertThrows(IllegalArgumentException.class,
				() -> DefaultMcpLocalizationCatalogExtractor.plan(registry, 100_001));
		IllegalStateException collision = assertThrows(IllegalStateException.class,
				() -> DefaultMcpLocalizationCatalogExtractor.extract(registry,
						ignored -> "forced-collision"));
		assertEquals("Unequal MCP text coordinates produced the same external key.",
				collision.getMessage());
	}

	@Test
	void subscriptionTerminalUsesActualResultMetadataPointerShape() {
		McpSubscriptionConfig subscriptions = McpSubscriptionConfig
				.withEventPublisher(
						McpLocalSubscriptionEventPublisher.fromDefaults())
				.notificationType(
						McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED)
				.build();
		McpEndpoint subscribed = McpEndpoint.withPath("/subscribed")
				.serverInformation(McpImplementation
						.withNameAndVersion("subscribed", "1")
						.title("Subscribed title")
						.description("Subscribed description")
						.build())
				.resource(McpResourceRegistration.withUriAndName(
						URI.create("catalog://subscribed"), "subscribed")
						.handler((request, resource, features) ->
								McpCompleteResult.fromResourceOutput(
										McpResourceOutput.builder()
												.content(McpTextResourceContents
														.withUriAndText(
																URI.create("catalog://subscribed"),
																"unused")
														.build())
												.build()))
						.build())
				.subscriptions(subscriptions)
				.build();
		McpCanonicalLocalizationPlan plan =
				DefaultMcpLocalizationCatalogExtractor.plan(
						McpEndpointRegistry.fromEndpoints(List.of(subscribed)), 10);

		assertEquals(List.of(
				"/_meta/io.modelcontextprotocol~1serverInfo/title",
				"/_meta/io.modelcontextprotocol~1serverInfo/description"),
				plan.endpoints().get(0)
						.response(McpCanonicalLocalizationPlan.ResponseKind
								.SUBSCRIPTION_TERMINAL)
						.orElseThrow().slots().stream()
						.map(McpCanonicalLocalizationPlan.Slot::targetPointer)
						.toList());
	}

	@Test
	void publicFactoryDelegatesToFinalResolverExtraction() {
		McpEndpointRegistry programmatic = McpEndpointRegistry.fromEndpoints(List.of(
				McpEndpoint.withPath("/generated/catalog")
						.serverInformation(McpImplementation
								.withNameAndVersion("generated", "1")
								.title("Generated server")
								.description("Generated description")
								.build())
						.instructions("Generated instructions")
						.tool(McpToolRegistration.withName("generated.search")
								.argumentType(GeneratedArguments.class)
								.handler((request, arguments, features) ->
										McpCompleteResult.fromToolText("unused"))
								.title("Generated tool")
								.description("Generated tool description")
								.build())
						.prompt(McpPromptRegistration.withName("generated.prompt")
								.handler((request, prompt, features) ->
										McpCompleteResult.fromPromptOutput(
												McpPromptOutput.fromMessages()))
								.title("Generated prompt")
								.description("Generated prompt description")
								.argument(McpPromptArgumentDefinition.withName("topic")
										.title("Generated topic")
										.description("Generated topic description")
										.required(true)
										.build())
								.build())
						.build()));

		assertEquals(DefaultMcpLocalizationCatalogExtractor.extract(programmatic),
				McpLocalizationCatalog.fromEndpointRegistry(programmatic).getTexts());
	}

	@Test
	void serverConstructionRetainsPlanAndRejectsOverBudgetResponse() {
		McpEndpointRegistry registry = McpEndpointRegistry.fromEndpoints(List.of(
				wireEndpoint()));
		McpLocalizer bounded = McpLocalizer.withFallbackLocale(Locale.ENGLISH)
				.contextProvider(request -> localizationContext(
						new AtomicInteger()))
				.maximumLocalizableTextCountPerResponse(2)
				.build();

		IllegalStateException exception = assertThrows(IllegalStateException.class,
				() -> wireServerBuilder(registry).localizer(bounded).build());
		assertTrue(exception.getMessage().contains("callback limit"));

		McpLocalizer sufficient = McpLocalizer
				.withFallbackLocale(Locale.ENGLISH)
				.contextProvider(request -> localizationContext(
						new AtomicInteger()))
				.maximumLocalizableTextCountPerResponse(3)
				.build();
		DefaultMcpServer server = (DefaultMcpServer) wireServerBuilder(registry)
				.localizer(sufficient).build();
		McpCanonicalLocalizationPlan plan = server.localizationPlan()
				.orElseThrow();
		assertEquals(McpLocalizationCatalog.fromEndpointRegistry(registry).getTexts(),
				plan.texts());
		assertEquals(3, plan.endpoints().get(0)
				.response(McpCanonicalLocalizationPlan.ResponseKind.DISCOVERY)
				.orElseThrow().slots().size());

		DefaultMcpServer disabled = (DefaultMcpServer) wireServerBuilder(registry)
				.build();
		assertTrue(disabled.localizationPlan().isEmpty());
	}

	@Test
	void generatedAnnotatedCatalogMatchesProgrammaticCatalog(
			@TempDir Path temporaryDirectory) throws Exception {
		Path sourceDirectory = temporaryDirectory.resolve("src/example");
		Path classDirectory = temporaryDirectory.resolve("classes");
		Path generatedDirectory = temporaryDirectory.resolve("generated");
		Files.createDirectories(sourceDirectory);
		Files.createDirectories(classDirectory);
		Files.createDirectories(generatedDirectory);
		Path source = sourceDirectory.resolve("LocalizedCatalogEndpoint.java");
		Files.writeString(source, annotatedParityEndpointSource(),
				StandardCharsets.UTF_8);
		compileAnnotatedEndpoint(source, classDirectory, generatedDirectory);

		try (URLClassLoader classLoader = new URLClassLoader(
				new URL[]{classDirectory.toUri().toURL()},
				McpLocalizationCatalogExtractionTests.class.getClassLoader())) {
			Class<?> endpointClass = Class.forName(
					"example.LocalizedCatalogEndpoint", false, classLoader);
			McpEndpointRegistry generated = McpEndpointRegistry.fromClasses(
					endpointClass);
			McpEndpointRegistry programmatic = McpEndpointRegistry.fromEndpoints(
					List.of(parityEndpoint()));

			List<McpLocalizableText> generatedTexts = McpLocalizationCatalog
					.fromEndpointRegistry(generated).getTexts();
			List<McpLocalizableText> programmaticTexts = McpLocalizationCatalog
					.fromEndpointRegistry(programmatic).getTexts();
			assertFalse(generatedTexts.isEmpty());
			assertEquals(programmaticTexts, generatedTexts);
		}
	}

	@Test
	void configuredLocalizerRendersDefaultTextWireIdenticalToGolden() {
		AtomicInteger contextInvocations = new AtomicInteger();
		AtomicInteger localizationInvocations = new AtomicInteger();
		McpLocalizer localizer = McpLocalizer
				.withFallbackLocale(Locale.ENGLISH)
				.contextProvider(request -> {
					contextInvocations.incrementAndGet();
					return localizationContext(localizationInvocations);
				})
				.build();
		McpEndpointRegistry registry = McpEndpointRegistry.fromEndpoints(List.of(
				wireEndpoint()));
		McpServer baseline = wireServerBuilder(registry).build();
		McpServer localized = wireServerBuilder(registry).localizer(localizer)
				.build();
		int discoverySlotCount = ((DefaultMcpServer) localized).localizationPlan()
				.orElseThrow().endpoints().get(0)
				.response(McpCanonicalLocalizationPlan.ResponseKind.DISCOVERY)
				.orElseThrow().slots().size();
		assertFalse(baseline.getLocalizationControl().isEnabled());
		assertTrue(localized.getLocalizationControl().isEnabled());
		assertEquals(0, contextInvocations.get());
		assertEquals(0, localizationInvocations.get());

		WireResponse baselineResponse = captureDiscovery(baseline);
		WireResponse localizedResponse = captureDiscovery(localized);
		assertEquals(baselineResponse.statusCode(), localizedResponse.statusCode());
		assertArrayEquals(baselineResponse.body(), localizedResponse.body());
		assertEquals(200, localizedResponse.statusCode());
		// Without a localizer the headers are the historical exact set; with one,
		// the representation varies by Accept-Language and an all-default render
		// is still the selected locale's representation.
		assertEquals(Map.of(
				"Cache-Control", Set.of("no-store"),
				"Content-Type", Set.of(JSON_MEDIA_TYPE)),
				baselineResponse.headers());
		assertEquals(Map.of(
				"Cache-Control", Set.of("no-store"),
				"Content-Type", Set.of(JSON_MEDIA_TYPE),
				"Vary", Set.of("Accept-Language"),
				"Content-Language", Set.of("en")),
				localizedResponse.headers());
		assertEquals(List.of("Cache-Control", "Content-Type", "Vary",
				"Content-Language"),
				List.copyOf(localizedResponse.headers().keySet()));
		assertArrayEquals(discoveryGolden().getBytes(StandardCharsets.UTF_8),
				localizedResponse.body());
		// L2 renders through the provider. Construction alone must still not call
		// it, one context is created per localizable response, and every planned
		// discovery slot is offered exactly once per response.
		assertEquals(1, contextInvocations.get(),
				"Exactly one context for the localized response, and none for the "
						+ "unconfigured baseline.");
		assertEquals(discoverySlotCount, localizationInvocations.get(),
				"Every planned slot must be offered exactly once per response.");
		assertTrue(localized.getLocalizationControl().isEnabled());
	}

	private static McpLocalizationContext localizationContext(
			AtomicInteger localizationInvocations) {
		return McpLocalizationContext.withLocale(Locale.ENGLISH)
				.localizer(text -> {
				localizationInvocations.incrementAndGet();
				return McpLocalizationResult.useDefaultText();
			})
				.build();
	}

	private static McpEndpoint wireEndpoint() {
		return McpEndpoint.withPath(WIRE_PATH)
				.serverInformation(McpImplementation
						.withNameAndVersion("localization-wire", "1.0")
						.title("Canonical title")
						.description("Canonical description")
						.build())
				.instructions("Use canonical instructions.")
				.build();
	}

	private static McpServer.Builder wireServerBuilder(
			McpEndpointRegistry registry) {
		return McpServer.withPort(0)
				.host(LOOPBACK)
				.endpointRegistry(registry)
				.admissionController(
						McpAdmissionController.acceptAllInstance())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(LOOPBACK));
	}

	/**
	 * Replays canonical discovery entirely off-network through
	 * {@link Soklet#runSimulator}, so no listener socket is ever bound and the
	 * server under test stays {@link McpServerStatus#STOPPED} throughout.
	 */
	private static WireResponse captureDiscovery(McpServer server) {
		OffNetworkMetrics metrics = new OffNetworkMetrics();
		SokletConfig config = SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(metrics)
				.build();
		AtomicReference<WireResponse> captured = new AtomicReference<>();

		assertServerNeverListened(server);
		Soklet.runSimulator(config, simulator -> {
			assertServerNeverListened(server);
			McpSimulation simulation = simulator.startMcpRequest(discoveryRequest());
			McpSimulationResponse response = awaitSimulatorResponse(simulation);
			assertEquals(McpSimulationBodyMode.JSON, response.getBodyMode());
			captured.set(new WireResponse(response.getStatusCode(),
					response.getHeaders(), response.getBody().orElseThrow()));
			assertEquals(McpStreamTerminationReason.COMPLETED,
					awaitSimulatorCompletion(simulation).getReason());
			assertServerNeverListened(server);
		});
		assertServerNeverListened(server);
		metrics.assertNoListenerSocketActivity();
		return captured.get();
	}

	private static Request discoveryRequest() {
		String body = "{\"jsonrpc\":\"2.0\","
				+ "\"id\":\"localization-inert\","
				+ "\"method\":\"server/discover\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\""
				+ PROTOCOL_VERSION + "\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
		return Request.withPath(HttpMethod.POST, WIRE_PATH)
				.headers(Map.of(
						"Host", Set.of(LOOPBACK + ":0"),
						"Content-Type", Set.of(JSON_MEDIA_TYPE + "; charset=UTF-8"),
						"Accept", Set.of(JSON_MEDIA_TYPE + ", text/event-stream"),
						"MCP-Protocol-Version", Set.of(PROTOCOL_VERSION),
						"Mcp-Method", Set.of("server/discover")))
				.body(body.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	private static void assertServerNeverListened(McpServer server) {
		assertFalse(server.isStarted());
		McpServerDiagnostics diagnostics = server.getDiagnostics();
		assertEquals(McpServerStatus.STOPPED, diagnostics.getStatus());
		assertTrue(diagnostics.getBoundAddress().isEmpty());
		assertEquals(0, diagnostics.getActiveHandlerExecutions());
		assertEquals(0, diagnostics.getQueuedRequests());
		assertEquals(0, diagnostics.getActiveRequestStreams());
		assertEquals(0, diagnostics.getActiveSubscriptions());
	}

	private static McpSimulationResponse awaitSimulatorResponse(
			McpSimulation simulation) {
		try {
			return simulation.awaitResponse(SIMULATOR_WAIT).orElseThrow(() ->
					new AssertionError("Timed out awaiting simulator response."));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private static McpSimulationCompletion awaitSimulatorCompletion(
			McpSimulation simulation) {
		try {
			return simulation.awaitCompletion(SIMULATOR_WAIT).orElseThrow(() ->
					new AssertionError("Timed out awaiting simulator completion."));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	/** Fails if any listener-socket or transport activity is ever recorded. */
	private static final class OffNetworkMetrics implements MetricsCollector {
		private final List<McpMetricsEvent> events = new CopyOnWriteArrayList<>();

		@Override
		public void didRecordMcpMetricsEvent(McpMetricsEvent event) {
			this.events.add(event);
		}

		private void assertNoListenerSocketActivity() {
			assertTrue(this.events.stream().anyMatch(event ->
					event instanceof McpMetricsEvent.RequestFinished),
					"Collector observed no request activity: " + this.events);
			assertTrue(this.events.stream().noneMatch(event ->
					event instanceof McpMetricsEvent.ServerStarted
							|| event instanceof McpMetricsEvent.ServerStopped
							|| event instanceof McpMetricsEvent.ConnectionAccepted
							|| event instanceof McpMetricsEvent.ConnectionRejected
							|| event instanceof McpMetricsEvent.TransportFailure),
					this.events.toString());
		}
	}

	private static String discoveryGolden() {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"localization-inert\","
				+ "\"result\":{\"supportedVersions\":[\"2026-07-28\"],"
				+ "\"capabilities\":{},"
				+ "\"instructions\":\"Use canonical instructions.\","
				+ "\"ttlMs\":0,\"cacheScope\":\"private\","
				+ "\"resultType\":\"complete\",\"_meta\":{"
				+ "\"io.modelcontextprotocol/serverInfo\":{"
				+ "\"name\":\"localization-wire\",\"version\":\"1.0\","
				+ "\"title\":\"Canonical title\","
				+ "\"description\":\"Canonical description\"}}}}";
	}

	private static McpEndpoint parityEndpoint() {
		McpResourceReadHandler resourceHandler = (request, resource, features) ->
				McpCompleteResult.fromResourceOutput(McpResourceOutput.builder()
						.content(McpTextResourceContents.withUriAndText(
								URI.create("catalog://unused"), "unused").build())
						.build());
		return McpEndpoint.withPath("/localized/catalog")
				.serverInformation(McpImplementation
						.withNameAndVersion("localized-catalog", "1.0")
						.title("Catalog server")
						.description("Catalog server description")
						.build())
				.instructions("Use catalog.search.")
				.tool(McpToolRegistration.withName("catalog.search")
						.types(ParityArguments.class, ParityResult.class)
						.handler((request, arguments, features) ->
								new ParityResult("unused"))
						.title("Catalog search")
						.description("Searches the catalog")
						.build())
				.prompt(McpPromptRegistration.withName("catalog.compose")
						.handler((request, prompt, features) ->
								McpCompleteResult.fromPromptOutput(
										McpPromptOutput.fromMessages()))
						.title("Catalog composer")
						.description("Builds a catalog prompt")
						.argument(McpPromptArgumentDefinition.withName("subject")
								.title("Prompt subject")
								.description("Subject to discuss")
								.required(true)
								.build())
						.build())
				.resource(McpResourceRegistration.withUriAndName(
						URI.create("catalog://summary"), "summary")
						.handler(resourceHandler)
						.title("Catalog summary")
						.description("Summary contents")
						.build())
				.resource(McpResourceRegistration.withUriTemplateAndName(
						"catalog://items/{id}", "item")
						.handler(resourceHandler)
						.title("Catalog item")
						.description("Item contents")
						.build())
				.build();
	}

	private static void compileAnnotatedEndpoint(Path source, Path classes,
			Path generated) throws IOException {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null)
			throw new IllegalStateException("A JDK compiler is required.");
		StringWriter diagnostics = new StringWriter();
		try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
				null, null, StandardCharsets.UTF_8)) {
			String classpath = classes + System.getProperty("path.separator")
					+ System.getProperty("java.class.path");
			JavaCompiler.CompilationTask task = compiler.getTask(diagnostics,
					fileManager, null, List.of("--release", "17", "-parameters",
						"-Asoklet.cacheMode=none", "-classpath", classpath,
						"-d", classes.toString(), "-s", generated.toString()),
					null, fileManager.getJavaFileObjects(source));
			task.setProcessors(List.of(new SokletProcessor()));
			if (!Boolean.TRUE.equals(task.call()))
				throw new AssertionError(diagnostics.toString());
		}
	}

	private static String annotatedParityEndpointSource() {
		return """
				package example;

				import com.soklet.McpPromptOutput;
				import com.soklet.McpResourceOutput;
				import com.soklet.annotation.McpPrompt;
				import com.soklet.annotation.McpPromptArgument;
				import com.soklet.annotation.McpResource;
				import com.soklet.annotation.McpResourceUriParameter;
				import com.soklet.annotation.McpServerEndpoint;
				import com.soklet.annotation.McpTool;
				import com.soklet.annotation.McpToolArgument;

				@McpServerEndpoint(
				    path = "/localized/catalog",
				    name = "localized-catalog",
				    version = "1.0",
				    title = "Catalog server",
				    description = "Catalog server description",
				    instructions = "Use catalog.search.")
				public final class LocalizedCatalogEndpoint {
				  @McpTool(
				      name = "catalog.search",
				      title = "Catalog search",
				      description = "Searches the catalog")
				  public SearchResult search(
				      @McpToolArgument(
				          name = "query",
				          title = "Search query",
				          description = "Text to search for") String query) {
				    return new SearchResult(query);
				  }

				  @McpPrompt(
				      name = "catalog.compose",
				      title = "Catalog composer",
				      description = "Builds a catalog prompt")
				  public McpPromptOutput compose(
				      @McpPromptArgument(
				          name = "subject",
				          title = "Prompt subject",
				          description = "Subject to discuss") String subject) {
				    return McpPromptOutput.fromMessages();
				  }

				  @McpResource(
				      uri = "catalog://summary",
				      name = "summary",
				      title = "Catalog summary",
				      description = "Summary contents")
				  public McpResourceOutput summary() { return null; }

				  @McpResource(
				      uri = "catalog://items/{id}",
				      name = "item",
				      title = "Catalog item",
				      description = "Item contents")
				  public McpResourceOutput item(
				      @McpResourceUriParameter("id") String id) { return null; }

				  public record SearchResult(
				      @McpToolArgument(
				          title = "Match title",
				          description = "Matched value") String match) {}
				}
				""";
	}

	private record WireResponse(int statusCode,
			Map<String, Set<String>> headers, byte[] body) {}

	private record ParityArguments(
			@McpToolArgument(name = "query", title = "Search query",
					description = "Text to search for") String query) {}

	private record ParityResult(
			@McpToolArgument(title = "Match title",
					description = "Matched value") String match) {}

	private static McpEndpoint endpoint(boolean customResourceList) {
		McpJsonObject inputSchema = McpJsonObject.builder()
				.put("type", "object")
				.put("properties", McpJsonObject.builder()
						.put("query/text", McpJsonObject.builder()
								.put("type", "string")
								.put("title", "Input title")
								.put("description", "Input description")
								.put("default", McpJsonObject.builder()
										.put("title", "Not schema text")
										.build())
								.build())
						.build())
				.build();
		McpToolRegistration<McpJsonObject> tool = McpToolRegistration
				.withName("catalog.search")
				.conformanceInputSchema(inputSchema)
				.handler((request, arguments, features) ->
						McpCompleteResult.fromToolText("unused"))
				.title("Tool title")
				.description("Tool description")
				.annotations(McpToolAnnotations.builder()
						.title("Annotation title").build())
				.build();
		McpToolRegistration<EmptyArguments> outputTool = McpToolRegistration
				.withName("catalog.output")
				.types(EmptyArguments.class, OutputArguments.class)
				.handler((request, arguments, features) ->
						new OutputArguments("value"))
				.build();
		McpPromptRegistration prompt = McpPromptRegistration
				.withName("support.summary")
				.handler((request, context, features) ->
						McpCompleteResult.fromPromptOutput(
								McpPromptOutput.fromMessages()))
				.title("Prompt title")
				.description("Prompt description")
				.argument(McpPromptArgumentDefinition.withName("topic")
						.title("Topic title")
						.description("Topic description")
						.build())
				.build();
		McpResourceReadHandler resourceHandler = (request, resource, features) ->
				McpCompleteResult.fromResourceOutput(McpResourceOutput.builder()
						.content(McpTextResourceContents.withUriAndText(
								URI.create("catalog://unused"), "unused").build())
						.build());
		McpEndpoint.Builder builder = McpEndpoint.withPath("/catalog/mcp")
				.serverInformation(McpImplementation
						.withNameAndVersion("catalog", "1")
						.title("Server title")
						.description("Server description")
						.build())
				.instructions("Endpoint instructions")
				.tool(tool)
				.tool(outputTool)
				.prompt(prompt)
				.resource(McpResourceRegistration.withUriAndName(
						URI.create("catalog://summary"), "summary")
						.handler(resourceHandler)
						.title("Resource title")
						.description("Resource description")
						.build())
				.resource(McpResourceRegistration.withUriTemplateAndName(
						"catalog://item/{id}", "item")
						.handler(resourceHandler)
						.title("Template title")
						.description("Template description")
						.build());
		if (customResourceList)
			builder.resourceListHandler((request, list, features) ->
					McpResourcePage.builder().build());
		return builder.build();
	}

	private static McpJsonObject schemaProperty(McpEndpoint endpoint,
			String property) {
		McpJsonObject properties = (McpJsonObject) endpoint.getTools().get(0)
				.getInputSchema().getDocument().find("properties").orElseThrow();
		return (McpJsonObject) properties.find(property).orElseThrow();
	}

	private record EmptyArguments() {}

	private record OutputArguments(
			@McpToolArgument(title = "Output title") String value) {}

	private record GeneratedArguments(
			@McpToolArgument(title = "Generated query",
					description = "Generated query description") String query) {}

}
