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

import com.soklet.annotation.SseEventSource;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * The bounded, side-effecting framework-setup phase shared by the direct
 * one-shot owner and the isolated simulator.  Configuration construction and
 * {@link Soklet#fromConfig(SokletConfig)} deliberately do not invoke it.
 */
@ThreadSafe
final class SokletFrameworkSetup {
	private enum State {
		NEW,
		RUNNING,
		SUCCEEDED,
		FAILED
	}

	@NonNull
	private final SokletConfig config;
	@NonNull
	private final AtomicReference<State> state;
	@NonNull
	private final AtomicReference<Set<ResourceMethod>> resourceMethods;
	@NonNull
	private final AtomicReference<Throwable> failure;

	SokletFrameworkSetup(@NonNull SokletConfig config) {
		this.config = requireNonNull(config);
		this.state = new AtomicReference<>(State.NEW);
		this.resourceMethods = new AtomicReference<>();
		this.failure = new AtomicReference<>();
	}

	/** Runs the one setup attempt on the caller's tracked lifecycle worker. */
	@NonNull
	Set<ResourceMethod> run(@NonNull InternalStartupContext startupContext,
			@NonNull DeadlineWaiter waiter) {
		requireNonNull(startupContext);
		requireNonNull(waiter);
		State observed = this.state.get();
		if (observed == State.SUCCEEDED)
			return requireNonNull(this.resourceMethods.get());
		if (observed == State.FAILED)
			throw propagate(requireNonNull(this.failure.get()));
		if (!this.state.compareAndSet(State.NEW, State.RUNNING))
			throw new IllegalStateException("Soklet framework setup is already running");

		try {
			Set<ResourceMethod> snapshot = Set.copyOf(resolveResourceMethods(
					startupContext, waiter));
			validateConfiguredTransports(snapshot);
			validateNoRemovedHttpServerInjection(snapshot);
			initializeMetrics();
			this.resourceMethods.set(snapshot);
			this.state.set(State.SUCCEEDED);
			return snapshot;
		} catch (DefaultResourceMethodResolver.StartupWaitCancelledException sentinel) {
			// A non-owning Soklet may abandon only its wait while the JVM-wide
			// resolver attempt continues.  Do not turn that lifecycle sentinel into
			// the resolver's or this setup object's immutable terminal failure.
			this.state.compareAndSet(State.RUNNING, State.NEW);
			throw sentinel;
		} catch (Throwable throwable) {
			this.failure.compareAndSet(null, throwable);
			this.state.set(State.FAILED);
			throw propagate(throwable);
		}
	}

	@NonNull
	private Set<ResourceMethod> resolveResourceMethods(
			@NonNull InternalStartupContext startupContext,
			@NonNull DeadlineWaiter waiter) {
		ResourceMethodResolver resolver = this.config.getResourceMethodResolver();
		if (resolver instanceof DefaultResourceMethodResolver defaultResolver)
			return defaultResolver.getResourceMethodsForLifecycle(startupContext,
					waiter);
		return requireNonNull(resolver.getResourceMethods(),
				"ResourceMethodResolver.getResourceMethods()");
	}

	private void validateConfiguredTransports(
			@NonNull Set<ResourceMethod> resourceMethods) {
		if (resourceMethods.isEmpty()
				&& (this.config.getHttpServer().isPresent()
				|| this.config.getSseServer().isPresent()))
			throw new IllegalStateException(format("No Soklet Resource Methods were found. First, try to rebuild and see if that solves the problem. If not, please ensure your %s is configured correctly. See https://www.soklet.com/docs/request-handling#resource-method-resolution for details.",
					ResourceMethodResolver.class.getSimpleName()));

		boolean hasStandardHttpResourceMethods = resourceMethods.stream()
				.anyMatch(resourceMethod -> !resourceMethod.isSseEventSource());
		if (hasStandardHttpResourceMethods && this.config.getHttpServer().isEmpty())
			throw new IllegalStateException(format("Resource Methods were found, but no %s is configured. See https://www.soklet.com/docs/server-configuration for details.",
					HttpServer.class.getSimpleName()));

		boolean hasSseResourceMethods = resourceMethods.stream()
				.anyMatch(ResourceMethod::isSseEventSource);
		if (hasSseResourceMethods && this.config.getSseServer().isEmpty())
			throw new IllegalStateException(format("Resource Methods annotated with @%s were found, but no %s is configured. See https://www.soklet.com/docs/server-sent-events for details.",
					SseEventSource.class.getSimpleName(),
					SseServer.class.getSimpleName()));
	}

	private void initializeMetrics() {
		MetricsCollector metricsCollector = this.config.getMetricsCollector();
		if (!(metricsCollector instanceof DefaultMetricsCollector defaultCollector))
			return;
		try {
			defaultCollector.initialize(this.config);
		} catch (Throwable throwable) {
			try {
				this.config.getAggregateLifecycleObserver().didReceiveLogEvent(
						LogEvent.with(LogEventType.METRICS_COLLECTOR_FAILED,
								format("An exception occurred while initializing %s",
										metricsCollector.getClass().getSimpleName()))
								.throwable(throwable).build());
			} catch (Throwable ignored) {
				// Metrics and observation remain non-controlling setup telemetry.
			}
		}
	}

	static void validateNoRemovedHttpServerInjection(
			@NonNull Set<ResourceMethod> resourceMethods) {
		List<ResourceMethod> ordered = new ArrayList<>(
				requireNonNull(resourceMethods));
		ordered.sort(Comparator
				.comparing((ResourceMethod resourceMethod) ->
						resourceMethod.getMethod().toGenericString())
				.thenComparing(resourceMethod -> resourceMethod.getHttpMethod().name())
				.thenComparing(resourceMethod -> resourceMethod
						.getResourcePathDeclaration().toString()));
		for (ResourceMethod resourceMethod : ordered)
			validateNoRemovedHttpServerInjection(resourceMethod);
	}

	static void validateNoRemovedHttpServerInjection(
			@NonNull ResourceMethod resourceMethod) {
		Parameter[] parameters = requireNonNull(resourceMethod).getMethod()
				.getParameters();
		for (Parameter parameter : parameters) {
			Type normalizedType = normalizedParameterType(parameter);
			if (isRemovedHttpServerInjectionType(normalizedType))
				throw unsupportedHttpServerParameter(resourceMethod, parameter,
						normalizedType);
		}
	}

	@NonNull
	static IllegalStateException unsupportedHttpServerParameter(
			@NonNull ResourceMethod resourceMethod,
			@NonNull Parameter parameter, @NonNull Type normalizedType) {
		return new IllegalStateException(format(
				"Resource Method %s declares unsupported parameter %s of type %s. HttpServer resource-method injection was removed in Soklet 4.0; inject an application service through InstanceProvider instead.",
				requireNonNull(resourceMethod).getMethod(),
				requireNonNull(parameter), requireNonNull(normalizedType).getTypeName()));
	}

	@NonNull
	static Type normalizedParameterType(@NonNull Parameter parameter) {
		Parameter required = requireNonNull(parameter);
		Type type = required.getParameterizedType();
		if (required.getType() == java.util.Optional.class
				&& type instanceof ParameterizedType parameterizedType
				&& parameterizedType.getActualTypeArguments().length == 1)
			return parameterizedType.getActualTypeArguments()[0];
		return type;
	}

	private static boolean isRemovedHttpServerInjectionType(
			@NonNull Type type) {
		if (type instanceof Class<?> typeClass)
			return HttpServer.class.isAssignableFrom(typeClass);
		if (type instanceof ParameterizedType parameterizedType)
			return isRemovedHttpServerInjectionType(
					requireNonNull(parameterizedType.getRawType()));
		if (type instanceof TypeVariable<?> typeVariable) {
			for (Type bound : typeVariable.getBounds())
				if (isRemovedHttpServerInjectionType(requireNonNull(bound)))
					return true;
			return false;
		}
		if (type instanceof WildcardType wildcardType) {
			for (Type bound : wildcardType.getLowerBounds())
				if (isRemovedHttpServerInjectionType(requireNonNull(bound)))
					return true;
			for (Type bound : wildcardType.getUpperBounds())
				if (isRemovedHttpServerInjectionType(requireNonNull(bound)))
					return true;
		}
		return false;
	}

	@NonNull
	private static RuntimeException propagate(@NonNull Throwable throwable) {
		if (throwable instanceof RuntimeException runtimeException)
			return runtimeException;
		if (throwable instanceof Error error)
			throw error;
		return new IllegalStateException("Soklet framework setup failed", throwable);
	}
}
