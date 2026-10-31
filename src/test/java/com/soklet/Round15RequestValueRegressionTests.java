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

import com.soklet.annotation.GET;
import com.soklet.annotation.RequestCookie;
import com.soklet.exception.IllegalRequestCookieException;
import com.soklet.exception.IllegalRequestException;
import com.soklet.internal.microhttp.Header;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class Round15RequestValueRegressionTests {
	private static final List<String> MALFORMED_TEXT = List.of("secret-\uD800", "secret-\uDC00",
			"secret-\uD800x", "secret-\uDC00\uD800");

	@Test
	void explicitEmptyCookieValuesRemainPresentInSelectedAndFullLookups() {
		for (boolean physicalHeaders : List.of(false, true)) {
			for (String value : List.of("session=", "session=\"\"")) {
				Request request = cookieRequest(List.of(value), physicalHeaders);
				assertEquals(Optional.of(""), request.getCookie("session"));
				assertEquals(List.of(""), request.getCookies().get("session"));
				assertEquals(Optional.of(""), request.getCookie("session"));
				assertThrows(UnsupportedOperationException.class, () -> request.getCookies().get("session").add("extra"));
			}
		}
	}

	@Test
	void emptyCookieOccurrencesCannotBypassScalarDuplicateChecks() {
		for (boolean physicalHeaders : List.of(false, true)) {
			for (List<String> headers : duplicateCookieHeaders()) {
				for (boolean materializeFirst : List.of(false, true)) {
					Request request = cookieRequest(headers, physicalHeaders);
					if (materializeFirst)
						request.getCookies();
					assertThrows(IllegalRequestCookieException.class, () -> request.getCookie("session"));
					assertEquals(2, request.getCookies().get("session").size());
					assertThrows(IllegalRequestCookieException.class, () -> request.getCookie("session"));
				}
			}
		}
	}

	@Test
	void scalarCookieBindingRejectsEmptyDuplicatesBeforeInvokingTheHandler() {
		CookieResource.invocations.set(0);
		SokletSimulator.run(SimulatorConfig.builder().httpServer()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(CookieResource.class)))
				.lifecycleObserver(new LifecycleObserver() {
					@Override public void didReceiveLogEvent(LogEvent event) {}
				})
				.build(), simulator -> {
			for (boolean physicalHeaders : List.of(false, true))
				for (List<String> headers : duplicateCookieHeaders())
					assertEquals(400, simulator.performHttpRequest(cookieRequest(headers, physicalHeaders)).getMarshaledResponse().getStatusCode());
			assertEquals(0, CookieResource.invocations.get());
			MarshaledResponse valid = simulator.performHttpRequest(cookieRequest(List.of("session=trusted"), false)).getMarshaledResponse();
			assertEquals(200, valid.getStatusCode());
			assertEquals("BOUND:trusted", new String(((MarshaledResponseBody.Bytes) valid.getBody().orElseThrow()).getBytes(), StandardCharsets.UTF_8));
			assertEquals(1, CookieResource.invocations.get());
		});
	}

	@Test
	void bareCookieTokensKeepTheirExistingValuelessPolicy() {
		for (boolean physicalHeaders : List.of(false, true)) {
			Request bare = cookieRequest(List.of("session"), physicalHeaders);
			assertEquals(Optional.empty(), bare.getCookie("session"));
			assertEquals(List.of(), bare.getCookies().get("session"));
			Request withValue = cookieRequest(List.of("session; session=trusted"), physicalHeaders);
			assertEquals(Optional.of("trusted"), withValue.getCookie("session"));
			assertEquals(List.of("trusted"), withValue.getCookies().get("session"));
			Request withEmptyValue = cookieRequest(List.of("session; session="), physicalHeaders);
			assertEquals(Optional.of(""), withEmptyValue.getCookie("session"));
			assertEquals(List.of(""), withEmptyValue.getCookies().get("session"));
		}
	}

	@Test
	void decodedQueryBuildersRejectMalformedNamesAndEveryValueAtConstruction() {
		for (String invalid : MALFORMED_TEXT) {
			assertInvalidQuery(() -> Request.withPath(HttpMethod.GET, "/")
					.queryParameters(Map.of(invalid, List.of("unused"), "selected", List.of("ok"))).build());
			assertInvalidQuery(() -> Request.withPath(HttpMethod.GET, "/")
					.queryParameters(Map.of(invalid, List.of())).build());
			assertInvalidQuery(() -> Request.withPath(HttpMethod.GET, "/")
					.queryParameters(Map.of("unselected", List.of("ok", invalid), "selected", List.of("ok"))).build());
		}
	}

	@Test
	void bothQueryCopierOverloadsRejectMalformedNamesAndValues() {
		Request original = Request.fromRawUrl(HttpMethod.GET, "/?selected=ok");
		for (String invalid : MALFORMED_TEXT) {
			assertInvalidQuery(() -> original.copy().queryParameters(Map.of("unselected", List.of(invalid))).finish());
			assertInvalidQuery(() -> original.copy().queryParameters(Map.of(invalid, List.of("unused"))).finish());
			assertInvalidQuery(() -> original.copy().queryParameters(values -> values.put("unselected", List.of(invalid))).finish());
			assertInvalidQuery(() -> original.copy().queryParameters(values -> values.put(invalid, List.of())).finish());
		}
		assertEquals(Optional.of("ok"), original.getQueryParameter("selected"));
		assertEquals("/?selected=ok", original.getRawPathAndQuery());
	}

	@Test
	void validSupplementaryQueryTextSurvivesCopiesAndWireRoundTrips() {
		Map<String, List<String>> parameters = new LinkedHashMap<>();
		parameters.put("name-🍪", List.of("value-𝄞", "", "�", "&=+%?#"));
		parameters.put("", List.of("🍪"));
		Request request = Request.withPath(HttpMethod.GET, "/").queryParameters(parameters).build();
		assertEquals(parameters, request.getQueryParameters());
		assertEquals(parameters, request.copy().finish().getQueryParameters());
		assertEquals(parameters, request.copy().queryParameters(parameters).finish().getQueryParameters());
		assertEquals(parameters, Request.fromRawUrl(HttpMethod.GET, request.getRawPathAndQuery()).getQueryParameters());
		Request modified = request.copy().queryParameters(values -> values.put("extra-𝄞", List.of("🍪", "�"))).finish();
		assertEquals(modified.getQueryParameters(), Request.fromRawUrl(HttpMethod.GET, modified.getRawPathAndQuery()).getQueryParameters());
	}

	@Test
	void queryEncodingRejectsMalformedTextBeforeReplacementEncoding() {
		for (QueryFormat format : QueryFormat.values()) {
			for (String invalid : MALFORMED_TEXT) {
				assertInvalidQuery(() -> Utilities.encodeQueryParameters(Map.of(invalid, List.of()), format));
				assertInvalidQuery(() -> Utilities.encodeQueryParameters(Map.of("name", List.of(invalid)), format));
			}
			Map<String, List<String>> valid = Map.of("name-𝄞", List.of("value-🍪", "�"));
			assertEquals(valid, Utilities.extractQueryParametersFromQuery(Utilities.encodeQueryParameters(valid, format), format));
		}
	}

	private static void assertInvalidQuery(Runnable operation) {
		IllegalRequestException failure = assertThrows(IllegalRequestException.class, operation::run);
		assertEquals("Invalid Unicode in query parameter.", failure.getMessage());
		assertNull(failure.getCause());
	}

	private static List<List<String>> duplicateCookieHeaders() {
		return List.of(List.of("session=; session=trusted"), List.of("session=trusted; session="),
				List.of("session=; session="), List.of("session=\"\"; session="),
				List.of("session=", "session=trusted"), List.of("session=", "session="));
	}

	private static Request cookieRequest(List<String> headers, boolean physicalHeaders) {
		return physicalHeaders
				? Request.withRawUrl(HttpMethod.GET, "/cookies").microhttpHeaders(headers.stream().map(value -> new Header("Cookie", value)).toList()).build()
				: Request.withPath(HttpMethod.GET, "/cookies").headers(Map.of("Cookie", headers)).build();
	}

	public static class CookieResource {
		private static final AtomicInteger invocations = new AtomicInteger();
		@GET("/cookies")
		public String cookie(@RequestCookie(name = "session") String session) {
			invocations.incrementAndGet();
			return "BOUND:" + session;
		}
	}
}
