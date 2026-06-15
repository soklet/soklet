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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ConditionalRequestsTests {
	private static final EntityTag ENTITY_TAG = EntityTag.fromStrongValue("v1");
	private static final Instant LAST_MODIFIED = Instant.parse("2026-05-04T01:02:03.456Z");
	private static final String LAST_MODIFIED_HEADER_VALUE = "Mon, 04 May 2026 01:02:03 GMT";

	@Test
	public void validatorHeadersIncludeSuppliedValidators() {
		Map<String, Set<String>> headers = ConditionalRequests.validatorHeaders(ENTITY_TAG, LAST_MODIFIED);

		Assertions.assertEquals(Set.of("\"v1\""), headers.get("ETag"));
		Assertions.assertEquals(Set.of(LAST_MODIFIED_HEADER_VALUE), headers.get("Last-Modified"));
		Assertions.assertThrows(UnsupportedOperationException.class, () -> headers.put("Cache-Control", Set.of("no-cache")));
	}

	@Test
	public void validatorHeadersCanIncludeExtraMetadataHeaders() {
		Map<String, Set<String>> headers = ConditionalRequests.validatorHeaders(
				ENTITY_TAG,
				LAST_MODIFIED,
				Map.of(
						"Cache-Control", Set.of("private, max-age=60"),
						"Vary", Set.of("Accept-Language")
				)
		);

		Assertions.assertEquals(Set.of("\"v1\""), headers.get("ETag"));
		Assertions.assertEquals(Set.of(LAST_MODIFIED_HEADER_VALUE), headers.get("Last-Modified"));
		Assertions.assertEquals(Set.of("private, max-age=60"), headers.get("Cache-Control"));
		Assertions.assertEquals(Set.of("Accept-Language"), headers.get("Vary"));
		Assertions.assertThrows(UnsupportedOperationException.class, () -> headers.put("X-Test", Set.of("value")));
	}

	@Test
	public void validatorHeadersRejectControlledExtraHeaderNames() {
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				ConditionalRequests.validatorHeaders(ENTITY_TAG, LAST_MODIFIED, Map.of("eTaG", Set.of("\"bad\""))));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				ConditionalRequests.validatorHeaders(ENTITY_TAG, LAST_MODIFIED, Map.of("Last-Modified", Set.of(LAST_MODIFIED_HEADER_VALUE))));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				ConditionalRequests.validatorHeaders(ENTITY_TAG, LAST_MODIFIED, Map.of("Content-Type", Set.of("application/json"))));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				ConditionalRequests.validatorHeaders(ENTITY_TAG, LAST_MODIFIED, Map.of("Content-Length", Set.of("0"))));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				ConditionalRequests.validatorHeaders(ENTITY_TAG, LAST_MODIFIED, Map.of("Transfer-Encoding", Set.of("chunked"))));
	}

	@Test
	public void ifNoneMatchWeakMatchForGetReturnsNotModified() {
		Request request = request(HttpMethod.GET, Map.of(
				"If-None-Match", Set.of("W/\"v1\"")
		));
		Map<String, Set<String>> extraHeaders = Map.of(
				"Cache-Control", Set.of("private, max-age=60"),
				"Vary", Set.of("Accept-Language")
		);

		Response response = ConditionalRequests.responseFor(request, ENTITY_TAG, LAST_MODIFIED, extraHeaders).orElseThrow();

		Assertions.assertEquals(304, response.getStatusCode());
		Assertions.assertEquals(Optional.empty(), response.getBody());
		Assertions.assertEquals(Set.of("\"v1\""), response.getHeaders().get("ETag"));
		Assertions.assertEquals(Set.of(LAST_MODIFIED_HEADER_VALUE), response.getHeaders().get("Last-Modified"));
		Assertions.assertEquals(Set.of("private, max-age=60"), response.getHeaders().get("Cache-Control"));
		Assertions.assertEquals(Set.of("Accept-Language"), response.getHeaders().get("Vary"));
		Assertions.assertFalse(response.getHeaders().containsKey("Content-Type"));
	}

	@Test
	public void ifNoneMatchWeakMatchForMutatingMethodReturnsPreconditionFailed() {
		Request request = request(HttpMethod.PUT, Map.of(
				"If-None-Match", Set.of("W/\"v1\"")
		));

		Response response = ConditionalRequests.responseFor(request, ENTITY_TAG, LAST_MODIFIED).orElseThrow();

		Assertions.assertEquals(412, response.getStatusCode());
		Assertions.assertEquals(Optional.empty(), response.getBody());
	}

	@Test
	public void ifMatchUsesStrongComparison() {
		Request staleRequest = request(HttpMethod.PUT, Map.of(
				"If-Match", Set.of("\"other\"")
		));
		Request weakRequest = request(HttpMethod.PUT, Map.of(
				"If-Match", Set.of("W/\"v1\"")
		));
		Request matchingRequest = request(HttpMethod.PUT, Map.of(
				"If-Match", Set.of("\"v1\"")
		));

		Assertions.assertEquals(412, ConditionalRequests.responseFor(staleRequest, ENTITY_TAG, LAST_MODIFIED).orElseThrow().getStatusCode());
		Assertions.assertEquals(412, ConditionalRequests.responseFor(weakRequest, ENTITY_TAG, LAST_MODIFIED).orElseThrow().getStatusCode());
		Assertions.assertEquals(Optional.empty(), ConditionalRequests.responseFor(matchingRequest, ENTITY_TAG, LAST_MODIFIED));
	}

	@Test
	public void ifUnmodifiedSinceCanFailPrecondition() {
		Request request = request(HttpMethod.PUT, Map.of(
				"If-Unmodified-Since", Set.of("Mon, 04 May 2026 01:02:02 GMT")
		));

		Response response = ConditionalRequests.responseFor(request, ENTITY_TAG, LAST_MODIFIED).orElseThrow();

		Assertions.assertEquals(412, response.getStatusCode());
	}

	@Test
	public void ifModifiedSinceCanReturnNotModifiedForGet() {
		Request request = request(HttpMethod.GET, Map.of(
				"If-Modified-Since", Set.of(LAST_MODIFIED_HEADER_VALUE)
		));

		Response response = ConditionalRequests.responseFor(request, ENTITY_TAG, LAST_MODIFIED).orElseThrow();

		Assertions.assertEquals(304, response.getStatusCode());
	}

	@Test
	public void entityTagPreconditionsTakePrecedenceOverDatePreconditions() {
		Request ifMatchRequest = request(HttpMethod.PUT, Map.of(
				"If-Match", Set.of("\"v1\""),
				"If-Unmodified-Since", Set.of("Mon, 04 May 2026 01:02:02 GMT")
		));
		Request ifNoneMatchRequest = request(HttpMethod.GET, Map.of(
				"If-None-Match", Set.of("\"other\""),
				"If-Modified-Since", Set.of("Mon, 04 May 2026 01:02:04 GMT")
		));

		Assertions.assertEquals(Optional.empty(), ConditionalRequests.responseFor(ifMatchRequest, ENTITY_TAG, LAST_MODIFIED));
		Assertions.assertEquals(Optional.empty(), ConditionalRequests.responseFor(ifNoneMatchRequest, ENTITY_TAG, LAST_MODIFIED));
	}

	@Test
	public void wildcardEntityTagConditionsAssumeRepresentationExists() {
		Request ifMatchRequest = request(HttpMethod.PUT, Map.of(
				"If-Match", Set.of("*")
		));
		Request getIfNoneMatchRequest = request(HttpMethod.GET, Map.of(
				"If-None-Match", Set.of("*")
		));
		Request putIfNoneMatchRequest = request(HttpMethod.PUT, Map.of(
				"If-None-Match", Set.of("*")
		));

		Assertions.assertEquals(Optional.empty(), ConditionalRequests.responseFor(ifMatchRequest, null, null));
		Assertions.assertEquals(304, ConditionalRequests.responseFor(getIfNoneMatchRequest, null, null).orElseThrow().getStatusCode());
		Assertions.assertEquals(412, ConditionalRequests.responseFor(putIfNoneMatchRequest, null, null).orElseThrow().getStatusCode());
	}

	@Test
	public void malformedConditionalHeadersAreIgnored() {
		Request request = request(HttpMethod.GET, Map.of(
				"If-Match", Set.of("\"unterminated"),
				"If-Modified-Since", Set.of("not a date")
		));

		Assertions.assertEquals(Optional.empty(), ConditionalRequests.responseFor(request, ENTITY_TAG, LAST_MODIFIED));
	}

	@Test
	public void repeatedEntityTagHeaderValuesAreEvaluatedAsOneConditionList() {
		Set<String> headerValues = new LinkedHashSet<>();
		headerValues.add("\"other\"");
		headerValues.add("\"v1\"");
		Request request = request(HttpMethod.GET, Map.of(
				"If-None-Match", headerValues
		));

		Response response = ConditionalRequests.responseFor(request, ENTITY_TAG, LAST_MODIFIED).orElseThrow();

		Assertions.assertEquals(304, response.getStatusCode());
	}

	@Test
	public void oversizedEntityTagListsAreIgnored() {
		String headerValue = IntStream.rangeClosed(0, 256)
				.mapToObj(index -> "\"v" + index + "\"")
				.collect(Collectors.joining(","));
		Request request = request(HttpMethod.GET, Map.of(
				"If-None-Match", Set.of(headerValue)
		));

		Assertions.assertEquals(Optional.empty(), ConditionalRequests.responseFor(request, ENTITY_TAG, LAST_MODIFIED));
	}

	@Test
	public void extraHeadersRejectControlledHeaderNames() {
		Request request = request(HttpMethod.GET, Map.of(
				"If-None-Match", Set.of("\"v1\"")
		));

		Assertions.assertThrows(IllegalArgumentException.class, () ->
				ConditionalRequests.responseFor(request, ENTITY_TAG, LAST_MODIFIED, Map.of("eTaG", Set.of("\"bad\""))));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				ConditionalRequests.responseFor(request, ENTITY_TAG, LAST_MODIFIED, Map.of("Last-Modified", Set.of(LAST_MODIFIED_HEADER_VALUE))));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				ConditionalRequests.responseFor(request, ENTITY_TAG, LAST_MODIFIED, Map.of("Content-Type", Set.of("application/json"))));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				ConditionalRequests.responseFor(request, ENTITY_TAG, LAST_MODIFIED, Map.of("Content-Length", Set.of("0"))));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				ConditionalRequests.responseFor(request, ENTITY_TAG, LAST_MODIFIED, Map.of("Transfer-Encoding", Set.of("chunked"))));
	}

	private static Request request(HttpMethod httpMethod,
																 Map<String, Set<String>> headers) {
		return Request.withPath(httpMethod, "/conditional")
				.headers(headers)
				.build();
	}
}
