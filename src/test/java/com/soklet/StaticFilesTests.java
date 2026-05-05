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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
	public void fileResponseServesFullAndPartialFiles(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("example.txt");
		Files.writeString(file, "abcdef", StandardCharsets.UTF_8);
		Instant lastModified = Instant.parse("2026-05-04T01:02:03.999Z");
		EntityTag entityTag = EntityTag.fromStrongValue("v1");
		FileResponse fileResponse = FileResponse.withPath(file)
				.contentType("text/plain; charset=UTF-8")
				.entityTag(entityTag)
				.lastModified(lastModified)
				.cacheControl("public, max-age=60")
				.build();

		MarshaledResponse fullResponse = fileResponse.marshaledResponseFor(Request.fromPath(HttpMethod.GET, "/example.txt"));
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
		MarshaledResponse rangeResponse = fileResponse.marshaledResponseFor(rangeRequest);
		Assertions.assertEquals(206, rangeResponse.getStatusCode());
		Assertions.assertEquals(Set.of("bytes 2-4/6"), rangeResponse.getHeaders().get("Content-Range"));
		MarshaledResponseBody.File rangeBody = (MarshaledResponseBody.File) rangeResponse.getBody().orElseThrow();
		Assertions.assertEquals(Long.valueOf(2), rangeBody.getOffset());
		Assertions.assertEquals(Long.valueOf(3), rangeBody.getCount());
	}

	@Test
	public void fileResponseAppliesConditionalsAndHeadRangeSemantics(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("example.txt");
		Files.writeString(file, "abcdef", StandardCharsets.UTF_8);
		FileResponse fileResponse = FileResponse.withPath(file)
				.entityTag(EntityTag.fromStrongValue("v1"))
				.lastModified(Instant.parse("2026-05-04T01:02:03Z"))
				.build();

		Request notModifiedRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of("If-None-Match", Set.of("W/\"v1\"")))
				.build();
		MarshaledResponse notModifiedResponse = fileResponse.marshaledResponseFor(notModifiedRequest);
		Assertions.assertEquals(304, notModifiedResponse.getStatusCode());
		Assertions.assertTrue(notModifiedResponse.getBody().isEmpty());
		Assertions.assertFalse(notModifiedResponse.getHeaders().containsKey("Content-Type"));

		Request ifMatchWildcardRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of("If-Match", Set.of("*")))
				.build();
		Assertions.assertEquals(200, fileResponse.marshaledResponseFor(ifMatchWildcardRequest).getStatusCode());

		Request ifNoneMatchWildcardRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of("If-None-Match", Set.of("*")))
				.build();
		Assertions.assertEquals(304, fileResponse.marshaledResponseFor(ifNoneMatchWildcardRequest).getStatusCode());

		Request modifiedSinceFutureRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of("If-Modified-Since", Set.of("Mon, 04 May 2026 01:02:04 GMT")))
				.build();
		Assertions.assertEquals(304, fileResponse.marshaledResponseFor(modifiedSinceFutureRequest).getStatusCode());

		Request modifiedSincePastRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of("If-Modified-Since", Set.of("Mon, 04 May 2026 01:02:02 GMT")))
				.build();
		Assertions.assertEquals(200, fileResponse.marshaledResponseFor(modifiedSincePastRequest).getStatusCode());

		Request failedPreconditionRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of("If-Match", Set.of("\"other\"")))
				.build();
		MarshaledResponse failedPreconditionResponse = fileResponse.marshaledResponseFor(failedPreconditionRequest);
		Assertions.assertEquals(412, failedPreconditionResponse.getStatusCode());
		Assertions.assertTrue(failedPreconditionResponse.getBody().isEmpty());

		Request failedHeadPreconditionRequest = Request.withPath(HttpMethod.HEAD, "/example.txt")
				.headers(Map.of("If-Match", Set.of("\"other\"")))
				.build();
		MarshaledResponse failedHeadPreconditionResponse = fileResponse.marshaledResponseFor(failedHeadPreconditionRequest);
		Assertions.assertEquals(412, failedHeadPreconditionResponse.getStatusCode());
		Assertions.assertTrue(failedHeadPreconditionResponse.getBody().isEmpty());
		MarshaledResponse marshaledFailedHeadPreconditionResponse = DefaultResponseMarshaler.defaultInstance().forHead(failedHeadPreconditionRequest, failedHeadPreconditionResponse);
		Assertions.assertTrue(marshaledFailedHeadPreconditionResponse.getBody().isEmpty());
		Assertions.assertEquals(Set.of("0"), marshaledFailedHeadPreconditionResponse.getHeaders().get("Content-Length"));

		Request headRequest = Request.fromPath(HttpMethod.HEAD, "/example.txt");
		MarshaledResponse headGetEquivalent = fileResponse.marshaledResponseFor(headRequest);
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
		MarshaledResponse headGetEquivalentResponse = fileResponse.marshaledResponseFor(headRangeRequest);
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
		MarshaledResponse unsatisfiableRangeResponse = fileResponse.marshaledResponseFor(unsatisfiableRangeRequest);
		Assertions.assertEquals(416, unsatisfiableRangeResponse.getStatusCode());
		Assertions.assertTrue(unsatisfiableRangeResponse.getBody().isEmpty());
	}

	@Test
	public void fileResponseAppliesIfRangeOnlyToGetRequests(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("example.txt");
		Files.writeString(file, "abcdef", StandardCharsets.UTF_8);
		FileResponse fileResponse = FileResponse.withPath(file)
				.entityTag(EntityTag.fromStrongValue("v1"))
				.lastModified(Instant.parse("2026-05-04T01:02:03Z"))
				.build();

		Request matchingStrongEntityTagRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of(
						"Range", Set.of("bytes=2-4"),
						"If-Range", Set.of("\"v1\"")
				))
				.build();
		Assertions.assertEquals(206, fileResponse.marshaledResponseFor(matchingStrongEntityTagRequest).getStatusCode());

		Request matchingWeakEntityTagRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of(
						"Range", Set.of("bytes=2-4"),
						"If-Range", Set.of("W/\"v1\"")
				))
				.build();
		MarshaledResponse weakEntityTagResponse = fileResponse.marshaledResponseFor(matchingWeakEntityTagRequest);
		Assertions.assertEquals(200, weakEntityTagResponse.getStatusCode());
		Assertions.assertFalse(weakEntityTagResponse.getHeaders().containsKey("Content-Range"));

		Request matchingDateRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of(
						"Range", Set.of("bytes=2-4"),
						"If-Range", Set.of("Mon, 04 May 2026 01:02:03 GMT")
				))
				.build();
		Assertions.assertEquals(206, fileResponse.marshaledResponseFor(matchingDateRequest).getStatusCode());

		Request nonmatchingDateRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of(
						"Range", Set.of("bytes=2-4"),
						"If-Range", Set.of("Mon, 04 May 2026 01:02:02 GMT")
				))
				.build();
		MarshaledResponse nonmatchingDateResponse = fileResponse.marshaledResponseFor(nonmatchingDateRequest);
		Assertions.assertEquals(200, nonmatchingDateResponse.getStatusCode());
		Assertions.assertFalse(nonmatchingDateResponse.getHeaders().containsKey("Content-Range"));

		FileResponse noValidatorFileResponse = FileResponse.withPath(file).build();
		Request ifRangeWithoutResponseValidatorRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of(
						"Range", Set.of("bytes=2-4"),
						"If-Range", Set.of("\"v1\"")
				))
				.build();
		Assertions.assertEquals(200, noValidatorFileResponse.marshaledResponseFor(ifRangeWithoutResponseValidatorRequest).getStatusCode());
	}

	@Test
	public void fileResponseCombinesRepeatedConditionalAndRangeHeaders(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("example.txt");
		Files.writeString(file, "abcdef", StandardCharsets.UTF_8);
		FileResponse fileResponse = FileResponse.withPath(file)
				.entityTag(EntityTag.fromStrongValue("v1"))
				.build();

		Request repeatedIfNoneMatchRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of("If-None-Match", Set.of("\"other\"", "\"v1\"")))
				.build();
		Assertions.assertEquals(304, fileResponse.marshaledResponseFor(repeatedIfNoneMatchRequest).getStatusCode());

		Request repeatedRangeRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of("Range", Set.of("bytes=0-1", "bytes=2-3")))
				.build();
		MarshaledResponse repeatedRangeResponse = fileResponse.marshaledResponseFor(repeatedRangeRequest);
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

		Optional<MarshaledResponse> appResponse = staticFiles.marshaledResponseFor(Request.fromPath(HttpMethod.GET, "/assets/app.js"), "app.js");
		Assertions.assertTrue(appResponse.isPresent());
		Assertions.assertEquals(200, appResponse.get().getStatusCode());
		Assertions.assertTrue(appResponse.get().getHeaders().containsKey("ETag"));
		Assertions.assertTrue(appResponse.get().getHeaders().containsKey("Last-Modified"));

		Optional<MarshaledResponse> indexResponse = staticFiles.marshaledResponseFor(Request.fromPath(HttpMethod.GET, "/assets/"), "");
		Assertions.assertTrue(indexResponse.isPresent());
		Assertions.assertEquals(200, indexResponse.get().getStatusCode());

		Assertions.assertTrue(staticFiles.marshaledResponseFor(Request.fromPath(HttpMethod.POST, "/assets/app.js"), "app.js").isEmpty());
		Assertions.assertTrue(staticFiles.marshaledResponseFor(Request.fromPath(HttpMethod.GET, "/assets/missing.js"), "missing.js").isEmpty());
		Assertions.assertTrue(staticFiles.marshaledResponseFor(Request.fromPath(HttpMethod.GET, "/assets/secret"), "../secret").isEmpty());
		Assertions.assertTrue(staticFiles.marshaledResponseFor(Request.fromPath(HttpMethod.GET, "/assets/secret"), tempDir.resolve("app.js").toString()).isEmpty());
		Assertions.assertTrue(staticFiles.marshaledResponseFor(Request.fromPath(HttpMethod.GET, "/assets/app.js"), "nested\\app.js").isEmpty());

		Path wellKnown = tempDir.resolve(".well-known");
		Files.createDirectories(wellKnown);
		Files.writeString(wellKnown.resolve("security.txt"), "contact: mailto:security@example.com", StandardCharsets.UTF_8);
		Assertions.assertTrue(staticFiles.marshaledResponseFor(Request.fromPath(HttpMethod.GET, "/assets/.well-known/security.txt"), ".well-known/security.txt").isPresent());
		Assertions.assertTrue(staticFiles.marshaledResponseFor(Request.fromPath(HttpMethod.GET, "/assets/long"), "a".repeat(4097)).isEmpty());
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

		MarshaledResponse indexResponse = staticFiles.marshaledResponseFor(Request.fromPath(HttpMethod.GET, "/assets/"), "").orElseThrow();
		Assertions.assertTrue(Files.isSameFile(indexHtml, resolvedPath.get()));
		Assertions.assertEquals(Set.of("no-cache"), indexResponse.getHeaders().get("Cache-Control"));
		Assertions.assertFalse(indexResponse.getHeaders().containsKey("Accept-Ranges"));
		Assertions.assertEquals(Set.of("index.html"), indexResponse.getHeaders().get("X-Static"));

		MarshaledResponse appResponse = staticFiles.marshaledResponseFor(Request.fromPath(HttpMethod.GET, "/assets/app.js"), "app.js").orElseThrow();
		Assertions.assertEquals(Set.of("public, max-age=31536000, immutable"), appResponse.getHeaders().get("Cache-Control"));
		Assertions.assertEquals(Set.of("bytes"), appResponse.getHeaders().get("Accept-Ranges"));
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

		MarshaledResponse response = staticFiles.marshaledResponseFor(Request.fromPath(HttpMethod.GET, "/example.txt"), "example.txt").orElseThrow();
		Assertions.assertEquals(Long.valueOf(6), response.getBodyLength());
	}

	@Test
	public void staticFilesRejectsResolverNullsAndHeaderConflicts(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("example.txt");
		Files.writeString(file, "abcdef", StandardCharsets.UTF_8);

		StaticFiles nullReturningResolver = StaticFiles.withRoot(tempDir)
				.cacheControlResolver((path, attributes) -> null)
				.build();
		NullPointerException nullPointerException = Assertions.assertThrows(NullPointerException.class, () ->
				nullReturningResolver.marshaledResponseFor(Request.fromPath(HttpMethod.GET, "/example.txt"), "example.txt"));
		Assertions.assertTrue(nullPointerException.getMessage().contains("cacheControlResolver returned null"));

		StaticFiles conflictingHeaderResolver = StaticFiles.withRoot(tempDir)
				.headersResolver(StaticFiles.HeadersResolver.fromHeaders(Map.of("Content-Encoding", Set.of("gzip"))))
				.build();
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				conflictingHeaderResolver.marshaledResponseFor(Request.fromPath(HttpMethod.GET, "/example.txt"), "example.txt"));

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
		Assertions.assertTrue(noFollowStaticFiles.marshaledResponseFor(Request.fromPath(HttpMethod.GET, "/link.txt"), "link.txt").isEmpty());

		StaticFiles followStaticFiles = StaticFiles.withRoot(tempDir)
				.followSymlinks(true)
				.build();
		Assertions.assertTrue(followStaticFiles.marshaledResponseFor(Request.fromPath(HttpMethod.GET, "/link.txt"), "link.txt").isPresent());
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
		Assertions.assertTrue(staticFiles.marshaledResponseFor(Request.fromPath(HttpMethod.GET, "/link1.txt"), "link1.txt").isEmpty());
	}

	@Test
	public void fileResponseBoundsEntityTagConditionLists(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("example.txt");
		Files.writeString(file, "abcdef", StandardCharsets.UTF_8);
		FileResponse fileResponse = FileResponse.withPath(file)
				.entityTag(EntityTag.fromStrongValue("v1"))
				.build();
		StringBuilder acceptedHeader = new StringBuilder("\"v1\"");

		for (int i = 1; i < 256; i++)
			acceptedHeader.append(",\"v").append(i).append("\"");

		Request acceptedRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of("If-None-Match", Set.of(acceptedHeader.toString())))
				.build();
		Assertions.assertEquals(304, fileResponse.marshaledResponseFor(acceptedRequest).getStatusCode());

		StringBuilder rejectedHeader = new StringBuilder(acceptedHeader);
		rejectedHeader.append(",\"v256\"");
		Request rejectedRequest = Request.withPath(HttpMethod.GET, "/example.txt")
				.headers(Map.of("If-None-Match", Set.of(rejectedHeader.toString())))
				.build();
		Assertions.assertEquals(200, fileResponse.marshaledResponseFor(rejectedRequest).getStatusCode());
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
			return this.staticFiles.marshaledResponseFor(request, assetPath).orElseGet(() -> MarshaledResponse.fromStatusCode(404));
		}
	}
}
