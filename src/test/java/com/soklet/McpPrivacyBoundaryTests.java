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
import com.soklet.annotation.Multipart;
import com.soklet.annotation.POST;
import com.soklet.annotation.PathParameter;
import com.soklet.annotation.QueryParameter;
import com.soklet.annotation.RequestBody;
import com.soklet.exception.IllegalFormParameterException;
import com.soklet.exception.IllegalMultipartFieldException;
import com.soklet.exception.IllegalPathParameterException;
import com.soklet.exception.IllegalQueryParameterException;
import com.soklet.exception.IllegalRequestBodyException;
import com.soklet.exception.IllegalRequestCookieException;
import com.soklet.exception.IllegalRequestException;
import com.soklet.exception.IllegalRequestHeaderException;
import com.soklet.exception.MissingQueryParameterException;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.AdmissionInput;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.PromptInvocation;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RateLimitInput;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RateLimitTarget;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RequestObservationInput;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.ResourceInvocation;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.ResourceListInvocation;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.ToolInvocation;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Canary coverage for Soklet-owned MCP diagnostic renderings.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class McpPrivacyBoundaryTests {
	private static final String SECRET = "privacy-canary-7a912fe4";
	private static final String SECRET_PARAMETER_NAME =
			"privacyCanary7a912fe4";
	private static final String SECRET_PARENT_ID = "7a912fe47a912fe4";
	private static final String SECRET_TRACE_ID =
			"7a912fe47a912fe47a912fe47a912fe4";

	@Test
	void requestAndRequestIdDiagnosticsPreserveShapeWithoutRawValues() {
		Request request = sensitiveRequest();
		McpRequestId stringRequestId = McpRequestId.fromString(SECRET);
		BigInteger integerValue = new BigInteger("79123456789");
		McpRequestId integerRequestId = McpRequestId.fromInteger(integerValue);

		assertEquals("Request{id=<redacted>, httpMethod=POST, path=<redacted>, "
				+ "cookies=<redacted>, queryParameters=<redacted>, "
				+ "headers=<redacted>, body=23 bytes}", request.toString());
		assertEquals("McpRequestId{string=<redacted>}",
				stringRequestId.toString());
		assertEquals("McpRequestId{integer=<redacted>}",
				integerRequestId.toString());

		assertEquals(SECRET, request.getId());
		assertTrue(request.getPath().contains(SECRET));
		assertEquals(SECRET, request.getQueryParameter("query").orElseThrow());
		assertEquals("Bearer " + SECRET,
				request.getHeader("Authorization").orElseThrow());
		assertEquals(SECRET, request.getCookie("session").orElseThrow());
		assertEquals(SECRET, request.getBodyAsString().orElseThrow());
		assertEquals(SECRET, stringRequestId.asString().orElseThrow());
		assertEquals(integerValue, integerRequestId.asInteger().orElseThrow());
		assertFalse(request.toString().contains(SECRET));
	}

	@Test
	void propagationAndBridgeDiagnosticsRedactExactApplicationCarriers() {
		Request request = sensitiveRequest();
		McpEndpoint endpoint = endpoint();
		McpAdmissionIdentity identity = McpAdmissionIdentity
				.withRateLimitPartitionKey(SECRET)
				.authorizationPartitionKey(SECRET)
				.principal(SECRET)
				.applicationContext(SECRET)
				.build();
		McpJsonObject secretJson = McpJsonObject.builder()
				.put(SECRET, SECRET)
				.build();
		McpInputResponses inputResponses = McpInputResponses.builder()
				.addResponse(SECRET, McpJsonString.fromValue(SECRET))
				.build();
		RequestObservationInput observationInput = new RequestObservationInput(
				request, endpoint, Map.of(SECRET, SECRET), SECRET,
				Optional.of(McpRequestId.fromString(SECRET)), "2026-07-28",
				Optional.of(SECRET), Optional.of(clientInformation()), secretJson,
				secretJson, inputResponses, Optional.empty(), Optional.of(SECRET),
				List.of(SECRET), identity);
		DefaultMcpRequestContext requestContext =
				new DefaultMcpRequestContext(observationInput);
		AdmissionInput admissionInput = new AdmissionInput(request, endpoint,
				Map.of(SECRET, SECRET), SECRET, false,
				Optional.of(McpRequestId.fromString(SECRET)), "2026-07-28",
				Optional.of(SECRET), Optional.of(clientInformation()),
				Optional.of(secretJson), List.of(URI.create("urn:" + SECRET)),
				Optional.of(secretJson));
		ResourceInvocation resourceInvocation = new ResourceInvocation(request,
				requestContext, endpoint, Map.of(SECRET, SECRET), SECRET,
				McpRequestId.fromString(SECRET), "2026-07-28", SECRET,
				Optional.of(clientInformation()), secretJson, secretJson, identity,
				"urn:" + SECRET, Map.of(SECRET, SECRET), () -> {});
		ResourceListInvocation resourceListInvocation = new ResourceListInvocation(
				request, requestContext, endpoint, Map.of(SECRET, SECRET), SECRET,
				McpRequestId.fromString(SECRET), "2026-07-28",
				Optional.of(clientInformation()), secretJson, secretJson, identity,
				Optional.of(SECRET), List.of(secretJson), () -> {});
		ToolInvocation toolInvocation = new ToolInvocation(request, requestContext,
				endpoint, Map.of(SECRET, SECRET), SECRET,
				McpRequestId.fromString(SECRET), "2026-07-28", SECRET,
				Optional.of(clientInformation()), secretJson, secretJson, identity,
				secretJson, () -> {});
		PromptInvocation promptInvocation = new PromptInvocation(request,
				requestContext, endpoint, Map.of(SECRET, SECRET), SECRET,
				McpRequestId.fromString(SECRET), "2026-07-28", SECRET,
				Optional.of(clientInformation()), secretJson, secretJson, identity,
				secretJson, () -> {});
		RateLimitInput rateLimitInput = new RateLimitInput(request, endpoint,
				identity, RateLimitTarget.TOOL, SECRET, Optional.of(SECRET));
		McpRequestPropagation propagation = McpRequestPropagation.fromMetadata(
				McpJsonObject.builder()
						.put("traceparent", "00-" + SECRET_TRACE_ID + "-"
								+ SECRET_PARENT_ID + "-01")
						.put("baggage", "private=" + SECRET)
						.build());

		for (String diagnostic : List.of(observationInput.toString(),
				admissionInput.toString(), resourceInvocation.toString(),
				resourceListInvocation.toString(), toolInvocation.toString(),
				promptInvocation.toString(), rateLimitInput.toString(),
				propagation.toString())) {
			assertFalse(diagnostic.contains(SECRET), diagnostic);
			assertFalse(diagnostic.contains(SECRET_TRACE_ID), diagnostic);
			assertFalse(diagnostic.contains(SECRET_PARENT_ID), diagnostic);
		}

		assertEquals(SECRET, observationInput.applicationRequestState().orElseThrow());
		assertEquals(secretJson, observationInput.requestMetadata());
		assertEquals(secretJson, admissionInput.requestMetadata().orElseThrow());
		assertEquals(SECRET, admissionInput.operationName().orElseThrow());
		assertEquals(SECRET, resourceInvocation.templateVariables().get(SECRET));
		assertEquals(secretJson, resourceInvocation.requestMetadata());
		assertEquals(SECRET, resourceListInvocation.cursor().orElseThrow());
		assertEquals(secretJson, resourceListInvocation.requestMetadata());
		assertEquals(secretJson, toolInvocation.rawArguments());
		assertEquals(secretJson, promptInvocation.rawArguments());
		assertEquals(SECRET, rateLimitInput.operationName().orElseThrow());
		assertEquals(SECRET, propagation.baggage().get("private"));
		assertEquals(SECRET_TRACE_ID,
				propagation.traceContext().orElseThrow().getTraceId());
		assertEquals(SECRET_PARENT_ID,
				propagation.traceContext().orElseThrow().getParentId());
		assertEquals(SECRET, identity.getPrincipal().orElseThrow());
		assertTrue(identity.isAuthenticated());
	}

	@Test
	void requestExceptionMessagesRedactExactApplicationValues() {
		IllegalRequestException invalidPath = assertThrows(
				IllegalRequestException.class,
				() -> Request.withPath(HttpMethod.GET,
						"/" + SECRET + "\u0000").build());
		assertMessageRedacted(invalidPath);

		String malformedUrl = "/?" + SECRET + "=" + SECRET + " value";
		IllegalRequestException invalidUrl = assertThrows(
				IllegalRequestException.class,
				() -> Utilities.extractQueryParametersFromUrl(malformedUrl,
						QueryFormat.RFC_3986_STRICT, StandardCharsets.UTF_8));
		assertMessageRedacted(invalidUrl);
		assertNull(invalidUrl.getCause());
		IllegalRequestException invalidRawQuery = assertThrows(
				IllegalRequestException.class,
				() -> Utilities.extractRawQueryFromUrlStrict(malformedUrl));
		assertMessageRedacted(invalidRawQuery);
		assertNull(invalidRawQuery.getCause());

		IllegalQueryParameterException query = assertThrows(
				IllegalQueryParameterException.class,
				() -> Request.withRawUrl(HttpMethod.GET,
						"/?" + SECRET + "=" + SECRET + "-one&" + SECRET
								+ "=" + SECRET + "-two")
						.build()
						.getQueryParameter(SECRET));
		assertMessageRedacted(query);
		assertEquals(SECRET, query.getQueryParameterName());
		assertTrue(query.getQueryParameterValue().orElseThrow().contains(SECRET));

		IllegalFormParameterException form = assertThrows(
				IllegalFormParameterException.class,
				() -> Request.withPath(HttpMethod.POST, "/")
						.headers(Map.of("Content-Type", Set.of(
								"application/x-www-form-urlencoded")))
						.body((SECRET + "=" + SECRET + "-one&" + SECRET
								+ "=" + SECRET + "-two").getBytes(StandardCharsets.UTF_8))
						.build()
						.getFormParameter(SECRET));
		assertMessageRedacted(form);
		assertEquals(SECRET, form.getFormParameterName());
		assertTrue(form.getFormParameterValue().orElseThrow().contains(SECRET));

		String headerName = "X-" + SECRET;
		IllegalRequestHeaderException header = assertThrows(
				IllegalRequestHeaderException.class,
				() -> Request.withPath(HttpMethod.GET, "/")
						.headers(Map.of(headerName,
								Set.of(SECRET + "-one", SECRET + "-two")))
						.build()
						.getHeader(headerName));
		assertMessageRedacted(header);
		assertEquals(headerName, header.getRequestHeaderName());
		assertTrue(header.getRequestHeaderValue().orElseThrow().contains(SECRET));

		IllegalRequestCookieException cookie = assertThrows(
				IllegalRequestCookieException.class,
				() -> Request.withPath(HttpMethod.GET, "/")
						.headers(Map.of("Cookie", Set.of(SECRET + "=" + SECRET
								+ "-one; " + SECRET + "=" + SECRET + "-two")))
						.build()
						.getCookie(SECRET));
		assertMessageRedacted(cookie);
		assertEquals(SECRET, cookie.getName());
		assertTrue(cookie.getValue().orElseThrow().contains(SECRET));

		String boundary = "privacy-boundary";
		String multipartBody = "--" + boundary + "\r\n"
				+ "Content-Disposition: form-data; name=\"" + SECRET
				+ "\"\r\n\r\n" + SECRET + "-one\r\n"
				+ "--" + boundary + "\r\n"
				+ "Content-Disposition: form-data; name=\"" + SECRET
				+ "\"\r\n\r\n" + SECRET + "-two\r\n"
				+ "--" + boundary + "--\r\n";
		IllegalMultipartFieldException multipart = assertThrows(
				IllegalMultipartFieldException.class,
				() -> Request.withPath(HttpMethod.POST, "/")
						.headers(Map.of("Content-Type", Set.of(
								"multipart/form-data; boundary=" + boundary)))
						.body(multipartBody.getBytes(StandardCharsets.UTF_8))
						.build()
						.getMultipartField(SECRET));
		assertMessageRedacted(multipart);
		assertEquals(SECRET, multipart.getMultipartField().getName());
		assertTrue(multipart.getMultipartField().getDataAsString()
				.orElseThrow().contains(SECRET));

		IllegalRequestBodyException invalidBoundary = assertThrows(
				IllegalRequestBodyException.class,
				() -> Request.withPath(HttpMethod.POST, "/")
						.headers(Map.of("Content-Type", Set.of(
								"multipart/form-data; boundary=" + SECRET + "@")))
						.body("invalid".getBytes(StandardCharsets.UTF_8))
						.build()
						.getMultipartFields());
		assertMessageRedacted(invalidBoundary);
	}

	@Test
	void annotationDrivenBindingExceptionsRedactRequestValuesAndCauses() {
		SokletConfig config = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(PrivacyBindingResource.class)))
				.build();

		Request convertedQueryRequest = Request.withRawUrl(HttpMethod.GET,
				"/privacy-binding/query?" + SECRET_PARAMETER_NAME + "=" + SECRET)
				.build();
		IllegalQueryParameterException convertedQuery = assertThrows(
				IllegalQueryParameterException.class,
				() -> bind(config, convertedQueryRequest));
		assertBindingFailureRedacted(convertedQuery);
		assertEquals(SECRET_PARAMETER_NAME,
				convertedQuery.getQueryParameterName());
		assertEquals(SECRET,
				convertedQuery.getQueryParameterValue().orElseThrow());

		Request missingQueryRequest = Request.withPath(HttpMethod.GET,
				"/privacy-binding/query").build();
		MissingQueryParameterException missingQuery = assertThrows(
				MissingQueryParameterException.class,
				() -> bind(config, missingQueryRequest));
		assertBindingFailureRedacted(missingQuery);
		assertEquals(SECRET_PARAMETER_NAME,
				missingQuery.getQueryParameterName());

		Request repeatedQueryRequest = Request.withRawUrl(HttpMethod.GET,
				"/privacy-binding/query?" + SECRET_PARAMETER_NAME + "=" + SECRET
						+ "-one&" + SECRET_PARAMETER_NAME + "=" + SECRET + "-two")
				.build();
		IllegalQueryParameterException repeatedQuery = assertThrows(
				IllegalQueryParameterException.class,
				() -> bind(config, repeatedQueryRequest));
		assertBindingFailureRedacted(repeatedQuery);
		assertTrue(repeatedQuery.getQueryParameterValue().orElseThrow()
				.contains(SECRET));

		Request pathRequest = Request.withPath(HttpMethod.GET,
				"/privacy-binding/path/" + SECRET).build();
		IllegalPathParameterException path = assertThrows(
				IllegalPathParameterException.class,
				() -> bind(config, pathRequest));
		assertBindingFailureRedacted(path);
		assertEquals(SECRET_PARAMETER_NAME, path.getPathParameterName());
		assertEquals(SECRET, path.getPathParameterValue().orElseThrow());

		Request bodyRequest = Request.withPath(HttpMethod.POST,
				"/privacy-binding/body")
				.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
				.body(SECRET.getBytes(StandardCharsets.UTF_8))
				.build();
		IllegalRequestBodyException body = assertThrows(
				IllegalRequestBodyException.class,
				() -> bind(config, bodyRequest));
		assertBindingFailureRedacted(body);
		assertEquals(SECRET, bodyRequest.getBodyAsString().orElseThrow());

		Request multipartRequest = multipartBindingRequest(SECRET);
		IllegalMultipartFieldException multipart = assertThrows(
				IllegalMultipartFieldException.class,
				() -> bind(config, multipartRequest));
		assertBindingFailureRedacted(multipart);
		assertEquals(SECRET_PARAMETER_NAME,
				multipart.getMultipartField().getName());
		assertEquals(SECRET, multipart.getMultipartField().getDataAsString()
				.orElseThrow());

		Request repeatedMultipartRequest = multipartBindingRequest(
				SECRET + "-one", SECRET + "-two");
		IllegalMultipartFieldException repeatedMultipart = assertThrows(
				IllegalMultipartFieldException.class,
				() -> bind(config, repeatedMultipartRequest));
		assertBindingFailureRedacted(repeatedMultipart);
		assertEquals(SECRET_PARAMETER_NAME,
				repeatedMultipart.getMultipartField().getName());
		assertTrue(repeatedMultipart.getMultipartField().getDataAsString()
				.orElseThrow().contains(SECRET));
	}

	private static void assertMessageRedacted(RuntimeException exception) {
		assertFalse(exception.getMessage().contains(SECRET), exception.getMessage());
		if (exception.getCause() != null)
			assertFalse(exception.getCause().toString().contains(SECRET),
					exception.getCause().toString());
	}

	private static void assertBindingFailureRedacted(
			RuntimeException exception) {
		assertFalse(exception.getMessage().contains(SECRET),
				exception.getMessage());
		assertFalse(exception.getMessage().contains(SECRET_PARAMETER_NAME),
				exception.getMessage());
		assertNull(exception.getCause());
	}

	private static void bind(SokletConfig config, Request request) {
		ResourceMethod resourceMethod = config.getResourceMethodResolver()
				.resourceMethodForRequest(request, ServerType.STANDARD_HTTP)
				.orElseThrow();
		config.getResourceMethodParameterProvider()
				.parameterValuesForResourceMethod(request, resourceMethod);
	}

	private static Request sensitiveRequest() {
		return Request.withRawUrl(HttpMethod.POST,
				"/" + SECRET + "?query=" + SECRET)
				.id(SECRET)
				.headers(Map.of(
						"Authorization", Set.of("Bearer " + SECRET),
						"Cookie", Set.of("session=" + SECRET),
						"Accept-Language", Set.of(SECRET)))
				.body(SECRET.getBytes(StandardCharsets.UTF_8))
				.build();
	}

	private static Request multipartBindingRequest(String... values) {
		String boundary = "privacy-binding-boundary";
		StringBuilder body = new StringBuilder();

		for (String value : values)
			body.append("--").append(boundary).append("\r\n")
					.append("Content-Disposition: form-data; name=\"")
					.append(SECRET_PARAMETER_NAME).append("\"\r\n\r\n")
					.append(value).append("\r\n");

		body.append("--").append(boundary).append("--\r\n");

		return Request.withPath(HttpMethod.POST, "/privacy-binding/multipart")
				.headers(Map.of("Content-Type", Set.of(
						"multipart/form-data; boundary=" + boundary)))
				.body(body.toString().getBytes(StandardCharsets.UTF_8))
				.build();
	}

	private static McpEndpoint endpoint() {
		return McpEndpoint.withPath("/" + SECRET, clientInformation())
				.build();
	}

	private static McpImplementation clientInformation() {
		return McpImplementation.withNameAndVersion(SECRET, SECRET).build();
	}

	public static final class PrivacyBindingResource {
		@GET("/privacy-binding/query")
		public void query(
				@QueryParameter(name = SECRET_PARAMETER_NAME) Integer value) {}

		@GET("/privacy-binding/path/{privacyCanary7a912fe4}")
		public void path(
				@PathParameter(name = SECRET_PARAMETER_NAME) Integer value) {}

		@POST("/privacy-binding/body")
		public void body(@RequestBody Integer value) {}

		@POST("/privacy-binding/multipart")
		public void multipart(
				@Multipart(name = SECRET_PARAMETER_NAME) Integer value) {}
	}
}
