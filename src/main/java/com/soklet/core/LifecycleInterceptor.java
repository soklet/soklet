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

package com.soklet.core;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * "Hook" methods for customizing behavior in response to system lifecycle events -
 * server started, request received, response written, and so on.
 * <p>
 * The ability to modify request processing control flow is provided via {@link #wrapRequest(Request, ResourceMethod, Consumer)}
 * and {@link #interceptRequest(Request, ResourceMethod, Function, Consumer)}.
 * <p>
 * Note: some of these methods are "fail-fast" - exceptions thrown will bubble out and stop execution - and for others, Soklet will
 * catch exceptions and surface separately via {@link #didReceiveLogEvent(LogEvent)}.  Generally speaking, lifecycle events that are scoped
 * at the server level (e.g. {@link #willStartServer(Server)}) will fail-fast and events that are scoped at the request level
 * (e.g. {@link #didStartRequestHandling(Request, ResourceMethod)}) will not fail-fast.
 * <p>
 * A standard implementation can be acquired via the {@link #withDefaults()} factory method.
 * <p>
 * Full documentation is available at <a href="https://www.soklet.com/docs/request-lifecycle">https://www.soklet.com/docs/request-lifecycle</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public interface LifecycleInterceptor {
	/**
	 * Called before the server starts.
	 * <p>
	 * This method <strong>is</strong> fail-fast. If an exception occurs when Soklet invokes this method, it will halt execution and bubble out for your application code to handle.
	 *
	 * @param server the server that will start
	 */
	default void willStartServer(@Nonnull Server server) {
		// No-op by default
	}

	/**
	 * Called after the server starts.
	 * <p>
	 * This method <strong>is</strong> fail-fast. If an exception occurs when Soklet invokes this method, it will halt execution and bubble out for your application code to handle.
	 *
	 * @param server the server that started
	 */
	default void didStartServer(@Nonnull Server server) {
		// No-op by default
	}

	/**
	 * Called before the server stops.
	 * <p>
	 * This method <strong>is</strong> fail-fast. If an exception occurs when Soklet invokes this method, it will halt execution and bubble out for your application code to handle.
	 *
	 * @param server the server that will stop
	 */
	default void willStopServer(@Nonnull Server server) {
		// No-op by default
	}

	/**
	 * Called after the server stops.
	 * <p>
	 * This method <strong>is</strong> fail-fast. If an exception occurs when Soklet invokes this method, it will halt execution and bubble out for your application code to handle.
	 *
	 * @param server the server that stopped
	 */
	default void didStopServer(@Nonnull Server server) {
		// No-op by default
	}

	/**
	 * Called as soon as a request is received and a <em>Resource Method</em> has been resolved to handle it.
	 * <p>
	 * This method <strong>is not</strong> fail-fast. If an exception occurs when Soklet invokes this method, Soklet will catch it and invoke {@link #didReceiveLogEvent(LogEvent)} with type {@link LogEventType#LIFECYCLE_INTERCEPTOR_DID_START_REQUEST_HANDLING_FAILED}.
	 *
	 * @param request        the request that was received
	 * @param resourceMethod the <em>Resource Method</em> that will handle the request
	 *                       May be {@code null} if no <em>Resource Method</em> was resolved, e.g. a 404
	 */
	default void didStartRequestHandling(@Nonnull Request request,
																			 @Nullable ResourceMethod resourceMethod) {
		// No-op by default
	}

	/**
	 * Called after a request has fully completed processing and a response has been sent to the client.
	 * <p>
	 * This method <strong>is not</strong> fail-fast. If an exception occurs when Soklet invokes this method, Soklet will catch it and invoke {@link #didReceiveLogEvent(LogEvent)} with type {@link LogEventType#LIFECYCLE_INTERCEPTOR_DID_FINISH_REQUEST_HANDLING_FAILED}.
	 *
	 * @param request            the request that was received
	 * @param resourceMethod     the <em>Resource Method</em> that will handle the request
	 *                           May be {@code null} if no <em>Resource Method</em> was resolved, e.g. a 404
	 * @param marshaledResponse  the response that was sent to the client
	 * @param processingDuration how long it took to process the whole request, including time to send the response to the client
	 * @param throwables         exceptions that occurred during request handling
	 */
	default void didFinishRequestHandling(@Nonnull Request request,
																				@Nullable ResourceMethod resourceMethod,
																				@Nonnull MarshaledResponse marshaledResponse,
																				@Nonnull Duration processingDuration,
																				@Nonnull List<Throwable> throwables) {
		// No-op by default
	}

	/**
	 * Called before the response is sent to the client.
	 * <p>
	 * This method <strong>is not</strong> fail-fast. If an exception occurs when Soklet invokes this method, Soklet will catch it and invoke {@link #didReceiveLogEvent(LogEvent)} with type {@link LogEventType#LIFECYCLE_INTERCEPTOR_WILL_START_RESPONSE_WRITING_FAILED}.
	 *
	 * @param request           the request that was received
	 * @param resourceMethod    the <em>Resource Method</em> that handled the request.
	 *                          May be {@code null} if no <em>Resource Method</em> was resolved, e.g. a 404
	 * @param marshaledResponse the response to send to the client
	 */
	default void willStartResponseWriting(@Nonnull Request request,
																				@Nullable ResourceMethod resourceMethod,
																				@Nonnull MarshaledResponse marshaledResponse) {
		// No-op by default
	}

	/**
	 * Called after the response is sent to the client.
	 * <p>
	 * This method <strong>is not</strong> fail-fast. If an exception occurs when Soklet invokes this method, Soklet will catch it and invoke {@link #didReceiveLogEvent(LogEvent)} with type {@link LogEventType#LIFECYCLE_INTERCEPTOR_DID_FINISH_RESPONSE_WRITING_FAILED}.
	 *
	 * @param request               the request that was received
	 * @param resourceMethod        the <em>Resource Method</em> that handled the request.
	 *                              May be {@code null} if no <em>Resource Method</em> was resolved, e.g. a 404
	 * @param marshaledResponse     the response that was sent to the client
	 * @param responseWriteDuration how long it took to send the response to the client
	 * @param throwable             the exception thrown during response writing (if any)
	 */
	default void didFinishResponseWriting(@Nonnull Request request,
																				@Nullable ResourceMethod resourceMethod,
																				@Nonnull MarshaledResponse marshaledResponse,
																				@Nonnull Duration responseWriteDuration,
																				@Nullable Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called when an event suitable for logging occurs during processing (generally, an exception).
	 * <p>
	 * This method <strong>is not</strong> fail-fast. If an exception occurs when Soklet invokes this method, Soklet will catch the exception and print its stack trace to stderr.
	 *
	 * @param logEvent the event that occurred
	 */
	default void didReceiveLogEvent(@Nonnull LogEvent logEvent) {
		requireNonNull(logEvent);

		Throwable throwable = logEvent.getThrowable().orElse(null);
		String message = logEvent.getMessage();

		if (throwable == null) {
			System.err.println(message);
		} else {
			StringWriter stringWriter = new StringWriter();
			PrintWriter printWriter = new PrintWriter(stringWriter);
			throwable.printStackTrace(printWriter);

			String throwableWithStackTrace = stringWriter.toString().trim();
			System.err.printf("%s\n%s\n", message, throwableWithStackTrace);
		}
	}

	/**
	 * Supports alteration of the request processing flow by enabling programmatic control over its two key phases: acquiring a response and writing the response to the client.
	 * <p>
	 * This is a more fine-grained approach than {@link #wrapRequest(Request, ResourceMethod, Consumer)}.
	 * <pre> // Default implementation: first, acquire a response for the given request.
	 * MarshaledResponse marshaledResponse = responseProducer.apply(request);
	 *
	 * // Second, send the response over the wire.
	 * responseWriter.accept(marshaledResponse);</pre>
	 * <p>
	 * This method <strong>is not</strong> fail-fast. If an exception occurs when Soklet invokes this method, Soklet will catch it and invoke {@link #didReceiveLogEvent(LogEvent)} with type {@link LogEventType#LIFECYCLE_INTERCEPTOR_INTERCEPT_REQUEST_FAILED}.
	 * <p>
	 * See <a href="https://www.soklet.com/docs/request-lifecycle#request-intercepting">https://www.soklet.com/docs/request-lifecycle#request-intercepting</a> for detailed documentation.
	 *
	 * @param request          the request that was received
	 * @param resourceMethod   the <em>Resource Method</em> that will handle the request
	 *                         May be {@code null} if no <em>Resource Method</em> was resolved, e.g. a 404
	 * @param responseProducer function that accepts the request as input and provides a response as output (usually by invoking the <em>Resource Method</em>)
	 * @param responseWriter   function that accepts a response as input and writes the response to the client
	 */
	default void interceptRequest(@Nonnull Request request,
																@Nullable ResourceMethod resourceMethod,
																@Nonnull Function<Request, MarshaledResponse> responseProducer,
																@Nonnull Consumer<MarshaledResponse> responseWriter) {
		requireNonNull(request);
		requireNonNull(responseProducer);
		requireNonNull(responseWriter);

		MarshaledResponse marshaledResponse = responseProducer.apply(request);
		responseWriter.accept(marshaledResponse);
	}

	/**
	 * Wraps around the whole "outside" of the entire request-handling flow.
	 * <p>
	 * The "inside" of the flow is everything from <em>Resource Method</em> execution to writing response bytes to the client.
	 * <p>
	 * This is a more coarse-grained approach than {@link #interceptRequest(Request, ResourceMethod, Function, Consumer)}.
	 * <pre> // Default implementation: let the request processing proceed as normal
	 * requestProcessor.accept(request);</pre>
	 * <p>
	 * This method <strong>is not</strong> fail-fast. If an exception occurs when Soklet invokes this method, Soklet will catch it and invoke {@link #didReceiveLogEvent(LogEvent)} with type {@link LogEventType#LIFECYCLE_INTERCEPTOR_WRAP_REQUEST_FAILED}.
	 * <p>
	 * See <a href="https://www.soklet.com/docs/request-lifecycle#request-wrapping">https://www.soklet.com/docs/request-lifecycle#request-wrapping</a> for detailed documentation.
	 *
	 * @param request          the request that was received
	 * @param resourceMethod   the <em>Resource Method</em> that will handle the request
	 *                         May be {@code null} if no <em>Resource Method</em> was resolved, e.g. a 404
	 * @param requestProcessor function that takes the request as input and performs all downstream processing
	 */
	default void wrapRequest(@Nonnull Request request,
													 @Nullable ResourceMethod resourceMethod,
													 @Nonnull Consumer<Request> requestProcessor) {
		requireNonNull(request);
		requireNonNull(requestProcessor);

		requestProcessor.accept(request);
	}

	/**
	 * Called before the Server-Sent Event server starts.
	 * <p>
	 * This method <strong>is</strong> fail-fast. If an exception occurs when Soklet invokes this method, it will halt execution and bubble out for your application code to handle.
	 *
	 * @param serverSentEventServer the Server-Sent Event server that will start
	 */
	default void willStartServerSentEventServer(@Nonnull ServerSentEventServer serverSentEventServer) {
		// No-op by default
	}

	/**
	 * Called after the Server-Sent Event server starts.
	 * <p>
	 * This method <strong>is</strong> fail-fast. If an exception occurs when Soklet invokes this method, it will halt execution and bubble out for your application code to handle.
	 *
	 * @param serverSentEventServer the Server-Sent Event server that started
	 */
	default void didStartServerSentEventServer(@Nonnull ServerSentEventServer serverSentEventServer) {
		// No-op by default
	}

	/**
	 * Called before the Server-Sent Event server stops.
	 * <p>
	 * This method <strong>is</strong> fail-fast. If an exception occurs when Soklet invokes this method, it will halt execution and bubble out for your application code to handle.
	 *
	 * @param serverSentEventServer the Server-Sent Event server that will stop
	 */
	default void willStopServerSentEventServer(@Nonnull ServerSentEventServer serverSentEventServer) {
		// No-op by default
	}

	/**
	 * Called after the Server-Sent Event server stops.
	 * <p>
	 * This method <strong>is</strong> fail-fast. If an exception occurs when Soklet invokes this method, it will halt execution and bubble out for your application code to handle.
	 *
	 * @param serverSentEventServer the Server-Sent Event server that stopped
	 */
	default void didStopServerSentEventServer(@Nonnull ServerSentEventServer serverSentEventServer) {
		// No-op by default
	}

	/**
	 * Called immediately before a Server-Sent Event connection of indefinite duration to the client is opened.
	 * <p>
	 * This occurs after the initial "handshake" Server-Sent Event request has successfully completed (that is, an HTTP 200 response).
	 * <p>
	 * This method <strong>is not</strong> fail-fast. If an exception occurs when Soklet invokes this method, Soklet will catch it and invoke {@link #didReceiveLogEvent(LogEvent)} with type {@link LogEventType#LIFECYCLE_INTERCEPTOR_WILL_ESTABLISH_SERVER_SENT_EVENT_CONNECTION_FAILED}.
	 *
	 * @param request        the initial "handshake" Server-Sent Event request that was received
	 * @param resourceMethod the <em>Resource Method</em> that handled the "handshake"
	 */
	default void willEstablishServerSentEventConnection(@Nonnull Request request,
																											@Nonnull ResourceMethod resourceMethod) {
		// No-op by default
	}

	/**
	 * Called immediately after a Server-Sent Event connection of indefinite duration to the client is opened.
	 * <p>
	 * This occurs after the initial "handshake" Server-Sent Event request has successfully completed (that is, an HTTP 200 response).
	 * <p>
	 * This method <strong>is not</strong> fail-fast. If an exception occurs when Soklet invokes this method, Soklet will catch it and invoke {@link #didReceiveLogEvent(LogEvent)} with type {@link LogEventType#LIFECYCLE_INTERCEPTOR_DID_ESTABLISH_SERVER_SENT_EVENT_CONNECTION_FAILED}.
	 *
	 * @param request        the initial "handshake" Server-Sent Event request that was received
	 * @param resourceMethod the <em>Resource Method</em> that handled the "handshake"
	 */
	default void didEstablishServerSentEventConnection(@Nonnull Request request,
																										 @Nonnull ResourceMethod resourceMethod) {
		// No-op by default
	}

	/**
	 * Called immediately before a Server-Sent Event connection to the client is terminated.
	 * <p>
	 * This method <strong>is not</strong> fail-fast. If an exception occurs when Soklet invokes this method, Soklet will catch it and invoke {@link #didReceiveLogEvent(LogEvent)} with type {@link LogEventType#LIFECYCLE_INTERCEPTOR_WILL_TERMINATE_SERVER_SENT_EVENT_CONNECTION_FAILED}.
	 *
	 * @param request        the initial "handshake" Server-Sent Event request that was received
	 * @param resourceMethod the <em>Resource Method</em> that handled the "handshake"
	 * @param throwable      the exception thrown which caused the connection to terminate (if any)
	 */
	default void willTerminateServerSentEventConnection(@Nonnull Request request,
																											@Nonnull ResourceMethod resourceMethod,
																											@Nullable Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called immediately after a Server-Sent Event connection to the client is terminated.
	 * <p>
	 * This method <strong>is not</strong> fail-fast. If an exception occurs when Soklet invokes this method, Soklet will catch it and invoke {@link #didReceiveLogEvent(LogEvent)} with type {@link LogEventType#LIFECYCLE_INTERCEPTOR_DID_TERMINATE_SERVER_SENT_EVENT_CONNECTION_FAILED}.
	 *
	 * @param request            the initial "handshake" Server-Sent Event request that was received
	 * @param resourceMethod     the <em>Resource Method</em> that handled the "handshake"
	 * @param connectionDuration how long the connection was open for
	 * @param throwable          the exception thrown which caused the connection to terminate (if any)
	 */
	default void didTerminateServerSentEventConnection(@Nonnull Request request,
																										 @Nonnull ResourceMethod resourceMethod,
																										 @Nonnull Duration connectionDuration,
																										 @Nullable Throwable throwable) {
		// No-op by default
	}

	/**
	 * Called before a Server-Sent Event is sent to the client.
	 * <p>
	 * This method <strong>is not</strong> fail-fast. If an exception occurs when Soklet invokes this method, Soklet will catch it and invoke {@link #didReceiveLogEvent(LogEvent)} with type {@link LogEventType#LIFECYCLE_INTERCEPTOR_WILL_START_SERVER_SENT_EVENT_WRITING_FAILED}.
	 *
	 * @param request         the initial "handshake" Server-Sent Event request that was received
	 * @param resourceMethod  the <em>Resource Method</em> that handled the "handshake"
	 * @param serverSentEvent the Server-Sent Event to send to the client
	 */
	default void willStartServerSentEventWriting(@Nonnull Request request,
																							 @Nonnull ResourceMethod resourceMethod,
																							 @Nonnull ServerSentEvent serverSentEvent) {
		// No-op by default
	}

	/**
	 * Called after a Server-Sent Event is sent to the client.
	 * <p>
	 * This method <strong>is not</strong> fail-fast. If an exception occurs when Soklet invokes this method, Soklet will catch it and invoke {@link #didReceiveLogEvent(LogEvent)} with type {@link LogEventType#LIFECYCLE_INTERCEPTOR_DID_FINISH_SERVER_SENT_EVENT_WRITING_FAILED}.
	 *
	 * @param request         the initial "handshake" Server-Sent Event request that was received
	 * @param resourceMethod  the <em>Resource Method</em> that handled the "handshake"
	 * @param serverSentEvent the Server-Sent Event that was sent to the client
	 * @param writeDuration   how long it took to send the Server-Sent Event to the client
	 * @param throwable       the exception thrown during Server-Sent Event writing (if any)
	 */
	default void didFinishServerSentEventWriting(@Nonnull Request request,
																							 @Nonnull ResourceMethod resourceMethod,
																							 @Nonnull ServerSentEvent serverSentEvent,
																							 @Nonnull Duration writeDuration,
																							 @Nullable Throwable throwable) {
		// No-op by default
	}

	/**
	 * Acquires a {@link LifecycleInterceptor} instance with sensible defaults.
	 * <p>
	 * Callers should not rely on reference identity; this method may return a new or cached instance.
	 *
	 * @return a {@code LifecycleInterceptor} with default settings
	 */
	@Nonnull
	static LifecycleInterceptor withDefaults() {
		return DefaultLifecycleInterceptor.defaultInstance();
	}
}