/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.soklet;

import com.soklet.annotation.FormParameter;
import com.soklet.annotation.POST;
import com.soklet.annotation.QueryParameter;
import com.soklet.annotation.RequestCookie;
import com.soklet.annotation.RequestHeader;
import com.soklet.converter.TypeReference;
import com.soklet.converter.ValueConverter;
import com.soklet.converter.ValueConverterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class ExplicitReflexiveValueConverterTests {
	@Test
	void explicitStringConverterWinsWithoutChangingItsInput() throws Exception {
		ValueConverter<String, String> converter = converter(String.class, String.class,
				value -> Optional.of(value == null ? "custom:null" : "custom<" + value + ">"));
		for (ValueConverterRegistry registry : List.of(
				ValueConverterRegistry.fromDefaultsSupplementedBy(Set.of(converter)),
				ValueConverterRegistry.fromBlankSlateSupplementedBy(Set.of(converter)))) {
			assertSame(converter, registry.get(String.class, String.class).orElseThrow());
			assertEquals(Optional.of("custom< abc >"), registry.get(String.class, String.class).orElseThrow().convert(" abc "));
			assertEquals(Optional.of("custom:null"), registry.get(String.class, String.class).orElseThrow().convert(null));
		}
	}

	@Test
	void explicitPrimitiveAndWrapperRegistrationsShareNormalizedLookup() throws Exception {
		for (Type registered : List.of(int.class, Integer.class)) {
			ValueConverter<Integer, Integer> converter = converter(registered, registered,
					value -> Optional.of(value + 7));
			for (ValueConverterRegistry registry : List.of(
					ValueConverterRegistry.fromDefaultsSupplementedBy(Set.of(converter)),
					ValueConverterRegistry.fromBlankSlateSupplementedBy(Set.of(converter)))) {
				for (Type from : List.of(int.class, Integer.class)) for (Type to : List.of(int.class, Integer.class)) {
					assertSame(converter, registry.get(from, to).orElseThrow());
					assertEquals(Optional.of(12), registry.get(from, to).orElseThrow().convert(5));
				}
			}
		}
	}

	@Test
	void exactGenericConvertersDoNotLeakAcrossErasedOrDifferentTypeKeys() throws Exception {
		TypeReference<List<String>> strings = new TypeReference<>() {};
		TypeReference<List<Integer>> integers = new TypeReference<>() {};
		ValueConverter<List<String>, List<String>> converter = converter(strings.getType(), strings.getType(),
				value -> Optional.of(List.of("custom")));
		ValueConverterRegistry registry = ValueConverterRegistry.fromDefaultsSupplementedBy(Set.of(converter));
		for (int lookup = 0; lookup < 3; lookup++) {
			assertSame(converter, registry.get(new TypeReference<List<String>>() {}, new TypeReference<List<String>>() {}).orElseThrow());
			assertEquals(Optional.of(List.of("custom")), registry.get(strings, strings).orElseThrow().convert(List.of("original")));
			assertNotSame(converter, registry.get(integers, integers).orElseThrow());
			assertNotSame(converter, registry.get(List.class, List.class).orElseThrow());
			assertTrue(registry.get(strings.getType(), integers.getType()).isEmpty());
		}
	}

	@Test
	void automaticIdentityConversionRemainsAConfiguredFallback() throws Exception {
		ValueConverterRegistry defaults = ValueConverterRegistry.fromDefaults();
		assertEquals(Optional.of("abc"), defaults.get(String.class, String.class).orElseThrow().convert(" abc "));
		assertEquals(Optional.of(4), defaults.get(int.class, Integer.class).orElseThrow().convert(4));
		List<String> original = List.of("same instance");
		assertSame(original, defaults.get(new TypeReference<List<String>>() {}, new TypeReference<List<String>>() {})
				.orElseThrow().convert(original).orElseThrow());
		assertTrue(ValueConverterRegistry.fromBlankSlate().get(String.class, String.class).isEmpty());
		assertTrue(ValueConverterRegistry.fromBlankSlate().get(int.class, Integer.class).isEmpty());
	}

	@Test
	void queryFormHeaderAndCookieBindingsUseTheExplicitStringConverter() {
		AtomicInteger conversions = new AtomicInteger();
		ValueConverter<String, String> converter = converter(String.class, String.class, value -> {
			conversions.incrementAndGet();
			return Optional.of("custom<" + value + ">");
		});
		ShutdownResult result = SokletSimulator.run(SimulatorConfig.builder().httpServer()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Bindings.class)))
				.valueConverterRegistry(ValueConverterRegistry.fromDefaultsSupplementedBy(Set.of(converter)))
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5))
						.startupCancelationTimeout(Duration.ofSeconds(1)).gracefulShutdownTimeout(Duration.ofSeconds(2))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build()).build(), simulator -> {
			MarshaledResponse response = simulator.performHttpRequest(Request.withRawUrl(HttpMethod.POST, "/custom-bindings?value=q")
					.headers(Map.of("Content-Type", List.of("application/x-www-form-urlencoded"),
							"X-Value", List.of("h"), "Cookie", List.of("value=c")))
					.body("value=f".getBytes(StandardCharsets.UTF_8)).build()).getMarshaledResponse();
			assertEquals(200, response.getStatusCode());
			assertEquals("custom<q>|custom<f>|custom<h>|custom<c>", new String(response.bodyBytesOrEmpty(), StandardCharsets.UTF_8));
			assertEquals(4, conversions.get());
		});
		assertTrue(result.isComplete());
	}

	private static <F, T> ValueConverter<F, T> converter(Type from, Type to,
			java.util.function.Function<F, Optional<T>> convert) {
		return new ValueConverter<>() {
			@Override public Type getFromType() { return from; }
			@Override public Type getToType() { return to; }
			@Override public Optional<T> convert(F value) { return convert.apply(value); }
		};
	}

	public static class Bindings {
		@POST("/custom-bindings") public String values(@QueryParameter(name="value") String query,
				@FormParameter(name="value") String form, @RequestHeader(name="X-Value") String header,
				@RequestCookie(name="value") String cookie) {
			return query + "|" + form + "|" + header + "|" + cookie;
		}
	}
}
