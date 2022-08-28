/*
 * Copyright 2022 Revetware LLC.
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

import com.soklet.core.CorsAuthorizer;
import com.soklet.core.CorsRequest;
import com.soklet.core.CorsResponse;
import com.soklet.core.HttpMethod;
import com.soklet.core.InstanceProvider;
import com.soklet.core.LifecycleInterceptor;
import com.soklet.core.LogHandler;
import com.soklet.core.MarshaledResponse;
import com.soklet.core.Request;
import com.soklet.core.RequestContext;
import com.soklet.core.RequestHandler;
import com.soklet.core.ResourceMethod;
import com.soklet.core.ResourceMethodParameterProvider;
import com.soklet.core.ResourceMethodResolver;
import com.soklet.core.Response;
import com.soklet.core.ResponseMarshaler;
import com.soklet.core.Server;
import com.soklet.core.StatusCode;
import com.soklet.core.Utilities;

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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetware.com">Mark Allen</a>
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

		sokletConfiguration.getServer().setRequestHandler(this);
	}

	public void start() throws Exception {
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

	@Override
	public void handleRequest(@Nonnull Request request,
														@Nonnull Consumer<MarshaledResponse> marshaledResponseConsumer) throws Exception {
		Instant processingStarted = Instant.now();

		SokletConfiguration sokletConfiguration = getSokletConfiguration();
		ResourceMethodResolver resourceMethodResolver = sokletConfiguration.getResourceMethodResolver();
		ResponseMarshaler responseMarshaler = sokletConfiguration.getResponseMarshaler();
		LifecycleInterceptor lifecycleInterceptor = sokletConfiguration.getLifecycleInterceptor();
		LogHandler logHandler = getSokletConfiguration().getLogHandler();

		// Holders to permit mutable effectively-final variables
		AtomicReference<MarshaledResponse> marshaledResponseHolder = new AtomicReference<>();
		AtomicReference<Throwable> resourceMethodResolutionExceptionHolder = new AtomicReference<>();
		AtomicReference<Request> requestHolder = new AtomicReference<>();
		AtomicReference<ResourceMethod> resourceMethodHolder = new AtomicReference<>();

		List<Throwable> throwables = new ArrayList<>(8);

		try {
			// Do we have an exact match for this resource method?
			resourceMethodHolder.set(resourceMethodResolver.resourceMethodForRequest(request).orElse(null));
		} catch (Throwable t) {
			// If an exception occurs here, keep track of it - we will rethrow below after letting LifecycleInterceptor
			// see that a request has come in.
			throwables.add(t);
			resourceMethodResolutionExceptionHolder.set(t);
		}

		try {
			RequestContext.setCurrent(new RequestContext(request, resourceMethodHolder.get()));

			lifecycleInterceptor.interceptRequest(request, resourceMethodHolder.get(), (interceptorRequest) -> {
				requestHolder.set(interceptorRequest);

				RequestContext.setCurrent(new RequestContext(requestHolder.get(), resourceMethodHolder.get()));

				lifecycleInterceptor.didStartRequestHandling(requestHolder.get(), resourceMethodHolder.get());

				try {
					if (resourceMethodResolutionExceptionHolder.get() != null)
						throw resourceMethodResolutionExceptionHolder.get();

					return toMarshaledResponse(requestHolder.get(), resourceMethodHolder.get());
				} catch (Throwable t) {
					throwables.add(t);
					logHandler.logError(format("An exception occurred while processing %s", request), t);

					// Unhappy path.  Try to use configuration's exception response marshaler...
					try {
						return responseMarshaler.toExceptionMarshaledResponse(requestHolder.get(), t, resourceMethodHolder.get());
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
			} finally {
				marshaledResponseHolder.set(provideFailsafeMarshaledResponse(requestHolder.get(), t));
			}
		} finally {
			try {
				lifecycleInterceptor.willStartResponseWriting(requestHolder.get(), resourceMethodHolder.get(), marshaledResponseHolder.get());

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
					RequestContext.setCurrent(null);
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

		// No resource method was found for this HTTP method and path.
		if (resourceMethod == null) {
			// If this was an OPTIONS request, do special processing.
			// If not, figure out if we should return a 404 or 405.
			if (request.getHttpMethod() == HttpMethod.OPTIONS) {
				// See what non-OPTIONS methods are available to us for this request's path
				Set<HttpMethod> otherHttpMethods = resolveOtherMatchingHttpMethods(request, resourceMethodResolver);
				Set<HttpMethod> allowedHttpMethods = new HashSet<>(otherHttpMethods);
				allowedHttpMethods.add(HttpMethod.OPTIONS);

				// Special handling for CORS, if needed
				CorsRequest corsRequest = Utilities.extractCorsRequest(request).orElse(null);

				if (corsRequest != null) {
					// Let configuration function determine if we should authorize this request
					CorsResponse corsResponse = corsAuthorizer.authorize(request, corsRequest, allowedHttpMethods).orElse(null);

					// Allow or reject CORS depending on what the function said to do
					if (corsResponse != null)
						return responseMarshaler.toCorsAllowedMarshaledResponse(request, corsRequest, corsResponse);
					else
						return responseMarshaler.toCorsRejectedMarshaledResponse(request, corsRequest);
				} else {
					// Just a normal OPTIONS response (non-CORS)
					return responseMarshaler.toOptionsMarshaledResponse(request, allowedHttpMethods);
				}
			} else {
				// Not an OPTIONS request, so it's possible we have a 405. See if other HTTP methods match...
				Set<HttpMethod> otherHttpMethods = resolveOtherMatchingHttpMethods(request, resourceMethodResolver);

				if (otherHttpMethods.size() > 0) {
					// ...if some do, it's a 405
					return responseMarshaler.toMethodNotAllowedMarshaledResponse(request, otherHttpMethods);
				} else {
					// no matching resource method found, it's a 404
					return responseMarshaler.toNotFoundMarshaledResponse(request);
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
		// If it's a Response object, re-use as is.
		// If it's a non-Response type of object, assume it's the response body
		if (responseObject == null)
			response = new Response.Builder(204).build();
		else if (responseObject instanceof Response)
			response = (Response) responseObject;
		else
			response = new Response.Builder(200).body(responseObject).build();

		return responseMarshaler.toDefaultMarshaledResponse(request, response, resourceMethod);
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
	public void close() throws Exception {
		getLock().lock();

		try {
			if (!isStarted())
				return;

			SokletConfiguration sokletConfiguration = getSokletConfiguration();
			LifecycleInterceptor lifecycleInterceptor = sokletConfiguration.getLifecycleInterceptor();
			Server server = sokletConfiguration.getServer();

			lifecycleInterceptor.willStopServer(server);
			server.close();
			lifecycleInterceptor.didStopServer(server);
		} finally {
			getLock().unlock();
		}
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