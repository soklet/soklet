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

import com.soklet.annotation.GET;
import com.soklet.annotation.QueryParameter;
import com.soklet.converter.ValueConversionException;
import com.soklet.converter.ValueConverter;
import com.soklet.converter.ValueConverterRegistry;
import com.soklet.converter.TypeReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class EnumValueConversionPolicyTests {
	private enum State { ACTIVE, PAUSED }

	@Test
	void generatedEnumsNormalizeWhitespaceButRetainExactNames() throws Exception {
		ValueConverter<String, State> converter = ValueConverterRegistry.fromDefaults()
				.get(new TypeReference<String>() {}, new TypeReference<State>() {}).orElseThrow();
		for (String value : List.of("ACTIVE", " ACTIVE ", "\u2002ACTIVE\u2002"))
			assertEquals(Optional.of(State.ACTIVE), converter.convert(value), value);
		for (String value : List.of("", "   ", "\u2002"))
			assertEquals(Optional.empty(), converter.convert(value));
		assertEquals(Optional.empty(), converter.convert(null));
		for (String value : List.of("active", " ACTIVE PAUSED ", "\tACTIVE\r\n", " \t\r\n"))
			assertThrows(ValueConversionException.class, () -> converter.convert(value), value);
	}

	@Test
	void explicitEnumConverterRetainsCompleteControlOverItsInput() throws Exception {
		AtomicReference<String> received = new AtomicReference<>();
		ValueConverter<String, State> custom = new ValueConverter<>() {
			@Override public Optional<State> convert(String from) {
				received.set(from);
				return Optional.of(State.PAUSED);
			}
			@Override public Type getFromType() { return String.class; }
			@Override public Type getToType() { return State.class; }
		};
		ValueConverter<String, State> selected = ValueConverterRegistry.fromDefaultsSupplementedBy(Set.of(custom))
				.get(new TypeReference<String>() {}, new TypeReference<State>() {}).orElseThrow();
		assertSame(custom, selected);
		assertEquals(Optional.of(State.PAUSED), selected.convert(" ACTIVE "));
		assertEquals(" ACTIVE ", received.get());
		assertEquals(Optional.of(State.PAUSED), selected.convert(""));
		assertEquals("", received.get());
		assertTrue(ValueConverterRegistry.fromBlankSlate().get(String.class, State.class).isEmpty());
	}

	@Test
	void defaultNumericAndUuidDomainsArePreserved() throws Exception {
		ValueConverterRegistry registry = ValueConverterRegistry.fromDefaults();
		ValueConverter<String, Double> doubles = registry
				.get(new TypeReference<String>() {}, new TypeReference<Double>() {}).orElseThrow();
		assertTrue(doubles.convert("NaN").orElseThrow().isNaN());
		assertEquals(Double.POSITIVE_INFINITY, doubles.convert("Infinity").orElseThrow());
		assertEquals(Double.NEGATIVE_INFINITY, doubles.convert("-Infinity").orElseThrow());
		assertEquals(Optional.of(123), registry.get(String.class, Integer.class).orElseThrow().convert("\u0661\u0662\u0663"));
		assertEquals("00000001-0001-0001-0001-000000000001",
				registry.get(String.class, UUID.class).orElseThrow().convert("1-1-1-1-1").orElseThrow().toString());
	}

	@Test
	void httpBindingsShareEnumWhitespaceAndEmptyValueRules() {
		LifecyclePolicy policy = LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5))
				.startupCancelationTimeout(Duration.ofSeconds(1)).gracefulShutdownTimeout(Duration.ofSeconds(2))
				.forcedShutdownTimeout(Duration.ofSeconds(1)).build();
		ShutdownResult shutdown = SokletSimulator.run(SimulatorConfig.builder().httpServer()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(EnumResource.class)))
				.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} })
				.lifecyclePolicy(policy).build(), simulator -> {
			assertResponse(simulator, "/enum/required?value=%20ACTIVE%20", 200, "ACTIVE");
			assertResponse(simulator, "/enum/optional?value=%20%20", 200, "empty");
			assertResponse(simulator, "/enum/list?value=%20ACTIVE%20&value=PAUSED", 200, "ACTIVE,PAUSED");
			assertEquals(400, simulator.performHttpRequest(Request.fromRawUrl(HttpMethod.GET,
					"/enum/required?value=active")).getMarshaledResponse().getStatusCode());
		});
		assertTrue(shutdown.isComplete());
	}

	private static void assertResponse(Simulator simulator, String url, Integer status, String body) {
		MarshaledResponse response = simulator.performHttpRequest(Request.fromRawUrl(HttpMethod.GET, url)).getMarshaledResponse();
		assertEquals(status, response.getStatusCode());
		assertEquals(body, new String(response.bodyBytesOrEmpty(), java.nio.charset.StandardCharsets.UTF_8));
	}

	public static class EnumResource {
		@GET("/enum/required") public String required(@QueryParameter(name="value") State value) { return value.name(); }
		@GET("/enum/optional") public String optional(@QueryParameter(name="value") Optional<State> value) { return value.map(State::name).orElse("empty"); }
		@GET("/enum/list") public String list(@QueryParameter(name="value") List<State> values) { return String.join(",", values.stream().map(State::name).toList()); }
	}
}
