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

import org.jspecify.annotations.NonNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Formal enumeration of valid HTTP status codes.
 * <p>
 * See <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status</a> for details.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum StatusCode {
	/**
	 * This interim response indicates that the client should continue the request or ignore the response if the request is already finished.
	 */
	HTTP_100(100, "Continue"),
	/**
	 * This code is sent in response to an <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Upgrade">Upgrade</a> request header from the client and indicates the protocol the server is switching to.
	 */
	HTTP_101(101, "Switching Protocols"),
	/**
	 * This code was used in <a href="https://developer.mozilla.org/en-US/docs/Glossary/WebDAV">WebDAV</a> contexts to indicate that a request has been received by the server, but no status was available at the time of the response.
	 */
	HTTP_102(102, "Processing"),
	/**
	 * This status code is primarily intended to be used with the <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Link">Link</a> header, letting the user agent start <a href="https://developer.mozilla.org/en-US/docs/Web/HTML/Attributes/rel/preload">preloading</a> resources while the server prepares a response or <a href="https://developer.mozilla.org/en-US/docs/Web/HTML/Attributes/rel/preconnect">preconnect</a> to an origin from which the page will need resources.
	 */
	HTTP_103(103, "Early Hints"),
	/**
	 * The request succeeded. The result and meaning of "success" depends on the HTTP method:
	 * <p>
	 * <ul>
	 * <li><a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/GET">GET</a>: The resource has been fetched and transmitted in the message body.</li>
	 * <li><a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/HEAD">HEAD</a>: Representation headers are included in the response without any message body.</li>
	 * <li><a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/PUT">PUT</a> or <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/POST">POST</a>: The resource describing the result of the action is transmitted in the message body.</li>
	 * <li><a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/TRACE">TRACE</a>: The message body contains the request as received by the server.</li>
	 * </ul>
	 */
	HTTP_200(200, "OK"),
	/**
	 * The request succeeded, and a new resource was created as a result.
	 * <p>
	 * This is typically the response sent after <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/POST">POST</a> requests, or some <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/PUT">PUT</a> requests.
	 */
	HTTP_201(201, "Created"),
	/**
	 * The request has been received but not yet acted upon. It is noncommittal, since there is no way in HTTP to later send an asynchronous response indicating the outcome of the request.
	 * <p>
	 * It is intended for cases where another process or server handles the request, or for batch processing.
	 */
	HTTP_202(202, "Accepted"),
	/**
	 * This response code means the returned metadata is not exactly the same as is available from the origin server, but is collected from a local or a third-party copy.
	 * <p>
	 * This is mostly used for mirrors or backups of another resource. Except for that specific case, the <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/200">200 OK</a> response is preferred to this status.
	 */
	HTTP_203(203, "Non-Authoritative Information"),
	/**
	 * There is no content to send for this request, but the headers are useful.
	 * <p>
	 * The user agent may update its cached headers for this resource with the new ones.
	 */
	HTTP_204(204, "No Content"),
	/**
	 * Tells the user agent to reset the document which sent this request.
	 */
	HTTP_205(205, "Reset Content"),
	/**
	 * This response code is used in response to a <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Range_requests">range request</a> when the client has requested a part or parts of a resource.
	 * <p>
	 * Soklet does not currently support <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Range_requests">range requests</a> out-of-the-box.
	 */
	HTTP_206(206, "Partial Content"),
	/**
	 * (<a href="https://developer.mozilla.org/en-US/docs/Glossary/WebDAV">WebDAV</a>) Conveys information about multiple resources, for situations where multiple status codes might be appropriate.
	 */
	HTTP_207(207, "Multi-Status"),
	/**
	 * (<a href="https://developer.mozilla.org/en-US/docs/Glossary/WebDAV">WebDAV</a>) Used inside a {@code <dav:propstat>} response element to avoid repeatedly enumerating the internal members of multiple bindings to the same collection.
	 */
	HTTP_208(208, "Already Reported"),
	/**
	 * (<a href="https://datatracker.ietf.org/doc/html/rfc3229">HTTP Delta encoding</a>) The server has fulfilled a <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/GET">GET</a> request for the resource, and the response is a representation of the result of one or more instance-manipulations applied to the current instance.
	 */
	HTTP_226(226, "IM Used"),
	/**
	 * In <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Content_negotiation#agent-driven_negotiation">agent-driven content negotiation</a>, the request has more than one possible response and the user agent or user should choose one of them.
	 * <p>
	 * There is no standardized way for clients to automatically choose one of the responses, so this is rarely used.
	 */
	HTTP_300(300, "Multiple Choices"),
	/**
	 * The URL of the requested resource has been changed permanently.
	 * <p>
	 * The new URL is given in the response.
	 */
	HTTP_301(301, "Moved Permanently"),
	/**
	 * This response code means that the URI of requested resource has been changed <em>temporarily</em>.
	 * <p>
	 * Further changes in the URI might be made in the future, so the same URI should be used by the client in future requests.
	 */
	HTTP_302(302, "Found"),
	/**
	 * The server sent this response to direct the client to get the requested resource at another URI with a <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/GET">GET</a> request.
	 */
	HTTP_303(303, "See Other"),
	/**
	 * This is used for caching purposes.
	 * <p>
	 * It tells the client that the response has not been modified, so the client can continue to use the same <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Caching">cached</a> version of the response.
	 */
	HTTP_304(304, "Not Modified"),
	/**
	 * Defined in a previous version of the HTTP specification to indicate that a requested response must be accessed by a proxy.
	 * <p>
	 * It has been deprecated due to security concerns regarding in-band configuration of a proxy.
	 */
	@Deprecated
	HTTP_305(305, "Use Proxy"),
	/**
	 * This response code is no longer used; but is reserved.
	 * <p>
	 * It was used in a previous version of the HTTP/1.1 specification.
	 */
	@Deprecated
	HTTP_306(306, "Unused"),
	/**
	 * The server sends this response to direct the client to get the requested resource at another URI with the same method that was used in the prior request.
	 * <p>
	 * This has the same semantics as the {@code 302 Found} response code, with the exception that the user agent <em>must not</em> change the HTTP method used: if a <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/POST">POST</a> was used in the first request, a <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/POST">POST</a> must be used in the redirected request.
	 */
	HTTP_307(307, "Temporary Redirect"),
	/**
	 * This means that the resource is now permanently located at another URI, specified by the <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Location">Location</a> response header.
	 * <p>
	 * This has the same semantics as the {@code 301 Moved Permanently} HTTP response code, with the exception that the user agent <em>must not</em> change the HTTP method used: if a <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/POST">POST</a> was used in the first request, a <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/POST">POST</a> must be used in the second request.
	 */
	HTTP_308(308, "Permanent Redirect"),
	/**
	 * The server cannot or will not process the request due to something that is perceived to be a client error (e.g., malformed request syntax, invalid request message framing, or deceptive request routing).
	 */
	HTTP_400(400, "Bad Request"),
	/**
	 * Although the HTTP standard specifies "unauthorized", semantically this response means "unauthenticated".
	 * <p>
	 * That is, the client must authenticate itself to get the requested response.
	 */
	HTTP_401(401, "Unauthorized"),
	/**
	 * The initial purpose of this code was for digital payment systems, however this status code is rarely used and no standard convention exists.
	 */
	HTTP_402(402, "Payment Required"),
	/**
	 * The client does not have access rights to the content; that is, it is unauthorized, so the server is refusing to give the requested resource.
	 * <p>
	 * Unlike {@code 401 Unauthorized}, the client's identity is known to the server.
	 */
	HTTP_403(403, "Forbidden"),
	/**
	 * The server cannot find the requested resource.
	 * <p>
	 * In the browser, this means the URL is not recognized.
	 * In an API, this can also mean that the endpoint is valid but the resource itself does not exist.
	 * Servers may also send this response instead of {@code 403 Forbidden} to hide the existence of a resource from an unauthorized client.
	 * This response code is probably the most well known due to its frequent occurrence on the web.
	 */
	HTTP_404(404, "Not Found"),
	/**
	 * The <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods">request method</a> is known by the server but is not supported by the target resource.
	 * <p>
	 * For example, an API may not allow {@code DELETE} on a resource, or the {@code TRACE} method entirely.
	 */
	HTTP_405(405, "Method Not Allowed"),
	/**
	 * This response is sent when the web server, after performing <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Content_negotiation#server-driven_content_negotiation">server-driven content negotiation</a>, doesn't find any content that conforms to the criteria given by the user agent.
	 */
	HTTP_406(406, "Not Acceptable"),
	/**
	 * This is similar to {@code 401 Unauthorized} but authentication is needed to be done by a proxy.
	 */
	HTTP_407(407, "Proxy Authentication Required"),
	/**
	 * This response is sent on an idle connection by some servers, even without any previous request by the client.
	 * <p>
	 * It means that the server would like to shut down this unused connection. This response is used much more since some browsers use HTTP pre-connection mechanisms to speed up browsing. Some servers may shut down a connection without sending this message.
	 */
	HTTP_408(408, "Request Timeout"),
	/**
	 * This response is sent when a request conflicts with the current state of the server.
	 * <p>
	 * In <a href="https://developer.mozilla.org/en-US/docs/Glossary/WebDAV">WebDAV</a> remote web authoring, {@code 409} responses are errors sent to the client so that a user might be able to resolve a conflict and resubmit the request.
	 */
	HTTP_409(409, "Conflict"),
	/**
	 * This response is sent when the requested content has been permanently deleted from server, with no forwarding address.
	 * <p>
	 * Clients are expected to remove their caches and links to the resource. The HTTP specification intends this status code to be used for "limited-time, promotional services". APIs should not feel compelled to indicate resources that have been deleted with this status code.
	 */
	HTTP_410(410, "Gone"),
	/**
	 * Server rejected the request because the <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Length">Content-Length</a> header field is not defined and the server requires it.
	 */
	HTTP_411(411, "Length Required"),
	/**
	 * In <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Conditional_requests">conditional requests</a>, the client has indicated preconditions in its headers which the server does not meet.
	 */
	HTTP_412(412, "Precondition Failed"),
	/**
	 * The request body is larger than limits defined by server.
	 * <p>
	 * The server might close the connection or return a <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Retry-After">Retry-After</a> header field.
	 */
	HTTP_413(413, "Content Too Large"),
	/**
	 * The URI requested by the client is longer than the server is willing to interpret.
	 */
	HTTP_414(414, "URI Too Long"),
	/**
	 * The media format of the requested data is not supported by the server, so the server is rejecting the request.
	 */
	HTTP_415(415, "Unsupported Media Type"),
	/**
	 * The <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Range_requests">ranges</a> specified by the {@code Range} header field in the request cannot be fulfilled.
	 * <p>
	 * It's possible that the range is outside the size of the target resource's data.
	 */
	HTTP_416(416, "Range Not Satisfiable"),
	/**
	 * This response code means the expectation indicated by the <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Expect">Expect</a> request header field cannot be met by the server.
	 */
	HTTP_417(417, "Expectation Failed"),
	/**
	 * The server refuses the attempt to brew coffee with a teapot.
	 */
	HTTP_418(418, "I'm a Teapot"),
	/**
	 * The request was directed at a server that is not able to produce a response.
	 * <p>
	 * This can be sent by a server that is not configured to produce responses for the combination of scheme and authority that are included in the request URI.
	 */
	HTTP_421(421, "Misdirected Request"),
	/**
	 * (<a href="https://developer.mozilla.org/en-US/docs/Glossary/WebDAV">WebDAV</a>) The request was well-formed but was unable to be followed due to semantic errors.
	 */
	HTTP_422(422, "Unprocessable Content"),
	/**
	 * (<a href="https://developer.mozilla.org/en-US/docs/Glossary/WebDAV">WebDAV</a>) The resource that is being accessed is locked.
	 */
	HTTP_423(423, "Locked"),
	/**
	 * (<a href="https://developer.mozilla.org/en-US/docs/Glossary/WebDAV">WebDAV</a>) The request failed due to failure of a previous request.
	 */
	HTTP_424(424, "Failed Dependency"),
	/**
	 * Indicates that the server is unwilling to risk processing a request that might be replayed.
	 */
	HTTP_425(425, "Too Early"),
	/**
	 * The server refuses to perform the request using the current protocol but might be willing to do so after the client upgrades to a different protocol.
	 * <p>
	 * The server sends an <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Upgrade">Upgrade</a> header in a 426 response to indicate the required protocol(s).
	 */
	HTTP_426(426, "Upgrade Required"),
	/**
	 * The origin server requires the request to be <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Conditional_requests">conditional</a>.
	 * <p>
	 * This response is intended to prevent the 'lost update' problem, where a client <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/GET">GET</a>s a resource's state, modifies it and <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/PUT">PUT</a>s it back to the server, when meanwhile a third party has modified the state on the server, leading to a conflict.
	 */
	HTTP_428(428, "Precondition Required"),
	/**
	 * The user has sent too many requests in a given amount of time (<a href="https://developer.mozilla.org/en-US/docs/Glossary/Rate_limit">rate limiting</a>).
	 */
	HTTP_429(429, "Too Many Requests"),
	/**
	 * The server is unwilling to process the request because its header fields are too large.
	 * <p>
	 * The request may be resubmitted after reducing the size of the request header fields.
	 */
	HTTP_431(431, "Request Header Fields Too Large"),
	/**
	 * The user agent requested a resource that cannot legally be provided, such as a web page censored by a government.
	 */
	HTTP_451(451, "Unavailable For Legal Reasons"),
	/**
	 * The server has encountered a situation it does not know how to handle.
	 * <p>
	 * This error is generic, indicating that the server cannot find a more appropriate {@code 5XX} status code to respond with.
	 */
	HTTP_500(500, "Internal Server Error"),
	/**
	 * The request method is not supported by the server and cannot be handled.
	 * <p>
	 * The only methods that servers are required to support (and therefore that must not return this code) are <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/GET">GET</a> and <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/HEAD">HEAD</a>.
	 */
	HTTP_501(501, "Not Implemented"),
	/**
	 * This error response means that the server, while working as a gateway to get a response needed to handle the request, got an invalid response.
	 */
	HTTP_502(502, "Bad Gateway"),
	/**
	 * The server is not ready to handle the request.
	 * <p>
	 * Common causes are a server that is down for maintenance or that is overloaded.
	 * <p>
	 * Note that together with this response, a user-friendly page explaining the problem should be sent. This response should be used for temporary conditions and the <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Retry-After">Retry-After</a> HTTP header should, if possible, contain the estimated time before the recovery of the service.
	 * <p>
	 * The webmaster must also take care about the caching-related headers that are sent along with this response, as these temporary condition responses should usually not be cached.
	 */
	HTTP_503(503, "Service Unavailable"),
	/**
	 * This error response is given when the server is acting as a gateway and cannot get a response in time.
	 */
	HTTP_504(504, "Gateway Timeout"),
	/**
	 * The HTTP version used in the request is not supported by the server.
	 */
	HTTP_505(505, "HTTP Version Not supported"),
	/**
	 * The server has an internal configuration error: during content negotiation, the chosen variant is configured to engage in content negotiation itself, which results in circular references when creating responses.
	 */
	HTTP_506(506, "Variant Also Negotiates"),
	/**
	 * (<a href="https://developer.mozilla.org/en-US/docs/Glossary/WebDAV">WebDAV</a>) The method could not be performed on the resource because the server is unable to store the representation needed to successfully complete the request.
	 */
	HTTP_507(507, "Insufficient Storage"),
	/**
	 * (<a href="https://developer.mozilla.org/en-US/docs/Glossary/WebDAV">WebDAV</a>) The server detected an infinite loop while processing the request.
	 */
	HTTP_508(508, "Loop Detected"),
	/**
	 * The client request declares an HTTP Extension (<a href="https://datatracker.ietf.org/doc/html/rfc2774">RFC 2774</a>) that should be used to process the request, but the extension is not supported.
	 */
	HTTP_510(510, "Not Extended"),
	/**
	 * Indicates that the client needs to authenticate to gain network access.
	 */
	HTTP_511(511, "Network Authentication Required");

	@NonNull
	private static final Map<Integer, StatusCode> STATUS_CODES_BY_NUMBER;

	static {
		Map<Integer, StatusCode> statusCodesByNumber = new HashMap<>();

		for (StatusCode statusCode : StatusCode.values())
			statusCodesByNumber.put(statusCode.getStatusCode(), statusCode);

		STATUS_CODES_BY_NUMBER = Collections.unmodifiableMap(statusCodesByNumber);
	}

	@NonNull
	private final Integer statusCode;
	@NonNull
	private final String reasonPhrase;

	StatusCode(@NonNull Integer statusCode,
						 @NonNull String reasonPhrase) {
		requireNonNull(statusCode);
		requireNonNull(reasonPhrase);

		this.statusCode = statusCode;
		this.reasonPhrase = reasonPhrase;
	}

	/**
	 * Given an HTTP status code, return the corresponding enum value.
	 *
	 * @param statusCode the HTTP status code
	 * @return the enum value that corresponds to the provided HTTP status code, or {@link Optional#empty()} if none exists
	 */
	@NonNull
	public static Optional<StatusCode> fromStatusCode(@NonNull Integer statusCode) {
		return Optional.ofNullable(STATUS_CODES_BY_NUMBER.get(statusCode));
	}

	@Override
	public String toString() {
		return format("%s.%s{statusCode=%s, reasonPhrase=%s}", getClass().getSimpleName(), name(), getStatusCode(), getReasonPhrase());
	}

	/**
	 * The HTTP status code that corresponds to this enum value.
	 *
	 * @return the HTTP status code
	 */
	@NonNull
	public Integer getStatusCode() {
		return this.statusCode;
	}

	/**
	 * An English-language description for this HTTP status code.
	 * <p>
	 * For example, {@link StatusCode#HTTP_404} has reason phrase {@code Not Found}.
	 *
	 * @return English description for this HTTP status code
	 */
	@NonNull
	public String getReasonPhrase() {
		return this.reasonPhrase;
	}
}
