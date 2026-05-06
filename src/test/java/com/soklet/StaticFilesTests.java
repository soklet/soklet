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

import com.soklet.ByteRangeSelection.ByteRangeSelectionType;
import com.soklet.annotation.GET;
import com.soklet.annotation.PathParameter;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class StaticFilesTests {
	@Test
	public void entityTagsParseAndCompare() {
		EntityTag strong = EntityTag.fromHeaderValue("\"abc123\"").orElseThrow();
		EntityTag weak = EntityTag.fromHeaderValue("W/\"abc123\"").orElseThrow();

		Assertions.assertFalse(strong.isWeak());
		Assertions.assertTrue(weak.isWeak());
		Assertions.assertEquals("\"abc123\"", strong.toHeaderValue());
		Assertions.assertEquals("W/\"abc123\"", weak.toHeaderValue());
		Assertions.assertTrue(strong.weaklyMatches(weak));
		Assertions.assertFalse(strong.stronglyMatches(weak));
		Assertions.assertTrue(EntityTag.fromHeaderValue("*").isEmpty());
		Assertions.assertTrue(EntityTag.fromHeaderValue("\"unterminated").isEmpty());
		Assertions.assertTrue(EntityTag.fromHeaderValue("\"one\", \"two\"").isEmpty());
	}

	@Test
	public void httpDateFormatsParsesAndKeepsCookieExpiresBehavior() throws InterruptedException {
		Instant instant = Instant.parse("2026-05-04T01:02:03Z");

		Assertions.assertEquals("Mon, 04 May 2026 01:02:03 GMT", HttpDate.toHeaderValue(instant));
		Assertions.assertEquals(instant, HttpDate.fromHeaderValue("Mon, 04 May 2026 01:02:03 GMT").orElseThrow());
		Assertions.assertEquals(Instant.parse("1994-11-06T08:49:37Z"), HttpDate.fromHeaderValue("Sunday, 06-Nov-94 08:49:37 GMT").orElseThrow());
		Assertions.assertEquals(Instant.parse("1994-11-06T08:49:37Z"), HttpDate.fromHeaderValue("Sun, 06 Nov 94 08:49:37 GMT").orElseThrow());
		Assertions.assertEquals(Instant.parse("1994-11-06T08:49:37Z"), HttpDate.fromHeaderValue("Sun Nov  6 08:49:37 1994").orElseThrow());
		Assertions.assertTrue(HttpDate.fromHeaderValue("not a date").isEmpty());

		String firstDate = HttpDate.currentSecondHeaderValue();
		String secondDate = HttpDate.currentSecondHeaderValue();
		Assertions.assertEquals(firstDate, secondDate);
		Long firstEpochSecond = Instant.now().getEpochSecond();

		while (Instant.now().getEpochSecond() == firstEpochSecond)
			Thread.sleep(5L);

		Assertions.assertNotEquals(firstDate, HttpDate.currentSecondHeaderValue());

		ResponseCookie cookie = ResponseCookie.with("sid", "abc").expires(instant).build();
		Assertions.assertTrue(cookie.toSetCookieHeaderRepresentation().contains("Expires=Mon, 04 May 2026 01:02:03 GMT"));
		Assertions.assertEquals(instant, ResponseCookie.fromSetCookieHeaderRepresentation("sid=abc; Expires=Mon, 04 May 2026 01:02:03 GMT").orElseThrow().getExpires().orElseThrow());
		Assertions.assertTrue(ResponseCookie.fromSetCookieHeaderRepresentation("sid=abc; Expires=not-a-date").orElseThrow().getExpires().isEmpty());
	}

	@Test
	public void byteRangeSelectionDistinguishesRangeStates() {
		Assertions.assertEquals(ByteRangeSelectionType.ABSENT, ByteRangeSelection.fromHeaderValue(null, 10L).getType());
		Assertions.assertEquals(ByteRangeSelectionType.UNSUPPORTED, ByteRangeSelection.fromHeaderValue("items=0-1", 10L).getType());
		Assertions.assertEquals(ByteRangeSelectionType.UNSUPPORTED, ByteRangeSelection.fromHeaderValue("bytes=0-1,3-4", 10L).getType());
		Assertions.assertEquals(ByteRangeSelectionType.MALFORMED, ByteRangeSelection.fromHeaderValue("bytes=4-2", 10L).getType());
		Assertions.assertEquals(ByteRangeSelectionType.UNSATISFIABLE, ByteRangeSelection.fromHeaderValue("bytes=20-30", 10L).getType());
		Assertions.assertEquals(ByteRangeSelectionType.UNSATISFIABLE, ByteRangeSelection.fromHeaderValue("bytes=-0", 10L).getType());
		Assertions.assertEquals(ByteRangeSelectionType.UNSATISFIABLE, ByteRangeSelection.fromHeaderValue("bytes=0-0", 0L).getType());

		ByteRange prefix = ByteRangeSelection.fromHeaderValue("bytes=2-4", 10L).getRange().orElseThrow();
		Assertions.assertEquals(Long.valueOf(2), prefix.getStart());
		Assertions.assertEquals(Long.valueOf(4), prefix.getEndInclusive());
		Assertions.assertEquals(Long.valueOf(3), prefix.getLength());
		Assertions.assertEquals("bytes 2-4/10", prefix.toContentRangeHeaderValue(10L));

		ByteRange suffix = ByteRangeSelection.fromHeaderValue("bytes=-4", 10L).getRange().orElseThrow();
		Assertions.assertEquals(Long.valueOf(6), suffix.getStart());
		Assertions.assertEquals(Long.valueOf(9), suffix.getEndInclusive());

		ByteRange hugeSuffix = ByteRangeSelection.fromHeaderValue("bytes=-999999999999999999999999999999999999", 10L).getRange().orElseThrow();
		Assertions.assertEquals(Long.valueOf(0), hugeSuffix.getStart());
		Assertions.assertEquals(Long.valueOf(9), hugeSuffix.getEndInclusive());
	}

	@Test
	public void marshaledFileResponseServesFullAndPartialFiles(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("example.txt");
		Files.writeString(file, "abcdef", StandardCharsets.UTF_8);
		Instant lastModified = Instant.parse("2026-05-04T01:02:03.999Z");
		EntityTag entityTag = EntityTag.fromStrongValue("v1");

		MarshaledResponse fullResponse = MarshaledResponse.withFile(file, Request.fromPath(HttpMethod.GET, "/example.txt"))
				.contentType("text/plain; charset=UTF-8")
				.entityTag(entityTag)
				.lastModified(lastModified)
				.cacheControl("public, max-age=60")
				.build();

		Assertions.assertEquals(200, fullResponse.getStatusCode());
		Assertions.assertEquals(Set.of("text/plain; charset=UTF-8"), fullResponse.getHeaders().get("Content-Type"));
		Assertions.assertEquals(Set.of("\"v1\""), fullResponse.getHeaders().get("ETag"));
		Assertions.assertEquals(Set.of("Mon, 04 May 2026 01:02:03 GMT"), fullResponse.getHeaders().get("Last-Modified"));
		Assertions.assertEquals(Set.of("bytes"), fullResponse.getHeaders().get("Accept-Ranges"));
		MarshaledResponseBody.File fullBody = (MarshaledResponseBody.File) fullResponse.getBody().orElseThrow();
		Assertions.assertEquals(Long.valueOf(0), fullBody.getOffset());
		Assertions.assertEquals(Long.valueOf(6), fullBody.getCount());

		Request rangeRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of("Range", Set.of("bytes=2-4")))
				.build();
		MarshaledResponse rangeResponse = MarshaledResponse.withFile(file, rangeRequest)
				.contentType("text/plain; charset=UTF-8")
				.entityTag(entityTag)
				.lastModified(lastModified)
				.cacheControl("public, max-age=60")
				.build();
		Assertions.assertEquals(206, rangeResponse.getStatusCode());
		Assertions.assertEquals(Set.of("bytes 2-4/6"), rangeResponse.getHeaders().get("Content-Range"));
		MarshaledResponseBody.File rangeBody = (MarshaledResponseBody.File) rangeResponse.getBody().orElseThrow();
		Assertions.assertEquals(Long.valueOf(2), rangeBody.getOffset());
		Assertions.assertEquals(Long.valueOf(3), rangeBody.getCount());
	}

	@Test
	public void marshaledFileResponseAppliesConditionalsAndHeadRangeSemantics(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("example.txt");
		Files.writeString(file, "abcdef", StandardCharsets.UTF_8);

		Request notModifiedRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of("If-None-Match", Set.of("W/\"v1\"")))
				.build();
		MarshaledResponse notModifiedResponse = fileBuilder(file, notModifiedRequest).build();
		Assertions.assertEquals(304, notModifiedResponse.getStatusCode());
		Assertions.assertTrue(notModifiedResponse.getBody().isEmpty());
		Assertions.assertFalse(notModifiedResponse.getHeaders().containsKey("Content-Type"));

		Request ifMatchWildcardRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of("If-Match", Set.of("*")))
				.build();
		Assertions.assertEquals(200, fileBuilder(file, ifMatchWildcardRequest).build().getStatusCode());

		Request ifNoneMatchWildcardRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of("If-None-Match", Set.of("*")))
				.build();
		Assertions.assertEquals(304, fileBuilder(file, ifNoneMatchWildcardRequest).build().getStatusCode());

		Request modifiedSinceFutureRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of("If-Modified-Since", Set.of("Mon, 04 May 2026 01:02:04 GMT")))
				.build();
		Assertions.assertEquals(304, fileBuilder(file, modifiedSinceFutureRequest).build().getStatusCode());

		Request modifiedSincePastRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of("If-Modified-Since", Set.of("Mon, 04 May 2026 01:02:02 GMT")))
				.build();
		Assertions.assertEquals(200, fileBuilder(file, modifiedSincePastRequest).build().getStatusCode());

		Request failedPreconditionRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of("If-Match", Set.of("\"other\"")))
				.build();
		MarshaledResponse failedPreconditionResponse = fileBuilder(file, failedPreconditionRequest).build();
		Assertions.assertEquals(412, failedPreconditionResponse.getStatusCode());
		Assertions.assertTrue(failedPreconditionResponse.getBody().isEmpty());

		Request failedHeadPreconditionRequest = Request.withPath(HttpMethod.HEAD, "/example.txt")
				.headers(Map.of("If-Match", Set.of("\"other\"")))
				.build();
		MarshaledResponse failedHeadPreconditionResponse = fileBuilder(file, failedHeadPreconditionRequest).build();
		Assertions.assertEquals(412, failedHeadPreconditionResponse.getStatusCode());
		Assertions.assertTrue(failedHeadPreconditionResponse.getBody().isEmpty());
		MarshaledResponse marshaledFailedHeadPreconditionResponse = DefaultResponseMarshaler.defaultInstance().forHead(failedHeadPreconditionRequest, failedHeadPreconditionResponse);
		Assertions.assertTrue(marshaledFailedHeadPreconditionResponse.getBody().isEmpty());
		Assertions.assertEquals(Set.of("0"), marshaledFailedHeadPreconditionResponse.getHeaders().get("Content-Length"));

		Request headRequest = Request.fromPath(HttpMethod.HEAD, "/example.txt");
		MarshaledResponse headGetEquivalent = fileBuilder(file, headRequest).build();
		Assertions.assertEquals(200, headGetEquivalent.getStatusCode());
		Assertions.assertEquals(Set.of("bytes"), headGetEquivalent.getHeaders().get("Accept-Ranges"));
		Assertions.assertEquals(Long.valueOf(6), headGetEquivalent.getBodyLength());

		MarshaledResponse marshaledHeadResponse = DefaultResponseMarshaler.defaultInstance().forHead(headRequest, headGetEquivalent);
		Assertions.assertTrue(marshaledHeadResponse.getBody().isEmpty());
		Assertions.assertEquals(Set.of("6"), marshaledHeadResponse.getHeaders().get("Content-Length"));
		Assertions.assertEquals(Set.of("bytes"), marshaledHeadResponse.getHeaders().get("Accept-Ranges"));

		Request headRangeRequest = Request.withPath(HttpMethod.HEAD, "/example.txt")
				.headers(Map.of(
						"Range", Set.of("bytes=2-4"),
						"If-Range", Set.of("\"v1\"")
				))
				.build();
		MarshaledResponse headGetEquivalentResponse = fileBuilder(file, headRangeRequest).build();
		Assertions.assertEquals(200, headGetEquivalentResponse.getStatusCode());
		Assertions.assertFalse(headGetEquivalentResponse.getHeaders().containsKey("Content-Range"));
		Assertions.assertEquals(Set.of("bytes"), headGetEquivalentResponse.getHeaders().get("Accept-Ranges"));
		Assertions.assertEquals(Long.valueOf(6), headGetEquivalentResponse.getBodyLength());

		MarshaledResponse headResponse = DefaultResponseMarshaler.defaultInstance().forHead(headRangeRequest, headGetEquivalentResponse);
		Assertions.assertTrue(headResponse.getBody().isEmpty());
		Assertions.assertEquals(200, headResponse.getStatusCode());
		Assertions.assertEquals(Set.of("6"), headResponse.getHeaders().get("Content-Length"));

		Request unsatisfiableRangeRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of("Range", Set.of("bytes=20-30")))
				.build();
		MarshaledResponse unsatisfiableRangeResponse = fileBuilder(file, unsatisfiableRangeRequest).build();
		Assertions.assertEquals(416, unsatisfiableRangeResponse.getStatusCode());
		Assertions.assertTrue(unsatisfiableRangeResponse.getBody().isEmpty());
	}

	@Test
	public void marshaledFileResponseAppliesIfRangeOnlyToGetRequests(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("example.txt");
		Files.writeString(file, "abcdef", StandardCharsets.UTF_8);

		Request matchingStrongEntityTagRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of(
						"Range", Set.of("bytes=2-4"),
						"If-Range", Set.of("\"v1\"")
				))
				.build();
		Assertions.assertEquals(206, fileBuilder(file, matchingStrongEntityTagRequest).build().getStatusCode());

		Request matchingWeakEntityTagRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of(
						"Range", Set.of("bytes=2-4"),
						"If-Range", Set.of("W/\"v1\"")
				))
				.build();
		MarshaledResponse weakEntityTagResponse = fileBuilder(file, matchingWeakEntityTagRequest).build();
		Assertions.assertEquals(200, weakEntityTagResponse.getStatusCode());
		Assertions.assertFalse(weakEntityTagResponse.getHeaders().containsKey("Content-Range"));

		Request matchingDateRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of(
						"Range", Set.of("bytes=2-4"),
						"If-Range", Set.of("Mon, 04 May 2026 01:02:03 GMT")
				))
				.build();
		Assertions.assertEquals(206, fileBuilder(file, matchingDateRequest).build().getStatusCode());

		Request nonmatchingDateRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of(
						"Range", Set.of("bytes=2-4"),
						"If-Range", Set.of("Mon, 04 May 2026 01:02:02 GMT")
				))
				.build();
		MarshaledResponse nonmatchingDateResponse = fileBuilder(file, nonmatchingDateRequest).build();
		Assertions.assertEquals(200, nonmatchingDateResponse.getStatusCode());
		Assertions.assertFalse(nonmatchingDateResponse.getHeaders().containsKey("Content-Range"));

		Request ifRangeWithoutResponseValidatorRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of(
						"Range", Set.of("bytes=2-4"),
						"If-Range", Set.of("\"v1\"")
				))
				.build();
		Assertions.assertEquals(200, MarshaledResponse.withFile(file, ifRangeWithoutResponseValidatorRequest).build().getStatusCode());
	}

	@Test
	public void marshaledFileResponseCombinesRepeatedConditionalAndRangeHeaders(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("example.txt");
		Files.writeString(file, "abcdef", StandardCharsets.UTF_8);

		Request repeatedIfNoneMatchRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of("If-None-Match", Set.of("\"other\"", "\"v1\"")))
				.build();
		Assertions.assertEquals(304, MarshaledResponse.withFile(file, repeatedIfNoneMatchRequest)
				.entityTag(EntityTag.fromStrongValue("v1"))
				.build()
				.getStatusCode());

		Request repeatedRangeRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of("Range", Set.of("bytes=0-1", "bytes=2-3")))
				.build();
		MarshaledResponse repeatedRangeResponse = MarshaledResponse.withFile(file, repeatedRangeRequest)
				.entityTag(EntityTag.fromStrongValue("v1"))
				.build();
		Assertions.assertEquals(200, repeatedRangeResponse.getStatusCode());
		Assertions.assertFalse(repeatedRangeResponse.getHeaders().containsKey("Content-Range"));
		Assertions.assertEquals(Long.valueOf(6), repeatedRangeResponse.getBodyLength());
	}

	@Test
	public void staticFilesResolvesPathsAndCollapsesMisses(@TempDir Path tempDir) throws IOException {
		Path appJs = tempDir.resolve("app.js");
		Path indexHtml = tempDir.resolve("index.html");
		Files.writeString(appJs, "console.log('hi');", StandardCharsets.UTF_8);
		Files.writeString(indexHtml, "<!doctype html>", StandardCharsets.UTF_8);
		StaticFiles staticFiles = StaticFiles.withRoot(tempDir)
				.indexFileNames(List.of("index.html"))
				.build();

		Optional<MarshaledResponse> appResponse = staticFiles.marshaledResponseFor("app.js", Request.fromPath(HttpMethod.GET, "/assets/app.js"));
		Assertions.assertTrue(appResponse.isPresent());
		Assertions.assertEquals(200, appResponse.get().getStatusCode());
		Assertions.assertTrue(appResponse.get().getHeaders().containsKey("ETag"));
		String defaultEntityTag = appResponse.get().getHeaders().get("ETag").iterator().next();
		Assertions.assertTrue(defaultEntityTag.startsWith("W/\"mtime-"));
		Assertions.assertTrue(defaultEntityTag.endsWith("-size-" + Files.size(appJs) + "\""));
		Assertions.assertTrue(appResponse.get().getHeaders().containsKey("Last-Modified"));

		Optional<MarshaledResponse> indexResponse = staticFiles.marshaledResponseFor("", Request.fromPath(HttpMethod.GET, "/assets/"));
		Assertions.assertTrue(indexResponse.isPresent());
		Assertions.assertEquals(200, indexResponse.get().getStatusCode());

		Assertions.assertTrue(staticFiles.marshaledResponseFor("app.js", Request.fromPath(HttpMethod.POST, "/assets/app.js")).isEmpty());
		Assertions.assertTrue(staticFiles.marshaledResponseFor("missing.js", Request.fromPath(HttpMethod.GET, "/assets/missing.js")).isEmpty());
		Assertions.assertTrue(staticFiles.marshaledResponseFor("../secret", Request.fromPath(HttpMethod.GET, "/assets/secret")).isEmpty());
		Assertions.assertTrue(staticFiles.marshaledResponseFor(tempDir.resolve("app.js").toString(), Request.fromPath(HttpMethod.GET, "/assets/secret")).isEmpty());
		Assertions.assertTrue(staticFiles.marshaledResponseFor("nested\\app.js", Request.fromPath(HttpMethod.GET, "/assets/app.js")).isEmpty());

		Path wellKnown = tempDir.resolve(".well-known");
		Files.createDirectories(wellKnown);
		Files.writeString(wellKnown.resolve("security.txt"), "contact: mailto:security@example.com", StandardCharsets.UTF_8);
		Assertions.assertTrue(staticFiles.marshaledResponseFor(".well-known/security.txt", Request.fromPath(HttpMethod.GET, "/assets/.well-known/security.txt")).isPresent());
		Assertions.assertTrue(staticFiles.marshaledResponseFor("a".repeat(4097), Request.fromPath(HttpMethod.GET, "/assets/long")).isEmpty());
	}

	@Test
	public void staticFilesAppliesPerPathResolvers(@TempDir Path tempDir) throws IOException {
		Path indexHtml = tempDir.resolve("index.html");
		Path appJs = tempDir.resolve("app.js");
		Files.writeString(indexHtml, "<!doctype html>", StandardCharsets.UTF_8);
		Files.writeString(appJs, "console.log('hi');", StandardCharsets.UTF_8);
		AtomicReference<Path> resolvedPath = new AtomicReference<>();
		StaticFiles staticFiles = StaticFiles.withRoot(tempDir)
				.indexFileNames(List.of("index.html"))
				.cacheControlResolver((path, attributes) -> Optional.of(path.getFileName().toString().endsWith(".js")
						? "public, max-age=31536000, immutable"
						: "no-cache"))
				.headersResolver((path, attributes) -> {
					resolvedPath.set(path);
					return Map.of("X-Static", Set.of(path.getFileName().toString()));
				})
				.rangeRequestsResolver((path, attributes) -> path.getFileName().toString().endsWith(".js"))
				.mimeTypeResolver((path) -> path.getFileName().toString().endsWith(".bin")
						? Optional.of("application/octet-stream")
						: Optional.empty())
				.build();

		MarshaledResponse indexResponse = staticFiles.marshaledResponseFor("", Request.fromPath(HttpMethod.GET, "/assets/")).orElseThrow();
		Assertions.assertTrue(Files.isSameFile(indexHtml, resolvedPath.get()));
		Assertions.assertEquals(Set.of("no-cache"), indexResponse.getHeaders().get("Cache-Control"));
		Assertions.assertFalse(indexResponse.getHeaders().containsKey("Accept-Ranges"));
		Assertions.assertEquals(Set.of("index.html"), indexResponse.getHeaders().get("X-Static"));

		MarshaledResponse appResponse = staticFiles.marshaledResponseFor("app.js", Request.fromPath(HttpMethod.GET, "/assets/app.js")).orElseThrow();
		Assertions.assertEquals(Set.of("public, max-age=31536000, immutable"), appResponse.getHeaders().get("Cache-Control"));
		Assertions.assertEquals(Set.of("bytes"), appResponse.getHeaders().get("Accept-Ranges"));
	}

	@Test
	public void staticFilesMimeTypeResolutionIsDeterministicAndExplicit(@TempDir Path tempDir) throws IOException {
		Path textFile = tempDir.resolve("example.txt");
		Path customFile = tempDir.resolve("example.foo");
		Path unknownFile = tempDir.resolve("example.unknown");
		Files.write(textFile, new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
		Files.writeString(customFile, "abcdef", StandardCharsets.UTF_8);
		Files.writeString(unknownFile, "abcdef", StandardCharsets.UTF_8);

		StaticFiles defaultStaticFiles = StaticFiles.withRoot(tempDir).build();
		MarshaledResponse textResponse = defaultStaticFiles.marshaledResponseFor("example.txt", Request.fromPath(HttpMethod.GET, "/example.txt")).orElseThrow();
		Assertions.assertEquals(Set.of("text/plain; charset=UTF-8"), textResponse.getHeaders().get("Content-Type"));
		MarshaledResponse unknownResponse = defaultStaticFiles.marshaledResponseFor("example.unknown", Request.fromPath(HttpMethod.GET, "/example.unknown")).orElseThrow();
		Assertions.assertFalse(unknownResponse.getHeaders().containsKey("Content-Type"));

		StaticFiles omittedStaticFiles = StaticFiles.withRoot(tempDir)
				.mimeTypeResolver((path) -> Optional.empty())
				.build();
		MarshaledResponse omittedResponse = omittedStaticFiles.marshaledResponseFor("example.txt", Request.fromPath(HttpMethod.GET, "/example.txt")).orElseThrow();
		Assertions.assertFalse(omittedResponse.getHeaders().containsKey("Content-Type"));

		StaticFiles customStaticFiles = StaticFiles.withRoot(tempDir)
				.mimeTypeResolver((path) -> Optional.of("application/x-example"))
				.build();
		MarshaledResponse customResponse = customStaticFiles.marshaledResponseFor("example.txt", Request.fromPath(HttpMethod.GET, "/example.txt")).orElseThrow();
		Assertions.assertEquals(Set.of("application/x-example"), customResponse.getHeaders().get("Content-Type"));

		MimeTypeResolver defaultMimeTypeResolver = MimeTypeResolver.defaultInstance();
		StaticFiles extendedStaticFiles = StaticFiles.withRoot(tempDir)
				.mimeTypeResolver((path) -> path.getFileName().toString().endsWith(".foo")
						? Optional.of("application/x-foo")
						: defaultMimeTypeResolver.contentTypeFor(path))
				.build();
		MarshaledResponse extendedCustomResponse = extendedStaticFiles.marshaledResponseFor("example.foo", Request.fromPath(HttpMethod.GET, "/example.foo")).orElseThrow();
		Assertions.assertEquals(Set.of("application/x-foo"), extendedCustomResponse.getHeaders().get("Content-Type"));
		MarshaledResponse extendedTextResponse = extendedStaticFiles.marshaledResponseFor("example.txt", Request.fromPath(HttpMethod.GET, "/example.txt")).orElseThrow();
		Assertions.assertEquals(Set.of("text/plain; charset=UTF-8"), extendedTextResponse.getHeaders().get("Content-Type"));
	}

	@Test
	public void staticFilesDefaultMimeTypeResolverCoversCommonWebAssets(@TempDir Path tempDir) throws IOException {
		Map<String, String> contentTypesByExtension = Map.ofEntries(
				Map.entry("html", "text/html; charset=UTF-8"),
				Map.entry("htm", "text/html; charset=UTF-8"),
				Map.entry("css", "text/css; charset=UTF-8"),
				Map.entry("js", "text/javascript; charset=UTF-8"),
				Map.entry("mjs", "text/javascript; charset=UTF-8"),
				Map.entry("json", "application/json; charset=UTF-8"),
				Map.entry("map", "application/json"),
				Map.entry("webmanifest", "application/manifest+json"),
				Map.entry("txt", "text/plain; charset=UTF-8"),
				Map.entry("xml", "application/xml; charset=UTF-8"),
				Map.entry("xhtml", "application/xhtml+xml"),
				Map.entry("atom", "application/atom+xml"),
				Map.entry("rss", "application/rss+xml"),
				Map.entry("csv", "text/csv; charset=UTF-8"),
				Map.entry("md", "text/markdown; charset=UTF-8"),
				Map.entry("markdown", "text/markdown; charset=UTF-8"),
				Map.entry("yaml", "application/yaml"),
				Map.entry("yml", "application/yaml"),
				Map.entry("jsonld", "application/ld+json"),
				Map.entry("ndjson", "application/x-ndjson"),
				Map.entry("svg", "image/svg+xml"),
				Map.entry("png", "image/png"),
				Map.entry("jpg", "image/jpeg"),
				Map.entry("jpeg", "image/jpeg"),
				Map.entry("gif", "image/gif"),
				Map.entry("webp", "image/webp"),
				Map.entry("avif", "image/avif"),
				Map.entry("jxl", "image/jxl"),
				Map.entry("heic", "image/heic"),
				Map.entry("heif", "image/heif"),
				Map.entry("apng", "image/apng"),
				Map.entry("bmp", "image/bmp"),
				Map.entry("tiff", "image/tiff"),
				Map.entry("tif", "image/tiff"),
				Map.entry("ico", "image/x-icon"),
				Map.entry("pdf", "application/pdf"),
				Map.entry("wasm", "application/wasm"),
				Map.entry("woff", "font/woff"),
				Map.entry("woff2", "font/woff2"),
				Map.entry("ttf", "font/ttf"),
				Map.entry("otf", "font/otf"),
				Map.entry("mp3", "audio/mpeg"),
				Map.entry("wav", "audio/wav"),
				Map.entry("ogg", "audio/ogg"),
				Map.entry("m4a", "audio/mp4"),
				Map.entry("aac", "audio/aac"),
				Map.entry("flac", "audio/flac"),
				Map.entry("opus", "audio/opus"),
				Map.entry("mp4", "video/mp4"),
				Map.entry("webm", "video/webm"),
				Map.entry("ogv", "video/ogg"),
				Map.entry("mov", "video/quicktime"),
				Map.entry("m4v", "video/mp4"),
				Map.entry("m3u8", "application/vnd.apple.mpegurl"),
				Map.entry("mpd", "application/dash+xml"),
				Map.entry("vtt", "text/vtt; charset=UTF-8")
		);
		StaticFiles staticFiles = StaticFiles.withRoot(tempDir).build();

		for (Map.Entry<String, String> entry : contentTypesByExtension.entrySet()) {
			String extension = entry.getKey();
			Path file = tempDir.resolve("example-" + extension + "." + extension);
			Files.writeString(file, "abcdef", StandardCharsets.UTF_8);

			MarshaledResponse response = staticFiles.marshaledResponseFor(file.getFileName().toString(), Request.fromPath(HttpMethod.GET, "/" + file.getFileName())).orElseThrow();
			Assertions.assertEquals(Set.of(entry.getValue()), response.getHeaders().get("Content-Type"), extension);
		}

		Path uppercaseFile = tempDir.resolve("example.JSON");
		Files.writeString(uppercaseFile, "{}", StandardCharsets.UTF_8);
		MarshaledResponse uppercaseResponse = staticFiles.marshaledResponseFor("example.JSON", Request.fromPath(HttpMethod.GET, "/example.JSON")).orElseThrow();
		Assertions.assertEquals(Set.of("application/json; charset=UTF-8"), uppercaseResponse.getHeaders().get("Content-Type"));
	}

	@Test
	public void staticFilesUsesOneAttributeSnapshotForResolversAndResponseLength(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("example.txt");
		Files.writeString(file, "abcdef", StandardCharsets.UTF_8);
		StaticFiles staticFiles = StaticFiles.withRoot(tempDir)
				.entityTagResolver(StaticFiles.EntityTagResolver.disabledInstance())
				.lastModifiedResolver(StaticFiles.LastModifiedResolver.disabledInstance())
				.cacheControlResolver((path, attributes) -> {
					Assertions.assertEquals(6L, attributes.size());
					try {
						Files.writeString(path, "xyz", StandardCharsets.UTF_8);
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
					return Optional.empty();
				})
				.build();

		MarshaledResponse response = staticFiles.marshaledResponseFor("example.txt", Request.fromPath(HttpMethod.GET, "/example.txt")).orElseThrow();
		Assertions.assertEquals(Long.valueOf(6), response.getBodyLength());
	}

	@Test
	public void staticFilesContentHashEntityTagResolverProducesStrongDeterministicTags(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("example.txt");
		Path emptyFile = tempDir.resolve("empty.txt");
		Path largeFile = tempDir.resolve("large.txt");
		Files.writeString(file, "abcdef", StandardCharsets.UTF_8);
		Files.write(emptyFile, new byte[0]);
		Files.writeString(largeFile, "a".repeat(9000), StandardCharsets.UTF_8);

		StaticFiles staticFiles = StaticFiles.withRoot(tempDir)
				.entityTagResolver(StaticFiles.EntityTagResolver.fromContentHash())
				.build();

		MarshaledResponse response = staticFiles.marshaledResponseFor("example.txt", Request.fromPath(HttpMethod.GET, "/example.txt")).orElseThrow();
		Assertions.assertEquals(Set.of("\"sha256-bef57ec7f53a6d40beb640a780a639c83bc29ac8a9816f1fc6c5c6dcd93c4721\""), response.getHeaders().get("ETag"));

		MarshaledResponse secondResponse = StaticFiles.withRoot(tempDir)
				.entityTagResolver(StaticFiles.EntityTagResolver.fromContentHash())
				.build()
				.marshaledResponseFor("example.txt", Request.fromPath(HttpMethod.GET, "/example.txt"))
				.orElseThrow();
		Assertions.assertEquals(response.getHeaders().get("ETag"), secondResponse.getHeaders().get("ETag"));

		MarshaledResponse emptyResponse = staticFiles.marshaledResponseFor("empty.txt", Request.fromPath(HttpMethod.GET, "/empty.txt")).orElseThrow();
		Assertions.assertEquals(Set.of("\"sha256-e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\""), emptyResponse.getHeaders().get("ETag"));

		MarshaledResponse largeResponse = staticFiles.marshaledResponseFor("large.txt", Request.fromPath(HttpMethod.GET, "/large.txt")).orElseThrow();
		Assertions.assertEquals(Set.of("\"sha256-0598aa54768194ade580b9806ac98ace43a0310aeceae95762f62491625eee52\""), largeResponse.getHeaders().get("ETag"));

		Files.writeString(file, "abcdefg", StandardCharsets.UTF_8);
		MarshaledResponse changedResponse = staticFiles.marshaledResponseFor("example.txt", Request.fromPath(HttpMethod.GET, "/example.txt")).orElseThrow();
		Assertions.assertNotEquals(response.getHeaders().get("ETag"), changedResponse.getHeaders().get("ETag"));

		Request notModifiedRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of("If-None-Match", changedResponse.getHeaders().get("ETag")))
				.build();
		Assertions.assertEquals(304, staticFiles.marshaledResponseFor("example.txt", notModifiedRequest).orElseThrow().getStatusCode());

		Request failedPreconditionRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of("If-Match", Set.of("\"sha256-bef57ec7f53a6d40beb640a780a639c83bc29ac8a9816f1fc6c5c6dcd93c4721\"")))
				.build();
		Assertions.assertEquals(412, staticFiles.marshaledResponseFor("example.txt", failedPreconditionRequest).orElseThrow().getStatusCode());

		Request ifRangeRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of(
						"Range", Set.of("bytes=0-2"),
						"If-Range", changedResponse.getHeaders().get("ETag")
				))
				.build();
		Assertions.assertEquals(206, staticFiles.marshaledResponseFor("example.txt", ifRangeRequest).orElseThrow().getStatusCode());

		Request nonmatchingIfRangeRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of(
						"Range", Set.of("bytes=0-2"),
						"If-Range", Set.of("\"sha256-bef57ec7f53a6d40beb640a780a639c83bc29ac8a9816f1fc6c5c6dcd93c4721\"")
				))
				.build();
		MarshaledResponse nonmatchingIfRangeResponse = staticFiles.marshaledResponseFor("example.txt", nonmatchingIfRangeRequest).orElseThrow();
		Assertions.assertEquals(200, nonmatchingIfRangeResponse.getStatusCode());
		Assertions.assertFalse(nonmatchingIfRangeResponse.getHeaders().containsKey("Content-Range"));
	}

	@Test
	public void staticFilesContentHashResolverRunsOnceForGetAndHead(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("example.txt");
		Files.writeString(file, "abcdef", StandardCharsets.UTF_8);
		AtomicInteger invocations = new AtomicInteger();
		StaticFiles.EntityTagResolver contentHashResolver = StaticFiles.EntityTagResolver.fromContentHash();
		StaticFiles staticFiles = StaticFiles.withRoot(tempDir)
				.entityTagResolver((path, attributes) -> {
					invocations.incrementAndGet();
					return contentHashResolver.entityTagFor(path, attributes);
				})
				.build();

		staticFiles.marshaledResponseFor("example.txt", Request.fromPath(HttpMethod.GET, "/example.txt")).orElseThrow();
		Assertions.assertEquals(1, invocations.get());

		staticFiles.marshaledResponseFor("example.txt", Request.fromPath(HttpMethod.HEAD, "/example.txt")).orElseThrow();
		Assertions.assertEquals(2, invocations.get());
	}

	@Test
	public void staticFilesAccessResolverAllowsHidesAndDeniesFiles(@TempDir Path tempDir) throws IOException {
		Path publicFile = tempDir.resolve("public.txt");
		Path hiddenFile = tempDir.resolve(".env");
		Path deniedFile = tempDir.resolve("internal.json");
		Files.writeString(publicFile, "public", StandardCharsets.UTF_8);
		Files.writeString(hiddenFile, "secret", StandardCharsets.UTF_8);
		Files.writeString(deniedFile, "internal", StandardCharsets.UTF_8);
		AtomicReference<Path> resolvedPath = new AtomicReference<>();
		StaticFiles staticFiles = StaticFiles.withRoot(tempDir)
				.accessResolver((path, attributes) -> {
					resolvedPath.set(path);

					if (path.getFileName().toString().equals(".env"))
						return StaticFiles.Access.HIDE;

					if (path.getFileName().toString().equals("internal.json"))
						return StaticFiles.Access.DENY;

					return StaticFiles.Access.ALLOW;
				})
				.build();

		Assertions.assertEquals(200, staticFiles.marshaledResponseFor("public.txt", Request.fromPath(HttpMethod.GET, "/public.txt")).orElseThrow().getStatusCode());
		Assertions.assertTrue(Files.isSameFile(publicFile, resolvedPath.get()));
		Assertions.assertTrue(staticFiles.marshaledResponseFor(".env", Request.fromPath(HttpMethod.GET, "/.env")).isEmpty());
		MarshaledResponse deniedResponse = staticFiles.marshaledResponseFor("internal.json", Request.fromPath(HttpMethod.GET, "/internal.json")).orElseThrow();
		Assertions.assertEquals(403, deniedResponse.getStatusCode());
		Assertions.assertTrue(deniedResponse.getBody().isEmpty());

		StaticFiles defaultStaticFiles = StaticFiles.withRoot(tempDir)
				.accessResolver(null)
				.build();
		Assertions.assertTrue(defaultStaticFiles.marshaledResponseFor(".env", Request.fromPath(HttpMethod.GET, "/.env")).isPresent());
	}

	@Test
	public void staticFilesAccessResolverShortCircuitsContentResolvers(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("hidden.txt");
		Files.writeString(file, "hidden", StandardCharsets.UTF_8);

		for (StaticFiles.Access access : List.of(StaticFiles.Access.HIDE, StaticFiles.Access.DENY)) {
			AtomicInteger mimeInvocations = new AtomicInteger();
			AtomicInteger entityTagInvocations = new AtomicInteger();
			AtomicInteger lastModifiedInvocations = new AtomicInteger();
			AtomicInteger cacheControlInvocations = new AtomicInteger();
			AtomicInteger headersInvocations = new AtomicInteger();
			AtomicInteger rangeInvocations = new AtomicInteger();
			StaticFiles staticFiles = StaticFiles.withRoot(tempDir)
					.accessResolver((path, attributes) -> access)
					.mimeTypeResolver((path) -> {
						mimeInvocations.incrementAndGet();
						return Optional.of("text/plain");
					})
					.entityTagResolver((path, attributes) -> {
						entityTagInvocations.incrementAndGet();
						return Optional.of(EntityTag.fromStrongValue("v1"));
					})
					.lastModifiedResolver((path, attributes) -> {
						lastModifiedInvocations.incrementAndGet();
						return Optional.of(Instant.now());
					})
					.cacheControlResolver((path, attributes) -> {
						cacheControlInvocations.incrementAndGet();
						return Optional.of("no-cache");
					})
					.headersResolver((path, attributes) -> {
						headersInvocations.incrementAndGet();
						return Map.of("X-Test", Set.of("true"));
					})
					.rangeRequestsResolver((path, attributes) -> {
						rangeInvocations.incrementAndGet();
						return true;
					})
					.build();

			Optional<MarshaledResponse> response = staticFiles.marshaledResponseFor("hidden.txt", Request.fromPath(HttpMethod.GET, "/hidden.txt"));

			if (access == StaticFiles.Access.HIDE) {
				Assertions.assertTrue(response.isEmpty());
			} else {
				Assertions.assertEquals(403, response.orElseThrow().getStatusCode());
			}

			Assertions.assertEquals(0, mimeInvocations.get());
			Assertions.assertEquals(0, entityTagInvocations.get());
			Assertions.assertEquals(0, lastModifiedInvocations.get());
			Assertions.assertEquals(0, cacheControlInvocations.get());
			Assertions.assertEquals(0, headersInvocations.get());
			Assertions.assertEquals(0, rangeInvocations.get());
		}
	}

	@Test
	public void staticFilesAccessResolverIsNotInvokedForUnresolvedPaths(@TempDir Path tempDir) throws IOException {
		Path root = tempDir.resolve("root");
		Path outside = tempDir.resolve("outside");
		Files.createDirectories(root);
		Files.createDirectories(outside);
		Path secret = outside.resolve("secret.txt");
		Files.writeString(secret, "secret", StandardCharsets.UTF_8);
		AtomicInteger accessInvocations = new AtomicInteger();
		StaticFiles staticFiles = StaticFiles.withRoot(root)
				.followSymlinks(true)
				.accessResolver((path, attributes) -> {
					accessInvocations.incrementAndGet();
					throw new AssertionError("access resolver should not run for unresolved paths");
				})
				.build();

		Assertions.assertTrue(staticFiles.marshaledResponseFor("missing.txt", Request.fromPath(HttpMethod.GET, "/missing.txt")).isEmpty());
		Assertions.assertTrue(staticFiles.marshaledResponseFor("../outside/secret.txt", Request.fromPath(HttpMethod.GET, "/secret.txt")).isEmpty());

		Path link = root.resolve("link.txt");

		try {
			Files.createSymbolicLink(link, secret);
			Assertions.assertTrue(staticFiles.marshaledResponseFor("link.txt", Request.fromPath(HttpMethod.GET, "/link.txt")).isEmpty());
		} catch (UnsupportedOperationException | IOException e) {
			// Some platforms or filesystems disallow symlink creation.
		}

		Assertions.assertEquals(0, accessInvocations.get());
	}

	@Test
	public void staticFilesAccessResolverIsSafeUnderConcurrentRequests(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("example.txt");
		Files.writeString(file, "abcdef", StandardCharsets.UTF_8);
		AtomicInteger accessInvocations = new AtomicInteger();
		StaticFiles staticFiles = StaticFiles.withRoot(tempDir)
				.accessResolver((path, attributes) -> {
					accessInvocations.incrementAndGet();
					return StaticFiles.Access.ALLOW;
				})
				.build();
		ExecutorService executorService = Executors.newFixedThreadPool(8);
		CountDownLatch startLatch = new CountDownLatch(1);
		List<Future<?>> futures = new ArrayList<>();

		try {
			for (int i = 0; i < 50; i++)
				futures.add(executorService.submit(() -> {
					startLatch.await();
					Assertions.assertEquals(200, staticFiles.marshaledResponseFor("example.txt", Request.fromPath(HttpMethod.GET, "/example.txt")).orElseThrow().getStatusCode());
					return null;
				}));

			startLatch.countDown();

			for (Future<?> future : futures)
				future.get(10L, TimeUnit.SECONDS);
		} finally {
			executorService.shutdownNow();
		}

		Assertions.assertEquals(50, accessInvocations.get());
	}

	@Test
	public void staticFilesContentHashFailuresPropagate(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("example.txt");
		Files.writeString(file, "abcdef", StandardCharsets.UTF_8);
		StaticFiles.EntityTagResolver contentHashResolver = StaticFiles.EntityTagResolver.fromContentHash();
		StaticFiles staticFiles = StaticFiles.withRoot(tempDir)
				.entityTagResolver((path, attributes) -> {
					try {
						Files.delete(path);
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}

					return contentHashResolver.entityTagFor(path, attributes);
				})
				.build();

		UncheckedIOException exception = Assertions.assertThrows(UncheckedIOException.class, () ->
				staticFiles.marshaledResponseFor("example.txt", Request.fromPath(HttpMethod.GET, "/example.txt")));
		Assertions.assertTrue(exception.getMessage().contains("Unable to hash static file"));
	}

	@Test
	public void staticFilesAccessResolverDoesNotFallbackAfterHiddenIndex(@TempDir Path tempDir) throws IOException {
		Path indexHtml = tempDir.resolve("index.html");
		Path indexHtm = tempDir.resolve("index.htm");
		Files.writeString(indexHtml, "hidden", StandardCharsets.UTF_8);
		Files.writeString(indexHtm, "fallback", StandardCharsets.UTF_8);
		AtomicReference<Path> resolvedPath = new AtomicReference<>();
		StaticFiles staticFiles = StaticFiles.withRoot(tempDir)
				.indexFileNames(List.of("index.html", "index.htm"))
				.accessResolver((path, attributes) -> {
					resolvedPath.set(path);
					return path.getFileName().toString().equals("index.html")
							? StaticFiles.Access.HIDE
							: StaticFiles.Access.ALLOW;
				})
				.build();

		Assertions.assertTrue(staticFiles.marshaledResponseFor("", Request.fromPath(HttpMethod.GET, "/")).isEmpty());
		Assertions.assertTrue(Files.isSameFile(indexHtml, resolvedPath.get()));
	}

	@Test
	public void staticFilesAccessResolverRejectsNullAndPropagatesExceptionsBeforeHeaders(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("example.txt");
		Files.writeString(file, "abcdef", StandardCharsets.UTF_8);

		StaticFiles nullReturningAccessResolver = StaticFiles.withRoot(tempDir)
				.accessResolver((path, attributes) -> null)
				.build();
		NullPointerException nullPointerException = Assertions.assertThrows(NullPointerException.class, () ->
				nullReturningAccessResolver.marshaledResponseFor("example.txt", Request.fromPath(HttpMethod.GET, "/example.txt")));
		Assertions.assertTrue(nullPointerException.getMessage().contains("accessResolver returned null"));

		AtomicInteger headerInvocations = new AtomicInteger();
		StaticFiles throwingAccessResolver = StaticFiles.withRoot(tempDir)
				.accessResolver((path, attributes) -> {
					throw new IllegalStateException("boom");
				})
				.headersResolver((path, attributes) -> {
					headerInvocations.incrementAndGet();
					return Map.of("X-Test", Set.of("true"));
				})
				.build();
		IllegalStateException exception = Assertions.assertThrows(IllegalStateException.class, () ->
				throwingAccessResolver.marshaledResponseFor("example.txt", Request.fromPath(HttpMethod.GET, "/example.txt")));
		Assertions.assertEquals("boom", exception.getMessage());
		Assertions.assertEquals(0, headerInvocations.get());
	}

	@Test
	public void staticFilesRejectsResolverNullsAndHeaderConflicts(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("example.txt");
		Files.writeString(file, "abcdef", StandardCharsets.UTF_8);

		StaticFiles nullReturningResolver = StaticFiles.withRoot(tempDir)
				.cacheControlResolver((path, attributes) -> null)
				.build();
		NullPointerException nullPointerException = Assertions.assertThrows(NullPointerException.class, () ->
				nullReturningResolver.marshaledResponseFor("example.txt", Request.fromPath(HttpMethod.GET, "/example.txt")));
		Assertions.assertTrue(nullPointerException.getMessage().contains("cacheControlResolver returned null"));

		StaticFiles conflictingHeaderResolver = StaticFiles.withRoot(tempDir)
				.headersResolver(StaticFiles.HeadersResolver.fromHeaders(Map.of("Content-Encoding", Set.of("gzip"))))
				.build();
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				conflictingHeaderResolver.marshaledResponseFor("example.txt", Request.fromPath(HttpMethod.GET, "/example.txt")));

		Assertions.assertThrows(IllegalArgumentException.class, () ->
				StaticFiles.CacheControlResolver.fromValue(" "));

		LinkedHashSet<String> headerValues = new LinkedHashSet<>();
		headerValues.add(null);
		Assertions.assertThrows(NullPointerException.class, () ->
				StaticFiles.HeadersResolver.fromHeaders(Map.of("X-Test", headerValues)));
	}

	@Test
	public void staticFilesRejectsSymlinksByDefault(@TempDir Path tempDir) throws IOException {
		Path target = tempDir.resolve("target.txt");
		Path link = tempDir.resolve("link.txt");
		Files.writeString(target, "abcdef", StandardCharsets.UTF_8);

		try {
			Files.createSymbolicLink(link, target.getFileName());
		} catch (UnsupportedOperationException | IOException e) {
			return;
		}

		StaticFiles noFollowStaticFiles = StaticFiles.withRoot(tempDir).build();
		Assertions.assertTrue(noFollowStaticFiles.marshaledResponseFor("link.txt", Request.fromPath(HttpMethod.GET, "/link.txt")).isEmpty());

		StaticFiles followStaticFiles = StaticFiles.withRoot(tempDir)
				.followSymlinks(true)
				.build();
		Assertions.assertTrue(followStaticFiles.marshaledResponseFor("link.txt", Request.fromPath(HttpMethod.GET, "/link.txt")).isPresent());
	}

	@Test
	public void staticFilesRejectsSymlinkChainsThatEscapeRoot(@TempDir Path tempDir) throws IOException {
		Path root = tempDir.resolve("root");
		Path outside = tempDir.resolve("outside");
		Files.createDirectories(root);
		Files.createDirectories(outside);
		Path secret = outside.resolve("secret.txt");
		Path link2 = tempDir.resolve("link2.txt");
		Path link1 = root.resolve("link1.txt");
		Files.writeString(secret, "secret", StandardCharsets.UTF_8);

		try {
			Files.createSymbolicLink(link2, secret);
			Files.createSymbolicLink(link1, link2);
		} catch (UnsupportedOperationException | IOException e) {
			return;
		}

		StaticFiles staticFiles = StaticFiles.withRoot(root)
				.followSymlinks(true)
				.build();
		Assertions.assertTrue(staticFiles.marshaledResponseFor("link1.txt", Request.fromPath(HttpMethod.GET, "/link1.txt")).isEmpty());
	}

	@Test
	public void marshaledFileResponseBoundsEntityTagConditionLists(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("example.txt");
		Files.writeString(file, "abcdef", StandardCharsets.UTF_8);
		StringBuilder acceptedHeader = new StringBuilder("\"v1\"");

		for (int i = 1; i < 256; i++)
			acceptedHeader.append(",\"v").append(i).append("\"");

		Request acceptedRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of("If-None-Match", Set.of(acceptedHeader.toString())))
				.build();
		Assertions.assertEquals(304, MarshaledResponse.withFile(file, acceptedRequest)
				.entityTag(EntityTag.fromStrongValue("v1"))
				.build()
				.getStatusCode());

		StringBuilder rejectedHeader = new StringBuilder(acceptedHeader);
		rejectedHeader.append(",\"v256\"");
		Request rejectedRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of("If-None-Match", Set.of(rejectedHeader.toString())))
				.build();
		Assertions.assertEquals(200, MarshaledResponse.withFile(file, rejectedRequest)
				.entityTag(EntityTag.fromStrongValue("v1"))
				.build()
				.getStatusCode());
	}

	@Test
	public void staticFilesHeadersReachSimulatorEndToEnd(@TempDir Path tempDir) throws IOException {
		Path appJs = tempDir.resolve("app.js");
		Files.writeString(appJs, "console.log('hi');", StandardCharsets.UTF_8);
		String appJsLength = String.valueOf(Files.size(appJs));
		StaticFiles staticFiles = StaticFiles.withRoot(tempDir)
				.cacheControlResolver((path, attributes) -> Optional.of(path.getFileName().toString().endsWith(".js")
						? "public, max-age=31536000, immutable"
						: "no-cache"))
				.build();
		StaticAssetResource resource = new StaticAssetResource(staticFiles);
		SokletConfig config = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(StaticAssetResource.class)))
				.instanceProvider(new InstanceProvider() {
					@Override
					@NonNull
					public <T> T provide(@NonNull Class<T> instanceClass) {
						if (instanceClass == StaticAssetResource.class)
							return instanceClass.cast(resource);

						return InstanceProvider.defaultInstance().provide(instanceClass);
					}
				})
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
						// Quiet
					}
				})
				.build();

		Soklet.runSimulator(config, simulator -> {
			HttpRequestResult result = simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/assets/app.js"));
			Assertions.assertEquals(200, result.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals(Set.of("public, max-age=31536000, immutable"), result.getMarshaledResponse().getHeaders().get("Cache-Control"));
			Assertions.assertEquals(Set.of("bytes"), result.getMarshaledResponse().getHeaders().get("Accept-Ranges"));

			HttpRequestResult headResult = simulator.performHttpRequest(Request.fromPath(HttpMethod.HEAD, "/assets/app.js"));
			Assertions.assertEquals(200, headResult.getMarshaledResponse().getStatusCode());
			Assertions.assertTrue(headResult.getMarshaledResponse().getBody().isEmpty());
			Assertions.assertEquals(Set.of(appJsLength), headResult.getMarshaledResponse().getHeaders().get("Content-Length"));
			Assertions.assertEquals(Set.of("bytes"), headResult.getMarshaledResponse().getHeaders().get("Accept-Ranges"));
		});
	}

	@Test
	public void simulatorStillEmitsDateHeaderAfterHttpDateMigration() {
		SokletConfig config = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(DateResource.class)))
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
						// Quiet
					}
				})
				.build();

		Soklet.runSimulator(config, simulator -> {
			HttpRequestResult result = simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/date"));
			String date = result.getMarshaledResponse().getHeaders().get("Date").stream().findFirst().orElse(null);
			Assertions.assertNotNull(date);
			Assertions.assertTrue(HttpDate.fromHeaderValue(date).isPresent());
		});
	}

	public static class DateResource {
		@GET("/date")
		public String date() {
			return "ok";
		}
	}

	private static MarshaledResponse.@NonNull FileBuilder fileBuilder(@NonNull Path file,
																																		@NonNull Request request) {
		return MarshaledResponse.withFile(file, request)
				.entityTag(EntityTag.fromStrongValue("v1"))
				.lastModified(Instant.parse("2026-05-04T01:02:03Z"));
	}

	public static class StaticAssetResource {
		@NonNull
		private final StaticFiles staticFiles;

		public StaticAssetResource(@NonNull StaticFiles staticFiles) {
			this.staticFiles = staticFiles;
		}

		@NonNull
		@GET("/assets/{assetPath*}")
		public MarshaledResponse asset(@NonNull Request request,
																		@PathParameter @NonNull String assetPath) {
			return this.staticFiles.marshaledResponseFor(assetPath, request).orElseGet(() -> MarshaledResponse.fromStatusCode(404));
		}
	}
}
