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

import com.soklet.annotation.Resource;
import com.soklet.core.Cors;
import com.soklet.core.CorsAuthorizer;
import com.soklet.core.CorsPreflight;
import com.soklet.core.CorsPreflightResponse;
import com.soklet.core.CorsResponse;
import com.soklet.core.HttpMethod;
import com.soklet.core.InstanceProvider;
import com.soklet.core.LifecycleInterceptor;
import com.soklet.core.LogEvent;
import com.soklet.core.LogEventType;
import com.soklet.core.MarshaledResponse;
import com.soklet.core.Request;
import com.soklet.core.RequestResult;
import com.soklet.core.ResourceMethod;
import com.soklet.core.ResourceMethodParameterProvider;
import com.soklet.core.ResourceMethodResolver;
import com.soklet.core.ResourcePath;
import com.soklet.core.Response;
import com.soklet.core.ResponseMarshaler;
import com.soklet.core.Server;
import com.soklet.core.ServerSentEvent;
import com.soklet.core.ServerSentEventBroadcaster;
import com.soklet.core.ServerSentEventServer;
import com.soklet.core.Simulator;
import com.soklet.core.StatusCode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.soklet.core.Utilities.emptyByteArray;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Soklet's main class - manages a {@link Server} (and optionally a {@link ServerSentEventServer}) using the provided system configuration.
 * <p>
 * <pre>{@code  // Use out-of-the-box defaults
 * SokletConfiguration config = SokletConfiguration.withServer(
 *   DefaultServer.withPort(8080).build()
 * ).build();
 *
 * try (Soklet soklet = Soklet.withConfiguration(config)) {
 *   soklet.start();
 *   System.out.println("Soklet started, press [enter] to exit");
 *   System.in.read(); // or Thread.currentThread().join() in containers
 * }}</pre>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class Soklet implements AutoCloseable {
	@Nonnull
	private final SokletConfiguration sokletConfiguration;
	@Nonnull
	private final ReentrantLock lock;

	/**
	 * Acquires a Soklet instance with the given configuration.
	 *
	 * @param sokletConfiguration configuration that drives the Soklet system
	 * @return a Soklet instance
	 */
	@Nonnull
	public static Soklet withConfiguration(@Nonnull SokletConfiguration sokletConfiguration) {
		requireNonNull(sokletConfiguration);
		return new Soklet(sokletConfiguration);
	}

	/**
	 * Creates a Soklet instance with the given configuration.
	 *
	 * @param sokletConfiguration configuration that drives the Soklet system
	 */
	private Soklet(@Nonnull SokletConfiguration sokletConfiguration) {
		requireNonNull(sokletConfiguration);

		this.sokletConfiguration = sokletConfiguration;
		this.lock = new ReentrantLock();

		// Fail fast in the event that Soklet appears misconfigured
		if (sokletConfiguration.getResourceMethodResolver().getResourceMethods().size() == 0)
			throw new IllegalArgumentException(format("No classes annotated with @%s were found.", Resource.class.getSimpleName()));

		// Use a layer of indirection here so the Soklet type does not need to directly implement the `RequestHandler` interface.
		// Reasoning: the `handleRequest` method for Soklet should not be public, which might lead to accidental invocation by users.
		// That method should only be called by the managed `Server` instance.
		Soklet soklet = this;

		sokletConfiguration.getServer().initialize(getSokletConfiguration(), (request, marshaledResponseConsumer) -> {
			// Delegate to Soklet's internal request handling method
			soklet.handleRequest(request, marshaledResponseConsumer);
		});

		ServerSentEventServer serverSentEventServer = sokletConfiguration.getServerSentEventServer().orElse(null);

		if (serverSentEventServer != null)
			serverSentEventServer.initialize(sokletConfiguration, (request, marshaledResponseConsumer) -> {
				// Delegate to Soklet's internal request handling method
				soklet.handleRequest(request, marshaledResponseConsumer);
			});
	}

	/**
	 * Starts the managed server instance[s].
	 * <p>
	 * If the managed server[s] are already started, this is a no-op.
	 */
	public void start() {
		getLock().lock();

		try {
			if (isStarted())
				return;

			SokletConfiguration sokletConfiguration = getSokletConfiguration();
			LifecycleInterceptor lifecycleInterceptor = sokletConfiguration.getLifecycleInterceptor();
			Server server = sokletConfiguration.getServer();

			lifecycleInterceptor.willStartServer(server);
			server.start();
			lifecycleInterceptor.didStartServer(server);

			ServerSentEventServer serverSentEventServer = sokletConfiguration.getServerSentEventServer().orElse(null);

			if (serverSentEventServer != null) {
				lifecycleInterceptor.willStartServerSentEventServer(serverSentEventServer);
				serverSentEventServer.start();
				lifecycleInterceptor.didStartServerSentEventServer(serverSentEventServer);
			}
		} finally {
			getLock().unlock();
		}
	}

	/**
	 * Stops the managed server instance[s].
	 * <p>
	 * If the managed server[s] are already stopped, this is a no-op.
	 */
	public void stop() {
		getLock().lock();

		try {
			if (!isStarted())
				return;

			SokletConfiguration sokletConfiguration = getSokletConfiguration();
			LifecycleInterceptor lifecycleInterceptor = sokletConfiguration.getLifecycleInterceptor();
			Server server = sokletConfiguration.getServer();

			if (server.isStarted()) {
				lifecycleInterceptor.willStopServer(server);
				server.stop();
				lifecycleInterceptor.didStopServer(server);
			}

			ServerSentEventServer serverSentEventServer = sokletConfiguration.getServerSentEventServer().orElse(null);

			if (serverSentEventServer != null && serverSentEventServer.isStarted()) {
				lifecycleInterceptor.willStopServerSentEventServer(serverSentEventServer);
				serverSentEventServer.stop();
				lifecycleInterceptor.didStopServerSentEventServer(serverSentEventServer);
			}
		} finally {
			getLock().unlock();
		}
	}

	/**
	 * Nonpublic "informal" implementation of {@link com.soklet.core.Server.RequestHandler} so Soklet does not need to expose {@code handleRequest} publicly.
	 * Reasoning: users of this library should never call {@code handleRequest} directly - it should only be invoked in response to events
	 * provided by a {@link Server} or {@link ServerSentEventServer} implementation.
	 */
	protected void handleRequest(@Nonnull Request request,
															 @Nonnull Consumer<RequestResult> requestResultConsumer) {
		requireNonNull(request);
		requireNonNull(requestResultConsumer);

		Instant processingStarted = Instant.now();

		SokletConfiguration sokletConfiguration = getSokletConfiguration();
		ResourceMethodResolver resourceMethodResolver = sokletConfiguration.getResourceMethodResolver();
		ResponseMarshaler responseMarshaler = sokletConfiguration.getResponseMarshaler();
		LifecycleInterceptor lifecycleInterceptor = sokletConfiguration.getLifecycleInterceptor();

		// Holders to permit mutable effectively-final variables
		AtomicReference<MarshaledResponse> marshaledResponseHolder = new AtomicReference<>();
		AtomicReference<Throwable> resourceMethodResolutionExceptionHolder = new AtomicReference<>();
		AtomicReference<Request> requestHolder = new AtomicReference<>(request);
		AtomicReference<ResourceMethod> resourceMethodHolder = new AtomicReference<>();
		AtomicReference<RequestResult> requestResultHolder = new AtomicReference<>();

		// Holders to permit mutable effectively-final state tracking
		AtomicBoolean willStartResponseWritingCompleted = new AtomicBoolean(false);
		AtomicBoolean didFinishResponseWritingCompleted = new AtomicBoolean(false);
		AtomicBoolean didFinishRequestHandlingCompleted = new AtomicBoolean(false);

		List<Throwable> throwables = new ArrayList<>(10);

		Consumer<LogEvent> safelyLog = (logEvent -> {
			try {
				lifecycleInterceptor.didReceiveLogEvent(logEvent);
			} catch (Throwable throwable) {
				throwable.printStackTrace();
				throwables.add(throwable);
			}
		});

		requestHolder.set(request);

		try {
			// Do we have an exact match for this resource method?
			resourceMethodHolder.set(resourceMethodResolver.resourceMethodForRequest(requestHolder.get()).orElse(null));
		} catch (Throwable t) {
			safelyLog.accept(LogEvent.with(LogEventType.RESOURCE_METHOD_RESOLUTION_FAILED, "Unable to resolve Resource Method")
					.throwable(t)
					.request(requestHolder.get())
					.build());

			// If an exception occurs here, keep track of it - we will surface them after letting LifecycleInterceptor
			// see that a request has come in.
			throwables.add(t);
			resourceMethodResolutionExceptionHolder.set(t);
		}

		try {
			lifecycleInterceptor.wrapRequest(request, resourceMethodHolder.get(), (wrappedRequest) -> {
				requestHolder.set(wrappedRequest);

				try {
					lifecycleInterceptor.didStartRequestHandling(requestHolder.get(), resourceMethodHolder.get());
				} catch (Throwable t) {
					safelyLog.accept(LogEvent.with(LogEventType.LIFECYCLE_INTERCEPTOR_DID_START_REQUEST_HANDLING_FAILED,
									format("An exception occurred while invoking %s::didStartRequestHandling",
											LifecycleInterceptor.class.getSimpleName()))
							.throwable(t)
							.request(requestHolder.get())
							.resourceMethod(resourceMethodHolder.get())
							.build());

					throwables.add(t);
				}

				try {
					lifecycleInterceptor.interceptRequest(request, resourceMethodHolder.get(), (interceptorRequest) -> {
						requestHolder.set(interceptorRequest);

						try {
							if (resourceMethodResolutionExceptionHolder.get() != null)
								throw resourceMethodResolutionExceptionHolder.get();

							RequestResult requestResult = toRequestResult(requestHolder.get(), resourceMethodHolder.get());
							requestResultHolder.set(requestResult);

							MarshaledResponse originalMarshaledResponse = requestResult.getMarshaledResponse();
							MarshaledResponse updatedMarshaledResponse = requestResult.getMarshaledResponse();

							// A few special cases that are "global" in that they can affect all requests and
							// need to happen after marshaling the response...

							// 1. Customize response for HEAD (e.g. remove body, set Content-Length header)
							updatedMarshaledResponse = applyHeadResponseIfApplicable(request, updatedMarshaledResponse);

							// 2. Apply other standard response customizations (CORS, Content-Length)
							updatedMarshaledResponse = applyCommonPropertiesToMarshaledResponse(request, updatedMarshaledResponse);

							// Update our result holder with the modified response if necessary
							if (originalMarshaledResponse != updatedMarshaledResponse) {
								marshaledResponseHolder.set(updatedMarshaledResponse);
								requestResultHolder.set(requestResult.copy()
										.marshaledResponse(updatedMarshaledResponse)
										.finish());
							}

							return updatedMarshaledResponse;
						} catch (Throwable t) {
							if (!Objects.equals(t, resourceMethodResolutionExceptionHolder.get())) {
								throwables.add(t);

								safelyLog.accept(LogEvent.with(LogEventType.REQUEST_PROCESSING_FAILED,
												"An exception occurred while processing request")
										.throwable(t)
										.request(requestHolder.get())
										.resourceMethod(resourceMethodHolder.get())
										.build());
							}

							// Unhappy path.  Try to use configuration's exception response marshaler...
							try {
								MarshaledResponse marshaledResponse = responseMarshaler.forThrowable(requestHolder.get(), t, resourceMethodHolder.get());
								marshaledResponse = applyCommonPropertiesToMarshaledResponse(request, marshaledResponse);
								marshaledResponseHolder.set(marshaledResponse);

								return marshaledResponse;
							} catch (Throwable t2) {
								throwables.add(t2);

								safelyLog.accept(LogEvent.with(LogEventType.RESPONSE_MARSHALER_FOR_THROWABLE_FAILED,
												format("An exception occurred while trying to write an exception response for %s", t))
										.throwable(t2)
										.request(requestHolder.get())
										.resourceMethod(resourceMethodHolder.get())
										.build());

								// The configuration's exception response marshaler failed - provide a failsafe response to recover
								return provideFailsafeMarshaledResponse(requestHolder.get(), t2);
							}
						}
					}, (interceptorMarshaledResponse) -> {
						marshaledResponseHolder.set(interceptorMarshaledResponse);
					});
				} catch (Throwable t) {
					throwables.add(t);

					try {
						// In the event that an error occurs during processing of a LifecycleInterceptor method, for example
						safelyLog.accept(LogEvent.with(LogEventType.LIFECYCLE_INTERCEPTOR_INTERCEPT_REQUEST_FAILED,
										format("An exception occurred while invoking %s::interceptRequest", LifecycleInterceptor.class.getSimpleName()))
								.throwable(t)
								.request(requestHolder.get())
								.resourceMethod(resourceMethodHolder.get())
								.build());

						MarshaledResponse marshaledResponse = responseMarshaler.forThrowable(requestHolder.get(), t, resourceMethodHolder.get());
						marshaledResponse = applyCommonPropertiesToMarshaledResponse(request, marshaledResponse);
						marshaledResponseHolder.set(marshaledResponse);
					} catch (Throwable t2) {
						throwables.add(t2);

						safelyLog.accept(LogEvent.with(LogEventType.RESPONSE_MARSHALER_FOR_THROWABLE_FAILED,
										format("An exception occurred while invoking %s::forThrowable when trying to write an exception response for %s", ResponseMarshaler.class.getSimpleName(), t))
								.throwable(t2)
								.request(requestHolder.get())
								.resourceMethod(resourceMethodHolder.get())
								.build());

						marshaledResponseHolder.set(provideFailsafeMarshaledResponse(requestHolder.get(), t2));
					}
				} finally {
					try {
						try {
							lifecycleInterceptor.willStartResponseWriting(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get());
						} finally {
							willStartResponseWritingCompleted.set(true);
						}

						Instant responseWriteStarted = Instant.now();

						try {
							RequestResult requestResult = requestResultHolder.get();

							if (requestResult != null)
								requestResultConsumer.accept(requestResult);
							else
								requestResultConsumer.accept(RequestResult.withMarshaledResponse(marshaledResponseHolder.get())
										.resourceMethod(resourceMethodHolder.get())
										.build());

							Instant responseWriteFinished = Instant.now();
							Duration responseWriteDuration = Duration.between(responseWriteStarted, responseWriteFinished);

							try {
								lifecycleInterceptor.didFinishResponseWriting(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), responseWriteDuration, null);
							} catch (Throwable t) {
								throwables.add(t);

								safelyLog.accept(LogEvent.with(LogEventType.LIFECYCLE_INTERCEPTOR_DID_FINISH_RESPONSE_WRITING_FAILED,
												format("An exception occurred while invoking %s::didFinishResponseWriting",
														LifecycleInterceptor.class.getSimpleName()))
										.throwable(t)
										.request(requestHolder.get())
										.resourceMethod(resourceMethodHolder.get())
										.marshaledResponse(marshaledResponseHolder.get())
										.build());
							} finally {
								didFinishResponseWritingCompleted.set(true);
							}
						} catch (Throwable t) {
							throwables.add(t);

							Instant responseWriteFinished = Instant.now();
							Duration responseWriteDuration = Duration.between(responseWriteStarted, responseWriteFinished);

							try {
								lifecycleInterceptor.didFinishResponseWriting(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), responseWriteDuration, t);
							} catch (Throwable t2) {
								throwables.add(t2);

								safelyLog.accept(LogEvent.with(LogEventType.LIFECYCLE_INTERCEPTOR_DID_FINISH_RESPONSE_WRITING_FAILED,
												format("An exception occurred while invoking %s::didFinishResponseWriting",
														LifecycleInterceptor.class.getSimpleName()))
										.throwable(t2)
										.request(requestHolder.get())
										.resourceMethod(resourceMethodHolder.get())
										.marshaledResponse(marshaledResponseHolder.get())
										.build());
							}
						}
					} finally {
						try {
							Instant processingFinished = Instant.now();
							Duration processingDuration = Duration.between(processingStarted, processingFinished);

							lifecycleInterceptor.didFinishRequestHandling(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), processingDuration, Collections.unmodifiableList(throwables));
						} catch (Throwable t) {
							safelyLog.accept(LogEvent.with(LogEventType.LIFECYCLE_INTERCEPTOR_DID_FINISH_REQUEST_HANDLING_FAILED,
											format("An exception occurred while invoking %s::didFinishRequestHandling",
													LifecycleInterceptor.class.getSimpleName()))
									.throwable(t)
									.request(requestHolder.get())
									.resourceMethod(resourceMethodHolder.get())
									.marshaledResponse(marshaledResponseHolder.get())
									.build());
						} finally {
							didFinishRequestHandlingCompleted.set(true);
						}
					}
				}
			});
		} catch (Throwable t) {
			// If an error occurred during request wrapping, it's possible a response was never written/communicated back to LifecycleInterceptor.
			// Detect that here and inform LifecycleInterceptor accordingly.
			safelyLog.accept(LogEvent.with(LogEventType.LIFECYCLE_INTERCEPTOR_WRAP_REQUEST_FAILED,
							format("An exception occurred while invoking %s::wrapRequest",
									LifecycleInterceptor.class.getSimpleName()))
					.throwable(t)
					.request(requestHolder.get())
					.resourceMethod(resourceMethodHolder.get())
					.marshaledResponse(marshaledResponseHolder.get())
					.build());

			// If we don't have a response, let the marshaler try to make one for the exception.
			// If that fails, use the failsafe.
			if (marshaledResponseHolder.get() == null) {
				try {
					MarshaledResponse marshaledResponse = responseMarshaler.forThrowable(requestHolder.get(), t, resourceMethodHolder.get());
					marshaledResponse = applyCommonPropertiesToMarshaledResponse(request, marshaledResponse);
					marshaledResponseHolder.set(marshaledResponse);
				} catch (Throwable t2) {
					throwables.add(t2);

					safelyLog.accept(LogEvent.with(LogEventType.RESPONSE_MARSHALER_FOR_THROWABLE_FAILED,
									format("An exception occurred during request wrapping while invoking %s::forThrowable",
											ResponseMarshaler.class.getSimpleName()))
							.throwable(t2)
							.request(requestHolder.get())
							.resourceMethod(resourceMethodHolder.get())
							.marshaledResponse(marshaledResponseHolder.get())
							.build());

					marshaledResponseHolder.set(provideFailsafeMarshaledResponse(requestHolder.get(), t));
				}
			}

			if (!willStartResponseWritingCompleted.get()) {
				try {
					lifecycleInterceptor.willStartResponseWriting(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get());
				} catch (Throwable t2) {
					safelyLog.accept(LogEvent.with(LogEventType.LIFECYCLE_INTERCEPTOR_WILL_START_RESPONSE_WRITING_FAILED,
									format("An exception occurred while invoking %s::willStartResponseWriting",
											LifecycleInterceptor.class.getSimpleName()))
							.throwable(t2)
							.request(requestHolder.get())
							.resourceMethod(resourceMethodHolder.get())
							.marshaledResponse(marshaledResponseHolder.get())
							.build());
				}
			}

			try {
				Instant responseWriteStarted = Instant.now();

				if (!didFinishResponseWritingCompleted.get()) {
					try {
						RequestResult requestResult = requestResultHolder.get();

						if (requestResult != null)
							requestResultConsumer.accept(requestResult);
						else
							requestResultConsumer.accept(RequestResult.withMarshaledResponse(marshaledResponseHolder.get())
									.resourceMethod(resourceMethodHolder.get())
									.build());

						Instant responseWriteFinished = Instant.now();
						Duration responseWriteDuration = Duration.between(responseWriteStarted, responseWriteFinished);

						try {
							lifecycleInterceptor.didFinishResponseWriting(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), responseWriteDuration, null);
						} catch (Throwable t2) {
							throwables.add(t2);

							safelyLog.accept(LogEvent.with(LogEventType.LIFECYCLE_INTERCEPTOR_DID_FINISH_RESPONSE_WRITING_FAILED,
											format("An exception occurred while invoking %s::didFinishResponseWriting",
													LifecycleInterceptor.class.getSimpleName()))
									.throwable(t2)
									.request(requestHolder.get())
									.resourceMethod(resourceMethodHolder.get())
									.marshaledResponse(marshaledResponseHolder.get())
									.build());
						}
					} catch (Throwable t2) {
						throwables.add(t2);

						Instant responseWriteFinished = Instant.now();
						Duration responseWriteDuration = Duration.between(responseWriteStarted, responseWriteFinished);

						try {
							lifecycleInterceptor.didFinishResponseWriting(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), responseWriteDuration, t);
						} catch (Throwable t3) {
							throwables.add(t3);

							safelyLog.accept(LogEvent.with(LogEventType.LIFECYCLE_INTERCEPTOR_DID_FINISH_RESPONSE_WRITING_FAILED,
											format("An exception occurred while invoking %s::didFinishResponseWriting",
													LifecycleInterceptor.class.getSimpleName()))
									.throwable(t3)
									.request(requestHolder.get())
									.resourceMethod(resourceMethodHolder.get())
									.marshaledResponse(marshaledResponseHolder.get())
									.build());
						}
					}
				}
			} finally {
				if (!didFinishRequestHandlingCompleted.get()) {
					try {
						Instant processingFinished = Instant.now();
						Duration processingDuration = Duration.between(processingStarted, processingFinished);

						lifecycleInterceptor.didFinishRequestHandling(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), processingDuration, Collections.unmodifiableList(throwables));
					} catch (Throwable t2) {
						safelyLog.accept(LogEvent.with(LogEventType.LIFECYCLE_INTERCEPTOR_DID_FINISH_REQUEST_HANDLING_FAILED,
										format("An exception occurred while invoking %s::didFinishRequestHandling",
												LifecycleInterceptor.class.getSimpleName()))
								.throwable(t2)
								.request(requestHolder.get())
								.resourceMethod(resourceMethodHolder.get())
								.marshaledResponse(marshaledResponseHolder.get())
								.build());
					}
				}
			}
		}
	}

	@Nonnull
	protected RequestResult toRequestResult(@Nonnull Request request,
																					@Nullable ResourceMethod resourceMethod) throws Throwable {
		ResourceMethodParameterProvider resourceMethodParameterProvider = getSokletConfiguration().getResourceMethodParameterProvider();
		InstanceProvider instanceProvider = getSokletConfiguration().getInstanceProvider();
		CorsAuthorizer corsAuthorizer = getSokletConfiguration().getCorsAuthorizer();
		ResourceMethodResolver resourceMethodResolver = getSokletConfiguration().getResourceMethodResolver();
		ResponseMarshaler responseMarshaler = getSokletConfiguration().getResponseMarshaler();
		CorsPreflight corsPreflight = request.getCorsPreflight().orElse(null);

		// Special short-circuit for big requests
		if (request.isContentTooLarge())
			return RequestResult.withMarshaledResponse(responseMarshaler.forContentTooLarge(request, resourceMethodResolver.resourceMethodForRequest(request).orElse(null)))
					.resourceMethod(resourceMethod)
					.build();

		// No resource method was found for this HTTP method and path.
		if (resourceMethod == null) {
			// If this was an OPTIONS request, do special processing.
			// If not, figure out if we should return a 404 or 405.
			if (request.getHttpMethod() == HttpMethod.OPTIONS) {
				// See what methods are available to us for this request's path
				Map<HttpMethod, ResourceMethod> matchingResourceMethodsByHttpMethod = resolveMatchingResourceMethodsByHttpMethod(request, resourceMethodResolver);

				// Special handling for CORS preflight requests, if needed
				if (corsPreflight != null) {
					// Let configuration function determine if we should authorize this request.
					// Discard any OPTIONS references - see https://stackoverflow.com/a/68529748
					Map<HttpMethod, ResourceMethod> nonOptionsMatchingResourceMethodsByHttpMethod = matchingResourceMethodsByHttpMethod.entrySet().stream()
							.filter(entry -> entry.getKey() != HttpMethod.OPTIONS)
							.collect(Collectors.toMap(Entry::getKey, Entry::getValue));

					CorsPreflightResponse corsPreflightResponse = corsAuthorizer.authorizePreflight(request, corsPreflight, nonOptionsMatchingResourceMethodsByHttpMethod).orElse(null);

					// Allow or reject CORS depending on what the function said to do
					if (corsPreflightResponse != null) {
						// Allow
						MarshaledResponse marshaledResponse = responseMarshaler.forCorsPreflightAllowed(request, corsPreflight, corsPreflightResponse);

						return RequestResult.withMarshaledResponse(marshaledResponse)
								.corsPreflightResponse(corsPreflightResponse)
								.resourceMethod(resourceMethod)
								.build();
					}

					// Reject
					return RequestResult.withMarshaledResponse(responseMarshaler.forCorsPreflightRejected(request, corsPreflight))
							.resourceMethod(resourceMethod)
							.build();
				} else {
					// Just a normal OPTIONS response (non-CORS-preflight).
					// If there's a matching OPTIONS resource method for this OPTIONS request, then invoke it.
					ResourceMethod optionsResourceMethod = matchingResourceMethodsByHttpMethod.get(HttpMethod.OPTIONS);

					if (optionsResourceMethod != null) {
						resourceMethod = optionsResourceMethod;
					} else {
						// Ensure OPTIONS is always present in the map, even if there is no explicit matching resource method for it
						if (!matchingResourceMethodsByHttpMethod.containsKey(HttpMethod.OPTIONS))
							matchingResourceMethodsByHttpMethod.put(HttpMethod.OPTIONS, null);

						// Ensure HEAD is always present in the map, even if there is no explicit matching resource method for it
						if (!matchingResourceMethodsByHttpMethod.containsKey(HttpMethod.HEAD))
							matchingResourceMethodsByHttpMethod.put(HttpMethod.HEAD, null);

						return RequestResult.withMarshaledResponse(responseMarshaler.forOptions(request, matchingResourceMethodsByHttpMethod.keySet()))
								.resourceMethod(resourceMethod)
								.build();
					}
				}
			} else if (request.getHttpMethod() == HttpMethod.HEAD) {
				// If there's a matching GET resource method for this HEAD request, then invoke it
				Request headGetRequest = Request.with(HttpMethod.GET, request.getUri()).build();
				ResourceMethod headGetResourceMethod = resourceMethodResolver.resourceMethodForRequest(headGetRequest).orElse(null);

				if (headGetResourceMethod != null)
					resourceMethod = headGetResourceMethod;
				else
					return RequestResult.withMarshaledResponse(responseMarshaler.forNotFound(request))
							.resourceMethod(resourceMethod)
							.build();
			} else {
				// Not an OPTIONS request, so it's possible we have a 405. See if other HTTP methods match...
				Map<HttpMethod, ResourceMethod> otherMatchingResourceMethodsByHttpMethod = resolveMatchingResourceMethodsByHttpMethod(request, resourceMethodResolver);

				Set<HttpMethod> matchingNonOptionsHttpMethods = otherMatchingResourceMethodsByHttpMethod.keySet().stream()
						.filter(httpMethod -> httpMethod != HttpMethod.OPTIONS)
						.collect(Collectors.toSet());

				// Ensure OPTIONS is always present in the map, even if there is no explicit matching resource method for it
				if (!otherMatchingResourceMethodsByHttpMethod.containsKey(HttpMethod.OPTIONS))
					otherMatchingResourceMethodsByHttpMethod.put(HttpMethod.OPTIONS, null);

				// Ensure HEAD is always present in the map, even if there is no explicit matching resource method for it
				if (!otherMatchingResourceMethodsByHttpMethod.containsKey(HttpMethod.HEAD))
					otherMatchingResourceMethodsByHttpMethod.put(HttpMethod.HEAD, null);

				if (matchingNonOptionsHttpMethods.size() > 0) {
					// ...if some do, it's a 405
					return RequestResult.withMarshaledResponse(responseMarshaler.forMethodNotAllowed(request, otherMatchingResourceMethodsByHttpMethod.keySet()))
							.resourceMethod(resourceMethod)
							.build();
				} else {
					// no matching resource method found, it's a 404
					return RequestResult.withMarshaledResponse(responseMarshaler.forNotFound(request))
							.resourceMethod(resourceMethod)
							.build();
				}
			}
		}

		// Found a resource method - happy path.
		// 1. Get an instance of the resource class
		// 2. Get values to pass to the resource method on the resource class
		// 3. Invoke the resource method and use its return value to drive a response
		Class<?> resourceClass = resourceMethod.getMethod().getDeclaringClass();
		Object resourceClassInstance;

		try {
			resourceClassInstance = instanceProvider.provide(resourceClass);
		} catch (Exception e) {
			throw new IllegalArgumentException(format("Unable to acquire an instance of %s", resourceClass.getName()), e);
		}

		List<Object> parameterValues = resourceMethodParameterProvider.parameterValuesForResourceMethod(request, resourceMethod);

		Object responseObject;

		try {
			responseObject = resourceMethod.getMethod().invoke(resourceClassInstance, parameterValues.toArray());
		} catch (InvocationTargetException e) {
			if (e.getTargetException() != null)
				throw e.getTargetException();

			throw e;
		}

		// Unwrap the Optional<T>, if one exists.  We do not recurse deeper than one level
		if (responseObject instanceof Optional<?>)
			responseObject = ((Optional<?>) responseObject).orElse(null);

		Response response;

		// If null/void return, it's a 204
		// If it's a MarshaledResponse object, no marshaling + return it immediately - caller knows exactly what it wants to write.
		// If it's a Response object, use as is.
		// If it's a non-Response type of object, assume it's the response body and wrap in a Response.
		if (responseObject == null)
			response = Response.withStatusCode(204).build();
		else if (responseObject instanceof MarshaledResponse)
			return RequestResult.withMarshaledResponse((MarshaledResponse) responseObject)
					.resourceMethod(resourceMethod)
					.build();
		else if (responseObject instanceof Response)
			response = (Response) responseObject;
		else
			response = Response.withStatusCode(200).body(responseObject).build();

		MarshaledResponse marshaledResponse = responseMarshaler.forHappyPath(request, response, resourceMethod);

		return RequestResult.withMarshaledResponse(marshaledResponse)
				.response(response)
				.resourceMethod(resourceMethod)
				.build();
	}

	@Nonnull
	protected MarshaledResponse applyHeadResponseIfApplicable(@Nonnull Request request,
																														@Nonnull MarshaledResponse marshaledResponse) {
		if (request.getHttpMethod() != HttpMethod.HEAD)
			return marshaledResponse;

		return getSokletConfiguration().getResponseMarshaler().forHead(request, marshaledResponse);
	}

	// Hat tip to Aslan Parçası and GrayStar
	@Nonnull
	protected MarshaledResponse applyCommonPropertiesToMarshaledResponse(@Nonnull Request request,
																																			 @Nonnull MarshaledResponse marshaledResponse) {
		requireNonNull(request);
		requireNonNull(marshaledResponse);

		marshaledResponse = applyContentLengthIfApplicable(request, marshaledResponse);
		marshaledResponse = applyCorsResponseIfApplicable(request, marshaledResponse);

		return marshaledResponse;
	}

	@Nonnull
	protected MarshaledResponse applyContentLengthIfApplicable(@Nonnull Request request,
																														 @Nonnull MarshaledResponse marshaledResponse) {
		requireNonNull(request);
		requireNonNull(marshaledResponse);

		Set<String> normalizedHeaderNames = marshaledResponse.getHeaders().keySet().stream()
				.map(headerName -> headerName.toLowerCase(Locale.US))
				.collect(Collectors.toSet());

		// If Content-Length is already specified, don't do anything
		if (normalizedHeaderNames.contains("content-length"))
			return marshaledResponse;

		// If Content-Length is not specified, specify as the number of bytes in the body
		return marshaledResponse.copy()
				.headers((mutableHeaders) -> {
					String contentLengthHeaderValue = String.valueOf(marshaledResponse.getBody().orElse(emptyByteArray()).length);
					mutableHeaders.put("Content-Length", Set.of(contentLengthHeaderValue));
				}).finish();
	}

	@Nonnull
	protected MarshaledResponse applyCorsResponseIfApplicable(@Nonnull Request request,
																														@Nonnull MarshaledResponse marshaledResponse) {
		requireNonNull(request);
		requireNonNull(marshaledResponse);

		Cors cors = request.getCors().orElse(null);

		// If non-CORS request, nothing further to do (note that CORS preflight was handled earlier)
		if (cors == null)
			return marshaledResponse;

		CorsAuthorizer corsAuthorizer = getSokletConfiguration().getCorsAuthorizer();

		// Does the authorizer say we are authorized?
		CorsResponse corsResponse = corsAuthorizer.authorize(request, cors).orElse(null);

		// Not authorized - don't apply CORS headers to the response
		if (corsResponse == null)
			return marshaledResponse;

		// Authorized - OK, let's apply the headers to the response
		return getSokletConfiguration().getResponseMarshaler().forCorsAllowed(request, cors, corsResponse, marshaledResponse);
	}

	@Nonnull
	protected Map<HttpMethod, ResourceMethod> resolveMatchingResourceMethodsByHttpMethod(@Nonnull Request request,
																																											 @Nonnull ResourceMethodResolver resourceMethodResolver) {
		requireNonNull(request);
		requireNonNull(resourceMethodResolver);

		Map<HttpMethod, ResourceMethod> matchingResourceMethodsByHttpMethod = new LinkedHashMap<>(HttpMethod.values().length);

		for (HttpMethod httpMethod : HttpMethod.values()) {
			Request otherRequest = Request.with(httpMethod, request.getUri()).build();
			ResourceMethod resourceMethod = resourceMethodResolver.resourceMethodForRequest(otherRequest).orElse(null);

			if (resourceMethod != null)
				matchingResourceMethodsByHttpMethod.put(httpMethod, resourceMethod);
		}

		return matchingResourceMethodsByHttpMethod;
	}

	@Nonnull
	protected MarshaledResponse provideFailsafeMarshaledResponse(@Nonnull Request request,
																															 @Nonnull Throwable throwable) {
		requireNonNull(request);
		requireNonNull(throwable);

		Integer statusCode = 500;
		Charset charset = StandardCharsets.UTF_8;

		return MarshaledResponse.withStatusCode(statusCode)
				.headers(Map.of("Content-Type", Set.of(format("text/plain; charset=%s", charset.name()))))
				.body(format("HTTP %d: %s", statusCode, StatusCode.fromStatusCode(statusCode).get().getReasonPhrase()).getBytes(charset))
				.build();
	}

	/**
	 * Synonym for {@link #stop()}.
	 */
	@Override
	public void close() {
		stop();
	}

	/**
	 * Is either the managed {@link Server} or {@link ServerSentEventServer} started?
	 *
	 * @return {@code true} if at least one is started, {@code false} otherwise
	 */
	@Nonnull
	public Boolean isStarted() {
		getLock().lock();

		try {
			if (getSokletConfiguration().getServer().isStarted())
				return true;

			ServerSentEventServer serverSentEventServer = getSokletConfiguration().getServerSentEventServer().orElse(null);
			return serverSentEventServer != null && serverSentEventServer.isStarted();
		} finally {
			getLock().unlock();
		}
	}

	/**
	 * Runs Soklet with a special "simulator" server that is useful for integration testing.
	 * <p>
	 * See <a href="https://www.soklet.com/docs/automated-testing">https://www.soklet.com/docs/automated-testing</a> for how to write these tests.
	 *
	 * @param sokletConfiguration configuration that drives the Soklet system
	 * @param simulatorConsumer   code to execute within the context of the simulator
	 */
	public static void runSimulator(@Nonnull SokletConfiguration sokletConfiguration,
																	@Nonnull Consumer<Simulator> simulatorConsumer) {
		requireNonNull(sokletConfiguration);
		requireNonNull(simulatorConsumer);

		MockServer server = new MockServer();
		MockServerSentEventServer serverSentEventServer = new MockServerSentEventServer();

		SokletConfiguration mockConfiguration = sokletConfiguration.copy()
				.server(server)
				.serverSentEventServer(serverSentEventServer)
				.finish();

		Simulator simulator = new DefaultSimulator(server, serverSentEventServer);

		try (Soklet soklet = Soklet.withConfiguration(mockConfiguration)) {
			soklet.start();
			simulatorConsumer.accept(simulator);
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Nonnull
	protected SokletConfiguration getSokletConfiguration() {
		return this.sokletConfiguration;
	}

	@Nonnull
	protected ReentrantLock getLock() {
		return this.lock;
	}

	@ThreadSafe
	static class DefaultSimulator implements Simulator {
		@Nullable
		private MockServer server;
		@Nullable
		private MockServerSentEventServer serverSentEventServer;

		public DefaultSimulator(@Nonnull MockServer server,
														@Nonnull MockServerSentEventServer serverSentEventServer) {
			requireNonNull(server);
			requireNonNull(serverSentEventServer);

			this.server = server;
			this.serverSentEventServer = serverSentEventServer;
		}

		@Nonnull
		@Override
		public RequestResult performRequest(@Nonnull Request request) {
			AtomicReference<RequestResult> requestResultHolder = new AtomicReference<>();
			Server.RequestHandler requestHandler = getServer().getRequestHandler().orElse(null);

			if (requestHandler == null)
				throw new IllegalStateException("You must register a request handler prior to simulating requests");

			requestHandler.handleRequest(request, (requestResult -> {
				requestResultHolder.set(requestResult);
			}));

			return requestResultHolder.get();
		}

		@Override
		public void registerServerSentEventConsumer(@Nonnull ResourcePath resourcePath,
																								@Nonnull Consumer<ServerSentEvent> serverSentEventConsumer) {
			requireNonNull(resourcePath);
			requireNonNull(serverSentEventConsumer);

			// Delegate to the mock SSE server
			getServerSentEventServer().registerServerSentEventConsumer(resourcePath, serverSentEventConsumer);
		}

		@Nonnull
		@Override
		public ServerSentEventBroadcaster acquireServerSentEventBroadcaster(@Nonnull ResourcePath resourcePath) {
			requireNonNull(resourcePath);

			// Delegate to the mock SSE server.
			// We know the mock will always provide us with a broadcaster, so it's safe to immediately "get" the result
			return getServerSentEventServer().acquireBroadcaster(resourcePath).get();
		}

		@Nullable
		protected MockServer getServer() {
			return this.server;
		}

		@Nullable
		protected MockServerSentEventServer getServerSentEventServer() {
			return this.serverSentEventServer;
		}
	}

	/**
	 * Mock server that doesn't touch the network at all, useful for testing.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	static class MockServer implements Server {
		@Nullable
		private SokletConfiguration sokletConfiguration;
		@Nullable
		private Server.RequestHandler requestHandler;

		@Override
		public void start() {
			// No-op
		}

		@Override
		public void stop() {
			// No-op
		}

		@Nonnull
		@Override
		public Boolean isStarted() {
			return true;
		}

		@Override
		public void initialize(@Nonnull SokletConfiguration sokletConfiguration,
													 @Nonnull RequestHandler requestHandler) {
			requireNonNull(sokletConfiguration);
			requireNonNull(requestHandler);

			this.requestHandler = requestHandler;
		}

		@Nonnull
		protected Optional<SokletConfiguration> getSokletConfiguration() {
			return Optional.ofNullable(this.sokletConfiguration);
		}

		@Nonnull
		protected Optional<RequestHandler> getRequestHandler() {
			return Optional.ofNullable(this.requestHandler);
		}
	}

	/**
	 * Mock Server-Sent Event broadcaster that doesn't touch the network at all, useful for testing.
	 */
	@ThreadSafe
	static class MockServerSentEventBroadcaster implements ServerSentEventBroadcaster {
		@Nonnull
		private final ResourcePath resourcePath;
		@Nonnull
		private final Set<Consumer<ServerSentEvent>> serverSentEventConsumers;

		public MockServerSentEventBroadcaster(@Nonnull ResourcePath resourcePath) {
			requireNonNull(resourcePath);

			this.resourcePath = resourcePath;
			this.serverSentEventConsumers = ConcurrentHashMap.newKeySet();
		}

		@Nonnull
		@Override
		public ResourcePath getResourcePath() {
			return this.resourcePath;
		}

		@Nonnull
		@Override
		public Long getClientCount() {
			return Long.valueOf(getServerSentEventConsumers().size());
		}

		@Override
		public void broadcast(@Nonnull ServerSentEvent serverSentEvent) {
			requireNonNull(serverSentEvent);

			for (Consumer<ServerSentEvent> serverSentEventConsumer : getServerSentEventConsumers()) {
				try {
					serverSentEventConsumer.accept(serverSentEvent);
				} catch (Throwable throwable) {
					// TODO: revisit this - should we communicate back exceptions, and should we fire these on separate threads for "realism" (probably not)?
					throwable.printStackTrace();
				}
			}
		}

		@Nonnull
		public Boolean registerServerSentEventConsumer(@Nonnull Consumer<ServerSentEvent> serverSentEventConsumer) {
			requireNonNull(serverSentEventConsumer);
			return getServerSentEventConsumers().add(serverSentEventConsumer);
		}

		@Nonnull
		protected Set<Consumer<ServerSentEvent>> getServerSentEventConsumers() {
			return this.serverSentEventConsumers;
		}
	}

	/**
	 * Mock Server-Sent Event server that doesn't touch the network at all, useful for testing.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	static class MockServerSentEventServer implements ServerSentEventServer {
		@Nullable
		private SokletConfiguration sokletConfiguration;
		@Nullable
		private ServerSentEventServer.RequestHandler requestHandler;
		@Nonnull
		private final ConcurrentHashMap<ResourcePath, MockServerSentEventBroadcaster> broadcastersByResourcePath;

		public MockServerSentEventServer() {
			this.broadcastersByResourcePath = new ConcurrentHashMap<>();
		}

		@Override
		public void start() {
			// No-op
		}

		@Override
		public void stop() {
			// No-op
		}

		@Nonnull
		@Override
		public Boolean isStarted() {
			return true;
		}

		@Nonnull
		@Override
		public Optional<? extends ServerSentEventBroadcaster> acquireBroadcaster(@Nullable ResourcePath resourcePath) {
			if (resourcePath == null)
				return Optional.empty();

			MockServerSentEventBroadcaster broadcaster = getBroadcastersByResourcePath()
					.computeIfAbsent(resourcePath, rp -> new MockServerSentEventBroadcaster(rp));

			return Optional.of(broadcaster);
		}

		public void registerServerSentEventConsumer(@Nonnull ResourcePath resourcePath,
																								@Nonnull Consumer<ServerSentEvent> serverSentEventConsumer) {
			requireNonNull(resourcePath);
			requireNonNull(serverSentEventConsumer);

			MockServerSentEventBroadcaster broadcaster = getBroadcastersByResourcePath()
					.computeIfAbsent(resourcePath, rp -> new MockServerSentEventBroadcaster(rp));

			broadcaster.registerServerSentEventConsumer(serverSentEventConsumer);
		}

		@Override
		public void initialize(@Nonnull SokletConfiguration sokletConfiguration,
													 @Nonnull ServerSentEventServer.RequestHandler requestHandler) {
			requireNonNull(sokletConfiguration);
			requireNonNull(requestHandler);

			this.sokletConfiguration = sokletConfiguration;
			this.requestHandler = requestHandler;
		}

		@Nullable
		protected Optional<SokletConfiguration> getSokletConfiguration() {
			return Optional.ofNullable(this.sokletConfiguration);
		}

		@Nullable
		protected Optional<ServerSentEventServer.RequestHandler> getRequestHandler() {
			return Optional.ofNullable(this.requestHandler);
		}

		@Nonnull
		protected ConcurrentHashMap<ResourcePath, MockServerSentEventBroadcaster> getBroadcastersByResourcePath() {
			return this.broadcastersByResourcePath;
		}
	}
}
