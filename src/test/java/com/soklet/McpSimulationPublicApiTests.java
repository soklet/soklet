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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Public signature and value contracts for modern off-network MCP simulation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpSimulationPublicApiTests {
	@Test
	public void simulatorSharedHostExposesExactModernMcpDescriptors()
			throws Exception {
		Method defaultStart = Simulator.class.getMethod("startMcpRequest",
				Request.class);
		Method configuredStart = Simulator.class.getMethod("startMcpRequest",
				Request.class, McpSimulationOptions.class);

		assertAbstractNonNullMethod(defaultStart, McpSimulation.class,
				Request.class);
		assertAbstractNonNullMethod(configuredStart, McpSimulation.class,
				Request.class, McpSimulationOptions.class);
		Assertions.assertEquals(6, Simulator.class.getDeclaredMethods().length);
		Assertions.assertEquals(Set.of(
				"onBroadcastError(java.util.function.Consumer)",
				"onUnicastError(java.util.function.Consumer)",
				"performHttpRequest(com.soklet.Request)",
				"performSseRequest(com.soklet.Request)",
				"startMcpRequest(com.soklet.Request)",
				"startMcpRequest(com.soklet.Request,com.soklet.McpSimulationOptions)"),
				Arrays.stream(Simulator.class.getDeclaredMethods())
						.map(McpSimulationPublicApiTests::methodDescriptor)
						.collect(java.util.stream.Collectors.toUnmodifiableSet()));
		Assertions.assertThrows(NoSuchMethodException.class,
				() -> Simulator.class.getMethod("performMcpRequest", Request.class));
		Assertions.assertThrows(NoSuchMethodException.class,
				() -> Simulator.class.getMethod("onMcpStreamError", Consumer.class));
	}

	@Test
	public void simulationSurfaceHasExactReferenceNullabilityAndClosedEnums()
			throws Exception {
		Assertions.assertTrue(AutoCloseable.class.isAssignableFrom(
				McpSimulation.class));
		Assertions.assertEquals(6, McpSimulation.class.getDeclaredMethods().length);
		assertOptionalMethod(McpSimulation.class.getMethod("awaitResponse",
				Duration.class), McpSimulationResponse.class, Duration.class);
		assertOptionalMethod(McpSimulation.class.getMethod("nextStreamItem",
				Duration.class), McpSimulationStreamItem.class, Duration.class);
		assertOptionalMethod(McpSimulation.class.getMethod("awaitCompletion",
				Duration.class), McpSimulationCompletion.class, Duration.class);
		assertNonNullMethod(McpSimulation.class.getMethod("isComplete"),
				Boolean.class);
		assertVoidMethod(McpSimulation.class.getMethod("cancel"));
		assertVoidMethod(McpSimulation.class.getMethod("close"));

		Assertions.assertEquals(4,
				McpSimulationResponse.class.getDeclaredMethods().length);
		assertNonNullMethod(McpSimulationResponse.class.getMethod("getStatusCode"),
				Integer.class);
		assertHeadersMethod(McpSimulationResponse.class.getMethod("getHeaders"));
		assertNonNullMethod(McpSimulationResponse.class.getMethod("getBodyMode"),
				McpSimulationBodyMode.class);
		assertOptionalMethod(McpSimulationResponse.class.getMethod("getBody"),
				byte[].class);

		Assertions.assertEquals(3,
				McpSimulationCompletion.class.getDeclaredMethods().length);
		assertNonNullMethod(McpSimulationCompletion.class.getMethod("getReason"),
				McpStreamTerminationReason.class);
		assertOptionalMethod(McpSimulationCompletion.class.getMethod(
				"getTerminalMessage"), McpJsonValue.class);
		assertListMethod(McpSimulationCompletion.class.getMethod("getThrowables"),
				Throwable.class);

		Assertions.assertEquals(4,
				McpSimulationStreamItem.class.getDeclaredMethods().length);
		assertNonNullMethod(McpSimulationStreamItem.class.getMethod("getType"),
				McpSimulationStreamItemType.class);
		assertOptionalMethod(McpSimulationStreamItem.class.getMethod("getMessage"),
				McpJsonValue.class);
		assertOptionalMethod(McpSimulationStreamItem.class.getMethod("getComment"),
				String.class);
		assertNonNullMethod(McpSimulationStreamItem.class.getMethod(
				"getEncodedBytes"), byte[].class);

		Assertions.assertArrayEquals(new McpSimulationBodyMode[]{
				McpSimulationBodyMode.EMPTY,
				McpSimulationBodyMode.JSON,
				McpSimulationBodyMode.SERVER_SENT_EVENTS
		}, McpSimulationBodyMode.values());
		Assertions.assertArrayEquals(new McpSimulationStreamItemType[]{
				McpSimulationStreamItemType.JSON_MESSAGE,
				McpSimulationStreamItemType.KEEP_ALIVE_COMMENT
		}, McpSimulationStreamItemType.values());
	}

	@Test
	public void simulationOptionsUsePositiveFiniteDefaultsAndIndependentBuilderState() {
		McpSimulationOptions defaults = McpSimulationOptions.defaultInstance();
		Assertions.assertSame(defaults, McpSimulationOptions.defaultInstance());
		Assertions.assertEquals(128, defaults.getStreamItemQueueCapacity());
		Assertions.assertEquals(10 * 1_024 * 1_024,
				defaults.getMaximumCapturedBytes());

		McpSimulationOptions.Builder firstBuilder = McpSimulationOptions.builder();
		Assertions.assertSame(firstBuilder,
				firstBuilder.streamItemQueueCapacity(7));
		Assertions.assertSame(firstBuilder,
				firstBuilder.maximumCapturedBytes(4_096));
		McpSimulationOptions first = firstBuilder.build();
		McpSimulationOptions second = McpSimulationOptions.builder().build();
		Assertions.assertEquals(7, first.getStreamItemQueueCapacity());
		Assertions.assertEquals(4_096, first.getMaximumCapturedBytes());
		Assertions.assertEquals(defaults.getStreamItemQueueCapacity(),
				second.getStreamItemQueueCapacity());
		Assertions.assertEquals(defaults.getMaximumCapturedBytes(),
				second.getMaximumCapturedBytes());

		Assertions.assertThrows(NullPointerException.class,
				() -> McpSimulationOptions.builder().streamItemQueueCapacity(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> McpSimulationOptions.builder().maximumCapturedBytes(null));
		for (Integer invalid : List.of(0, -1, Integer.MIN_VALUE)) {
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> McpSimulationOptions.builder()
							.streamItemQueueCapacity(invalid).build());
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> McpSimulationOptions.builder()
							.maximumCapturedBytes(invalid).build());
		}
		Assertions.assertDoesNotThrow(() -> McpSimulationOptions.builder()
				.streamItemQueueCapacity(Integer.MAX_VALUE)
				.maximumCapturedBytes(Integer.MAX_VALUE)
				.build());
	}

	private static void assertAbstractNonNullMethod(@NonNull Method method,
			@NonNull Class<?> returnType, @NonNull Class<?>... parameterTypes) {
		Assertions.assertTrue(Modifier.isAbstract(method.getModifiers()));
		assertNonNullMethod(method, returnType, parameterTypes);
	}

	private static void assertNonNullMethod(@NonNull Method method,
			@NonNull Class<?> returnType, @NonNull Class<?>... parameterTypes) {
		Assertions.assertEquals(returnType, method.getReturnType());
		Assertions.assertArrayEquals(parameterTypes, method.getParameterTypes());
		Assertions.assertTrue(method.getAnnotatedReturnType()
				.isAnnotationPresent(NonNull.class), method.toString());
		for (AnnotatedType parameter : method.getAnnotatedParameterTypes())
			Assertions.assertTrue(parameter.isAnnotationPresent(NonNull.class),
					method.toString());
	}

	private static void assertVoidMethod(@NonNull Method method) {
		Assertions.assertEquals(void.class, method.getReturnType());
		Assertions.assertEquals(0, method.getParameterCount());
	}

	private static void assertOptionalMethod(@NonNull Method method,
			@NonNull Class<?> valueType, @NonNull Class<?>... parameterTypes) {
		assertNonNullMethod(method, Optional.class, parameterTypes);
		ParameterizedType genericReturn = Assertions.assertInstanceOf(
				ParameterizedType.class, method.getGenericReturnType());
		Assertions.assertEquals(Optional.class, genericReturn.getRawType());
		Assertions.assertArrayEquals(new Object[]{valueType},
				genericReturn.getActualTypeArguments());
		AnnotatedParameterizedType annotatedReturn = Assertions.assertInstanceOf(
				AnnotatedParameterizedType.class, method.getAnnotatedReturnType());
		AnnotatedType value = annotatedReturn.getAnnotatedActualTypeArguments()[0];
		Assertions.assertEquals(valueType, value.getType());
		Assertions.assertTrue(value.isAnnotationPresent(NonNull.class),
				method.toString());
	}

	private static void assertHeadersMethod(@NonNull Method method) {
		assertNonNullMethod(method, Map.class);
		ParameterizedType map = Assertions.assertInstanceOf(ParameterizedType.class,
				method.getGenericReturnType());
		Assertions.assertEquals(Map.class, map.getRawType());
		Assertions.assertEquals(String.class, map.getActualTypeArguments()[0]);
		ParameterizedType set = Assertions.assertInstanceOf(ParameterizedType.class,
				map.getActualTypeArguments()[1]);
		Assertions.assertEquals(Set.class, set.getRawType());
		Assertions.assertArrayEquals(new Object[]{String.class},
				set.getActualTypeArguments());

		AnnotatedParameterizedType annotatedMap = Assertions.assertInstanceOf(
				AnnotatedParameterizedType.class, method.getAnnotatedReturnType());
		AnnotatedType[] mapArguments = annotatedMap.getAnnotatedActualTypeArguments();
		Assertions.assertTrue(mapArguments[0].isAnnotationPresent(NonNull.class));
		Assertions.assertTrue(mapArguments[1].isAnnotationPresent(NonNull.class));
		AnnotatedParameterizedType annotatedSet = Assertions.assertInstanceOf(
				AnnotatedParameterizedType.class, mapArguments[1]);
		Assertions.assertTrue(annotatedSet.getAnnotatedActualTypeArguments()[0]
				.isAnnotationPresent(NonNull.class));
	}

	private static void assertListMethod(@NonNull Method method,
			@NonNull Class<?> valueType) {
		assertNonNullMethod(method, List.class);
		ParameterizedType list = Assertions.assertInstanceOf(ParameterizedType.class,
				method.getGenericReturnType());
		Assertions.assertEquals(List.class, list.getRawType());
		Assertions.assertArrayEquals(new Object[]{valueType},
				list.getActualTypeArguments());
		AnnotatedParameterizedType annotatedList = Assertions.assertInstanceOf(
				AnnotatedParameterizedType.class, method.getAnnotatedReturnType());
		Assertions.assertTrue(annotatedList.getAnnotatedActualTypeArguments()[0]
				.isAnnotationPresent(NonNull.class));
	}

	@NonNull
	private static String methodDescriptor(@NonNull Method method) {
		return method.getName() + "(" + Arrays.stream(method.getParameterTypes())
				.map(Class::getName).collect(java.util.stream.Collectors.joining(","))
				+ ")";
	}
}
