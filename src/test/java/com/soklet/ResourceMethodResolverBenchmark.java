package com.soklet;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Small dependency-free benchmark for resource method resolution.
 * <p>
 * This is intentionally not a JUnit test. Run after {@code mvn test-compile}:
 * <pre>{@code
 * java -cp target/classes:target/test-classes com.soklet.ResourceMethodResolverBenchmark
 * }</pre>
 */
public final class ResourceMethodResolverBenchmark {
	private static final int[] ROUTE_COUNTS = {50, 500, 5_000};
	private static final Method BENCHMARK_METHOD = benchmarkMethod();
	private static final List<ScenarioConfig> SCENARIO_CONFIGS = scenarioConfigs();
	private static final com.sun.management.ThreadMXBean THREAD_MX_BEAN =
			(com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

	private static volatile long blackhole;

	private ResourceMethodResolverBenchmark() {
	}

	public static void main(String[] args) throws Exception {
		int warmups = integerProperty("soklet.benchmark.warmups", 5);
		int measurements = integerProperty("soklet.benchmark.measurements", 7);
		int operations = integerProperty("soklet.benchmark.operations", 250_000);

		enableThreadAllocatedBytes();
		System.out.printf(Locale.ROOT,
				"Resource method resolver benchmark: warmups=%d measurements=%d operations=%d allocationTracking=%s%n",
				warmups, measurements, operations, allocationTrackingAvailable());

		for (ScenarioConfig scenarioConfig : SCENARIO_CONFIGS)
			runScenario(scenarioConfig.name(), warmups, measurements, operations,
					operationCount -> resolve(scenarioConfig.resolver(), scenarioConfig.request(), operationCount));

		System.out.println("blackhole=" + blackhole);
	}

	private static void runScenario(String name,
																	int warmups,
																	int measurements,
																	int operations,
																	Scenario scenario) throws Exception {
		for (int i = 0; i < warmups; i++)
			scenario.run(operations);

		long[] nanosPerOperation = new long[measurements];
		long[] bytesPerOperation = new long[measurements];

		for (int i = 0; i < measurements; i++) {
			System.gc();
			Thread.sleep(50L);

			long allocatedBefore = threadAllocatedBytes();
			long startedAt = System.nanoTime();
			scenario.run(operations);
			long elapsed = System.nanoTime() - startedAt;
			long allocatedAfter = threadAllocatedBytes();

			nanosPerOperation[i] = elapsed / operations;
			bytesPerOperation[i] = allocatedBefore >= 0L && allocatedAfter >= 0L
					? (allocatedAfter - allocatedBefore) / operations
					: -1L;
		}

		Arrays.sort(nanosPerOperation);
		Arrays.sort(bytesPerOperation);

		long medianNs = nanosPerOperation[nanosPerOperation.length / 2];
		long medianBytes = bytesPerOperation[bytesPerOperation.length / 2];
		double operationsPerSecond = 1_000_000_000D / Math.max(1D, medianNs);

		System.out.printf(Locale.ROOT,
				"%-20s median=%8d ns/op throughput=%10.0f ops/s allocated=%6s bytes/op%n",
				name, medianNs, operationsPerSecond, medianBytes < 0L ? "n/a" : Long.toString(medianBytes));
	}

	private static void resolve(DefaultResourceMethodResolver resolver,
															Request request,
															int operations) {
		for (int i = 0; i < operations; i++)
			consume(resolver.resourceMethodForRequest(request, ServerType.STANDARD_HTTP));
	}

	private static List<ScenarioConfig> scenarioConfigs() {
		List<ScenarioConfig> configs = new ArrayList<>();

		for (int routeCount : ROUTE_COUNTS) {
			configs.add(new ScenarioConfig("literal" + routeCount,
					resolverFor(routeCount, RouteShape.LITERAL),
					Request.withPath(HttpMethod.GET, "/benchmark/literal/route-" + (routeCount - 1)).build()));
			configs.add(new ScenarioConfig("placeholder" + routeCount,
					resolverFor(routeCount, RouteShape.PLACEHOLDER),
					Request.withPath(HttpMethod.GET, "/benchmark/tenant-a/item-" + (routeCount - 1)).build()));
			configs.add(new ScenarioConfig("varargs" + routeCount,
					resolverFor(routeCount, RouteShape.VARARGS),
					Request.withPath(HttpMethod.GET, "/benchmark/files-" + (routeCount - 1) + "/css/app.css").build()));
		}

		return List.copyOf(configs);
	}

	private static DefaultResourceMethodResolver resolverFor(int routeCount,
																													 RouteShape routeShape) {
		Set<ResourceMethod> resourceMethods = new HashSet<>(routeCount);

		for (int i = 0; i < routeCount; i++)
			resourceMethods.add(ResourceMethod.fromComponents(HttpMethod.GET,
					ResourcePathDeclaration.fromPath(pathFor(routeShape, i)),
					BENCHMARK_METHOD,
					false));

		return DefaultResourceMethodResolver.fromResourceMethods(resourceMethods);
	}

	private static String pathFor(RouteShape routeShape,
																int index) {
		return switch (routeShape) {
			case LITERAL -> "/benchmark/literal/route-" + index;
			case PLACEHOLDER -> "/benchmark/{tenant}/item-" + index;
			case VARARGS -> "/benchmark/files-" + index + "/{path*}";
		};
	}

	private static void consume(Optional<ResourceMethod> resourceMethod) {
		ResourceMethod value = resourceMethod.orElseThrow();
		blackhole ^= value.getHttpMethod().ordinal();
		blackhole ^= value.getResourcePathDeclaration().getPath().hashCode();
		blackhole ^= value.getMethod().getName().hashCode();
	}

	private static int integerProperty(String name, int defaultValue) {
		String value = System.getProperty(name);
		if (value == null || value.isBlank())
			return defaultValue;
		return Integer.parseInt(value);
	}

	private static void enableThreadAllocatedBytes() {
		if (THREAD_MX_BEAN.isThreadAllocatedMemorySupported() && !THREAD_MX_BEAN.isThreadAllocatedMemoryEnabled())
			THREAD_MX_BEAN.setThreadAllocatedMemoryEnabled(true);
	}

	private static boolean allocationTrackingAvailable() {
		return THREAD_MX_BEAN.isThreadAllocatedMemorySupported() && THREAD_MX_BEAN.isThreadAllocatedMemoryEnabled();
	}

	private static long threadAllocatedBytes() {
		if (!allocationTrackingAvailable())
			return -1L;
		return THREAD_MX_BEAN.getThreadAllocatedBytes(Thread.currentThread().getId());
	}

	private static Method benchmarkMethod() {
		try {
			return BenchmarkResource.class.getMethod("handle");
		} catch (NoSuchMethodException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private enum RouteShape {
		LITERAL,
		PLACEHOLDER,
		VARARGS
	}

	private record ScenarioConfig(String name,
																DefaultResourceMethodResolver resolver,
																Request request) {
	}

	@FunctionalInterface
	private interface Scenario {
		void run(int operations) throws Exception;
	}

	public static class BenchmarkResource {
		public String handle() {
			return "ok";
		}
	}
}
