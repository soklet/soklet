package com.soklet;

import com.soklet.exception.IllegalRequestBodyException;
import com.soklet.exception.IllegalRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class FinalDeepDiveParsingRegressionTests {
	@Test
	void malformedCookieQuotesCannotHideLaterSessionCookies() {
		for (String value : List.of("bad=oops\"; session=valid; pref=ok", "bad=\"oops; session=valid",
				"bad=\"oops; session=valid; pref=\"ok\"", "bad=oops\\; session=valid")) {
			Request request = Request.withPath(HttpMethod.GET, "/").headers(Map.of("Cookie", List.of(value))).build();
			assertEquals(Optional.of("valid"), request.getCookie("session"), value);
			assertEquals(List.of("valid"), request.getCookies().get("session"), value);
		}
		Request quoted = Request.withPath(HttpMethod.GET, "/")
				.headers(Map.of("Cookie", List.of("pref=\"a;b\\\"c\"; session=valid"))).build();
		assertEquals(Optional.of("a;b\"c"), quoted.getCookie("pref"));
		assertEquals(Optional.of("valid"), quoted.getCookie("session"));
	}

	@Test
	void charsetAliasesRetainInstalledSupportWithoutResolvingUnknownClientNames() {
		for (String name : List.of("UTF8", "iso-8859-1", "windows-1252", "ISO-2022-CN")) {
			if (Charset.isSupported(name))
				assertEquals(Optional.of(Charset.forName(name)), Utilities.extractCharsetFromHeaderValue("text/plain; charset=\"" + name + "\""));
		}
		for (String name : List.of("missing-charset", "bad charset", "UTF-8/invalid"))
			assertTrue(Utilities.extractCharsetFromHeaderValue("text/plain; charset=\"" + name + "\"").isEmpty());
	}

	@Test
	void multipartInteriorQuotesDoNotSwallowLaterParameters() {
		MultipartField field = upload("form-data; name=\"a\\\"b\"; filename=\"old\\\"name.txt\"; filename*=UTF-8''caf%C3%A9.txt")
				.getMultipartFields().get("a\\\"b").getFirst();
		assertEquals(Optional.of("café.txt"), field.getFilename());
		// A browser's literal trailing slash remains a terminating value.
		assertEquals(Optional.of("tail\\"), upload("form-data; name=\"a\"; filename=\"tail\\\"")
				.getMultipartFields().get("a").getFirst().getFilename());
	}

	@Test
	void malformedEncodedWordsRemainLiteralWithoutExceptionDrivenFallback() {
		for (String word : List.of("=?", "=?utf-8?B?=", "=?utf-8?B?A?=", "=?utf-8?B?=AAA?=",
				"=?utf-8?Q?=GG?=", "=?utf-8?Q?=ＦＦ?=", "=?missing?Q?text?=", "=?utf-8?X?text?=")) {
			assertEquals(Optional.of(word), upload("form-data; name=\"a\"; filename=\"" + word + "\"")
					.getMultipartFields().get("a").getFirst().getFilename());
		}
		assertEquals(Optional.of("café.txt"), upload("form-data; name=\"a\"; filename=\"=?UTF-8?B?Y2Fmw6kudHh0?=\"")
				.getMultipartFields().get("a").getFirst().getFilename());
	}

	@Test
	void multipartMetadataHasFiniteParameterAndEncodedWordLimits() {
		assertThrows(IllegalRequestBodyException.class, () -> upload("form-data; name=\"a\"" + "; x=1".repeat(128)).getMultipartFields());
		assertThrows(IllegalRequestBodyException.class, () -> upload("form-data; name=\"a\"; filename=\"" + "=?utf-8?Q?a?= ".repeat(129) + "\"").getMultipartFields());
		assertEquals(1, upload("form-data; name=\"a\"" + "; x=1".repeat(126)).getMultipartFields().size());
	}

	@Test
	void preflightHeaderNamesAreBoundedAndPreserveOrderWithHashCollisions() {
		List<String> names = new ArrayList<>();
		for (int i = 0; i < 129; i++) {
			StringBuilder name = new StringBuilder();
			for (int bit = 0; bit < 8; bit++) name.append((i & (1 << bit)) == 0 ? "Aa" : "BB");
			names.add(name.toString());
		}
		Map<String,List<String>> headers = Map.of("Origin", List.of("https://example.test"),
				"Access-Control-Request-Method", List.of("GET"),
				"Access-Control-Request-Headers", List.of(String.join(",", names.subList(0,128))));
		CorsPreflight accepted = CorsPreflight.fromHeaders(headers).orElseThrow();
		assertEquals(names.subList(0,128), new ArrayList<>(accepted.getAccessControlRequestHeaders()));
		assertThrows(UnsupportedOperationException.class, () -> accepted.getAccessControlRequestHeaders().clear());
		assertThrows(IllegalRequestException.class, () -> CorsPreflight.fromHeaders(Map.of("Origin", List.of("https://example.test"),
				"Access-Control-Request-Method", List.of("GET"), "Access-Control-Request-Headers", List.of(String.join(",",names)))));
	}

	@Test
	void staticFileTraversalBeyondAFileIsNotFoundAfterLongSymlinkExpansion(@TempDir Path root) throws Exception {
		Files.writeString(root.resolve("asset.txt"), "data");
		Files.createSymbolicLink(root.resolve("self"), Path.of("."));
		StaticFiles files = StaticFiles.withRoot(root).trustedImmutableRoot(true).followSymlinks(true).build();
		for (int count : List.of(1, 40, 41, 64)) {
			String path = "self/".repeat(count) + "asset.txt/x";
			assertTrue(files.marshaledResponseFor(path, Request.withPath(HttpMethod.GET, "/assets/" + path).build()).isEmpty());
		}
	}

	@Test
	void parsedHttpDatesAlwaysRemainSerializable() {
		for (String invalid : List.of("Sat, 01 Jan 0000 00:00:00 GMT", "Sat Jan  1 00:00:00 0000",
				"Sat, 01 Jan 10000 00:00:00 GMT"))
			assertTrue(HttpDate.fromHeaderValue(invalid).isEmpty(), invalid);
		for (Instant instant : List.of(Instant.parse("0001-01-01T00:00:00Z"), Instant.parse("2026-10-30T12:00:00Z"),
				Instant.parse("9999-12-31T23:59:59Z"))) {
			String header = HttpDate.toHeaderValue(instant);
			assertEquals(Optional.of(instant), HttpDate.fromHeaderValue(header));
		}
	}

	private static Request upload(String disposition) {
		return Request.withPath(HttpMethod.POST,"/upload")
				.headers(Map.of("Content-Type", List.of("multipart/form-data; boundary=review")))
				.body(("--review\r\nContent-Disposition: " + disposition + "\r\n\r\nx\r\n--review--\r\n").getBytes(StandardCharsets.UTF_8)).build();
	}
}
