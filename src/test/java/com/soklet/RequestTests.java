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

import com.soklet.exception.IllegalQueryParameterException;
import com.soklet.exception.IllegalRequestException;
import com.soklet.internal.microhttp.Header;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class RequestTests {
	@Test
	public void queryDecodingUsesUtf8RegardlessOfContentTypeCharset() {
		Map<String, Set<String>> headers = Map.of("Content-Type", Set.of("text/plain; charset=ISO-8859-1"));

		Request request = Request.withRawUrl(HttpMethod.GET, "/q?q=%C3%A9")
				.headers(headers)
				.build();

		Assertions.assertEquals(Set.of("\u00E9"), request.getQueryParameters().get("q"));
	}

	@Test
	public void rawUrlQueryParametersRemainDecodedAndImmutableWhenAccessed() {
		Request request = Request.withRawUrl(HttpMethod.GET, "/widgets?include=inventory&tenant=tenant-a&empty=")
				.build();

		Assertions.assertEquals("include=inventory&tenant=tenant-a&empty=", request.getRawQuery().orElse(null));
		Assertions.assertEquals("inventory", request.getQueryParameter("include").orElse(null));
		Assertions.assertEquals(Set.of("tenant-a"), request.getQueryParameters().get("tenant"));
		Assertions.assertEquals(Set.of(""), request.getQueryParameters().get("empty"));
		Assertions.assertThrows(UnsupportedOperationException.class, () -> request.getQueryParameters().put("x", Set.of("y")));
		Assertions.assertThrows(UnsupportedOperationException.class, () -> request.getQueryParameters().get("tenant").add("tenant-b"));
	}

	@Test
	public void rawUrlSingleQueryParameterAccessPreservesDuplicateSemantics() {
		Request repeatedDifferentValueRequest = Request.withRawUrl(HttpMethod.GET, "/widgets?include=inventory&include=pricing")
				.build();
		Request repeatedSameValueRequest = Request.withRawUrl(HttpMethod.GET, "/widgets?include=inventory&include=inventory")
				.build();

		Assertions.assertThrows(IllegalQueryParameterException.class, () -> repeatedDifferentValueRequest.getQueryParameter("include"));
		Assertions.assertEquals("inventory", repeatedSameValueRequest.getQueryParameter("include").orElse(null));
	}

	@Test
	public void malformedRawUrlQueryPercentEncodingStillFailsAtConstruction() {
		Assertions.assertThrows(IllegalRequestException.class, () ->
				Request.withRawUrl(HttpMethod.GET, "/widgets?include=%ZZ").build());
	}

	@Test
	public void malformedRawUrlQuerySyntaxStillFailsAtConstruction() {
		Assertions.assertThrows(IllegalRequestException.class, () ->
				Request.withRawUrl(HttpMethod.GET, "/widgets?include=hello world").build());
	}

	@Test
	public void remoteAddressIsPreservedOnBuild() {
		InetSocketAddress address = new InetSocketAddress("127.0.0.1", 1234);

		Request request = Request.withPath(HttpMethod.GET, "/")
				.remoteAddress(address)
				.build();

		Assertions.assertEquals(address, request.getRemoteAddress().orElse(null));
	}

	@Test
	public void publicBuilderHeadersAreCopiedAndCaseInsensitive() {
		Map<String, Set<String>> headers = new LinkedHashMap<>();
		headers.put("X-Trace-Id", new LinkedHashSet<>(List.of("abc123")));

		Request request = Request.withPath(HttpMethod.GET, "/")
				.headers(headers)
				.build();
		headers.clear();

		Assertions.assertEquals(Set.of("abc123"), request.getHeaders().get("x-trace-id"));
		Assertions.assertEquals("abc123", request.getHeader("X-TRACE-ID").orElse(null));
		Assertions.assertThrows(UnsupportedOperationException.class, () -> request.getHeaders().put("X-Test", Set.of("value")));
	}

	@Test
	public void microhttpHeadersPreserveRequestSemanticsWithoutEagerMapMaterialization() {
		List<Header> headers = List.of(
				new Header("Content-Type", "text/plain; charset=UTF-8"),
				new Header("Origin", "https://example.com"),
				new Header("Cache-Control", "no-cache, no-store"),
				new Header("X-Empty", "   "),
				new Header("X-Trace-Id", "abc123"));

		Request request = Request.withRawUrl(HttpMethod.GET, "/")
				.microhttpHeaders(headers)
				.build();

		Assertions.assertEquals(Set.of("abc123"), request.getHeaders().get("x-trace-id"));
		Assertions.assertEquals("abc123", request.getHeader("X-TRACE-ID").orElse(null));
		Assertions.assertEquals("text/plain", request.getContentType().orElse(null));
		Assertions.assertEquals(StandardCharsets.UTF_8, request.getCharset().orElse(null));
		Assertions.assertTrue(request.getCors().isPresent());
		Assertions.assertEquals(new LinkedHashSet<>(List.of("no-cache", "no-store")), request.getHeaders().get("Cache-Control"));
		Assertions.assertFalse(request.getHeaders().containsKey("X-Empty"));
		Assertions.assertThrows(UnsupportedOperationException.class, () -> request.getHeaders().put("X-Test", Set.of("value")));
		Assertions.assertThrows(UnsupportedOperationException.class, () -> request.getHeaders().get("X-Trace-Id").add("def456"));
	}

	@Test
	public void rawPathPreservesDotSegmentsAndTrailingSlash() {
		Request request = Request.withRawUrl(HttpMethod.GET, "/a/../b/").build();

		Assertions.assertEquals("/a/../b/", request.getRawPath());
		Assertions.assertEquals("/b", request.getPath());
	}

	@Test
	public void copyUpdatesRawQueryWhenQueryParametersChange() {
		Request request = Request.withRawUrl(HttpMethod.GET, "/search?q=one%20two&lang=en").build();

		Assertions.assertEquals("q=one%20two&lang=en", request.getRawQuery().orElse(null));

		Request updated = request.copy()
				.queryParameters(parameters -> {
					parameters.clear();
					parameters.put("q", new LinkedHashSet<>(List.of("new value")));
					parameters.put("lang", new LinkedHashSet<>(List.of("en")));
					parameters.put("page", new LinkedHashSet<>(List.of("2")));
				})
				.finish();

		Assertions.assertEquals("q=new%20value&lang=en&page=2", updated.getRawQuery().orElse(null));
		Assertions.assertEquals("/search?q=new%20value&lang=en&page=2", updated.getRawPathAndQuery());
	}

	@Test
	public void copyQueryParameterConsumerCanMutateExistingValues() {
		Request request = Request.withRawUrl(HttpMethod.GET, "/search?q=one").build();
		request.getQueryParameters();

		Request updated = request.copy()
				.queryParameters(parameters -> parameters.get("q").add("two"))
				.finish();

		Assertions.assertEquals(new LinkedHashSet<>(List.of("one", "two")), updated.getQueryParameters().get("q"));
		Assertions.assertEquals(Set.of("one"), request.getQueryParameters().get("q"));
	}

	@Test
	public void copyHeadersConsumerCanMutateExistingValues() {
		List<Header> headers = List.of(new Header("X-Trace-Id", "abc123"));
		Request request = Request.withRawUrl(HttpMethod.GET, "/")
				.microhttpHeaders(headers)
				.build();
		request.getHeaders();

		Request updated = request.copy()
				.headers(mutableHeaders -> mutableHeaders.get("x-trace-id").add("def456"))
				.finish();

		Assertions.assertEquals(new LinkedHashSet<>(List.of("abc123", "def456")), updated.getHeaders().get("X-Trace-Id"));
		Assertions.assertEquals(Set.of("abc123"), request.getHeaders().get("X-Trace-Id"));
	}
}
