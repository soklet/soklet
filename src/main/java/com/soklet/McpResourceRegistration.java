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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable programmatic registration for one MCP resource-read route.
 *
 * <p>Registration is staged so a handler must be supplied before optional
 * descriptor metadata can be configured. Exact and template registrations
 * intentionally expose different builders: only an exact resource may
 * advertise a size. The registration cache policy owns the fixed scope and
 * default time to live for reads; the default is
 * {@link McpCachePolicy#privateNoCacheInstance()}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpResourceRegistration {
	@NonNull
	private final McpResourceAddressType addressType;
	@Nullable
	private final URI uri;
	@Nullable
	private final String uriTemplate;
	@NonNull
	private final String name;
	@Nullable
	private final String title;
	@Nullable
	private final String description;
	@Nullable
	private final String mimeType;
	@NonNull
	private final List<@NonNull McpIcon> icons;
	@Nullable
	private final McpContentAnnotations annotations;
	@Nullable
	private final Long size;
	@NonNull
	private final McpCachePolicy cachePolicy;
	@NonNull
	private final McpJsonObject metadata;
	@NonNull
	private final McpResourceHandler handler;

	/**
	 * Begins a staged exact-resource registration.
	 *
	 * @param uri absolute normalized resource URI in ASCII wire form
	 * @param name nonblank resource name
	 * @return required-handler stage
	 * @throws IllegalArgumentException if the URI is relative, not normalized,
	 * not in ASCII wire form, or the name is blank
	 */
	@NonNull
	public static ExactNamedBuilder withUriAndName(@NonNull URI uri,
			@NonNull String name) {
		return new ExactNamedBuilder(requireExactUri(uri), requireName(name));
	}

	/**
	 * Begins a staged URI-template resource registration.
	 *
	 * <p>This method performs inexpensive local validation. Endpoint/server
	 * construction performs complete RFC 6570 Level 1 parsing and route
	 * validation.
	 *
	 * @param uriTemplate nonblank URI template containing simple
	 * {@code {variable}} expressions
	 * @param name nonblank resource name
	 * @return required-handler stage
	 * @throws IllegalArgumentException if the template fails local structural
	 * validation or the name is blank
	 */
	@NonNull
	public static TemplateNamedBuilder withUriTemplateAndName(
			@NonNull String uriTemplate, @NonNull String name) {
		return new TemplateNamedBuilder(
				requireBasicUriTemplate(uriTemplate), requireName(name));
	}

	private McpResourceRegistration(@NonNull BuilderState state) {
		this.addressType = state.addressType;
		this.uri = state.uri;
		this.uriTemplate = state.uriTemplate;
		this.name = state.name;
		this.title = state.title;
		this.description = state.description;
		this.mimeType = state.mimeType;
		this.icons = List.copyOf(state.icons);
		this.annotations = state.annotations;
		this.size = state.size;
		this.cachePolicy = state.cachePolicy;
		this.metadata = state.metadata;
		this.handler = state.handler;
	}

	/** @return whether this registration uses an exact URI or URI template */
	@NonNull
	public McpResourceAddressType getAddressType() {
		return this.addressType;
	}

	/** @return exact resource URI, or empty for a template registration */
	@NonNull
	public Optional<@NonNull URI> getUri() {
		return Optional.ofNullable(this.uri);
	}

	/** @return resource URI template, or empty for an exact registration */
	@NonNull
	public Optional<@NonNull String> getUriTemplate() {
		return Optional.ofNullable(this.uriTemplate);
	}

	/** @return nonblank resource name */
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

	/** @return resource MIME type, if configured */
	@NonNull
	public Optional<@NonNull String> getMimeType() {
		return Optional.ofNullable(this.mimeType);
	}

	/** @return immutable icon list in insertion order */
	@NonNull
	public List<@NonNull McpIcon> getIcons() {
		return this.icons;
	}

	/** @return content annotations, if configured */
	@NonNull
	public Optional<@NonNull McpContentAnnotations> getAnnotations() {
		return Optional.ofNullable(this.annotations);
	}

	/**
	 * Returns the exact resource's advertised size.
	 *
	 * @return nonnegative byte count, or empty when omitted or templated
	 */
	@NonNull
	public Optional<@NonNull Long> getSize() {
		return Optional.ofNullable(this.size);
	}

	/** @return fixed scope and default time to live for resource reads */
	@NonNull
	public McpCachePolicy getCachePolicy() {
		return this.cachePolicy;
	}

	/** @return immutable protocol extension metadata */
	@NonNull
	public McpJsonObject getMetadata() {
		return this.metadata;
	}

	/** @return resource-read handler */
	@NonNull
	public McpResourceHandler getHandler() {
		return this.handler;
	}

	@NonNull
	private static URI requireExactUri(@NonNull URI uri) {
		return McpResourceValueSupport.requireAbsoluteNormalizedUri(uri);
	}

	@NonNull
	private static String requireName(@NonNull String name) {
		requireNonNull(name);
		if (name.isBlank())
			throw new IllegalArgumentException(
					"MCP resource names must not be blank.");
		return name;
	}

	@NonNull
	private static String requireMimeType(@NonNull String mimeType) {
		requireNonNull(mimeType);
		if (mimeType.isBlank())
			throw new IllegalArgumentException(
					"MCP resource MIME types must not be blank.");
		return mimeType;
	}

	@NonNull
	private static String requireBasicUriTemplate(
			@NonNull String uriTemplate) {
		requireNonNull(uriTemplate);
		if (uriTemplate.isBlank())
			throw new IllegalArgumentException(
					"MCP resource URI templates must not be blank.");

		boolean foundVariable = false;
		for (int index = 0; index < uriTemplate.length(); index++) {
			char character = uriTemplate.charAt(index);
			if (character == '}')
				throw invalidUriTemplate();
			if (character != '{')
				continue;

			int close = uriTemplate.indexOf('}', index + 1);
			if (close < 0)
				throw invalidUriTemplate();
			String variable = uriTemplate.substring(index + 1, close);
			if (variable.isEmpty() || variable.indexOf('{') >= 0)
				throw invalidUriTemplate();
			foundVariable = true;
			index = close;
		}
		if (!foundVariable)
			throw new IllegalArgumentException(
					"MCP resource URI templates must contain a variable expression.");
		return uriTemplate;
	}

	@NonNull
	private static IllegalArgumentException invalidUriTemplate() {
		return new IllegalArgumentException(
				"MCP resource URI templates must use balanced {variable} expressions.");
	}

	/**
	 * Required-handler stage for an exact resource.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class ExactNamedBuilder {
		@NonNull
		private final URI uri;
		@NonNull
		private final String name;

		private ExactNamedBuilder(@NonNull URI uri, @NonNull String name) {
			this.uri = requireNonNull(uri);
			this.name = requireNonNull(name);
		}

		/**
		 * Supplies the required resource-read handler.
		 *
		 * @param handler resource-read handler
		 * @return exact-resource optional-metadata builder
		 */
		@NonNull
		public ExactBuilder handler(@NonNull McpResourceHandler handler) {
			return new ExactBuilder(new BuilderState(this.uri, null,
					this.name, requireNonNull(handler)));
		}
	}

	/**
	 * Required-handler stage for a URI-template resource.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class TemplateNamedBuilder {
		@NonNull
		private final String uriTemplate;
		@NonNull
		private final String name;

		private TemplateNamedBuilder(@NonNull String uriTemplate,
				@NonNull String name) {
			this.uriTemplate = requireNonNull(uriTemplate);
			this.name = requireNonNull(name);
		}

		/**
		 * Supplies the required resource-read handler.
		 *
		 * @param handler resource-read handler
		 * @return template-resource optional-metadata builder
		 */
		@NonNull
		public TemplateBuilder handler(@NonNull McpResourceHandler handler) {
			return new TemplateBuilder(new BuilderState(null,
					this.uriTemplate, this.name, requireNonNull(handler)));
		}
	}

	/**
	 * Mutable builder for an immutable exact-resource registration.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class ExactBuilder {
		@NonNull
		private final BuilderState state;

		private ExactBuilder(@NonNull BuilderState state) {
			this.state = requireNonNull(state);
		}

		/** @param title human-readable title
		 * @return this builder */
		@NonNull
		public ExactBuilder title(@NonNull String title) {
			this.state.title = requireNonNull(title);
			return this;
		}

		/** @param description human-readable description
		 * @return this builder */
		@NonNull
		public ExactBuilder description(@NonNull String description) {
			this.state.description = requireNonNull(description);
			return this;
		}

		/** @param mimeType nonblank resource MIME type
		 * @return this builder
		 * @throws IllegalArgumentException if {@code mimeType} is blank */
		@NonNull
		public ExactBuilder mimeType(@NonNull String mimeType) {
			this.state.mimeType = requireMimeType(mimeType);
			return this;
		}

		/** @param icon icon descriptor to append
		 * @return this builder */
		@NonNull
		public ExactBuilder icon(@NonNull McpIcon icon) {
			this.state.icons.add(requireNonNull(icon));
			return this;
		}

		/** @param annotations content annotations
		 * @return this builder */
		@NonNull
		public ExactBuilder annotations(
				@NonNull McpContentAnnotations annotations) {
			this.state.annotations = requireNonNull(annotations);
			return this;
		}

		/**
		 * Sets the exact resource's size in bytes.
		 *
		 * @param size nonnegative byte count
		 * @return this builder
		 * @throws IllegalArgumentException if {@code size} is negative
		 */
		@NonNull
		public ExactBuilder size(long size) {
			if (size < 0)
				throw new IllegalArgumentException(
						"MCP resource sizes must not be negative.");
			this.state.size = size;
			return this;
		}

		/** @param cachePolicy fixed scope and default time to live
		 * @return this builder */
		@NonNull
		public ExactBuilder cachePolicy(@NonNull McpCachePolicy cachePolicy) {
			this.state.cachePolicy = requireNonNull(cachePolicy);
			return this;
		}

		/** @param metadata protocol extension metadata
		 * @return this builder */
		@NonNull
		public ExactBuilder metadata(@NonNull McpJsonObject metadata) {
			this.state.metadata = requireNonNull(metadata);
			return this;
		}

		/** @return immutable exact-resource registration */
		@NonNull
		public McpResourceRegistration build() {
			return new McpResourceRegistration(this.state);
		}
	}

	/**
	 * Mutable builder for an immutable URI-template resource registration.
	 *
	 * <p>Template registrations do not advertise a size because the value can
	 * vary for each concrete expansion.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class TemplateBuilder {
		@NonNull
		private final BuilderState state;

		private TemplateBuilder(@NonNull BuilderState state) {
			this.state = requireNonNull(state);
		}

		/** @param title human-readable title
		 * @return this builder */
		@NonNull
		public TemplateBuilder title(@NonNull String title) {
			this.state.title = requireNonNull(title);
			return this;
		}

		/** @param description human-readable description
		 * @return this builder */
		@NonNull
		public TemplateBuilder description(@NonNull String description) {
			this.state.description = requireNonNull(description);
			return this;
		}

		/** @param mimeType nonblank resource MIME type
		 * @return this builder
		 * @throws IllegalArgumentException if {@code mimeType} is blank */
		@NonNull
		public TemplateBuilder mimeType(@NonNull String mimeType) {
			this.state.mimeType = requireMimeType(mimeType);
			return this;
		}

		/** @param icon icon descriptor to append
		 * @return this builder */
		@NonNull
		public TemplateBuilder icon(@NonNull McpIcon icon) {
			this.state.icons.add(requireNonNull(icon));
			return this;
		}

		/** @param annotations content annotations
		 * @return this builder */
		@NonNull
		public TemplateBuilder annotations(
				@NonNull McpContentAnnotations annotations) {
			this.state.annotations = requireNonNull(annotations);
			return this;
		}

		/** @param cachePolicy fixed scope and default time to live
		 * @return this builder */
		@NonNull
		public TemplateBuilder cachePolicy(
				@NonNull McpCachePolicy cachePolicy) {
			this.state.cachePolicy = requireNonNull(cachePolicy);
			return this;
		}

		/** @param metadata protocol extension metadata
		 * @return this builder */
		@NonNull
		public TemplateBuilder metadata(@NonNull McpJsonObject metadata) {
			this.state.metadata = requireNonNull(metadata);
			return this;
		}

		/** @return immutable URI-template resource registration */
		@NonNull
		public McpResourceRegistration build() {
			return new McpResourceRegistration(this.state);
		}
	}

	@NotThreadSafe
	private static final class BuilderState {
		@NonNull
		private final McpResourceAddressType addressType;
		@Nullable
		private final URI uri;
		@Nullable
		private final String uriTemplate;
		@NonNull
		private final String name;
		@Nullable
		private String title;
		@Nullable
		private String description;
		@Nullable
		private String mimeType;
		@NonNull
		private final List<@NonNull McpIcon> icons = new ArrayList<>();
		@Nullable
		private McpContentAnnotations annotations;
		@Nullable
		private Long size;
		@NonNull
		private McpCachePolicy cachePolicy =
				McpCachePolicy.privateNoCacheInstance();
		@NonNull
		private McpJsonObject metadata = McpJsonObject.emptyInstance();
		@NonNull
		private final McpResourceHandler handler;

		private BuilderState(@Nullable URI uri,
				@Nullable String uriTemplate, @NonNull String name,
				@NonNull McpResourceHandler handler) {
			if ((uri == null) == (uriTemplate == null))
				throw new IllegalArgumentException(
						"Exactly one MCP resource address must be supplied.");
			this.addressType = uri == null
					? McpResourceAddressType.URI_TEMPLATE
					: McpResourceAddressType.URI;
			this.uri = uri;
			this.uriTemplate = uriTemplate;
			this.name = requireNonNull(name);
			this.handler = requireNonNull(handler);
		}
	}
}
