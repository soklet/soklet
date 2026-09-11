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

import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Canary coverage for sensitive values held by core public response types.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class DiagnosticRedactionTests {
	@Test
	void sseEventDiagnosticsPreserveShapeWithoutRawValues() {
		String event = "event-secret-7a912fe4";
		String id = "id-secret-7a912fe4";
		String data = "data-secret-7a912fe4";
		SseEvent sseEvent = SseEvent.withEvent(event)
				.id(id)
				.data(data)
				.retry(Duration.ofSeconds(7))
				.build();

		String rendering = sseEvent.toString();

		assertEquals("SseEvent{event=<redacted>, id=<redacted>, retry=PT7S, data=["
				+ data.length() + " characters]}", rendering);
		assertFalse(rendering.contains(event));
		assertFalse(rendering.contains(id));
		assertFalse(rendering.contains(data));
	}

	@Test
	void sseCommentDiagnosticsPreserveTypeAndLengthWithoutRawValue() {
		String comment = "comment-secret-7a912fe4";
		SseComment sseComment = SseComment.fromComment(comment);

		String rendering = sseComment.toString();

		assertEquals("SseComment{commentType=COMMENT, comment=[" + comment.length()
				+ " characters]}", rendering);
		assertFalse(rendering.contains(comment));
		assertEquals("SseComment{commentType=HEARTBEAT, comment=[not available]}",
				SseComment.heartbeatInstance().toString());
	}

	@Test
	void multipartFieldDiagnosticsPreservePresenceAndSizeWithoutRawValues() {
		String name = "name-secret-7a912fe4";
		String filename = "filename-secret-7a912fe4";
		String contentType = "content-type-secret-7a912fe4";
		byte[] data = "multipart-data-secret-7a912fe4"
				.getBytes(StandardCharsets.UTF_8);
		MultipartField multipartField = MultipartField.with(name, data)
				.filename(filename)
				.contentType(contentType)
				.build();

		String rendering = multipartField.toString();

		assertEquals("MultipartField{name=<redacted>, filename=<redacted>, "
				+ "contentType=<redacted>, data=[" + data.length + " bytes]}",
				rendering);
		assertFalse(rendering.contains(name));
		assertFalse(rendering.contains(filename));
		assertFalse(rendering.contains(contentType));
		assertFalse(rendering.contains(new String(data, StandardCharsets.UTF_8)));
	}

	@Test
	void responseDiagnosticsPreserveStatusAndCountsWithoutRawValues() {
		String cookieName = "cookie-name-secret-7a912fe4";
		String cookieValue = "cookie-value-secret-7a912fe4";
		String headerName = "X-Header-Secret-7a912fe4";
		String headerValue = "header-value-secret-7a912fe4";
		String body = "body-secret-7a912fe4";
		Response response = Response.withStatusCode(201)
				.cookies(Set.of(ResponseCookie.with(cookieName, cookieValue).build()))
				.headers(Map.of(headerName, Set.of(headerValue)))
				.body(body)
				.build();

		String rendering = response.toString();

		assertEquals("Response{statusCode=201, cookieCount=1, headerCount=1, "
				+ "bodyPresent=true}", rendering);
		assertFalse(rendering.contains(cookieName));
		assertFalse(rendering.contains(cookieValue));
		assertFalse(rendering.contains(headerName));
		assertFalse(rendering.contains(headerValue));
		assertFalse(rendering.contains(body));
	}

	@Test
	void responseCookieDiagnosticsRequireExplicitWireRenderingForRawValues() {
		String name = "cookie-name-secret-7a912fe4";
		String value = "cookie-value-secret-7a912fe4";
		String domain = "cookie-domain-secret-7a912fe4.example";
		String path = "/cookie-path-secret-7a912fe4";
		ResponseCookie cookie = ResponseCookie.with(name, value)
				.domain(domain)
				.path(path)
				.httpOnly(true)
				.secure(true)
				.build();

		String diagnostic = cookie.toString();

		assertFalse(diagnostic.contains(name));
		assertFalse(diagnostic.contains(value));
		assertFalse(diagnostic.contains(domain));
		assertFalse(diagnostic.contains(path));
		assertEquals("ResponseCookie{name=<redacted>, value=<redacted>, "
				+ "maxAge=null, expires=null, domain=<redacted>, path=<redacted>, "
				+ "secure=true, httpOnly=true, sameSite=null, priority=null, "
				+ "partitioned=false}", diagnostic);
		String wire = cookie.toSetCookieHeaderRepresentation();
		assertFalse(wire.equals(diagnostic));
		assertEquals(true, wire.contains(name));
		assertEquals(true, wire.contains(value));
	}

	@Test
	void corsDiagnosticsRedactRequestControlledHeaderValues() {
		String origin = "https://origin-secret-7a912fe4.example";
		String requestedHeader = "X-Header-Secret-7a912fe4";
		Cors cors = Cors.fromHeaders(HttpMethod.GET,
				Map.of("Origin", Set.of(origin))).orElseThrow();
		CorsPreflight preflight = CorsPreflight.fromHeaders(Map.of(
				"Origin", Set.of(origin),
				"Access-Control-Request-Method", Set.of("PATCH"),
				"Access-Control-Request-Headers", Set.of(requestedHeader)))
				.orElseThrow();
		Request request = Request.withPath(HttpMethod.OPTIONS, "/cors").build();
		CorsResponse response = CorsAuthorizer.acceptAllInstance()
				.authorize(request, cors).orElseThrow();
		CorsPreflightResponse preflightResponse = CorsAuthorizer.acceptAllInstance()
				.authorizePreflight(request, preflight, Set.of(HttpMethod.PATCH))
				.orElseThrow();
		HttpRequestResult requestResult = HttpRequestResult.withMarshaledResponse(
				MarshaledResponse.withStatusCode(204).build())
				.corsPreflightResponse(preflightResponse).build();

		assertEquals("Cors{httpMethod=GET, origin=<redacted>}", cors.toString());
		assertEquals("CorsPreflight{origin=<redacted>, "
				+ "accessControlRequestMethod=PATCH, "
				+ "accessControlRequestHeaders=<redacted>}",
				preflight.toString());
		assertEquals("CorsResponse{accessControlAllowOrigin=<redacted>, "
				+ "accessControlAllowCredentials=Optional[true], "
				+ "accessControlExposeHeaders=[]}", response.toString());
		assertEquals("CorsPreflightResponse{accessControlAllowOrigin=<redacted>, "
				+ "accessControlAllowCredentials=Optional[true], "
				+ "accessControlMaxAge=Optional[PT10M], "
				+ "accessControlAllowMethods=[PATCH], "
				+ "accessControlAllowHeaders=<redacted>}",
				preflightResponse.toString());
		assertFalse(cors.toString().contains(origin));
		assertFalse(preflight.toString().contains(origin));
		assertFalse(preflight.toString().contains(requestedHeader));
		assertFalse(response.toString().contains(origin));
		assertFalse(preflightResponse.toString().contains(origin));
		assertFalse(preflightResponse.toString().contains(requestedHeader));
		assertFalse(requestResult.toString().contains(origin));
		assertFalse(requestResult.toString().contains(requestedHeader));
	}
}
