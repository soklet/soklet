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

package com.soklet.internal.mcp.generated;

import com.soklet.ResourcePathDeclaration;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static java.util.Objects.requireNonNull;

/**
 * Internal versioned encoding for the generated endpoint-provider index.
 *
 * <p>This type is public only so {@code SokletProcessor} can share the exact
 * encoding with the runtime loader. It is not part of Soklet's supported
 * public API or published Javadocs.</p>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpGeneratedEndpointProviderIndex {
	/** Generated classpath index location. */
	@NonNull
	public static final String RESOURCE_PATH =
			"META-INF/soklet/mcp-endpoint-descriptor-providers";
	@NonNull
	private static final String FORMAT_VERSION = "3";

	private McpGeneratedEndpointProviderIndex() {
	}

	/**
	 * Encodes one deterministic provider-index row.
	 *
	 * @param endpointClassName annotated endpoint binary name
	 * @param providerClassName generated provider binary name
	 * @param topLevelClassName top-level source owner binary name
	 * @param endpointPath normalized fixed endpoint path
	 * @return one index row without a trailing newline
	 */
	@NonNull
	public static String formatLine(@NonNull String endpointClassName,
			@NonNull String providerClassName,
			@NonNull String topLevelClassName,
			@NonNull String endpointPath) {
		endpointClassName = requireBinaryName(endpointClassName);
		topLevelClassName = requireBinaryName(topLevelClassName);
		requireTopLevelOwner(endpointClassName, topLevelClassName);
		return String.join("|", FORMAT_VERSION,
				encode(endpointClassName),
				encode(requireBinaryName(providerClassName)),
				encode(topLevelClassName),
				encode(requireEndpointPath(endpointPath)));
	}

	@NonNull
	static Entry parseLine(@NonNull String line) {
		requireNonNull(line);
		String[] fields = line.split("\\|", -1);
		if (fields.length != 5 || !FORMAT_VERSION.equals(fields[0]))
			throw new IllegalArgumentException(
					"Unsupported generated MCP endpoint-provider index row.");
		try {
			String endpointClassName = requireBinaryName(decode(fields[1]));
			String providerClassName = requireBinaryName(decode(fields[2]));
			String topLevelClassName = requireBinaryName(decode(fields[3]));
			String endpointPath = requireEndpointPath(decode(fields[4]));
			requireTopLevelOwner(endpointClassName, topLevelClassName);
			return new Entry(endpointClassName, providerClassName,
					topLevelClassName, endpointPath);
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException(
					"Malformed generated MCP endpoint-provider index row.",
					exception);
		}
	}

	@NonNull
	private static String encode(@NonNull String value) {
		return Base64.getEncoder().encodeToString(
				value.getBytes(StandardCharsets.UTF_8));
	}

	@NonNull
	private static String decode(@NonNull String value) {
		return new String(Base64.getDecoder().decode(value),
				StandardCharsets.UTF_8);
	}

	@NonNull
	private static String requireBinaryName(@NonNull String value) {
		requireNonNull(value);
		if (value.isBlank() || value.codePoints().anyMatch(
				character -> Character.isWhitespace(character)
						|| Character.isISOControl(character)))
			throw new IllegalArgumentException(
					"Generated MCP class names must be nonblank and contain no whitespace or control characters.");
		return value;
	}

	private static void requireTopLevelOwner(@NonNull String endpointClassName,
			@NonNull String topLevelClassName) {
		if (!endpointClassName.equals(topLevelClassName)
				&& !endpointClassName.startsWith(topLevelClassName + "$"))
			throw new IllegalArgumentException(
					"Generated MCP endpoint class must belong to its top-level source owner.");
	}

	@NonNull
	private static String requireEndpointPath(@NonNull String endpointPath) {
		requireNonNull(endpointPath);
		if (!endpointPath.startsWith("/") || endpointPath.length() == 1
				|| endpointPath.contains("?") || endpointPath.contains("#")
				|| endpointPath.indexOf('{') >= 0
				|| endpointPath.indexOf('}') >= 0
				|| !ResourcePathDeclaration.fromPath(endpointPath).getPath()
						.equals(endpointPath))
			throw new IllegalArgumentException(
					"Generated MCP endpoint path must be a normalized, fixed, non-root absolute path.");
		return endpointPath;
	}

	/**
	 * One decoded endpoint/provider association.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	static record Entry(@NonNull String endpointClassName,
			@NonNull String providerClassName,
			@NonNull String topLevelClassName,
			@NonNull String endpointPath) {
		Entry {
			requireNonNull(endpointClassName);
			requireNonNull(providerClassName);
			requireNonNull(topLevelClassName);
			requireNonNull(endpointPath);
		}
	}
}
