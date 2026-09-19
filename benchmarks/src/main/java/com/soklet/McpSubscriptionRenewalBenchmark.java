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

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;

/**
 * Real-socket capacity benchmark for MCP subscription authorization renewal.
 * <p>
 * The evidence profile keeps 1,000 subscription streams open through two
 * complete renewal cycles. It deliberately leaves the application-handler
 * concurrency, application-handler queue capacity, authorization timeout, and
 * maximum authorization duration at their server defaults. Initial requests
 * carry unique authorization partition keys so the default per-partition cap
 * remains in force without limiting the fleet-wide subscription count.
 * <p>
 * Run after {@code mvn -q clean package} in {@code benchmarks/}:
 * <pre>{@code
 * java -Dsoklet.subscriptionRenewal.candidate=FULL_40_HEX_COMMIT_SHA \
 *   -cp target/soklet-benchmarks.jar \
 *   com.soklet.McpSubscriptionRenewalBenchmark
 * }</pre>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public final class McpSubscriptionRenewalBenchmark {
	private static final String HOST = "127.0.0.1";
	private static final String MCP_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String SUBSCRIPTION_HEADER =
			"X-Soklet-Benchmark-Subscription";
	private static final int DEFAULT_SUBSCRIPTIONS = 1_000;
	private static final int DEFAULT_RENEWAL_CYCLES = 2;
	private static final int DEFAULT_CLIENT_OPEN_CONCURRENCY = 24;
	private static final int EXPECTED_DEFAULT_HANDLER_CONCURRENCY = 32;
	private static final int EXPECTED_DEFAULT_HANDLER_QUEUE_CAPACITY = 128;
	private static final int MAXIMUM_ACCEPTED_CONCURRENT_AUTHORIZATIONS = 24;
	private static final Pattern COMMIT_IDENTITY =
			Pattern.compile("[0-9a-f]{40}");
	private static final Duration DEFAULT_AUTHORIZATION_TIMEOUT =
			Duration.ofSeconds(5);
	private static final Duration DEFAULT_MAXIMUM_AUTHORIZATION_DURATION =
			Duration.ofMinutes(1);
	private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(15);
	private static final Duration ACCOUNTING_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration MAXIMUM_ACCEPTED_P99_QUEUE_WAIT =
			Duration.ofMillis(1_250);
	private static final long MAXIMUM_RENEWAL_STAGGER_NANOS =
			Duration.ofSeconds(1).toNanos();

	private McpSubscriptionRenewalBenchmark() {
	}

	public static void main(String[] args) throws Exception {
		Config config = Config.fromSystemProperties();
		BenchmarkState benchmark = new BenchmarkState(config);
		RecordingMetrics metrics = new RecordingMetrics();
		McpServer server = server(config, benchmark);
		ObservedRuntimeConfiguration runtimeConfiguration =
				ObservedRuntimeConfiguration.from(server);
		benchmark.bindRuntimeConfiguration(runtimeConfiguration);
		Soklet owner = managedSoklet(server, metrics);
		List<BenchmarkClient> clients = new ArrayList<>(config.subscriptions());
		long benchmarkStartedAtNanos = System.nanoTime();

		try {
			owner.start();
			McpServerDiagnostics startedDiagnostics = server.getDiagnostics();
			int port = startedDiagnostics.getBoundAddress().orElseThrow().getPort();
			printConfiguration(config, runtimeConfiguration, startedDiagnostics, port);

			long clientOpenStartedAtNanos = System.nanoTime();
			clients.addAll(openClients(config, port));
			long clientsOpenedAtNanos = System.nanoTime();
			awaitActiveSubscriptions(server, config.subscriptions());
			System.out.printf(Locale.ROOT,
					"Opened %,d concurrent subscriptions in %.3f seconds; awaiting %,d callbacks (%d initial + %d renewals per subscription).%n",
					clients.size(), seconds(clientsOpenedAtNanos
							- clientOpenStartedAtNanos),
					config.targetCallbacks(), config.subscriptions(),
					config.renewalCycles());

			benchmark.awaitTargetCallbacks(
					config.callbackWait(runtimeConfiguration));
			awaitAccounting(server, metrics, config.targetCallbacks(),
					config.subscriptions());

			long measurementFinishedAtNanos = System.nanoTime();
			McpServerDiagnostics finalDiagnostics = server.getDiagnostics();
			BenchmarkResult result = BenchmarkResult.from(config,
					runtimeConfiguration, benchmark, metrics, startedDiagnostics,
					finalDiagnostics, benchmarkStartedAtNanos,
					clientOpenStartedAtNanos, clientsOpenedAtNanos,
					measurementFinishedAtNanos);
			writeJson(config.outputPath(), result);
			printResult(config.outputPath(), result);

			if (!result.accepted())
				throw new IllegalStateException(
						"Subscription renewal benchmark did not satisfy its acceptance contract: "
								+ result.rejectionReasons());
		} finally {
			closeClients(clients);
			owner.close();
		}
	}

	@NonNull
	private static McpServer server(@NonNull Config config,
			@NonNull BenchmarkState benchmark) {
		McpSubscriptionConfig subscriptions = McpSubscriptionConfig
				.withEventPublisherAndNotificationTypes(
						McpSubscriptionEventPublisher.fromInMemoryDefaults(),
						Set.of(McpSubscriptionNotificationType
								.RESOURCES_LIST_CHANGED))
				.build();
		McpEndpoint endpoint = McpEndpoint.withPath(MCP_PATH,
				McpImplementation.withNameAndVersion(
						"subscription-renewal-benchmark", "4.0.0")
						.build())
				.subscriptionConfig(subscriptions)
				.addResource(McpResourceRegistration
						.withUriAndName(
								java.net.URI.create("benchmark://subscription-renewal"),
								"Subscription renewal benchmark resource")
						.handler((request, read, features) ->
								McpCompleteResult.fromResourceOutput(
										McpResourceOutput.withContent(
												McpTextResourceContents.withUriAndText(
														read.getUri(), "benchmark")
														.build())
												.build()))
						.build())
				.build();

		McpServer.Builder builder = McpServer.withPort(0)
				.host(HOST)
				.endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint)))
				.admissionController(context -> {
					String subscriptionId = context.getRequest()
							.getHeader(SUBSCRIPTION_HEADER)
							.orElseThrow(() -> new IllegalArgumentException(
									"Missing benchmark subscription header."));
					SubscriptionState state = benchmark.state(subscriptionId);
					return McpAdmissionDecision.accepted(McpAdmissionIdentity
							.withRateLimitPartitionKey(subscriptionId)
							.authorizationPartitionKey(subscriptionId)
							.principal(subscriptionId)
							.applicationContext(state)
							.build());
				})
				.subscriptionAuthorizer(benchmark::authorize)
				.requestRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of(HOST));
		config.maximumAuthorizationDurationOverride().ifPresent(
				builder::maximumSubscriptionAuthorizationDuration);
		return builder.build();
	}

	@NonNull
	private static Soklet managedSoklet(@NonNull McpServer server,
			@NonNull MetricsCollector metrics) {
		return Soklet.fromConfig(SokletConfig.withMcpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(metrics)
				.lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(15))
						.startupCancelationTimeout(Duration.ofSeconds(5))
						.gracefulShutdownTimeout(Duration.ofSeconds(15))
						.forcedShutdownTimeout(Duration.ofSeconds(5))
						.build())
				.build());
	}

	@NonNull
	private static List<@NonNull BenchmarkClient> openClients(
			@NonNull Config config, int port) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(
				Math.min(config.clientOpenConcurrency(), config.subscriptions()));
		List<Future<BenchmarkClient>> futures = new ArrayList<>(
				config.subscriptions());
		List<BenchmarkClient> clients = new ArrayList<>(config.subscriptions());
		List<BenchmarkClient> openedClients = Collections.synchronizedList(
				new ArrayList<>(config.subscriptions()));
		AtomicBoolean retainOpenedClients = new AtomicBoolean(true);
		boolean complete = false;
		try {
			for (int index = 0; index < config.subscriptions(); index++) {
				int subscriptionIndex = index;
				futures.add(executor.submit(() -> {
					BenchmarkClient client = BenchmarkClient.open(port,
							subscriptionId(subscriptionIndex));
					if (!retainOpenedClients.get()) {
						client.closeWithReset();
						throw new IOException(
								"Benchmark client opening was canceled.");
					}
					openedClients.add(client);
					if (!retainOpenedClients.get()
							&& openedClients.remove(client)) {
						client.closeWithReset();
						throw new IOException(
								"Benchmark client opening was canceled.");
					}
					return client;
				}));
			}
			for (Future<BenchmarkClient> future : futures)
				clients.add(future.get(CLIENT_TIMEOUT.toNanos(),
						TimeUnit.NANOSECONDS));
			complete = true;
			return List.copyOf(clients);
		} catch (Exception | Error throwable) {
			retainOpenedClients.set(false);
			for (Future<BenchmarkClient> future : futures)
				future.cancel(true);
			throw throwable;
		} finally {
			executor.shutdownNow();
			try {
				executor.awaitTermination(CLIENT_TIMEOUT.toNanos(),
						TimeUnit.NANOSECONDS);
			} finally {
				if (!complete) {
					retainOpenedClients.set(false);
					List<BenchmarkClient> cleanup;
					synchronized (openedClients) {
						cleanup = List.copyOf(openedClients);
					}
					closeClients(cleanup);
				}
			}
		}
	}

	private static void awaitActiveSubscriptions(@NonNull McpServer server,
			int expected) throws InterruptedException {
		long deadlineNanos = System.nanoTime() + ACCOUNTING_TIMEOUT.toNanos();
		while (server.getDiagnostics().getActiveSubscriptions() != expected
				&& System.nanoTime() - deadlineNanos < 0L)
			Thread.sleep(10L);
		int actual = server.getDiagnostics().getActiveSubscriptions();
		if (actual != expected)
			throw new IllegalStateException("Expected " + expected
					+ " active subscriptions, found " + actual + '.');
	}

	private static void awaitAccounting(@NonNull McpServer server,
			@NonNull RecordingMetrics metrics, int expectedAuthorizations,
			int expectedSubscriptions) throws InterruptedException {
		long deadlineNanos = System.nanoTime() + ACCOUNTING_TIMEOUT.toNanos();
		while ((metrics.successfulAuthorizations() < expectedAuthorizations
				|| server.getDiagnostics().getActiveSubscriptions()
						!= expectedSubscriptions)
				&& System.nanoTime() - deadlineNanos < 0L)
			Thread.sleep(10L);
	}

	private static void closeClients(
			@NonNull List<@NonNull BenchmarkClient> clients) {
		for (BenchmarkClient client : clients) {
			try {
				client.closeWithReset();
			} catch (IOException ignored) {
				// Best-effort teardown after the measured interval has ended.
			}
		}
	}

	private static void printConfiguration(@NonNull Config config,
			@NonNull ObservedRuntimeConfiguration runtimeConfiguration,
			@NonNull McpServerDiagnostics diagnostics, int port) {
		System.out.printf(Locale.ROOT,
				"Soklet MCP subscription renewal benchmark: host=%s port=%d subscriptions=%,d renewalCycles=%d clientOpenConcurrency=%d maxAuthorization=%s authorizationTimeout=%s handlerConcurrency=%d handlerQueueCapacity=%d evidenceProfile=%s%n",
				HOST, port, config.subscriptions(), config.renewalCycles(),
				config.clientOpenConcurrency(),
				runtimeConfiguration.maximumAuthorizationDuration(),
				runtimeConfiguration.authorizationTimeout(),
				diagnostics.getRequestHandlerConcurrency(),
				diagnostics.getRequestHandlerQueueCapacity(),
				config.evidenceProfile(runtimeConfiguration));
		System.out.printf(Locale.ROOT, "JVM: %s %s; OS: %s %s (%s); CPUs=%d%n",
				System.getProperty("java.vendor"), System.getProperty("java.version"),
				System.getProperty("os.name"), System.getProperty("os.version"),
				System.getProperty("os.arch"),
				Runtime.getRuntime().availableProcessors());
	}

	private static void printResult(@NonNull Path outputPath,
			@NonNull BenchmarkResult result) {
		System.out.printf(Locale.ROOT,
				"Renewal callbacks: %,d; observed %.3f callbacks/second.%n",
				result.renewalCallbacks(), result.renewalCallbacksPerSecond());
		System.out.printf(Locale.ROOT,
				"Queue-inclusive wait p50/p90/p99/max: %.3f / %.3f / %.3f / %.3f ms; callback latency p50/p90/p99/max: %.3f / %.3f / %.3f / %.3f ms.%n",
				millis(result.queueWait().p50()), millis(result.queueWait().p90()),
				millis(result.queueWait().p99()), millis(result.queueWait().max()),
				millis(result.callbackLatency().p50()),
				millis(result.callbackLatency().p90()),
				millis(result.callbackLatency().p99()),
				millis(result.callbackLatency().max()));
		System.out.printf(Locale.ROOT,
				"Capacity rejections=%d; authorization timeouts=%d; unintended closures=%d; active subscriptions=%d; maximum concurrent callbacks=%d; p99 queue-wait limit=%.3fms; max queue+callback observation=%.3fms.%n",
				result.capacityRejections(), result.authorizationTimeouts(),
				result.unintendedClosures(), result.activeSubscriptions(),
				result.maximumConcurrentCallbacks(),
				millis(result.p99QueueWaitLimitNanos()),
				millis(result.queueAndCallback().max()));
		System.out.printf(Locale.ROOT, "Acceptance: %s%s%n",
				result.accepted() ? "PASS" : "FAIL",
				result.rejectionReasons().isEmpty() ? ""
						: " " + result.rejectionReasons());
		System.out.printf(Locale.ROOT, "Wrote %s%n", outputPath);
	}

	private static void writeJson(@NonNull Path path,
			@NonNull BenchmarkResult result) throws IOException {
		Path absolute = path.toAbsolutePath();
		Path parent = absolute.getParent();
		if (parent != null)
			Files.createDirectories(parent);
		Files.writeString(absolute, result.toJson(), StandardCharsets.UTF_8);
	}

	@NonNull
	private static String subscriptionId(int index) {
		return String.format(Locale.ROOT, "renewal-%04d", index);
	}

	private static double seconds(long nanos) {
		return nanos / 1_000_000_000.0d;
	}

	private static double millis(long nanos) {
		return nanos / 1_000_000.0d;
	}

	private static long saturatedDurationNanos(@NonNull Instant from,
			@NonNull Instant to) {
		try {
			return Duration.between(from, to).toNanos();
		} catch (ArithmeticException exception) {
			return to.isBefore(from) ? Long.MIN_VALUE : Long.MAX_VALUE;
		}
	}

	@NonNull
	private static String jsonString(@NonNull String value) {
		StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			switch (character) {
				case '"' -> escaped.append("\\\"");
				case '\\' -> escaped.append("\\\\");
				case '\b' -> escaped.append("\\b");
				case '\f' -> escaped.append("\\f");
				case '\n' -> escaped.append("\\n");
				case '\r' -> escaped.append("\\r");
				case '\t' -> escaped.append("\\t");
				default -> {
					if (character < 0x20)
						escaped.append(String.format(Locale.ROOT, "\\u%04x",
								(int) character));
					else
						escaped.append(character);
				}
			}
		}
		return escaped.append('"').toString();
	}

	@NonNull
	private static String jsonStringArray(@NonNull List<@NonNull String> values) {
		StringBuilder json = new StringBuilder("[");
		for (int index = 0; index < values.size(); index++) {
			if (index > 0)
				json.append(',');
			json.append(jsonString(values.get(index)));
		}
		return json.append(']').toString();
	}

	private record Config(int subscriptions, int renewalCycles,
			int clientOpenConcurrency, @NonNull Path outputPath,
			@NonNull String candidateIdentity,
			java.util.Optional<@NonNull Duration>
					maximumAuthorizationDurationOverride) {
		private Config {
			if (subscriptions < 1)
				throw new IllegalArgumentException("subscriptions must be positive");
			if (renewalCycles < 1)
				throw new IllegalArgumentException("renewalCycles must be positive");
			if (clientOpenConcurrency < 1)
				throw new IllegalArgumentException(
						"clientOpenConcurrency must be positive");
		}

		@NonNull
		private static Config fromSystemProperties() {
			int subscriptions = positiveInt("soklet.subscriptionRenewal.subscriptions",
					DEFAULT_SUBSCRIPTIONS);
			int renewalCycles = positiveInt(
					"soklet.subscriptionRenewal.renewalCycles",
					DEFAULT_RENEWAL_CYCLES);
			int openConcurrency = positiveInt(
					"soklet.subscriptionRenewal.clientOpenConcurrency",
					DEFAULT_CLIENT_OPEN_CONCURRENCY);
			String durationValue = System.getProperty(
					"soklet.subscriptionRenewal.authorizationDurationSeconds");
			java.util.Optional<Duration> durationOverride = durationValue == null
					? java.util.Optional.empty()
					: java.util.Optional.of(Duration.ofSeconds(
							positiveLong("authorizationDurationSeconds",
									durationValue)));
			return new Config(subscriptions, renewalCycles, openConcurrency,
					Path.of(System.getProperty(
							"soklet.subscriptionRenewal.output",
							"target/mcp-subscription-renewal-results.json")),
					System.getProperty(
							"soklet.subscriptionRenewal.candidate",
							"unrecorded"),
					durationOverride);
		}

		private static int positiveInt(@NonNull String property,
				int defaultValue) {
			String value = System.getProperty(property);
			if (value == null)
				return defaultValue;
			try {
				int parsed = Integer.parseInt(value);
				if (parsed < 1)
					throw new IllegalArgumentException(
							property + " must be positive");
				return parsed;
			} catch (NumberFormatException exception) {
				throw new IllegalArgumentException(
						property + " must be a positive integer", exception);
			}
		}

		private static long positiveLong(@NonNull String description,
				@NonNull String value) {
			try {
				long parsed = Long.parseLong(value);
				if (parsed < 1L)
					throw new IllegalArgumentException(description
							+ " must be positive");
				return parsed;
			} catch (NumberFormatException exception) {
				throw new IllegalArgumentException(description
						+ " must be a positive integer", exception);
			}
		}

		private int targetCallbacks() {
			return Math.multiplyExact(subscriptions, renewalCycles + 1);
		}

		@NonNull
		private Duration callbackWait(
				@NonNull ObservedRuntimeConfiguration runtimeConfiguration) {
			long cycles = Math.multiplyExact(
					runtimeConfiguration.maximumAuthorizationDuration().toSeconds(),
					renewalCycles);
			return Duration.ofSeconds(Math.addExact(cycles, 30L));
		}

		private boolean evidenceWorkloadProfile() {
			return subscriptions == DEFAULT_SUBSCRIPTIONS
					&& renewalCycles == DEFAULT_RENEWAL_CYCLES
					&& clientOpenConcurrency == DEFAULT_CLIENT_OPEN_CONCURRENCY
					&& maximumAuthorizationDurationOverride.isEmpty();
		}

		private boolean candidateIdentityIsCommit() {
			return COMMIT_IDENTITY.matcher(candidateIdentity).matches();
		}

		private boolean evidenceProfile(
				@NonNull ObservedRuntimeConfiguration runtimeConfiguration) {
			return evidenceWorkloadProfile() && candidateIdentityIsCommit()
					&& runtimeConfiguration.authorizationTimeout().equals(
							DEFAULT_AUTHORIZATION_TIMEOUT)
					&& runtimeConfiguration.maximumAuthorizationDuration().equals(
							DEFAULT_MAXIMUM_AUTHORIZATION_DURATION);
		}
	}

	private record ObservedRuntimeConfiguration(
			@NonNull Duration authorizationTimeout,
			@NonNull Duration maximumAuthorizationDuration) {
		private ObservedRuntimeConfiguration {
			java.util.Objects.requireNonNull(authorizationTimeout);
			java.util.Objects.requireNonNull(maximumAuthorizationDuration);
		}

		@NonNull
		private static ObservedRuntimeConfiguration from(@NonNull McpServer server) {
			if (!(server instanceof DefaultMcpServer defaultServer))
				throw new IllegalStateException(
						"The benchmark requires Soklet's built-in MCP server.");
			return new ObservedRuntimeConfiguration(
					defaultServer.subscriptionAuthorizationTimeout(),
					defaultServer.maximumSubscriptionAuthorizationDuration());
		}
	}

	@ThreadSafe
	private static final class BenchmarkState {
		@NonNull
		private final Config config;
		@NonNull
		private final Map<@NonNull String, @NonNull SubscriptionState> states;
		@NonNull
		private final CountDownLatch targetCallbacks;
		@NonNull
		private final LongAdder extraCallbacks;
		@NonNull
		private final LongAdder contextContractViolations;
		@NonNull
		private final AtomicInteger activeCallbacks;
		@NonNull
		private final AtomicInteger maximumConcurrentCallbacks;
		@NonNull
		private final AtomicReference<ObservedRuntimeConfiguration>
				runtimeConfiguration;

		private BenchmarkState(@NonNull Config config) {
			this.config = config;
			Map<String, SubscriptionState> mutable = new java.util.LinkedHashMap<>();
			for (int index = 0; index < config.subscriptions(); index++) {
				String id = subscriptionId(index);
				mutable.put(id, new SubscriptionState(index, id,
						config.renewalCycles() + 1));
			}
			this.states = Collections.unmodifiableMap(mutable);
			this.targetCallbacks = new CountDownLatch(config.targetCallbacks());
			this.extraCallbacks = new LongAdder();
			this.contextContractViolations = new LongAdder();
			this.activeCallbacks = new AtomicInteger();
			this.maximumConcurrentCallbacks = new AtomicInteger();
			this.runtimeConfiguration = new AtomicReference<>();
		}

		private void bindRuntimeConfiguration(
				@NonNull ObservedRuntimeConfiguration runtimeConfiguration) {
			if (!this.runtimeConfiguration.compareAndSet(null, runtimeConfiguration))
				throw new IllegalStateException(
						"The benchmark runtime configuration is already bound.");
		}

		@NonNull
		private SubscriptionState state(@NonNull String subscriptionId) {
			SubscriptionState state = this.states.get(subscriptionId);
			if (state == null)
				throw new IllegalArgumentException(
						"Unknown benchmark subscription " + subscriptionId);
			return state;
		}

		@NonNull
		private McpSubscriptionAuthorization authorize(
				@NonNull McpSubscriptionAuthorizationContext context,
				@NonNull McpInvocationFeatures features) {
			Object applicationContext = context.getApplicationContext().orElseThrow(
					() -> new IllegalStateException(
							"Benchmark authorization context is absent."));
			if (!(applicationContext instanceof SubscriptionState state))
				throw new IllegalStateException(
						"Unexpected benchmark authorization context type.");

			int active = this.activeCallbacks.incrementAndGet();
			this.maximumConcurrentCallbacks.accumulateAndGet(active, Math::max);
			try {
				int cycle = state.reserveCycle();
				Instant startedAt = Instant.now();
				long startedAtNanos = System.nanoTime();
				long remainingNanos = saturatedDurationNanos(startedAt,
						context.getDeadline());
				long queueWaitNanos = Math.max(0L,
						expectedCheckBudgetNanos(state, cycle) - remainingNanos);
				boolean previousGrantExpected = cycle > 0;
				if (context.getPreviousValidUntil().isPresent()
						!= previousGrantExpected)
					this.contextContractViolations.increment();

				McpSubscriptionAuthorization.Allowed result =
						McpSubscriptionAuthorization.Allowed
								.withValidUntil(Instant.now().plus(Duration.ofHours(24)))
								.applicationContext(state)
								.build();
				long finishedAtNanos = System.nanoTime();
				if (cycle < state.samples.length) {
					state.samples[cycle] = new CallbackSample(state.index, cycle,
							startedAtNanos, finishedAtNanos, queueWaitNanos,
							Math.max(0L, finishedAtNanos - startedAtNanos));
					this.targetCallbacks.countDown();
				} else {
					this.extraCallbacks.increment();
				}
				return result;
			} finally {
				this.activeCallbacks.decrementAndGet();
			}
		}

		private long expectedCheckBudgetNanos(@NonNull SubscriptionState state,
				int cycle) {
			ObservedRuntimeConfiguration runtimeConfiguration =
					this.runtimeConfiguration.get();
			if (runtimeConfiguration == null)
				throw new IllegalStateException(
						"The benchmark runtime configuration is not bound.");
			long authorizationTimeoutNanos = runtimeConfiguration
					.authorizationTimeout().toNanos();
			if (cycle == 0)
				return authorizationTimeoutNanos;
			long durationNanos = runtimeConfiguration.maximumAuthorizationDuration()
					.toNanos();
			long halfDurationNanos = Math.max(1L, durationNanos / 2L);
			long maximumStaggerNanos = Math.min(
					MAXIMUM_RENEWAL_STAGGER_NANOS,
					Math.max(0L, durationNanos / 20L));
			long staggerNanos = maximumStaggerNanos == 0L ? 0L
					: Integer.toUnsignedLong(state.id.hashCode())
							% (maximumStaggerNanos + 1L);
			long renewalOffsetNanos = Math.min(durationNanos,
					halfDurationNanos + staggerNanos);
			return Math.min(authorizationTimeoutNanos,
					Math.max(0L, durationNanos - renewalOffsetNanos));
		}

		private boolean awaitTargetCallbacks(@NonNull Duration timeout)
				throws InterruptedException {
			return this.targetCallbacks.await(timeout.toNanos(),
					TimeUnit.NANOSECONDS);
		}

		private long completedTargetCallbacks() {
			return this.config.targetCallbacks() - this.targetCallbacks.getCount();
		}

		@NonNull
		private List<@NonNull CallbackSample> samples() {
			List<CallbackSample> samples = new ArrayList<>(
					this.config.targetCallbacks());
			for (SubscriptionState state : this.states.values())
				for (CallbackSample sample : state.samples)
					if (sample != null)
						samples.add(sample);
			return List.copyOf(samples);
		}

		private int completedRenewalCycles() {
			return this.states.values().stream()
					.mapToInt(state -> Math.max(0,
							Math.min(this.config.renewalCycles(),
									state.invocations.get() - 1)))
					.min().orElse(0);
		}
	}

	private static final class SubscriptionState {
		private final int index;
		@NonNull
		private final String id;
		@NonNull
		private final AtomicInteger invocations;
		private final CallbackSample[] samples;

		private SubscriptionState(int index, @NonNull String id,
				int targetCallbacks) {
			this.index = index;
			this.id = id;
			this.invocations = new AtomicInteger();
			this.samples = new CallbackSample[targetCallbacks];
		}

		private int reserveCycle() {
			return this.invocations.getAndIncrement();
		}
	}

	private record CallbackSample(int subscriptionIndex, int cycle,
			long startedAtNanos, long finishedAtNanos, long queueWaitNanos,
			long callbackLatencyNanos) {
		private long queueAndCallbackNanos() {
			long sum = queueWaitNanos + callbackLatencyNanos;
			return sum < 0L ? Long.MAX_VALUE : sum;
		}
	}

	@ThreadSafe
	private static final class RecordingMetrics implements MetricsCollector {
		@NonNull
		private final Map<McpMetricsEvent.SubscriptionMaintenance.Work,
				Map<McpMetricsEvent.SubscriptionMaintenance.Outcome, LongAdder>>
				maintenance;
		@NonNull
		private final Map<McpStreamTerminationReason, LongAdder> closures;
		@NonNull
		private final LongAdder handlerQueued;
		@NonNull
		private final LongAdder handlerCapacityRejected;

		private RecordingMetrics() {
			this.maintenance = new EnumMap<>(
					McpMetricsEvent.SubscriptionMaintenance.Work.class);
			for (McpMetricsEvent.SubscriptionMaintenance.Work work
					: McpMetricsEvent.SubscriptionMaintenance.Work.values()) {
				Map<McpMetricsEvent.SubscriptionMaintenance.Outcome, LongAdder>
						outcomes = new EnumMap<>(
								McpMetricsEvent.SubscriptionMaintenance.Outcome.class);
				for (McpMetricsEvent.SubscriptionMaintenance.Outcome outcome
						: McpMetricsEvent.SubscriptionMaintenance.Outcome.values())
					outcomes.put(outcome, new LongAdder());
				this.maintenance.put(work, outcomes);
			}
			this.closures = new EnumMap<>(McpStreamTerminationReason.class);
			for (McpStreamTerminationReason reason
					: McpStreamTerminationReason.values())
				this.closures.put(reason, new LongAdder());
			this.handlerQueued = new LongAdder();
			this.handlerCapacityRejected = new LongAdder();
		}

		@Override
		public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
			if (event instanceof McpMetricsEvent.SubscriptionMaintenance value)
				this.maintenance.get(value.getWork()).get(value.getOutcome())
						.increment();
			else if (event instanceof McpMetricsEvent.SubscriptionClosed value)
				this.closures.get(value.getReason()).increment();
			else if (event instanceof McpMetricsEvent.HandlerQueued)
				this.handlerQueued.increment();
			else if (event instanceof McpMetricsEvent.HandlerCapacityRejected)
				this.handlerCapacityRejected.increment();
		}

		private long maintenance(
				McpMetricsEvent.SubscriptionMaintenance.@NonNull Work work,
				McpMetricsEvent.SubscriptionMaintenance.@NonNull Outcome outcome) {
			return this.maintenance.get(work).get(outcome).sum();
		}

		private long successfulAuthorizations() {
			return maintenance(
					McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome.SUCCEEDED);
		}

		private long closureCount() {
			return this.closures.values().stream().mapToLong(LongAdder::sum).sum();
		}
	}

	private record Distribution(long count, double mean, long min, long p50,
			long p90, long p99, long max) {
		@NonNull
		private static Distribution from(long @NonNull [] values) {
			if (values.length == 0)
				return new Distribution(0L, 0.0d, 0L, 0L, 0L, 0L, 0L);
			long[] sorted = values.clone();
			Arrays.sort(sorted);
			double mean = Arrays.stream(sorted).average().orElse(0.0d);
			return new Distribution(sorted.length, mean, sorted[0],
					percentile(sorted, 0.50d), percentile(sorted, 0.90d),
					percentile(sorted, 0.99d), sorted[sorted.length - 1]);
		}

		private static long percentile(long @NonNull [] sorted,
				double percentile) {
			int index = Math.max(0, (int) Math.ceil(percentile * sorted.length) - 1);
			return sorted[Math.min(index, sorted.length - 1)];
		}

		@NonNull
		private String toJson() {
			return "{\"count\":" + count + ",\"meanNanos\":"
					+ String.format(Locale.ROOT, "%.3f", mean)
					+ ",\"minNanos\":" + min + ",\"p50Nanos\":" + p50
					+ ",\"p90Nanos\":" + p90 + ",\"p99Nanos\":" + p99
					+ ",\"maxNanos\":" + max + '}';
		}
	}

	private record CycleThroughput(int cycle, int callbacks,
			double callbacksPerSecond, long elapsedNanos) {
	}

	private record BenchmarkResult(@NonNull Config config,
			@NonNull ObservedRuntimeConfiguration runtimeConfiguration,
			@NonNull Instant recordedAt, int handlerConcurrency,
			int handlerQueueCapacity, int activeSubscriptions,
			int completedRenewalCycles, int totalCallbacks,
			int renewalCallbacks, double renewalCallbacksPerSecond,
			@NonNull Distribution queueWait,
			@NonNull Distribution callbackLatency,
			@NonNull Distribution queueAndCallback,
			@NonNull List<@NonNull CycleThroughput> cycleThroughputs,
			long successfulAuthorizations, long capacityRejections,
			long authorizationTimeouts, long authorizationDenied,
			long authorizationFailed, long staleResults,
			long handlerQueued, long handlerCapacityRejections,
			long unintendedClosures, long extraCallbacks,
			long contextContractViolations,
			int maximumConcurrentCallbacks, long p99QueueWaitLimitNanos,
			long clientOpenNanos, long totalElapsedNanos,
			@NonNull List<@NonNull CallbackSample> samples,
			@NonNull List<@NonNull String> rejectionReasons) {
		@NonNull
		private static BenchmarkResult from(@NonNull Config config,
				@NonNull ObservedRuntimeConfiguration runtimeConfiguration,
				@NonNull BenchmarkState benchmark,
				@NonNull RecordingMetrics metrics,
				@NonNull McpServerDiagnostics startedDiagnostics,
				@NonNull McpServerDiagnostics finalDiagnostics,
				long benchmarkStartedAtNanos, long clientOpenStartedAtNanos,
				long clientsOpenedAtNanos, long measurementFinishedAtNanos) {
			List<CallbackSample> samples = benchmark.samples();
			long[] queueWaits = samples.stream()
					.mapToLong(CallbackSample::queueWaitNanos).toArray();
			long[] callbackLatencies = samples.stream()
					.mapToLong(CallbackSample::callbackLatencyNanos).toArray();
			long[] queueAndCallbacks = samples.stream()
					.mapToLong(CallbackSample::queueAndCallbackNanos).toArray();
			List<CycleThroughput> cycleThroughputs = new ArrayList<>();
			for (int cycle = 0; cycle <= config.renewalCycles(); cycle++)
				cycleThroughputs.add(cycleThroughput(samples, cycle));
			List<CallbackSample> renewalSamples = samples.stream()
					.filter(sample -> sample.cycle() > 0).toList();
			long lastRenewalFinishedAtNanos = renewalSamples.stream()
					.mapToLong(CallbackSample::finishedAtNanos).max()
					.orElse(clientsOpenedAtNanos);
			long renewalElapsedNanos = Math.max(1L,
					lastRenewalFinishedAtNanos - clientsOpenedAtNanos);
			double renewalCallbacksPerSecond = renewalElapsedNanos == 0L
					? 0.0d : renewalSamples.size() / seconds(renewalElapsedNanos);
			long successful = metrics.successfulAuthorizations();
			long capacityRejected = metrics.maintenance(
					McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome.CAPACITY_REJECTED);
			long timedOut = metrics.maintenance(
					McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome.TIMED_OUT);
			long denied = metrics.maintenance(
					McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome.DENIED);
			long failed = metrics.maintenance(
					McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome.FAILED);
			long stale = metrics.maintenance(
					McpMetricsEvent.SubscriptionMaintenance.Work.AUTHORIZATION,
					McpMetricsEvent.SubscriptionMaintenance.Outcome
							.STALE_RESULT_DISCARDED);
			Distribution queueWait = Distribution.from(queueWaits);
			Distribution callbackLatency = Distribution.from(callbackLatencies);
			Distribution queueAndCallback = Distribution.from(queueAndCallbacks);
			List<String> rejections = new ArrayList<>();
			if (startedDiagnostics.getRequestHandlerConcurrency()
					!= EXPECTED_DEFAULT_HANDLER_CONCURRENCY
					|| startedDiagnostics.getRequestHandlerQueueCapacity()
							!= EXPECTED_DEFAULT_HANDLER_QUEUE_CAPACITY)
				rejections.add("application executor defaults were not 32/128");
			if (samples.size() != config.targetCallbacks())
				rejections.add("missing target callbacks");
			if (benchmark.completedRenewalCycles() < config.renewalCycles())
				rejections.add("not every subscription completed every renewal cycle");
			if (successful != config.targetCallbacks())
				rejections.add("successful authorization metric count did not match target");
			if (capacityRejected != 0L || metrics.handlerCapacityRejected.sum() != 0L)
				rejections.add("bounded application capacity rejected work");
			if (timedOut != 0L)
				rejections.add("authorization work timed out");
			if (denied != 0L || failed != 0L || stale != 0L)
				rejections.add("authorization work had a non-success outcome");
			if (metrics.closureCount() != 0L)
				rejections.add("a subscription closed during the measured interval");
			if (finalDiagnostics.getActiveSubscriptions() != config.subscriptions())
				rejections.add("active subscription count did not match target");
			if (benchmark.extraCallbacks.sum() != 0L)
				rejections.add("an unexpected extra renewal began before measurement ended");
			if (benchmark.contextContractViolations.sum() != 0L)
				rejections.add("authorization context renewal history was inconsistent");
			if (queueWait.p99() >= MAXIMUM_ACCEPTED_P99_QUEUE_WAIT.toNanos())
				rejections.add("p99 queue wait was not below 1.25 seconds");
			if (benchmark.maximumConcurrentCallbacks.get()
					> MAXIMUM_ACCEPTED_CONCURRENT_AUTHORIZATIONS)
				rejections.add("maximum concurrent authorization callbacks exceeded 24");
			if (config.evidenceWorkloadProfile()
					&& !config.candidateIdentityIsCommit())
				rejections.add(
						"candidate identity was not a full 40-hex commit SHA");
			if (config.evidenceWorkloadProfile()
					&& !runtimeConfiguration.authorizationTimeout().equals(
							DEFAULT_AUTHORIZATION_TIMEOUT))
				rejections.add(
						"subscription authorization timeout default was not 5 seconds");
			if (config.evidenceWorkloadProfile()
					&& !runtimeConfiguration.maximumAuthorizationDuration().equals(
							DEFAULT_MAXIMUM_AUTHORIZATION_DURATION))
				rejections.add(
						"maximum subscription authorization duration default was not 60 seconds");

			return new BenchmarkResult(config, runtimeConfiguration, Instant.now(),
					startedDiagnostics.getRequestHandlerConcurrency(),
					startedDiagnostics.getRequestHandlerQueueCapacity(),
					finalDiagnostics.getActiveSubscriptions(),
					benchmark.completedRenewalCycles(), samples.size(),
					renewalSamples.size(), renewalCallbacksPerSecond,
					queueWait, callbackLatency,
					queueAndCallback, List.copyOf(cycleThroughputs), successful,
					capacityRejected, timedOut, denied, failed, stale,
					metrics.handlerQueued.sum(),
					metrics.handlerCapacityRejected.sum(), metrics.closureCount(),
					benchmark.extraCallbacks.sum(),
					benchmark.contextContractViolations.sum(),
					benchmark.maximumConcurrentCallbacks.get(),
					MAXIMUM_ACCEPTED_P99_QUEUE_WAIT.toNanos(),
					clientsOpenedAtNanos - clientOpenStartedAtNanos,
					measurementFinishedAtNanos - benchmarkStartedAtNanos,
					samples, List.copyOf(rejections));
		}

		private boolean accepted() {
			return this.rejectionReasons.isEmpty();
		}

		@NonNull
		private static CycleThroughput cycleThroughput(
				@NonNull List<@NonNull CallbackSample> samples, int cycle) {
			List<CallbackSample> selected = samples.stream()
					.filter(sample -> sample.cycle() == cycle).toList();
			long elapsed = elapsed(selected);
			double throughput = elapsed == 0L ? 0.0d
					: selected.size() / seconds(elapsed);
			return new CycleThroughput(cycle, selected.size(), throughput, elapsed);
		}

		private static long elapsed(
				@NonNull List<@NonNull CallbackSample> samples) {
			if (samples.isEmpty())
				return 0L;
			long first = samples.stream().mapToLong(CallbackSample::startedAtNanos)
					.min().orElseThrow();
			long last = samples.stream().mapToLong(CallbackSample::finishedAtNanos)
					.max().orElseThrow();
			return Math.max(1L, last - first);
		}

		@NonNull
		private String toJson() {
			StringBuilder json = new StringBuilder(256 * 1_024);
			json.append("{\n")
					.append("  \"schemaVersion\": 1,\n")
					.append("  \"benchmark\": \"mcp-subscription-renewal\",\n")
					.append("  \"recordedAt\": ").append(jsonString(
							recordedAt.toString())).append(",\n")
					.append("  \"candidateIdentity\": ").append(jsonString(
							config.candidateIdentity())).append(",\n")
					.append("  \"environment\": {\"javaVendor\":")
					.append(jsonString(System.getProperty("java.vendor")))
					.append(",\"javaVersion\":")
					.append(jsonString(System.getProperty("java.version")))
					.append(",\"osName\":")
					.append(jsonString(System.getProperty("os.name")))
					.append(",\"osVersion\":")
					.append(jsonString(System.getProperty("os.version")))
					.append(",\"osArch\":")
					.append(jsonString(System.getProperty("os.arch")))
					.append(",\"availableProcessors\":")
					.append(Runtime.getRuntime().availableProcessors()).append("},\n")
					.append("  \"configuration\": {\"subscriptions\":")
						.append(config.subscriptions())
						.append(",\"renewalCycles\":").append(config.renewalCycles())
						.append(",\"clientOpenConcurrency\":")
						.append(config.clientOpenConcurrency())
						.append(",\"maximumAuthorizationDurationNanos\":")
						.append(runtimeConfiguration.maximumAuthorizationDuration()
								.toNanos())
						.append(",\"maximumAuthorizationDurationOverridden\":")
						.append(config.maximumAuthorizationDurationOverride().isPresent())
						.append(",\"authorizationTimeoutNanos\":")
						.append(runtimeConfiguration.authorizationTimeout().toNanos())
						.append(",\"handlerConcurrency\":")
						.append(handlerConcurrency)
						.append(",\"handlerQueueCapacity\":")
						.append(handlerQueueCapacity)
						.append(",\"evidenceProfile\":")
						.append(config.evidenceProfile(runtimeConfiguration))
						.append("},\n")
					.append("  \"result\": {\"accepted\":").append(accepted())
					.append(",\"rejectionReasons\":")
					.append(jsonStringArray(rejectionReasons))
					.append(",\"activeSubscriptions\":")
					.append(activeSubscriptions)
					.append(",\"completedRenewalCycles\":")
					.append(completedRenewalCycles)
					.append(",\"totalCallbacks\":").append(totalCallbacks)
					.append(",\"renewalCallbacks\":").append(renewalCallbacks)
					.append(",\"renewalCallbacksPerSecond\":")
					.append(String.format(Locale.ROOT, "%.6f",
							renewalCallbacksPerSecond))
					.append(",\"successfulAuthorizations\":")
					.append(successfulAuthorizations)
					.append(",\"capacityRejections\":").append(capacityRejections)
					.append(",\"authorizationTimeouts\":")
					.append(authorizationTimeouts)
					.append(",\"authorizationDenied\":")
					.append(authorizationDenied)
					.append(",\"authorizationFailed\":")
					.append(authorizationFailed)
					.append(",\"staleResults\":").append(staleResults)
					.append(",\"handlerQueued\":").append(handlerQueued)
					.append(",\"handlerCapacityRejections\":")
					.append(handlerCapacityRejections)
					.append(",\"unintendedClosures\":")
					.append(unintendedClosures)
					.append(",\"extraCallbacks\":").append(extraCallbacks)
					.append(",\"contextContractViolations\":")
					.append(contextContractViolations)
					.append(",\"maximumConcurrentCallbacks\":")
					.append(maximumConcurrentCallbacks)
					.append(",\"p99QueueWaitLimitNanos\":")
					.append(p99QueueWaitLimitNanos)
					.append(",\"clientOpenNanos\":").append(clientOpenNanos)
					.append(",\"totalElapsedNanos\":").append(totalElapsedNanos)
					.append("},\n")
					.append("  \"queueInclusiveWait\": ")
					.append(queueWait.toJson()).append(",\n")
					.append("  \"callbackLatency\": ")
					.append(callbackLatency.toJson()).append(",\n")
					.append("  \"queueWaitPlusCallbackLatency\": ")
					.append(queueAndCallback.toJson()).append(",\n")
					.append("  \"cycles\": [");
			for (int index = 0; index < cycleThroughputs.size(); index++) {
				CycleThroughput cycle = cycleThroughputs.get(index);
				if (index > 0)
					json.append(',');
				json.append("{\"cycle\":").append(cycle.cycle())
						.append(",\"callbacks\":").append(cycle.callbacks())
						.append(",\"callbacksPerSecond\":")
						.append(String.format(Locale.ROOT, "%.6f",
								cycle.callbacksPerSecond()))
						.append(",\"elapsedNanos\":")
						.append(cycle.elapsedNanos()).append('}');
			}
			json.append("],\n  \"callbackSamples\": [");
			for (int index = 0; index < samples.size(); index++) {
				CallbackSample sample = samples.get(index);
				if (index > 0)
					json.append(',');
				json.append("{\"subscriptionIndex\":")
						.append(sample.subscriptionIndex())
						.append(",\"cycle\":").append(sample.cycle())
						.append(",\"startedAtNanos\":")
						.append(sample.startedAtNanos())
						.append(",\"finishedAtNanos\":")
						.append(sample.finishedAtNanos())
						.append(",\"queueWaitNanos\":")
						.append(sample.queueWaitNanos())
						.append(",\"callbackLatencyNanos\":")
						.append(sample.callbackLatencyNanos()).append('}');
			}
			return json.append("]\n}\n").toString();
		}
	}

	private static final class BenchmarkClient {
		private static final int MAXIMUM_HEAD_BYTES = 64 * 1_024;
		private static final int MAXIMUM_CHUNK_BYTES = 1 * 1_024 * 1_024;
		@NonNull
		private final Socket socket;
		@NonNull
		private final InputStream input;

		private BenchmarkClient(@NonNull Socket socket) throws IOException {
			this.socket = socket;
			this.input = socket.getInputStream();
		}

		@NonNull
		private static BenchmarkClient open(int port,
				@NonNull String subscriptionId) throws IOException {
			Socket socket = new Socket();
			try {
				socket.setTcpNoDelay(true);
				socket.setKeepAlive(true);
				socket.setSoTimeout((int) CLIENT_TIMEOUT.toMillis());
				socket.connect(new InetSocketAddress(HOST, port),
						(int) CLIENT_TIMEOUT.toMillis());
				BenchmarkClient client = new BenchmarkClient(socket);
				client.writeSubscriptionRequest(port, subscriptionId);
				int status = client.readStatus();
				if (status != 200)
					throw new IOException("Subscription " + subscriptionId
							+ " returned HTTP " + status + '.');
				String acknowledgment = client.readChunkText();
				if (!acknowledgment.contains(
						"notifications/subscriptions/acknowledged"))
					throw new IOException("Subscription " + subscriptionId
							+ " did not receive an acknowledgment: "
							+ acknowledgment);
				socket.setSoTimeout(0);
				return client;
		} catch (IOException | RuntimeException | Error throwable) {
				try {
					socket.close();
				} catch (Throwable suppressed) {
					throwable.addSuppressed(suppressed);
				}
				throw throwable;
			}
		}

		private void writeSubscriptionRequest(int port,
				@NonNull String subscriptionId) throws IOException {
			String body = "{\"jsonrpc\":\"2.0\",\"id\":"
					+ jsonString(subscriptionId)
					+ ",\"method\":\"subscriptions/listen\",\"params\":{"
					+ "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\""
					+ PROTOCOL_VERSION + "\","
					+ "\"io.modelcontextprotocol/clientCapabilities\":{}},"
					+ "\"notifications\":{\"resourcesListChanged\":true}}}";
			byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
			String head = "POST " + MCP_PATH + " HTTP/1.1\r\n"
					+ "Host: " + HOST + ':' + port + "\r\n"
					+ "Content-Type: application/json; charset=UTF-8\r\n"
					+ "Accept: application/json, text/event-stream\r\n"
					+ "MCP-Protocol-Version: " + PROTOCOL_VERSION + "\r\n"
					+ "Mcp-Method: subscriptions/listen\r\n"
					+ SUBSCRIPTION_HEADER + ": " + subscriptionId + "\r\n"
					+ "Content-Length: " + bodyBytes.length + "\r\n\r\n";
			this.socket.getOutputStream().write(
					head.getBytes(StandardCharsets.ISO_8859_1));
			this.socket.getOutputStream().write(bodyBytes);
			this.socket.getOutputStream().flush();
		}

		private int readStatus() throws IOException {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			int matched = 0;
			while (bytes.size() < MAXIMUM_HEAD_BYTES) {
				int value = this.input.read();
				if (value < 0)
					throw new EOFException(
							"Socket closed before the HTTP head was complete.");
				bytes.write(value);
				matched = switch (matched) {
					case 0 -> value == '\r' ? 1 : 0;
					case 1 -> value == '\n' ? 2 : value == '\r' ? 1 : 0;
					case 2 -> value == '\r' ? 3 : 0;
					case 3 -> value == '\n' ? 4 : 0;
					default -> matched;
				};
				if (matched == 4)
					break;
			}
			if (matched != 4)
				throw new IOException("HTTP response head exceeded the byte bound.");
			String raw = bytes.toString(StandardCharsets.ISO_8859_1);
			String statusLine = raw.substring(0, raw.indexOf("\r\n"));
			String[] parts = statusLine.split(" ", 3);
			if (parts.length < 2)
				throw new IOException("Malformed HTTP status line: " + statusLine);
			try {
				return Integer.parseInt(parts[1]);
			} catch (NumberFormatException exception) {
				throw new IOException("Malformed HTTP status line: " + statusLine,
						exception);
			}
		}

		@NonNull
		private String readChunkText() throws IOException {
			String sizeLine = readCrlfLine();
			int extension = sizeLine.indexOf(';');
			String hexadecimal = (extension < 0 ? sizeLine
					: sizeLine.substring(0, extension)).trim();
			long size;
			try {
				size = Long.parseLong(hexadecimal, 16);
			} catch (NumberFormatException exception) {
				throw new IOException("Malformed HTTP chunk size: " + sizeLine,
						exception);
			}
			if (size < 1L || size > MAXIMUM_CHUNK_BYTES)
				throw new IOException("Unexpected HTTP chunk size " + size + '.');
			byte[] payload = readExactly((int) size);
			if (this.input.read() != '\r' || this.input.read() != '\n')
				throw new IOException("HTTP chunk payload was not followed by CRLF.");
			return new String(payload, StandardCharsets.UTF_8);
		}

		@NonNull
		private String readCrlfLine() throws IOException {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			boolean carriageReturn = false;
			while (bytes.size() < MAXIMUM_HEAD_BYTES) {
				int value = this.input.read();
				if (value < 0)
					throw new EOFException(
							"Socket closed while reading an HTTP chunk line.");
				if (carriageReturn && value == '\n') {
					byte[] line = bytes.toByteArray();
					return new String(line, 0, line.length - 1,
							StandardCharsets.US_ASCII);
				}
				bytes.write(value);
				carriageReturn = value == '\r';
			}
			throw new IOException("HTTP chunk line exceeded the byte bound.");
		}

		private byte[] readExactly(int length) throws IOException {
			byte[] bytes = new byte[length];
			int offset = 0;
			while (offset < bytes.length) {
				int read = this.input.read(bytes, offset, bytes.length - offset);
				if (read < 0)
					throw new EOFException("Socket closed with "
							+ (bytes.length - offset) + " bytes remaining.");
				offset += read;
			}
			return bytes;
		}

		private void closeWithReset() throws IOException {
			if (this.socket.isClosed())
				return;
			IOException failure = null;
			try {
				this.socket.setSoLinger(true, 0);
			} catch (IOException exception) {
				failure = exception;
			}
			try {
				this.socket.close();
			} catch (IOException exception) {
				if (failure == null)
					failure = exception;
				else
					failure.addSuppressed(exception);
			}
			if (failure != null)
				throw failure;
		}
	}
}
