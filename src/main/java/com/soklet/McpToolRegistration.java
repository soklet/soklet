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

import com.soklet.converter.TypeReference;
import com.soklet.internal.mcp.protocol.McpMirroredHeaderPlan;
import com.soklet.internal.mcp.schema.McpRuntimeTypedSchemaBridge;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * Immutable programmatic registration for one MCP tool.
 *
 * <p>Registration is staged: choose a name, choose typed or raw-JSON
 * arguments, provide a handler, configure optional metadata, and explicitly
 * call {@code build()}. Typed schemas and intrinsic binding plans compile
 * synchronously while the type tokens are still in hand.
 *
 * @param <A> bound argument type
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpToolRegistration<A> {
	private static final int MAXIMUM_NAME_LENGTH = 128;
	@NonNull
	private static final Pattern NAME_PATTERN =
			Pattern.compile("[A-Za-z0-9_.-]+");
	@NonNull
	private static final McpSchema JSON_OBJECT_SCHEMA = new McpSchema(
			McpJsonObject.builder().put("type", "object").build());
	@NonNull
	private static final McpMirroredHeaderPlan EMPTY_MIRRORED_HEADER_PLAN =
			McpMirroredHeaderPlan.empty();

	@NonNull
	private final String name;
	@Nullable
	private final String title;
	@Nullable
	private final String description;
	@NonNull
	private final List<@NonNull McpIcon> icons;
	@NonNull
	private final Type argumentType;
	@NonNull
	private final McpSchema inputSchema;
	@NonNull
	private final McpMirroredHeaderPlan mirroredHeaderPlan;
	@Nullable
	private final Type outputType;
	@Nullable
	private final McpSchema outputSchema;
	@Nullable
	private final McpRuntimeTypedSchemaBridge<?> outputSchemaBridge;
	@Nullable
	private final McpToolAnnotations annotations;
	@Nullable
	private final String rateLimiterName;
	@Nullable
	private final McpRateLimiter rateLimiter;
	private final boolean structuredContentTextMirroringEnabled;
	@NonNull
	private final McpJsonObject metadata;
	@NonNull
	private final McpToolHandler<A> handler;
	@NonNull
	private final ArgumentDecoder<A> argumentDecoder;

	/**
	 * Begins a staged registration for a named tool.
	 *
	 * <p>The next stage selects typed or raw-JSON arguments. Supplying both
	 * argument and result types selects the simple typed-completion path;
	 * supplying only an argument type selects the advanced
	 * {@link McpOperationResult} path. No stage exposes {@code build()} before
	 * a handler has been supplied.
	 *
	 * @param name published MCP tool name
	 * @return argument-shape selection stage
	 * @throws IllegalArgumentException if the name is not 1-128 characters
	 * from {@code A-Z}, {@code a-z}, {@code 0-9}, underscore, hyphen, and dot
	 */
	@NonNull
	public static NamedBuilder withName(@NonNull String name) {
		return new NamedBuilder(requireName(name));
	}

	private McpToolRegistration(@NonNull RegistrationState<A> state) {
		this.name = state.name;
		this.title = state.title;
		this.description = state.description;
		this.icons = List.copyOf(state.icons);
		this.argumentType = state.argumentType;
		this.inputSchema = state.inputSchema;
		this.mirroredHeaderPlan = state.mirroredHeaderPlan;
		this.outputType = state.outputType;
		this.outputSchema = state.outputSchema;
		this.outputSchemaBridge = state.outputSchemaBridge;
		this.annotations = state.annotations;
		this.rateLimiterName = state.rateLimiterName;
		this.rateLimiter = state.rateLimiter;
		this.structuredContentTextMirroringEnabled =
				state.structuredContentTextMirroringEnabled;
		this.metadata = state.metadata;
		this.handler = state.handler;
		this.argumentDecoder = state.argumentDecoder;
	}

	/** @return published MCP tool name */
	@NonNull
	public String getName() {
		return this.name;
	}

	/** @return human-readable title, if configured */
	@NonNull
	public Optional<@NonNull String> getTitle() {
		return Optional.ofNullable(this.title);
	}

	/** @return human-readable description, if configured */
	@NonNull
	public Optional<@NonNull String> getDescription() {
		return Optional.ofNullable(this.description);
	}

	/** @return immutable icon list in registration order */
	@NonNull
	public List<@NonNull McpIcon> getIcons() {
		return this.icons;
	}

	/** @return declared Java argument type */
	@NonNull
	public Type getArgumentType() {
		return this.argumentType;
	}

	/** @return generated or fixed input schema */
	@NonNull
	public McpSchema getInputSchema() {
		return this.inputSchema;
	}

	/**
	 * Returns the immutable internal custom-header validation plan retained from
	 * the compiled input schema.
	 */
	@NonNull
	McpMirroredHeaderPlan getMirroredHeaderPlan() {
		return this.mirroredHeaderPlan;
	}

	/** @return generated output schema for a typed-completion registration */
	@NonNull
	public Optional<@NonNull McpSchema> getOutputSchema() {
		return Optional.ofNullable(this.outputSchema);
	}

	/**
	 * Returns the Java output type from which Soklet derived a schema.
	 *
	 * @return declared output type for a typed-completion registration
	 */
	@NonNull
	public Optional<@NonNull Type> getOutputType() {
		return Optional.ofNullable(this.outputType);
	}

	/** @return advisory tool annotations, if configured */
	@NonNull
	public Optional<@NonNull McpToolAnnotations> getAnnotations() {
		return Optional.ofNullable(this.annotations);
	}

	/**
	 * Returns the named rate-limiter override.
	 *
	 * <p>At most one of this value and {@link #getRateLimiter()} is present.
	 *
	 * @return limiter name, or empty for a direct or inherited limiter
	 */
	@NonNull
	public Optional<@NonNull String> getRateLimiterName() {
		return Optional.ofNullable(this.rateLimiterName);
	}

	/**
	 * Returns the direct rate-limiter override.
	 *
	 * <p>At most one of this value and {@link #getRateLimiterName()} is
	 * present.
	 *
	 * @return direct limiter, or empty for a named or inherited limiter
	 */
	@NonNull
	public Optional<@NonNull McpRateLimiter> getRateLimiter() {
		return Optional.ofNullable(this.rateLimiter);
	}

	/**
	 * Indicates whether structured content is also mirrored as canonical JSON
	 * text in the tool's content array.
	 *
	 * @return {@code true} when mirroring is enabled
	 */
	public boolean isStructuredContentTextMirroringEnabled() {
		return this.structuredContentTextMirroringEnabled;
	}

	/** @return immutable protocol extension metadata */
	@NonNull
	public McpJsonObject getMetadata() {
		return this.metadata;
	}

	/**
	 * Returns the normalized advanced handler.
	 *
	 * <p>For a typed-completion registration, Soklet supplies an adapter that
	 * converts the Java result with the same compiled binding that generated
	 * {@link #getOutputSchema()}.
	 *
	 * @return normalized handler
	 */
	@NonNull
	public McpToolHandler<A> getHandler() {
		return this.handler;
	}

	/**
	 * Validates and decodes raw arguments, invokes the normalized handler, and
	 * returns a recognized result. This package-private seam keeps all
	 * type-erasure handling inside registration while exposing only public MCP
	 * values to Soklet's package-peer server implementation.
	 */
	@NonNull
	McpOperationResult invoke(@NonNull McpRequestContext request,
			@NonNull McpJsonObject rawArguments,
			@NonNull McpInvocationFeatures features) throws Exception {
		requireNonNull(request);
		requireNonNull(rawArguments);
		requireNonNull(features);
		A arguments;
		try {
			arguments = requireNonNull(
					this.argumentDecoder.decode(rawArguments),
					"The MCP argument decoder returned null.");
		} catch (IllegalArgumentException exception) {
			throw new McpInvalidToolArgumentsException(exception);
		}
		McpToolCallContext<A> call =
				new DefaultToolCallContext<>(arguments, rawArguments);
		return requireNonNull(this.handler.handle(request, call, features),
				"The MCP tool handler returned null.");
	}

	/**
	 * Validates structured output against the compiled schema retained by a
	 * typed-completion registration. Advanced registrations have no output
	 * schema and therefore accept every structured JSON value here.
	 */
	boolean isStructuredOutputValid(@NonNull McpJsonValue structuredOutput) {
		requireNonNull(structuredOutput);
		return this.outputSchemaBridge == null
				|| this.outputSchemaBridge.isValid(structuredOutput);
	}

	@NonNull
	private static String requireName(@NonNull String name) {
		requireNonNull(name);
		if (name.length() < 1 || name.length() > MAXIMUM_NAME_LENGTH
				|| !NAME_PATTERN.matcher(name).matches())
			throw new IllegalArgumentException(
					"MCP tool names must contain 1-128 characters from "
							+ "[A-Za-z0-9_.-].");
		return name;
	}

	/**
	 * Staged selection of a tool's argument and result shapes.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class NamedBuilder {
		@NonNull
		private final String name;

		private NamedBuilder(@NonNull String name) {
			this.name = requireNonNull(name);
		}

		/**
		 * Selects class-token argument and result types.
		 *
		 * @param argumentType argument type
		 * @param resultType structured result type
		 * @param <T> argument type
		 * @param <R> result type
		 * @return typed handler-selection stage
		 */
		@NonNull
		public <T, R> CompleteHandlerStage<T, R> types(
				@NonNull Class<T> argumentType,
				@NonNull Class<R> resultType) {
			return typedStage(argumentType, resultType);
		}

		/**
		 * Selects a class-token argument type and generic result type.
		 *
		 * @param argumentType argument type
		 * @param resultType structured result type token
		 * @param <T> argument type
		 * @param <R> result type
		 * @return typed handler-selection stage
		 */
		@NonNull
		public <T, R> CompleteHandlerStage<T, R> types(
				@NonNull Class<T> argumentType,
				@NonNull TypeReference<R> resultType) {
			requireNonNull(resultType);
			return typedStage(argumentType, resultType.getType());
		}

		/**
		 * Selects a generic argument type and class-token result type.
		 *
		 * @param argumentType argument type token
		 * @param resultType structured result type
		 * @param <T> argument type
		 * @param <R> result type
		 * @return typed handler-selection stage
		 */
		@NonNull
		public <T, R> CompleteHandlerStage<T, R> types(
				@NonNull TypeReference<T> argumentType,
				@NonNull Class<R> resultType) {
			requireNonNull(argumentType);
			return typedStage(argumentType.getType(), resultType);
		}

		/**
		 * Selects generic argument and result types.
		 *
		 * @param argumentType argument type token
		 * @param resultType structured result type token
		 * @param <T> argument type
		 * @param <R> result type
		 * @return typed handler-selection stage
		 */
		@NonNull
		public <T, R> CompleteHandlerStage<T, R> types(
				@NonNull TypeReference<T> argumentType,
				@NonNull TypeReference<R> resultType) {
			requireNonNull(argumentType);
			requireNonNull(resultType);
			return typedStage(argumentType.getType(), resultType.getType());
		}

		/**
		 * Selects a class-token argument type for an advanced handler.
		 *
		 * @param argumentType argument type
		 * @param <T> argument type
		 * @return advanced handler-selection stage
		 */
		@NonNull
		public <T> OperationHandlerStage<T> argumentType(
				@NonNull Class<T> argumentType) {
			return operationStage(argumentType);
		}

		/**
		 * Selects a generic argument type for an advanced handler.
		 *
		 * @param argumentType argument type token
		 * @param <T> argument type
		 * @return advanced handler-selection stage
		 */
		@NonNull
		public <T> OperationHandlerStage<T> argumentType(
				@NonNull TypeReference<T> argumentType) {
			requireNonNull(argumentType);
			return operationStage(argumentType.getType());
		}

		/**
		 * Selects raw JSON-object arguments for an advanced handler.
		 *
		 * <p>The registration publishes and enforces the fixed
		 * {@code {"type":"object"}} schema.
		 *
		 * @return raw-JSON handler-selection stage
		 */
		@NonNull
		public OperationHandlerStage<McpJsonObject> jsonArguments() {
			return new OperationHandlerStage<>(this.name, McpJsonObject.class,
					JSON_OBJECT_SCHEMA, EMPTY_MIRRORED_HEADER_PLAN,
					rawArguments -> rawArguments);
		}

		@NonNull
		private <T, R> CompleteHandlerStage<T, R> typedStage(
				@NonNull Type argumentType, @NonNull Type resultType) {
			requireNonNull(argumentType);
			requireNonNull(resultType);
			McpRuntimeTypedSchemaBridge<T> inputBridge =
					McpRuntimeTypedSchemaBridge.compileToolInput(argumentType);
			McpRuntimeTypedSchemaBridge<R> outputBridge =
					McpRuntimeTypedSchemaBridge.compileToolOutput(resultType);
			return new CompleteHandlerStage<>(this.name, argumentType,
					resultType, inputBridge, outputBridge);
		}

		@NonNull
		private <T> OperationHandlerStage<T> operationStage(
				@NonNull Type argumentType) {
			requireNonNull(argumentType);
			McpRuntimeTypedSchemaBridge<T> inputBridge =
					McpRuntimeTypedSchemaBridge.compileToolInput(argumentType);
			McpSchema inputSchema =
					new McpSchema(inputBridge.getSchemaDocument());
			return new OperationHandlerStage<>(this.name, argumentType,
					inputSchema, inputBridge.getMirroredHeaderPlan(),
					inputBridge::decode);
		}
	}

	/**
	 * Handler-selection stage for the simple typed-completion path.
	 *
	 * @param <A> argument type
	 * @param <R> structured result type
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class CompleteHandlerStage<A, R> {
		@NonNull
		private final String name;
		@NonNull
		private final Type argumentType;
		@NonNull
		private final Type resultType;
		@NonNull
		private final McpRuntimeTypedSchemaBridge<A> inputBridge;
		@NonNull
		private final McpRuntimeTypedSchemaBridge<R> outputBridge;

		private CompleteHandlerStage(@NonNull String name,
				@NonNull Type argumentType, @NonNull Type resultType,
				@NonNull McpRuntimeTypedSchemaBridge<A> inputBridge,
				@NonNull McpRuntimeTypedSchemaBridge<R> outputBridge) {
			this.name = requireNonNull(name);
			this.argumentType = requireNonNull(argumentType);
			this.resultType = requireNonNull(resultType);
			this.inputBridge = requireNonNull(inputBridge);
			this.outputBridge = requireNonNull(outputBridge);
		}

		/**
		 * Supplies the required complete handler.
		 *
		 * @param handler complete handler
		 * @return optional-metadata builder
		 */
		@NonNull
		public CompleteBuilder<A, R> handler(
				@NonNull McpCompleteToolHandler<A, R> handler) {
			requireNonNull(handler);
			McpToolHandler<A> normalizedHandler = (request, call, features) -> {
				R result = requireNonNull(
						handler.handle(request, call, features),
						"The MCP complete tool handler returned null.");
				McpJsonValue structuredContent =
						this.outputBridge.encodeForDeferredValidation(result);
				return McpCompleteResult.fromToolStructuredContent(
						structuredContent);
			};
			RegistrationState<A> state = new RegistrationState<>(this.name,
					this.argumentType,
					new McpSchema(this.inputBridge.getSchemaDocument()),
					this.inputBridge.getMirroredHeaderPlan(),
					this.resultType,
					new McpSchema(this.outputBridge.getSchemaDocument()),
					this.outputBridge,
					normalizedHandler, this.inputBridge::decode);
			return new CompleteBuilder<>(state);
		}
	}

	/**
	 * Handler-selection stage for the advanced operation-result path.
	 *
	 * @param <A> argument type
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class OperationHandlerStage<A> {
		@NonNull
		private final String name;
		@NonNull
		private final Type argumentType;
		@NonNull
		private final McpSchema inputSchema;
		@NonNull
		private final McpMirroredHeaderPlan mirroredHeaderPlan;
		@NonNull
		private final ArgumentDecoder<A> argumentDecoder;

		private OperationHandlerStage(@NonNull String name,
				@NonNull Type argumentType, @NonNull McpSchema inputSchema,
				@NonNull McpMirroredHeaderPlan mirroredHeaderPlan,
				@NonNull ArgumentDecoder<A> argumentDecoder) {
			this.name = requireNonNull(name);
			this.argumentType = requireNonNull(argumentType);
			this.inputSchema = requireNonNull(inputSchema);
			this.mirroredHeaderPlan = requireNonNull(mirroredHeaderPlan);
			this.argumentDecoder = requireNonNull(argumentDecoder);
		}

		/**
		 * Supplies the required advanced handler.
		 *
		 * @param handler advanced handler
		 * @return optional-metadata builder
		 */
		@NonNull
		public Builder<A> handler(@NonNull McpToolHandler<A> handler) {
			RegistrationState<A> state = new RegistrationState<>(this.name,
					this.argumentType, this.inputSchema, this.mirroredHeaderPlan,
					null, null, null,
					requireNonNull(handler), this.argumentDecoder);
			return new Builder<>(state);
		}
	}

	/**
	 * Builder for an advanced handler that returns the
	 * {@link McpOperationResult} spine directly.
	 *
	 * @param <A> argument type
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder<A> {
		@NonNull
		private final RegistrationState<A> state;

		private Builder(@NonNull RegistrationState<A> state) {
			this.state = requireNonNull(state);
		}

		/** @param title human-readable title
		 * @return this builder */
		@NonNull
		public Builder<A> title(@NonNull String title) {
			this.state.title = requireNonNull(title);
			return this;
		}

		/** @param description human-readable description
		 * @return this builder */
		@NonNull
		public Builder<A> description(@NonNull String description) {
			this.state.description = requireNonNull(description);
			return this;
		}

		/** @param icon icon descriptor to append
		 * @return this builder */
		@NonNull
		public Builder<A> icon(@NonNull McpIcon icon) {
			this.state.icons.add(requireNonNull(icon));
			return this;
		}

		/** @param annotations advisory tool annotations
		 * @return this builder */
		@NonNull
		public Builder<A> annotations(
				@NonNull McpToolAnnotations annotations) {
			this.state.annotations = requireNonNull(annotations);
			return this;
		}

		/**
		 * Sets a named rate-limiter override.
		 *
		 * <p>Named and direct setter calls are last-call-wins.
		 *
		 * @param limiterName nonblank name in the server limiter registry
		 * @return this builder
		 */
		@NonNull
		public Builder<A> rateLimiter(@NonNull String limiterName) {
			this.state.rateLimiterName = requireNonBlank(limiterName,
					"Rate-limiter name");
			this.state.rateLimiter = null;
			return this;
		}

		/**
		 * Sets a direct rate-limiter override.
		 *
		 * <p>Named and direct setter calls are last-call-wins.
		 *
		 * @param rateLimiter direct application limiter
		 * @return this builder
		 */
		@NonNull
		public Builder<A> rateLimiter(@NonNull McpRateLimiter rateLimiter) {
			this.state.rateLimiter = requireNonNull(rateLimiter);
			this.state.rateLimiterName = null;
			return this;
		}

		/**
		 * Controls canonical JSON text mirroring for structured tool content.
		 *
		 * <p>Mirroring is enabled by default. Pass {@code false} to opt out.
		 *
		 * @param enabled whether mirroring is enabled
		 * @return this builder
		 */
		@NonNull
		public Builder<A> mirrorStructuredContentAsText(boolean enabled) {
			this.state.structuredContentTextMirroringEnabled = enabled;
			return this;
		}

		/** @param metadata protocol extension metadata
		 * @return this builder */
		@NonNull
		public Builder<A> metadata(@NonNull McpJsonObject metadata) {
			this.state.metadata = requireNonNull(metadata);
			return this;
		}

		/** @return immutable tool registration */
		@NonNull
		public McpToolRegistration<A> build() {
			return new McpToolRegistration<>(this.state);
		}
	}

	/**
	 * Builder for a tool that always completes with a supported structured Java
	 * result.
	 *
	 * <p>The staged type declaration is the sole source for schema derivation
	 * and intrinsic conversion. This builder intentionally has no output-type,
	 * output-schema, or converter override.
	 *
	 * @param <A> argument type
	 * @param <R> structured result type
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class CompleteBuilder<A, R> {
		@NonNull
		private final RegistrationState<A> state;

		private CompleteBuilder(@NonNull RegistrationState<A> state) {
			this.state = requireNonNull(state);
		}

		/** @param title human-readable title
		 * @return this builder */
		@NonNull
		public CompleteBuilder<A, R> title(@NonNull String title) {
			this.state.title = requireNonNull(title);
			return this;
		}

		/** @param description human-readable description
		 * @return this builder */
		@NonNull
		public CompleteBuilder<A, R> description(
				@NonNull String description) {
			this.state.description = requireNonNull(description);
			return this;
		}

		/** @param icon icon descriptor to append
		 * @return this builder */
		@NonNull
		public CompleteBuilder<A, R> icon(@NonNull McpIcon icon) {
			this.state.icons.add(requireNonNull(icon));
			return this;
		}

		/** @param annotations advisory tool annotations
		 * @return this builder */
		@NonNull
		public CompleteBuilder<A, R> annotations(
				@NonNull McpToolAnnotations annotations) {
			this.state.annotations = requireNonNull(annotations);
			return this;
		}

		/**
		 * Sets a named rate-limiter override.
		 *
		 * <p>Named and direct setter calls are last-call-wins.
		 *
		 * @param limiterName nonblank name in the server limiter registry
		 * @return this builder
		 */
		@NonNull
		public CompleteBuilder<A, R> rateLimiter(
				@NonNull String limiterName) {
			this.state.rateLimiterName = requireNonBlank(limiterName,
					"Rate-limiter name");
			this.state.rateLimiter = null;
			return this;
		}

		/**
		 * Sets a direct rate-limiter override.
		 *
		 * <p>Named and direct setter calls are last-call-wins.
		 *
		 * @param rateLimiter direct application limiter
		 * @return this builder
		 */
		@NonNull
		public CompleteBuilder<A, R> rateLimiter(
				@NonNull McpRateLimiter rateLimiter) {
			this.state.rateLimiter = requireNonNull(rateLimiter);
			this.state.rateLimiterName = null;
			return this;
		}

		/**
		 * Controls canonical JSON text mirroring for structured tool content.
		 *
		 * <p>Mirroring is enabled by default. Pass {@code false} to opt out.
		 *
		 * @param enabled whether mirroring is enabled
		 * @return this builder
		 */
		@NonNull
		public CompleteBuilder<A, R> mirrorStructuredContentAsText(
				boolean enabled) {
			this.state.structuredContentTextMirroringEnabled = enabled;
			return this;
		}

		/** @param metadata protocol extension metadata
		 * @return this builder */
		@NonNull
		public CompleteBuilder<A, R> metadata(
				@NonNull McpJsonObject metadata) {
			this.state.metadata = requireNonNull(metadata);
			return this;
		}

		/** @return immutable tool registration */
		@NonNull
		public McpToolRegistration<A> build() {
			return new McpToolRegistration<>(this.state);
		}
	}

	@NonNull
	private static String requireNonBlank(@NonNull String value,
			@NonNull String description) {
		requireNonNull(value);
		requireNonNull(description);
		if (value.isBlank())
			throw new IllegalArgumentException(
					description + " must not be blank.");
		return value;
	}

	@ThreadSafe
	@FunctionalInterface
	private interface ArgumentDecoder<A> {
		@NonNull
		A decode(@NonNull McpJsonObject rawArguments);
	}

	@ThreadSafe
	private static final class DefaultToolCallContext<A>
			implements McpToolCallContext<A> {
		@NonNull
		private final A arguments;
		@NonNull
		private final McpJsonObject rawArguments;

		private DefaultToolCallContext(@NonNull A arguments,
				@NonNull McpJsonObject rawArguments) {
			this.arguments = requireNonNull(arguments);
			this.rawArguments = requireNonNull(rawArguments);
		}

		@Override
		@NonNull
		public A getArguments() {
			return this.arguments;
		}

		@Override
		@NonNull
		public McpJsonObject getRawArguments() {
			return this.rawArguments;
		}
	}

	@NotThreadSafe
	private static final class RegistrationState<A> {
		@NonNull
		private final String name;
		@NonNull
		private final Type argumentType;
		@NonNull
		private final McpSchema inputSchema;
		@NonNull
		private final McpMirroredHeaderPlan mirroredHeaderPlan;
		@Nullable
		private final Type outputType;
		@Nullable
		private final McpSchema outputSchema;
		@Nullable
		private final McpRuntimeTypedSchemaBridge<?> outputSchemaBridge;
		@NonNull
		private final McpToolHandler<A> handler;
		@NonNull
		private final ArgumentDecoder<A> argumentDecoder;
		@Nullable
		private String title;
		@Nullable
		private String description;
		@NonNull
		private final List<@NonNull McpIcon> icons = new ArrayList<>();
		@Nullable
		private McpToolAnnotations annotations;
		@Nullable
		private String rateLimiterName;
		@Nullable
		private McpRateLimiter rateLimiter;
		private boolean structuredContentTextMirroringEnabled = true;
		@NonNull
		private McpJsonObject metadata = McpJsonObject.emptyInstance();

		private RegistrationState(@NonNull String name,
				@NonNull Type argumentType, @NonNull McpSchema inputSchema,
				@NonNull McpMirroredHeaderPlan mirroredHeaderPlan,
				@Nullable Type outputType, @Nullable McpSchema outputSchema,
				@Nullable McpRuntimeTypedSchemaBridge<?> outputSchemaBridge,
				@NonNull McpToolHandler<A> handler,
				@NonNull ArgumentDecoder<A> argumentDecoder) {
			this.name = requireNonNull(name);
			this.argumentType = requireNonNull(argumentType);
			this.inputSchema = requireNonNull(inputSchema);
			this.mirroredHeaderPlan = requireNonNull(mirroredHeaderPlan);
			this.outputType = outputType;
			this.outputSchema = outputSchema;
			this.outputSchemaBridge = outputSchemaBridge;
			this.handler = requireNonNull(handler);
			this.argumentDecoder = requireNonNull(argumentDecoder);
			if ((this.outputType == null) != (this.outputSchema == null)
					|| (this.outputSchema == null)
					!= (this.outputSchemaBridge == null))
				throw new IllegalArgumentException(
						"Output type, schema, and bridge must be present together.");
		}
	}
}
