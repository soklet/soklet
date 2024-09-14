/*
 * Copyright 2022-2024 Revetware LLC.
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
import com.soklet.core.RequestHandler;
import com.soklet.core.ResourceMethod;
import com.soklet.core.ResourceMethodParameterProvider;
import com.soklet.core.ResourceMethodResolver;
import com.soklet.core.Response;
import com.soklet.core.ResponseMarshaler;
import com.soklet.core.Server;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.soklet.core.Utilities.emptyByteArray;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Soklet's main class - manages a {@link Server} using the provided system configuration.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class Soklet implements AutoCloseable, RequestHandler {
	@Nonnull
	private final SokletConfiguration sokletConfiguration;
	@Nonnull
	private final ReentrantLock lock;

	/**
	 * Creates a Soklet instance with the given configuration.
	 *
	 * @param sokletConfiguration configuration that drives the Soklet system
	 */
	public Soklet(@Nonnull SokletConfiguration sokletConfiguration) {
		requireNonNull(sokletConfiguration);

		this.sokletConfiguration = sokletConfiguration;
		this.lock = new ReentrantLock();

		// Fail fast in the event that Soklet appears misconfigured
		if (sokletConfiguration.getResourceMethodResolver().getResourceMethods().size() == 0)
			throw new IllegalArgumentException(format("No classes annotated with @%s were found.", Resource.class.getSimpleName()));

		sokletConfiguration.getServer().registerRequestHandler(this);
	}

	/**
	 * Starts the managed server instance.
	 * <p>
	 * If the server is already started, this is a no-op.
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
		} finally {
			getLock().unlock();
		}
	}

	/**
	 * Stops the managed server instance.
	 * <p>
	 * If the server is already stopped, this is a no-op.
	 */
	public void stop() {
		getLock().lock();

		try {
			if (!isStarted())
				return;

			SokletConfiguration sokletConfiguration = getSokletConfiguration();
			LifecycleInterceptor lifecycleInterceptor = sokletConfiguration.getLifecycleInterceptor();
			Server server = sokletConfiguration.getServer();

			lifecycleInterceptor.willStopServer(server);
			server.stop();
			lifecycleInterceptor.didStopServer(server);
		} finally {
			getLock().unlock();
		}
	}

	/**
	 * Performs request handling - <strong>this method should not be called directly unless you are implementing your own instance of {@link Server}</strong>.
	 *
	 * @param request                   the request to handle
	 * @param marshaledResponseConsumer the sink for marshaled responses (to write back to clients over the wire)
	 */
	@Override
	public void handleRequest(@Nonnull Request request,
														@Nonnull Consumer<MarshaledResponse> marshaledResponseConsumer) {
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

		// Holders to permit mutable effectively-final state tracking
		AtomicBoolean willStartResponseWritingCompleted = new AtomicBoolean(false);
		AtomicBoolean didFinishResponseWritingCompleted = new AtomicBoolean(false);
		AtomicBoolean didFinishRequestHandlingCompleted = new AtomicBoolean(false);

		List<Throwable> throwables = new ArrayList<>(10);

		Consumer<LogEvent> safelyLog = (logEvent -> {
			try {
				lifecycleInterceptor.didReceiveLogEvent(logEvent);
			} catch (Throwable t) {
				throwables.add(t);
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

							MarshaledResponse marshaledResponse = toMarshaledResponse(requestHolder.get(), resourceMethodHolder.get());

							// A few special cases that are "global" in that they can affect all requests and
							// need to happen after marshaling the response...

							// 1. Customize response for HEAD (e.g. remove body, set Content-Length header)
							marshaledResponse = applyHeadResponseIfApplicable(request, marshaledResponse);

							// 2. Write any CORS-related headers
							marshaledResponse = applyCorsResponseIfApplicable(request, marshaledResponse);

							// 3. If there is no Content-Length header already applied, apply it
							marshaledResponse = applyContentLengthIfApplicable(request, marshaledResponse);

							return marshaledResponse;
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
								return responseMarshaler.forThrowable(requestHolder.get(), t, resourceMethodHolder.get());
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

						marshaledResponseHolder.set(responseMarshaler.forThrowable(requestHolder.get(), t, resourceMethodHolder.get()));
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
							marshaledResponseConsumer.accept(marshaledResponseHolder.get());

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
					marshaledResponseHolder.set(responseMarshaler.forThrowable(requestHolder.get(), t, resourceMethodHolder.get()));
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
						marshaledResponseConsumer.accept(marshaledResponseHolder.get());

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
	protected MarshaledResponse toMarshaledResponse(@Nonnull Request request,
																									@Nullable ResourceMethod resourceMethod) throws Throwable {
		ResourceMethodParameterProvider resourceMethodParameterProvider = getSokletConfiguration().getResourceMethodParameterProvider();
		InstanceProvider instanceProvider = getSokletConfiguration().getInstanceProvider();
		CorsAuthorizer corsAuthorizer = getSokletConfiguration().getCorsAuthorizer();
		ResourceMethodResolver resourceMethodResolver = getSokletConfiguration().getResourceMethodResolver();
		ResponseMarshaler responseMarshaler = getSokletConfiguration().getResponseMarshaler();
		CorsPreflight corsPreflight = request.getCorsPreflight().orElse(null);

		// Special short-circuit for big requests
		if (request.isContentTooLarge())
			return responseMarshaler.forContentTooLarge(request, resourceMethodResolver.resourceMethodForRequest(request).orElse(null));

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
					if (corsPreflightResponse != null)
						return responseMarshaler.forCorsPreflightAllowed(request, corsPreflight, corsPreflightResponse);
					else
						return responseMarshaler.forCorsPreflightRejected(request, corsPreflight);
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

						return responseMarshaler.forOptions(request, matchingResourceMethodsByHttpMethod.keySet());
					}
				}
			} else if (request.getHttpMethod() == HttpMethod.HEAD) {
				// If there's a matching GET resource method for this HEAD request, then invoke it
				Request headGetRequest = Request.with(HttpMethod.GET, request.getUri()).build();
				ResourceMethod headGetResourceMethod = resourceMethodResolver.resourceMethodForRequest(headGetRequest).orElse(null);

				if (headGetResourceMethod != null)
					resourceMethod = headGetResourceMethod;
				else
					return responseMarshaler.forNotFound(request);
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
					return responseMarshaler.forMethodNotAllowed(request, otherMatchingResourceMethodsByHttpMethod.keySet());
				} else {
					// no matching resource method found, it's a 404
					return responseMarshaler.forNotFound(request);
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
			return (MarshaledResponse) responseObject;
		else if (responseObject instanceof Response)
			response = (Response) responseObject;
		else
			response = Response.withStatusCode(200).body(responseObject).build();

		return responseMarshaler.forHappyPath(request, response, resourceMethod);
	}

	@Nonnull
	protected MarshaledResponse applyHeadResponseIfApplicable(@Nonnull Request request,
																														@Nonnull MarshaledResponse marshaledResponse) {
		if (request.getHttpMethod() != HttpMethod.HEAD)
			return marshaledResponse;

		return getSokletConfiguration().getResponseMarshaler().forHead(request, marshaledResponse);
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
	 * Is the managed server instance started?
	 *
	 * @return {@code true} if started, {@code false} otherwise
	 */
	@Nonnull
	public Boolean isStarted() {
		getLock().lock();

		try {
			return getSokletConfiguration().getServer().isStarted();
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

		MockServer mockServer = new MockServer();

		SokletConfiguration mockConfiguration = sokletConfiguration.copy()
				.server(mockServer)
				.finish();

		try (Soklet soklet = new Soklet(mockConfiguration)) {
			soklet.start();
			simulatorConsumer.accept(mockServer);
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

	/**
	 * Mock server that doesn't touch the network at all, useful for testing.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	static class MockServer implements Server, Simulator {
		@Nullable
		private RequestHandler requestHandler;

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
		public MarshaledResponse performRequest(@Nonnull Request request) {
			AtomicReference<MarshaledResponse> marshaledResponseHolder = new AtomicReference<>();
			RequestHandler requestHandler = getRequestHandler().orElse(null);

			if (requestHandler == null)
				throw new IllegalStateException("You must register a request handler prior to simulating requests");

			requestHandler.handleRequest(request, (marshaledResponse -> {
				marshaledResponseHolder.set(marshaledResponse);
			}));

			return marshaledResponseHolder.get();
		}

		@Override
		public void registerRequestHandler(@Nullable RequestHandler requestHandler) {
			this.requestHandler = requestHandler;
		}

		@Nullable
		protected Optional<RequestHandler> getRequestHandler() {
			return Optional.ofNullable(this.requestHandler);
		}
	}
}
