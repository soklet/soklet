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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Focused public signature and value contracts for MCP localization.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpLocalizationPublicApiTests {
	@Test
	public void localizationSurfaceExposesExactPublicMethodsAndClosedValues() {
		Map<Class<?>, Set<String>> expectedMethods = Map.ofEntries(
				Map.entry(McpLocalizer.class, Set.of(
						"getContextProvider()",
						"getFailurePolicy()",
						"getFallbackLocale()",
						"getMaximumLocalizableTextCountPerResponse()",
						"withFallbackLocale(java.util.Locale)")),
				Map.entry(McpLocalizer.Builder.class, Set.of(
						"build()",
						"failurePolicy(com.soklet.McpLocalizationFailurePolicy)",
						"maximumLocalizableTextCountPerResponse(java.lang.Integer)")),
				Map.entry(McpLocalizer.ContextProviderStage.class, Set.of(
						"contextProvider(com.soklet.McpLocalizationContextProvider)")),
				Map.entry(McpLocalizationContextProvider.class, Set.of(
						"createContext(com.soklet.McpLocalizationRequest)")),
				Map.entry(McpLocalizationRequest.class, Set.of(
						"getContinuationLocale()",
						"getFallbackLocale()",
						"getLanguageRanges()",
						"getRequestContext()",
						"getResourceListCursor()")),
				Map.entry(McpLocalizationContext.class, Set.of(
						"getLocale()",
						"getRevision()",
						"localize(com.soklet.McpLocalizableText)")),
				Map.entry(McpLocalizationResult.class, Set.of(
						"fromDefaultText()",
						"fromFailure()",
						"fromFallbackText(java.lang.String,java.util.Locale)",
						"fromLocalizedText(java.lang.String)")),
				Map.entry(McpLocalizationRevision.class, Set.of(
						"equals(java.lang.Object)",
						"fromValue(java.lang.String)",
						"getValue()",
						"hashCode()",
						"toString()")),
				Map.entry(McpLocalizationCatalog.class, Set.of(
						"fromHandlerResolver(com.soklet.McpHandlerResolver)",
						"getTexts()")),
				Map.entry(McpLocalizationControl.class, Set.of(
						"catalogsChanged()", "isEnabled()")),
				Map.entry(McpLocalizableText.class, Set.of(
						"equals(java.lang.Object)",
						"getCoordinate()",
						"getDefaultText()",
						"hashCode()",
						"toString()")),
				Map.entry(McpTextCoordinate.class, Set.of(
						"equals(java.lang.Object)",
						"getEndpointPath()",
						"getKind()",
						"getMemberPath()",
						"getSubjectIdentifier()",
						"hashCode()",
						"toExternalKey()",
						"toString()")));

		for (Map.Entry<Class<?>, Set<String>> entry : expectedMethods.entrySet())
			Assertions.assertEquals(entry.getValue(), publicDeclaredMethods(
					entry.getKey()), () -> entry.getKey().getName()
					+ " public method surface changed");

		Assertions.assertTrue(McpLocalizationResult.class.isSealed());
		Assertions.assertEquals(Set.of(
				McpLocalizationResult.Localized.class,
				McpLocalizationResult.Fallback.class,
				McpLocalizationResult.UseDefaultText.class,
				McpLocalizationResult.Failure.class),
				Set.of(McpLocalizationResult.class.getPermittedSubclasses()));
		assertRecordComponents(McpLocalizationResult.Localized.class, "text");
		assertRecordComponents(McpLocalizationResult.Fallback.class, "text",
				"resolvedLocale");
		assertRecordComponents(McpLocalizationResult.UseDefaultText.class);
		assertRecordComponents(McpLocalizationResult.Failure.class);
		Assertions.assertArrayEquals(new McpLocalizationFailurePolicy[]{
				McpLocalizationFailurePolicy.USE_DEFAULT_TEXT,
				McpLocalizationFailurePolicy.FAIL_REQUEST
		}, McpLocalizationFailurePolicy.values());
		Assertions.assertArrayEquals(new McpTextCoordinate.Kind[]{
				McpTextCoordinate.Kind.SERVER_INFORMATION,
				McpTextCoordinate.Kind.ENDPOINT,
				McpTextCoordinate.Kind.TOOL,
				McpTextCoordinate.Kind.PROMPT,
				McpTextCoordinate.Kind.RESOURCE,
				McpTextCoordinate.Kind.RESOURCE_TEMPLATE
		}, McpTextCoordinate.Kind.values());
	}

	@Test
	public void localizationSurfaceUsesExactNullnessAndCallbackThrowsContracts()
			throws Exception {
		List<Class<?>> types = List.of(
				McpLocalizer.class,
				McpLocalizer.Builder.class,
				McpLocalizer.ContextProviderStage.class,
				McpLocalizationContextProvider.class,
				McpLocalizationRequest.class,
				McpLocalizationContext.class,
				McpLocalizationResult.class,
				McpLocalizationResult.Localized.class,
				McpLocalizationResult.Fallback.class,
				McpLocalizationResult.UseDefaultText.class,
				McpLocalizationResult.Failure.class,
				McpLocalizationRevision.class,
				McpLocalizationCatalog.class,
				McpLocalizationControl.class,
				McpLocalizableText.class,
				McpTextCoordinate.class);

		for (Class<?> type : types) {
			for (Method method : type.getDeclaredMethods()) {
				if (!Modifier.isPublic(method.getModifiers())
						|| isObjectContract(method))
					continue;
				if (method.getReturnType() != void.class
						&& !method.getReturnType().isPrimitive())
					Assertions.assertTrue(method.getAnnotatedReturnType()
							.isAnnotationPresent(NonNull.class), method.toString());
				for (AnnotatedType parameter : method.getAnnotatedParameterTypes())
					Assertions.assertTrue(parameter.isAnnotationPresent(
							NonNull.class), method.toString());
			}
			for (Constructor<?> constructor : type.getDeclaredConstructors()) {
				if (!Modifier.isPublic(constructor.getModifiers()))
					continue;
				for (AnnotatedType parameter
						: constructor.getAnnotatedParameterTypes())
					Assertions.assertTrue(parameter.isAnnotationPresent(
							NonNull.class), constructor.toString());
			}
		}

		Method createContext = McpLocalizationContextProvider.class.getMethod(
				"createContext", McpLocalizationRequest.class);
		Method localize = McpLocalizationContext.class.getMethod("localize",
				McpLocalizableText.class);
		Assertions.assertArrayEquals(new Class<?>[]{Exception.class},
				createContext.getExceptionTypes());
		Assertions.assertArrayEquals(new Class<?>[0],
				localize.getExceptionTypes());
		Assertions.assertNotNull(McpLocalizationContextProvider.class
				.getAnnotation(FunctionalInterface.class));
		Assertions.assertNotNull(McpLocalizer.ContextProviderStage.class
				.getAnnotation(FunctionalInterface.class));

		assertParameterizedPayload(McpLocalizationRequest.class.getMethod(
				"getLanguageRanges"), Locale.LanguageRange.class);
		assertParameterizedPayload(McpLocalizationRequest.class.getMethod(
				"getContinuationLocale"), Locale.class);
		assertParameterizedPayload(McpLocalizationRequest.class.getMethod(
				"getResourceListCursor"), String.class);
		assertParameterizedPayload(McpLocalizationContext.class.getMethod(
				"getRevision"), McpLocalizationRevision.class);
		assertParameterizedPayload(McpLocalizationCatalog.class.getMethod(
				"getTexts"), McpLocalizableText.class);
	}

	@Test
	public void localizerUsesStagedConstructionAndReviewedBounds() {
		McpLocalizationContextProvider provider = request -> context(
				request.getFallbackLocale());
		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH)
				.contextProvider(provider)
				.build();

		Assertions.assertEquals(Locale.ENGLISH, localizer.getFallbackLocale());
		Assertions.assertSame(provider, localizer.getContextProvider());
		Assertions.assertEquals(McpLocalizationFailurePolicy.USE_DEFAULT_TEXT,
				localizer.getFailurePolicy());
		Assertions.assertEquals(32_768,
				localizer.getMaximumLocalizableTextCountPerResponse());

		McpLocalizer configured = McpLocalizer.withFallbackLocale(Locale.ENGLISH)
				.contextProvider(provider)
				.failurePolicy(McpLocalizationFailurePolicy.FAIL_REQUEST)
				.maximumLocalizableTextCountPerResponse(100_000)
				.build();
		Assertions.assertEquals(McpLocalizationFailurePolicy.FAIL_REQUEST,
				configured.getFailurePolicy());
		Assertions.assertEquals(100_000,
				configured.getMaximumLocalizableTextCountPerResponse());

		Assertions.assertThrows(NullPointerException.class,
				() -> McpLocalizer.withFallbackLocale(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpLocalizer.withFallbackLocale(Locale.ENGLISH)
						.contextProvider(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpLocalizer.withFallbackLocale(Locale.ENGLISH)
						.contextProvider(provider).failurePolicy(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpLocalizer.withFallbackLocale(Locale.ENGLISH)
						.contextProvider(provider)
						.maximumLocalizableTextCountPerResponse(null));
		for (Integer invalid : List.of(-1, 0, 100_001, Integer.MAX_VALUE))
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> McpLocalizer.withFallbackLocale(Locale.ENGLISH)
							.contextProvider(provider)
							.maximumLocalizableTextCountPerResponse(invalid));
	}

	@Test
	public void localizationLocalesAreCanonicalBoundedAndAllowPrivateUse() {
		McpLocalizationContextProvider provider = request -> context(
				request.getFallbackLocale());
		Locale privateUse = Locale.forLanguageTag("x-private");
		Assertions.assertEquals(privateUse,
				McpLocalizer.withFallbackLocale(privateUse)
						.contextProvider(provider).build().getFallbackLocale());

		for (Locale invalid : List.of(Locale.ROOT,
				Locale.forLanguageTag("und"),
				Locale.forLanguageTag("und-Latn"))) {
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> McpLocalizer.withFallbackLocale(invalid));
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> McpLocalizationResult.fromFallbackText(
							"translation", invalid));
		}
	}

	@Test
	public void localizationResultsValidateValuesAndRedactSensitiveText() {
		McpLocalizationResult.Localized localized =
				McpLocalizationResult.fromLocalizedText("translated secret");
		McpLocalizationResult.Fallback fallback =
				McpLocalizationResult.fromFallbackText(
						"fallback secret", Locale.FRENCH);
		McpLocalizationResult.UseDefaultText defaultText =
				McpLocalizationResult.fromDefaultText();
		McpLocalizationResult.Failure failure =
				McpLocalizationResult.fromFailure();

		Assertions.assertEquals("translated secret", localized.text());
		Assertions.assertEquals("fallback secret", fallback.text());
		Assertions.assertEquals(Locale.FRENCH, fallback.resolvedLocale());
		Assertions.assertEquals(new McpLocalizationResult.UseDefaultText(),
				defaultText);
		Assertions.assertEquals(new McpLocalizationResult.Failure(), failure);
		Assertions.assertFalse(localized.toString().contains("translated secret"));
		Assertions.assertFalse(fallback.toString().contains("fallback secret"));
		Assertions.assertFalse(fallback.toString().contains("fr"));
		Assertions.assertEquals(0, failure.getClass().getRecordComponents().length);

		Assertions.assertThrows(NullPointerException.class,
				() -> McpLocalizationResult.fromLocalizedText(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpLocalizationResult.fromFallbackText(null, Locale.FRENCH));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpLocalizationResult.fromFallbackText("text", null));
		for (String blank : List.of("", " ", "\t\n")) {
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> McpLocalizationResult.fromLocalizedText(blank));
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> McpLocalizationResult.fromFallbackText(
							blank, Locale.FRENCH));
		}
	}

	@Test
	public void revisionsAreVisibleAsciiBoundedImmutableValues() {
		McpLocalizationRevision revision =
				McpLocalizationRevision.fromValue("release-2026.08.12");
		Assertions.assertEquals("release-2026.08.12", revision.getValue());
		Assertions.assertEquals(McpLocalizationRevision.fromValue(
				"release-2026.08.12"), revision);
		Assertions.assertEquals(McpLocalizationRevision.fromValue(
				"release-2026.08.12").hashCode(), revision.hashCode());
		Assertions.assertFalse(revision.toString().contains("release-2026.08.12"));
		Assertions.assertEquals("x".repeat(128),
				McpLocalizationRevision.fromValue("x".repeat(128)).getValue());

		Assertions.assertThrows(NullPointerException.class,
				() -> McpLocalizationRevision.fromValue(null));
		for (String invalid : List.of("", "has space", "line\nbreak",
				"é", "x".repeat(129)))
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> McpLocalizationRevision.fromValue(invalid));
	}

	@Test
	public void coordinateExternalKeysUseTheFrozenDomainSeparatedEncoding() {
		McpTextCoordinate coordinate = new McpTextCoordinate("/catalog/mcp",
				McpTextCoordinate.Kind.TOOL, "catalog.search", "/description");
		McpTextCoordinate equal = new McpTextCoordinate("/catalog/mcp",
				McpTextCoordinate.Kind.TOOL, "catalog.search", "/description");
		McpTextCoordinate unequal = new McpTextCoordinate("/catalog/mcp",
				McpTextCoordinate.Kind.TOOL, "catalog.search", "/title");

		Assertions.assertEquals(
				"soklet-mcp-text-v1.tool.bwpmb6shUs9Ii2yE2ivLnb-RJfIRQGrj82_uS3QmxJw",
				coordinate.toExternalKey());
		Assertions.assertEquals(coordinate, equal);
		Assertions.assertEquals(coordinate.hashCode(), equal.hashCode());
		Assertions.assertEquals(coordinate.toExternalKey(), equal.toExternalKey());
		Assertions.assertNotEquals(coordinate, unequal);
		Assertions.assertNotEquals(coordinate.toExternalKey(),
				unequal.toExternalKey());
		Assertions.assertFalse(coordinate.toString().contains("/catalog/mcp"));
		Assertions.assertFalse(coordinate.toString().contains("catalog.search"));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new McpTextCoordinate("/catalog/mcp",
						McpTextCoordinate.Kind.TOOL, "bad\uD800", "/title"));
	}

	@Test
	public void catalogAndServerHostSeamsAreInertButUsable() {
		McpHandlerResolver resolver = resolver();
		McpLocalizationCatalog catalog =
				McpLocalizationCatalog.fromHandlerResolver(resolver);
		Assertions.assertTrue(catalog.getTexts().isEmpty());
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> catalog.getTexts().add(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpLocalizationCatalog.fromHandlerResolver(null));

		McpServer disabled = serverBuilder(resolver).build();
		McpLocalizationControl disabledControl =
				disabled.getLocalizationControl();
		Assertions.assertSame(disabledControl,
				disabled.getLocalizationControl());
		Assertions.assertFalse(disabledControl.isEnabled());
		Assertions.assertTrue(((DefaultMcpServer) disabled).localizer().isEmpty());
		Assertions.assertThrows(IllegalStateException.class,
				disabledControl::catalogsChanged);

		McpLocalizer localizer = McpLocalizer.withFallbackLocale(Locale.ENGLISH)
				.contextProvider(request -> context(Locale.ENGLISH))
				.build();
		McpServer enabled = serverBuilder(resolver).localizer(localizer).build();
		McpLocalizationControl enabledControl = enabled.getLocalizationControl();
		Assertions.assertSame(enabledControl, enabled.getLocalizationControl());
		Assertions.assertNotSame(disabledControl, enabledControl);
		Assertions.assertTrue(enabledControl.isEnabled());
		Assertions.assertSame(localizer,
				((DefaultMcpServer) enabled).localizer().orElseThrow());
		Assertions.assertDoesNotThrow(
				enabledControl::catalogsChanged);
		enabled.stop();
		Assertions.assertDoesNotThrow(
				enabledControl::catalogsChanged);

		McpServer anotherEnabled = serverBuilder(resolver)
				.localizer(localizer).build();
		Assertions.assertNotSame(enabledControl,
				anotherEnabled.getLocalizationControl());
		Assertions.assertTrue(anotherEnabled.getLocalizationControl().isEnabled());

		Assertions.assertThrows(NullPointerException.class,
				() -> serverBuilder(resolver).localizer(null));
		McpHandlerInvocation invocation = () -> {
			throw new AssertionError("The default feature lookup must not invoke.");
		};
		Assertions.assertTrue(invocation.getFeatures()
				.find(McpLocalizationContext.class).isEmpty());
	}

	private static McpLocalizationContext context(Locale locale) {
		return new McpLocalizationContext() {
			@Override
			public Locale getLocale() {
				return locale;
			}

			@Override
			public McpLocalizationResult localize(McpLocalizableText text) {
				return McpLocalizationResult.fromDefaultText();
			}
		};
	}

	private static McpHandlerResolver resolver() {
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp")
				.serverInformation(McpImplementation.withNameAndVersion(
						"localization-api-test", "3.6.0-SNAPSHOT").build())
				.build();
		return McpHandlerResolver.fromEndpoints(List.of(endpoint));
	}

	private static McpServer.Builder serverBuilder(McpHandlerResolver resolver) {
		return McpServer.withPort(0)
				.handlerResolver(resolver)
				.requestAdmissionPolicy(
						McpRequestAdmissionPolicy.acceptAllInstance());
	}

	private static Set<String> publicDeclaredMethods(Class<?> type) {
		return Arrays.stream(type.getDeclaredMethods())
				.filter(method -> Modifier.isPublic(method.getModifiers()))
				.map(McpLocalizationPublicApiTests::methodDescriptor)
				.collect(Collectors.toUnmodifiableSet());
	}

	private static String methodDescriptor(Method method) {
		return method.getName() + "(" + Arrays.stream(method.getParameterTypes())
				.map(Class::getName).collect(Collectors.joining(",")) + ")";
	}

	private static void assertRecordComponents(Class<?> type,
			String... expectedNames) {
		Assertions.assertTrue(type.isRecord());
		RecordComponent[] components = type.getRecordComponents();
		Assertions.assertArrayEquals(expectedNames, Arrays.stream(components)
				.map(RecordComponent::getName).toArray(String[]::new));
	}

	private static void assertParameterizedPayload(Method method,
			Class<?> expectedPayload) {
		AnnotatedParameterizedType returnType = Assertions.assertInstanceOf(
				AnnotatedParameterizedType.class, method.getAnnotatedReturnType());
		AnnotatedType[] arguments = returnType.getAnnotatedActualTypeArguments();
		Assertions.assertEquals(1, arguments.length);
		Assertions.assertEquals(expectedPayload, arguments[0].getType());
		Assertions.assertTrue(arguments[0].isAnnotationPresent(NonNull.class),
				method.toString());
	}

	private static boolean isObjectContract(Method method) {
		return method.getName().equals("equals")
				|| method.getName().equals("hashCode")
				|| method.getName().equals("toString");
	}
}
