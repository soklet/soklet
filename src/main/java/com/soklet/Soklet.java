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

import com.soklet.core.Cors;
import com.soklet.core.CorsAuthorizer;
import com.soklet.core.CorsPreflightResponse;
import com.soklet.core.CorsResponse;
import com.soklet.core.HttpMethod;
import com.soklet.core.InstanceProvider;
import com.soklet.core.LifecycleInterceptor;
import com.soklet.core.LogHandler;
import com.soklet.core.MarshaledResponse;
import com.soklet.core.Request;
import com.soklet.core.RequestHandler;
import com.soklet.core.ResourceMethod;
import com.soklet.core.ResourceMethodParameterProvider;
import com.soklet.core.ResourceMethodResolver;
import com.soklet.core.Response;
import com.soklet.core.ResponseMarshaler;
import com.soklet.core.Server;
import com.soklet.core.StatusCode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class Soklet implements AutoCloseable, RequestHandler {
	@Nonnull
	private final SokletConfiguration sokletConfiguration;
	@Nonnull
	private final ReentrantLock lock;

	public Soklet(@Nonnull SokletConfiguration sokletConfiguration) {
		requireNonNull(sokletConfiguration);

		this.sokletConfiguration = sokletConfiguration;
		this.lock = new ReentrantLock();

		sokletConfiguration.getServer().registerRequestHandler(this);
	}

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

	@Override
	public void handleRequest(@Nonnull Request request,
														@Nonnull Consumer<MarshaledResponse> marshaledResponseConsumer) {
		Instant processingStarted = Instant.now();

		SokletConfiguration sokletConfiguration = getSokletConfiguration();
		ResourceMethodResolver resourceMethodResolver = sokletConfiguration.getResourceMethodResolver();
		ResponseMarshaler responseMarshaler = sokletConfiguration.getResponseMarshaler();
		LifecycleInterceptor lifecycleInterceptor = sokletConfiguration.getLifecycleInterceptor();
		LogHandler logHandler = getSokletConfiguration().getLogHandler();

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

		requestHolder.set(request);

		try {
			// Do we have an exact match for this resource method?
			resourceMethodHolder.set(resourceMethodResolver.resourceMethodForRequest(requestHolder.get()).orElse(null));
		} catch (Throwable t) {
			// If an exception occurs here, keep track of it - we will surface them after letting LifecycleInterceptor
			// see that a request has come in.
			throwables.add(t);
			resourceMethodResolutionExceptionHolder.set(t);
		}

		try {
			lifecycleInterceptor.wrapRequest(request, resourceMethodHolder.get(), () -> {
				try {
					lifecycleInterceptor.didStartRequestHandling(requestHolder.get(), resourceMethodHolder.get());
				} catch (Throwable t) {
					logHandler.logError(format("An exception occurred while invoking %s#didStartRequestHandling when processing %s",
							LifecycleInterceptor.class.getSimpleName(), requestHolder.get()), t);
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
							throwables.add(t);
							logHandler.logError(format("An exception occurred while processing %s", request), t);

							// Unhappy path.  Try to use configuration's exception response marshaler...
							try {
								return responseMarshaler.forException(requestHolder.get(), t, resourceMethodHolder.get());
							} catch (Throwable t2) {
								throwables.add(t2);
								logHandler.logError(format("An exception occurred while trying to write an exception response for %s while processing %s", t, requestHolder.get()), t2);
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
						logHandler.logError(format("An exception occurred while processing %s", requestHolder.get()), t);
						marshaledResponseHolder.set(responseMarshaler.forException(requestHolder.get(), t, resourceMethodHolder.get()));
					} catch (Throwable t2) {
						throwables.add(t2);
						logHandler.logError(format("An exception occurred when writing a response while processing %s", requestHolder.get()), t2);
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
								logHandler.logError(format("An exception occurred while invoking %s#didFinishResponseWriting when processing %s",
										LifecycleInterceptor.class.getSimpleName(), requestHolder.get()), t);
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
								logHandler.logError(format("An exception occurred while invoking %s#didFinishResponseWriting when processing %s",
										LifecycleInterceptor.class.getSimpleName(), requestHolder.get()), t2);
							}
						}
					} finally {
						try {
							Instant processingFinished = Instant.now();
							Duration processingDuration = Duration.between(processingStarted, processingFinished);

							lifecycleInterceptor.didFinishRequestHandling(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), processingDuration, Collections.unmodifiableList(throwables));
						} catch (Throwable t) {
							logHandler.logError(format("An exception occurred while invoking %s.didFinishRequestProcessing() when processing %s",
									LifecycleInterceptor.class.getSimpleName(), requestHolder.get()), t);
						} finally {
							didFinishRequestHandlingCompleted.set(true);
						}
					}
				}
			});
		} catch (Throwable t) {
			// If an error occurred during request wrapping, it's possible a response was never written/communicated back to LifecycleInterceptor.
			// Detect that here and inform LifecycleInterceptor accordingly.
			logHandler.logError(format("An exception occurred during request wrapping while processing %s", requestHolder.get()), t);

			// If we don't have a response, let the marshaler try to make one for the exception.
			// If that fails, use the failsafe.
			if (marshaledResponseHolder.get() == null) {
				try {
					marshaledResponseHolder.set(responseMarshaler.forException(requestHolder.get(), t, resourceMethodHolder.get()));
				} catch (Throwable t2) {
					throwables.add(t2);

					logHandler.logError(format("An exception occurred during request wrapping while invoking %s#forException when processing %s",
							ResponseMarshaler.class.getSimpleName(), requestHolder.get()), t2);

					marshaledResponseHolder.set(provideFailsafeMarshaledResponse(requestHolder.get(), t));
				}
			}

			if (!willStartResponseWritingCompleted.get()) {
				try {
					lifecycleInterceptor.willStartResponseWriting(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get());
				} catch (Throwable t2) {
					logHandler.logError(format("An exception occurred while invoking %s.willStartResponseWriting() when processing %s",
							LifecycleInterceptor.class.getSimpleName(), requestHolder.get()), t);
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
							logHandler.logError(format("An exception occurred while invoking %s#didFinishResponseWriting when processing %s",
									LifecycleInterceptor.class.getSimpleName(), requestHolder.get()), t2);
						}
					} catch (Throwable t2) {
						throwables.add(t2);

						Instant responseWriteFinished = Instant.now();
						Duration responseWriteDuration = Duration.between(responseWriteStarted, responseWriteFinished);

						try {
							lifecycleInterceptor.didFinishResponseWriting(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get(), responseWriteDuration, t);
						} catch (Throwable t3) {
							throwables.add(t3);
							logHandler.logError(format("An exception occurred while invoking %s#didFinishResponseWriting when processing %s",
									LifecycleInterceptor.class.getSimpleName(), requestHolder.get()), t2);
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
						logHandler.logError(format("An exception occurred while invoking %s.didFinishRequestProcessing() when processing %s",
								LifecycleInterceptor.class.getSimpleName(), requestHolder.get()), t2);
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
		Cors cors = request.getCors().orElse(null);

		// No resource method was found for this HTTP method and path.
		if (resourceMethod == null) {
			// If this was an OPTIONS request, do special processing.
			// If not, figure out if we should return a 404 or 405.
			if (request.getHttpMethod() == HttpMethod.OPTIONS) {
				// See what non-OPTIONS methods are available to us for this request's path
				Set<HttpMethod> otherHttpMethods = resolveOtherMatchingHttpMethods(request, resourceMethodResolver);
				Set<HttpMethod> allowedHttpMethods = new HashSet<>(otherHttpMethods);
				allowedHttpMethods.add(HttpMethod.OPTIONS);

				// Special handling for CORS preflight requests, if needed
				if (cors != null && cors.isPreflight()) {
					// Let configuration function determine if we should authorize this request
					CorsPreflightResponse corsPreflightResponse = corsAuthorizer.authorizePreflight(request, allowedHttpMethods).orElse(null);

					// Allow or reject CORS depending on what the function said to do
					if (corsPreflightResponse != null)
						return responseMarshaler.forCorsPreflightAllowed(request, corsPreflightResponse);
					else
						return responseMarshaler.forCorsPreflightRejected(request);
				} else {
					// Just a normal OPTIONS response (non-CORS-preflight)
					return responseMarshaler.forOptions(request, allowedHttpMethods);
				}
			} else if (request.getHttpMethod() == HttpMethod.HEAD) {
				// If there's a matching GET resource method for this HEAD request, then invoke it
				Request headGetRequest = new Request.Builder(HttpMethod.GET, request.getUri()).build();
				ResourceMethod headGetResourceMethod = resourceMethodResolver.resourceMethodForRequest(headGetRequest).orElse(null);

				if (headGetResourceMethod != null)
					resourceMethod = headGetResourceMethod;
				else
					return responseMarshaler.forNotFound(request);
			} else {
				// Not an OPTIONS request, so it's possible we have a 405. See if other HTTP methods match...
				Set<HttpMethod> otherHttpMethods = resolveOtherMatchingHttpMethods(request, resourceMethodResolver);

				if (otherHttpMethods.size() > 0) {
					// ...if some do, it's a 405
					return responseMarshaler.forMethodNotAllowed(request, otherHttpMethods);
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

		Response response;

		// If null/void return, it's a 204
		// If it's a MicrohttpResponse object, re-use as is.
		// If it's a non-MicrohttpResponse type of object, assume it's the response body
		if (responseObject == null)
			response = new Response.Builder(204).build();
		else if (responseObject instanceof Response)
			response = (Response) responseObject;
		else
			response = new Response.Builder(200).body(responseObject).build();

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

		// If non-CORS request or preflight CORS, nothing further to do (preflight was handled earlier)
		if (cors == null || cors.isPreflight())
			return marshaledResponse;

		CorsAuthorizer corsAuthorizer = getSokletConfiguration().getCorsAuthorizer();

		// Does the authorizer say we are authorized?
		CorsResponse corsResponse = corsAuthorizer.authorize(request).orElse(null);

		// Not authorized - don't apply CORS headers to the response
		if (corsResponse == null)
			return marshaledResponse;

		// Authorized - OK, let's apply the headers to the response
		return getSokletConfiguration().getResponseMarshaler().forCorsAllowed(request, corsResponse, marshaledResponse);
	}

	@Nonnull
	protected Set<HttpMethod> resolveOtherMatchingHttpMethods(@Nonnull Request request,
																														@Nonnull ResourceMethodResolver resourceMethodResolver) {
		requireNonNull(request);
		requireNonNull(resourceMethodResolver);

		Set<HttpMethod> otherHttpMethods = new HashSet<>(HttpMethod.values().length);

		for (HttpMethod otherHttpMethod : HttpMethod.values()) {
			Request otherRequest = new Request.Builder(otherHttpMethod, request.getUri()).build();
			if (request.getHttpMethod() != otherHttpMethod && resourceMethodResolver.resourceMethodForRequest(otherRequest).isPresent())
				otherHttpMethods.add(otherHttpMethod);
		}

		return otherHttpMethods;
	}

	@Nonnull
	protected MarshaledResponse provideFailsafeMarshaledResponse(@Nonnull Request request,
																															 @Nonnull Throwable throwable) {
		requireNonNull(request);
		requireNonNull(throwable);

		Integer statusCode = 500;

		return new MarshaledResponse.Builder(statusCode)
				.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
				.body(format("HTTP %d: %s", statusCode, StatusCode.fromStatusCode(statusCode).get().getReasonPhrase()).getBytes(StandardCharsets.UTF_8))
				.build();
	}

	@Override
	public void close() {
		stop();
	}

	@Nonnull
	public Boolean isStarted() {
		getLock().lock();

		try {
			return getSokletConfiguration().getServer().isStarted();
		} finally {
			getLock().unlock();
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
}
