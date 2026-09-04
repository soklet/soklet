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

import com.soklet.InstanceProvider;
import com.soklet.McpEndpoint;
import com.soklet.McpRequestContext;
import com.soklet.McpToolSchema;
import com.soklet.McpToolRegistration;
import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import com.soklet.internal.mcp.protocol.McpPublicJsonValueConverter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * Strict runtime loader for generated MCP endpoint providers.
 *
 * <p>This type is public only because the core public registry delegates to an
 * internal package. It is not part of Soklet's supported public API or
 * published Javadocs.</p>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpGeneratedEndpointProviderLoader {
	private static final String ABSENT_OUTPUT_SCHEMA_DIGEST =
			"NO_OUTPUT_SCHEMA";

	/**
	 * Internal request-context contract used only by generated endpoint adapters.
	 * It deliberately exposes instance acquisition rather than the owning
	 * application provider itself.
	 */
	public interface GeneratedInvocationContext {
		/**
		 * Acquires the application instance for one generated endpoint invocation.
		 *
		 * @param instanceClass generated endpoint class
		 * @param <T> endpoint type
		 * @return application-owned endpoint instance
		 */
		@NonNull
		<T> T provideGeneratedEndpointInstance(@NonNull Class<T> instanceClass);
	}

	private McpGeneratedEndpointProviderLoader() {
	}

	/**
	 * Loads every indexed endpoint visible through a class loader.
	 *
	 * @param classLoader class loader used for index and provider discovery
	 * @return immutable endpoints ordered by endpoint binary name
	 */
	@NonNull
	public static List<@NonNull McpEndpoint> loadAll(
			@NonNull ClassLoader classLoader) {
		return List.copyOf(loadAllWithProvenance(classLoader).values());
	}

	/**
	 * Loads every indexed endpoint and its exact source-class identity.
	 *
	 * @param classLoader class loader used for index and provider discovery
	 * @return immutable class-to-endpoint mappings ordered by endpoint binary name
	 */
	@NonNull
	public static Map<@NonNull Class<?>, @NonNull McpEndpoint>
			loadAllWithProvenance(@NonNull ClassLoader classLoader) {
		requireNonNull(classLoader);
		Map<String, McpGeneratedEndpointProviderIndex.Entry> entries =
				readEntries(classLoader);
		if (entries.isEmpty())
			throw new IllegalStateException(
					"No generated MCP endpoint descriptors were found.");
		Map<Class<?>, McpEndpoint> endpoints = new LinkedHashMap<>();
		for (McpGeneratedEndpointProviderIndex.Entry entry : entries.values()) {
			LoadedEndpoint loadedEndpoint = loadEndpoint(classLoader, entry, null);
			endpoints.put(loadedEndpoint.endpointClass(),
					loadedEndpoint.endpoint());
		}
		return Collections.unmodifiableMap(endpoints);
	}

	/**
	 * Loads generated descriptors for explicitly selected endpoint classes.
	 *
	 * @param endpointClasses endpoint classes in caller-selected order
	 * @return immutable endpoints in the supplied order
	 */
	@NonNull
	public static List<@NonNull McpEndpoint> loadClasses(
			@NonNull List<@NonNull Class<?>> endpointClasses) {
		return List.copyOf(loadClassesWithProvenance(endpointClasses).values());
	}

	/**
	 * Loads selected generated endpoints and their exact source-class identities.
	 *
	 * @param endpointClasses endpoint classes in caller-selected order
	 * @return immutable class-to-endpoint mappings in the supplied order
	 */
	@NonNull
	public static Map<@NonNull Class<?>, @NonNull McpEndpoint>
			loadClassesWithProvenance(
					@NonNull List<@NonNull Class<?>> endpointClasses) {
		requireNonNull(endpointClasses);
		if (endpointClasses.isEmpty())
			throw new IllegalArgumentException(
					"At least one annotated MCP endpoint class is required.");
		Set<Class<?>> selected = Collections.newSetFromMap(
				new IdentityHashMap<>());
		IdentityHashMap<ClassLoader,
				Map<String, McpGeneratedEndpointProviderIndex.Entry>>
				entriesByLoader = new IdentityHashMap<>();
		Map<Class<?>, McpEndpoint> endpoints = new LinkedHashMap<>();

		for (Class<?> endpointClass : endpointClasses) {
			requireNonNull(endpointClass);
			if (!selected.add(endpointClass))
				throw new IllegalArgumentException(
						"Duplicate annotated MCP endpoint class '"
								+ endpointClass.getName() + "'.");
			ClassLoader classLoader = classLoader(endpointClass);
			Map<String, McpGeneratedEndpointProviderIndex.Entry> entries =
					entriesByLoader.computeIfAbsent(classLoader,
							McpGeneratedEndpointProviderLoader::readEntries);
			McpGeneratedEndpointProviderIndex.Entry entry =
					entries.get(endpointClass.getName());
			if (entry == null)
				throw new IllegalArgumentException(
						"No generated MCP endpoint descriptor exists for '"
								+ endpointClass.getName() + "'.");
			LoadedEndpoint loadedEndpoint = loadEndpoint(classLoader, entry,
					endpointClass);
			endpoints.put(loadedEndpoint.endpointClass(),
					loadedEndpoint.endpoint());
		}

		return Collections.unmodifiableMap(endpoints);
	}

	@NonNull
	private static Map<String, McpGeneratedEndpointProviderIndex.Entry> readEntries(
			@NonNull ClassLoader classLoader) {
		List<McpGeneratedEndpointProviderIndex.Entry> discovered =
				new ArrayList<>();
		try {
			Enumeration<URL> resources = classLoader.getResources(
					McpGeneratedEndpointProviderIndex.RESOURCE_PATH);
			while (resources.hasMoreElements()) {
				URL resource = resources.nextElement();
				try (BufferedReader reader = new BufferedReader(
						new InputStreamReader(resource.openStream(),
								StandardCharsets.UTF_8))) {
					int lineNumber = 0;
					for (String line; (line = reader.readLine()) != null; ) {
						lineNumber++;
						String stripped = line.strip();
						if (stripped.isEmpty() || stripped.startsWith("#"))
							continue;
						try {
							discovered.add(
									McpGeneratedEndpointProviderIndex.parseLine(
											stripped));
						} catch (IllegalArgumentException exception) {
							throw new IllegalStateException(
									"Malformed generated MCP endpoint-provider index at "
											+ resource + ":" + lineNumber + ".",
									exception);
						}
					}
				}
			}
		} catch (IOException exception) {
			throw new IllegalStateException(
					"Unable to read generated MCP endpoint-provider indexes.",
					exception);
		}

		discovered.sort(Comparator
				.comparing(McpGeneratedEndpointProviderIndex.Entry::endpointClassName)
				.thenComparing(
						McpGeneratedEndpointProviderIndex.Entry::providerClassName)
				.thenComparing(
						McpGeneratedEndpointProviderIndex.Entry::topLevelClassName)
				.thenComparing(
						McpGeneratedEndpointProviderIndex.Entry::endpointPath));
		Map<String, McpGeneratedEndpointProviderIndex.Entry> byEndpoint =
				new LinkedHashMap<>();
		Map<String, String> endpointByProvider = new LinkedHashMap<>();
		for (McpGeneratedEndpointProviderIndex.Entry entry : discovered) {
			McpGeneratedEndpointProviderIndex.Entry previous =
					byEndpoint.putIfAbsent(entry.endpointClassName(), entry);
			if (previous != null && !previous.equals(entry))
				throw new IllegalStateException(
						"Conflicting generated MCP endpoint providers for '"
								+ entry.endpointClassName() + "'.");
			String previousEndpoint = endpointByProvider.putIfAbsent(
					entry.providerClassName(), entry.endpointClassName());
			if (previousEndpoint != null
					&& !previousEndpoint.equals(entry.endpointClassName()))
				throw new IllegalStateException(
						"Generated MCP endpoint provider '"
								+ entry.providerClassName()
								+ "' is assigned to multiple endpoint classes.");
		}
		return Collections.unmodifiableMap(byEndpoint);
	}

	@NonNull
	private static LoadedEndpoint loadEndpoint(@NonNull ClassLoader classLoader,
			McpGeneratedEndpointProviderIndex.@NonNull Entry entry,
			@Nullable Class<?> selectedEndpointClass) {
		try {
			Class<?> endpointClass = selectedEndpointClass == null
					? Class.forName(entry.endpointClassName(), false, classLoader)
					: selectedEndpointClass;
			Class<?> topLevelClass = endpointClass;
			while (topLevelClass.getEnclosingClass() != null)
				topLevelClass = topLevelClass.getEnclosingClass();
			if (!entry.topLevelClassName().equals(topLevelClass.getName()))
				throw new IllegalStateException(
						"Generated MCP endpoint top-level owner does not match '"
								+ entry.endpointClassName() + "'.");
			Class<?> providerClass = Class.forName(entry.providerClassName(),
					false, classLoader);
			if (!Modifier.isPublic(providerClass.getModifiers())
					|| !Modifier.isFinal(providerClass.getModifiers()))
				throw new IllegalStateException(
						"Generated MCP endpoint provider '"
								+ entry.providerClassName()
								+ "' must be public and final.");
			Method endpointClassMethod = providerClass.getDeclaredMethod(
					"endpointClass");
			Method endpointMethod = providerClass.getDeclaredMethod("endpoint",
					Function.class);
			Method schemaDigestsMethod = providerClass.getDeclaredMethod(
					"schemaDigests");
			if (endpointClassMethod.getReturnType() != Class.class
					|| endpointMethod.getReturnType() != McpEndpoint.class
					|| schemaDigestsMethod.getReturnType() != String[].class
					|| !publicInstanceMethod(endpointClassMethod)
					|| !publicInstanceMethod(endpointMethod)
					|| !publicInstanceMethod(schemaDigestsMethod))
				throw new IllegalStateException(
						"Generated MCP endpoint provider '"
								+ entry.providerClassName()
								+ "' has an incompatible provider contract.");
			Constructor<?> constructor = providerClass.getConstructor();
			requireReflectiveAccess(constructor, entry);
			requireReflectiveAccess(endpointClassMethod, entry);
			requireReflectiveAccess(endpointMethod, entry);
			requireReflectiveAccess(schemaDigestsMethod, entry);
			Object provider = constructor.newInstance();
			Class<?> providerEndpointClass = (Class<?>) requireProviderValue(
					endpointClassMethod.invoke(provider),
					"The generated MCP endpoint provider returned a null endpoint class.");
			if (providerEndpointClass != endpointClass
					|| !entry.endpointClassName().equals(endpointClass.getName()))
				throw new IllegalStateException(
						"Generated MCP endpoint provider identity does not match '"
								+ entry.endpointClassName() + "'.");
			McpEndpoint endpoint = (McpEndpoint) requireProviderValue(
					endpointMethod.invoke(provider,
							instanceResolver(endpointClass)),
					"The generated MCP endpoint provider returned null.");
			if (!entry.endpointPath().equals(endpoint.getPath()))
				throw new IllegalStateException(
						"Generated MCP endpoint path does not match '"
								+ entry.endpointClassName() + "'.");
			String[] schemaDigests = (String[]) requireProviderValue(
					schemaDigestsMethod.invoke(provider),
					"The generated MCP endpoint provider returned null schema digests.");
			verifySchemaDigests(entry, endpoint, schemaDigests);
			return new LoadedEndpoint(endpointClass, endpoint);
		} catch (IllegalStateException exception) {
			throw exception;
		} catch (InvocationTargetException exception) {
			Throwable cause = exception.getCause();
			throw new IllegalStateException(
					"Unable to create generated MCP endpoint descriptor for '"
							+ entry.endpointClassName() + "'.",
					cause == null ? exception : cause);
		} catch (ReflectiveOperationException | LinkageError exception) {
			throw new IllegalStateException(
					"Unable to load generated MCP endpoint descriptor for '"
							+ entry.endpointClassName() + "'.",
					exception);
		}
	}

	@NonNull
	private static <T> Function<@NonNull McpRequestContext, @NonNull T>
			instanceResolver(@NonNull Class<T> endpointClass) {
		Class<T> exactEndpointClass = requireNonNull(endpointClass);
		return requestContext -> {
			McpRequestContext exactContext = requireNonNull(requestContext);
			T endpointInstance = exactContext
					instanceof GeneratedInvocationContext context
					? context.provideGeneratedEndpointInstance(exactEndpointClass)
					: InstanceProvider.defaultInstance().provide(exactEndpointClass);
			return requireNonNull(endpointInstance,
					"The application instance provider returned null for '"
							+ exactEndpointClass.getName() + "'.");
		};
	}

	private record LoadedEndpoint(@NonNull Class<?> endpointClass,
			@NonNull McpEndpoint endpoint) {
		private LoadedEndpoint {
			requireNonNull(endpointClass);
			requireNonNull(endpoint);
		}
	}

	private static boolean publicInstanceMethod(@NonNull Method method) {
		int modifiers = method.getModifiers();
		return Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers);
	}

	private static void requireReflectiveAccess(
			@NonNull AccessibleObject member,
			McpGeneratedEndpointProviderIndex.@NonNull Entry entry) {
		try {
			if (!member.trySetAccessible())
				throw inaccessibleProvider(entry, null);
		} catch (SecurityException exception) {
			throw inaccessibleProvider(entry, exception);
		}
	}

	@NonNull
	private static IllegalStateException inaccessibleProvider(
			McpGeneratedEndpointProviderIndex.@NonNull Entry entry,
			@Nullable Throwable cause) {
		return new IllegalStateException(
				"Generated MCP endpoint provider '" + entry.providerClassName()
						+ "' is not reflectively accessible. Named application modules must open or export the endpoint package to Soklet.",
				cause);
	}

	@NonNull
	private static Object requireProviderValue(@Nullable Object value,
			@NonNull String message) {
		if (value == null)
			throw new IllegalStateException(message);
		return value;
	}

	private static void verifySchemaDigests(
			McpGeneratedEndpointProviderIndex.@NonNull Entry entry,
			@NonNull McpEndpoint endpoint,
			String @NonNull [] schemaDigests) {
		List<McpToolRegistration<?>> tools = endpoint.getTools();
		if (schemaDigests.length != tools.size() * 3)
			throw schemaMismatch(entry);

		for (int index = 0; index < tools.size(); index++) {
			McpToolRegistration<?> tool = tools.get(index);
			int digestIndex = index * 3;
			String expectedOutputDigest = schemaDigests[digestIndex + 2];
			boolean outputSchemaMatches = tool.getOutputSchema()
					.map(outputSchema -> schemaDigest(outputSchema).equals(
							expectedOutputDigest))
					.orElseGet(() -> ABSENT_OUTPUT_SCHEMA_DIGEST.equals(
							expectedOutputDigest));
			if (!tool.getName().equals(schemaDigests[digestIndex])
					|| !schemaDigest(tool.getInputSchema()).equals(
						schemaDigests[digestIndex + 1])
					|| !outputSchemaMatches)
				throw schemaMismatch(entry);
		}
	}

	@NonNull
	private static IllegalStateException schemaMismatch(
			McpGeneratedEndpointProviderIndex.@NonNull Entry entry) {
		return new IllegalStateException(
				"Generated MCP endpoint provider schema does not match its runtime types for '"
						+ entry.endpointClassName() + "'.");
	}

	@NonNull
	private static String schemaDigest(@NonNull McpToolSchema schema) {
		byte[] canonicalBytes = new McpJsonCodec(
				McpJsonLimits.productionDefaults()).toUtf8Bytes(
				McpPublicJsonValueConverter.toInternal(schema.getDocument()));
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(canonicalBytes);
			char[] hexadecimal = new char[digest.length * 2];
			char[] digits = "0123456789abcdef".toCharArray();
			for (int index = 0; index < digest.length; index++) {
				int value = digest[index] & 0xff;
				hexadecimal[index * 2] = digits[value >>> 4];
				hexadecimal[index * 2 + 1] = digits[value & 0xf];
			}
			return new String(hexadecimal);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available.", exception);
		}
	}

	@NonNull
	private static ClassLoader classLoader(@NonNull Class<?> endpointClass) {
		ClassLoader classLoader = endpointClass.getClassLoader();
		if (classLoader != null)
			return classLoader;
		ClassLoader contextClassLoader =
				Thread.currentThread().getContextClassLoader();
		if (contextClassLoader != null)
			return contextClassLoader;
		return McpGeneratedEndpointProviderLoader.class.getClassLoader();
	}
}
