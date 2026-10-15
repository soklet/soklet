package com.soklet;

import com.soklet.annotation.*;
import com.soklet.converter.ValueConverter;
import com.soklet.converter.ValueConverterRegistry;
import com.soklet.exception.IllegalRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.lang.reflect.Type;

import static org.junit.jupiter.api.Assertions.*;

class DeepDiveBindingRegressionTests {
	private static final byte[] BINARY = {(byte) 0x89, 'P', 'N', 'G', (byte) 0xff, 0};

	@Test
	void wrapFailuresHavePairedLifecycleCallbacksAndCountAsUnmatchedServerErrors() {
		for (boolean throwsException : List.of(true, false)) {
			MetricsCollector collector = MetricsCollector.defaultInstance();
			java.util.ArrayList<String> events = new java.util.ArrayList<>();
			SokletSimulator.run(SimulatorConfig.builder().httpServer()
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(PathResource.class)))
					.lifecyclePolicy(LifecyclePolicy.builder()
							.startupTimeout(java.time.Duration.ofSeconds(1))
							.startupCancelationTimeout(java.time.Duration.ofSeconds(1))
							.gracefulShutdownTimeout(java.time.Duration.ofSeconds(1))
							.forcedShutdownTimeout(java.time.Duration.ofSeconds(1)).build())
					.metricsCollector(collector)
					.lifecycleObserver(new LifecycleObserver() {
						@Override public void didStartRequestHandling(ServerType type, Request request, ResourceMethod method) { events.add("start"); }
						@Override public void didFinishRequestHandling(ServerType type, Request request, ResourceMethod method,
								MarshaledResponse response, java.time.Duration duration, List<Throwable> throwables) { events.add("finish"); }
					})
					.requestInterceptor(new RequestInterceptor() {
						@Override public void wrapRequest(ServerType type, Request request, java.util.function.Consumer<Request> processor) {
							if (throwsException) throw new IllegalStateException("wrap failed");
						}
					}).build(), simulator -> {
				assertEquals(500, simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/admin")).getMarshaledResponse().getStatusCode());
				assertEquals(List.of("start", "finish"), events);
				var snapshot = collector.snapshot().orElseThrow();
				assertEquals(0L, snapshot.getActiveRequests());
				assertEquals(1L, snapshot.getHttpRequestDurations().values().stream().mapToLong(MetricsCollector.HistogramSnapshot::getCount).sum());
				assertEquals(1L, snapshot.getHttpResponseBodyBytes().values().stream().mapToLong(MetricsCollector.HistogramSnapshot::getCount).sum());
			});
		}
	}

	@Test
	void multipleHttpAnnotationsRetainTheirOwnPaths() throws Exception {
		for (ResourceMethodResolver resolver : List.of(
				ResourceMethodResolver.fromClasses(Set.of(MultiMethodResource.class)),
				ResourceMethodResolver.fromMethods(Set.of(MultiMethodResource.class.getMethod("handle"))))) {
			assertEquals(2, resolver.getResourceMethods().size());
			assertTrue(resolver.resourceMethodForRequest(Request.fromPath(HttpMethod.GET, "/read"), ServerType.HTTP).isPresent());
			assertTrue(resolver.resourceMethodForRequest(Request.fromPath(HttpMethod.POST, "/write"), ServerType.HTTP).isPresent());
			assertTrue(resolver.resourceMethodForRequest(Request.fromPath(HttpMethod.POST, "/read"), ServerType.HTTP).isEmpty());
			assertTrue(resolver.resourceMethodForRequest(Request.fromPath(HttpMethod.GET, "/write"), ServerType.HTTP).isEmpty());
		}
	}

	public static class MultiMethodResource {
		@GET("/read") @POST("/write") public String handle() { return "ok"; }
	}

	@Test
	void binaryMultipartBindingDecodesOnlyTextTargets() {
		SokletSimulator.run(SimulatorConfig.builder().httpServer()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(UploadResource.class))).build(), simulator -> {
			for (String route : List.of("field", "bytes", "list", "optional", "implicit")) {
				var result = simulator.performHttpRequest(upload("/upload/" + route));
				assertEquals(200, result.getMarshaledResponse().getStatusCode(), route);
				assertArrayEquals(BINARY, result.getMarshaledResponse().bodyBytesOrEmpty(), route);
			}
			assertEquals(400, simulator.performHttpRequest(upload("/upload/text")).getMarshaledResponse().getStatusCode());
		});
	}

	private static Request upload(String path) {
		byte[] prefix = "--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"image.png\"\r\nContent-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
		byte[] suffix = "\r\n--boundary--\r\n".getBytes(StandardCharsets.US_ASCII);
		byte[] body = new byte[prefix.length + BINARY.length + suffix.length];
		System.arraycopy(prefix, 0, body, 0, prefix.length);
		System.arraycopy(BINARY, 0, body, prefix.length, BINARY.length);
		System.arraycopy(suffix, 0, body, prefix.length + BINARY.length, suffix.length);
		return Request.withPath(HttpMethod.POST, path).headers(Map.of("Content-Type", List.of("multipart/form-data; boundary=boundary"))).body(body).build();
	}

	public static class UploadResource {
		@POST("/upload/field") public byte[] field(@Multipart(name="file") MultipartField file) { return file.getData().orElseThrow(); }
		@POST("/upload/bytes") public byte[] bytes(@Multipart(name="file") byte[] file) { return file; }
		@POST("/upload/list") public byte[] list(@Multipart(name="file") List<MultipartField> file) { return file.getFirst().getData().orElseThrow(); }
		@POST("/upload/optional") public byte[] optional(@Multipart(name="file") Optional<MultipartField> file) { return file.orElseThrow().getData().orElseThrow(); }
		@POST("/upload/implicit") public byte[] implicit(MultipartField file) { return file.getData().orElseThrow(); }
		@POST("/upload/text") public String text(@Multipart(name="file") String file) { return file; }
	}

	@Test
	void encodedTrailingSpacesRemainPathDataForRoutingAndCopies() {
		SokletSimulator.run(SimulatorConfig.builder().httpServer()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(PathResource.class))).build(), simulator -> {
			for (String target : List.of("/admin%20", "/admin%C2%A0", "/admin/%20")) {
				Request request = Request.fromRawUrl(HttpMethod.GET, target);
				assertEquals(request.getPath(), request.getResourcePath().getPath());
				assertEquals(request.getPath(), request.copy().finish().getPath());
				assertEquals(404, simulator.performHttpRequest(request).getMarshaledResponse().getStatusCode(), target);
			}
			assertEquals(200, simulator.performHttpRequest(Request.fromRawUrl(HttpMethod.GET, "/admin")).getMarshaledResponse().getStatusCode());
		});
	}

	public static class PathResource {
		@GET("/admin") public String admin() { return "admin"; }
	}

	@Test
	void formFieldLimitCountsRepeatedNamesAndSkipsEmptySeparators() {
		var request = form("x=1&".repeat(1_000));
		assertEquals(1_000, request.getFormParameters().get("x").size());
		assertThrows(IllegalRequestException.class, () -> form("x=1&".repeat(1_001)).getFormParameters());
		assertTrue(form("&".repeat(10_000)).getFormParameters().isEmpty());
	}

	private static Request form(String body) {
		return Request.withPath(HttpMethod.POST, "/form").headers(Map.of("Content-Type", List.of("application/x-www-form-urlencoded")))
				.body(body.getBytes(StandardCharsets.UTF_8)).build();
	}

	@Test
	void defaultArbitraryPrecisionNumberConversionsAreBounded() throws Exception {
		var registry = ValueConverterRegistry.fromDefaults();
		for (Class<?> type : List.of(BigInteger.class, BigDecimal.class, Number.class)) {
			var converter = registry.get(String.class, type).orElseThrow();
			assertTrue(converter.convert("9".repeat(4_096)).isPresent());
			assertThrows(com.soklet.converter.ValueConversionException.class, () -> converter.convert("9".repeat(4_097)), type.getName());
		}
	}

	@Test
	void languageParsingIsBoundedAcrossHeaderLines() {
		for (List<String> values : List.of(List.of("x".repeat(4_097)), List.of("en,".repeat(31) + "en", "fr"))) {
			Request request = Request.withPath(HttpMethod.GET, "/").headers(Map.of("Accept-Language", values)).build();
			assertTrue(request.getLocales().isEmpty());
			assertTrue(request.getLanguageRanges().isEmpty());
		}
		assertFalse(Request.withPath(HttpMethod.GET, "/").headers(Map.of("Accept-Language", List.of("en,".repeat(31) + "en"))).build().getLocales().isEmpty());
	}

	@Test
	void listBindingsOmitEmptyConversionsAndRequiredListsStillFail() {
		ValueConverter<String, Integer> converter = new ValueConverter<>() {
			public Type getFromType() { return String.class; }
			public Type getToType() { return Integer.class; }
			public Optional<Integer> convert(String from) { return "skip".equals(from) ? Optional.empty() : Optional.of(Integer.valueOf(from)); }
		};
		SokletSimulator.run(SimulatorConfig.builder().httpServer()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(ListResource.class)))
				.valueConverterRegistry(ValueConverterRegistry.fromDefaultsSupplementedBy(Set.of(converter))).build(), simulator -> {
			assertEquals(400, simulator.performHttpRequest(Request.fromRawUrl(HttpMethod.GET, "/list?value=skip")).getMarshaledResponse().getStatusCode());
			assertEquals("[2]", new String(simulator.performHttpRequest(Request.fromRawUrl(HttpMethod.GET, "/list?value=skip&value=2")).getMarshaledResponse().bodyBytesOrEmpty(), StandardCharsets.UTF_8));
		});
	}

	public static class ListResource {
		@GET("/list") public String list(@QueryParameter(name="value") List<Integer> value) { return value.toString(); }
	}
}
