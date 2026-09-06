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

import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Reflection contracts for the reviewed core lifecycle API. */
@ThreadSafe
class CoreLifecyclePublicApiTests {
	@Test
	void transportRuntimeUsesPublicShutdownVocabulary() throws Exception {
		assertVoidMethod(TransportRuntime.class, "start", StartupContext.class);
		assertVoidMethod(TransportRuntime.class, "shutdownGracefully",
				ShutdownContext.class);
		assertVoidMethod(TransportRuntime.class, "shutdownForcibly",
				ShutdownContext.class);
		Assertions.assertThrows(NoSuchMethodException.class,
				() -> TransportRuntime.class.getDeclaredMethod("quiesce",
						ShutdownContext.class));
		Assertions.assertThrows(NoSuchMethodException.class,
				() -> TransportRuntime.class.getDeclaredMethod("force",
						ShutdownContext.class));
	}

	@Test
	void lifecycleTimeoutApiHasOnlyFiniteTimeoutVocabulary() throws Exception {
		Assertions.assertEquals(Duration.class, LifecyclePolicy.class
				.getDeclaredMethod("getStartupTimeout").getReturnType());
		Assertions.assertEquals(Duration.class, StartupContext.class
				.getDeclaredMethod("getRemainingTime").getReturnType());
		Assertions.assertEquals(Duration.class, LifecyclePolicy.class
				.getDeclaredMethod("getGracefulShutdownTimeout").getReturnType());
		Assertions.assertEquals(Duration.class, LifecyclePolicy.class
				.getDeclaredMethod("getForcedShutdownTimeout").getReturnType());
		Assertions.assertThrows(NoSuchMethodException.class, () ->
				LifecyclePolicy.Builder.class.getDeclaredMethod("noStartupTimeout"));
		Assertions.assertThrows(NoSuchMethodException.class, () -> LifecyclePolicy.class
				.getDeclaredMethod("getGracefulShutdownDuration"));
		Assertions.assertThrows(NoSuchMethodException.class, () -> LifecyclePolicy.class
				.getDeclaredMethod("getForcedShutdownDuration"));
	}

	@Test
	void attachmentAndShutdownResultRenamesAreHardCutovers()
			throws Exception {
		assertAttachmentMethod(HttpTransportAttachmentContext.class,
				HttpServer.class, HttpServer.RequestHandler.class);
		assertAttachmentMethod(SseTransportAttachmentContext.class,
				SseServer.class, SseServer.RequestHandler.class);
		Assertions.assertEquals(java.util.List.class,
				ShutdownComponentResult.class.getDeclaredMethod("getThrowables")
						.getReturnType());
		Assertions.assertThrows(NoSuchMethodException.class,
				() -> ShutdownComponentResult.class.getDeclaredMethod("getFailures"));
	}

	@Test
	void lifecycleExceptionFamilyIsSealedToKnownOutcomes() {
		Assertions.assertTrue(SokletLifecycleException.class.isSealed());
		Set<Class<?>> permitted = Arrays.stream(SokletLifecycleException.class
				.getPermittedSubclasses()).collect(Collectors.toUnmodifiableSet());
		Assertions.assertEquals(Set.of(SokletStartupException.class,
				SokletUnexpectedTerminationException.class,
				SokletShutdownIncompleteException.class,
				SokletShutdownCleanupException.class), permitted);
	}

	@Test
	void applicationConfigurationParametersUseTypeAlignedName()
			throws Exception {
		Assertions.assertEquals("sokletConfig", SokletApplication.class
				.getDeclaredMethod("fromConfig", SokletConfig.class)
				.getParameters()[0].getName());
		Assertions.assertEquals("sokletConfig", SokletApplication.class
				.getDeclaredMethod("run", SokletConfig.class)
				.getParameters()[0].getName());
		Assertions.assertEquals("sokletConfig", SokletApplication.class
				.getDeclaredMethod("run", SokletConfig.class,
						ShutdownTrigger[].class).getParameters()[0].getName());
	}

	@Test
	void simulatorDerivationEntrypointsUseReviewedVocabularyAndNullness()
			throws Exception {
		Method withConfig = SimulatorConfig.class.getDeclaredMethod(
				"withSokletConfig", SokletConfig.class);
		Method fromConfig = SimulatorConfig.class.getDeclaredMethod(
				"fromSokletConfig", SokletConfig.class);
		Method directRun = SokletSimulator.class.getDeclaredMethod("run",
				SokletConfig.class, SokletSimulator.Simulation.class);
		Method configureMcp = SimulatorConfig.Builder.class.getDeclaredMethod(
				"configureMcpServer", Consumer.class);

		for (Method method : List.of(withConfig, fromConfig, directRun,
				configureMcp)) {
			Assertions.assertTrue(method.getAnnotatedReturnType()
					.isAnnotationPresent(NonNull.class), method::toString);
			Arrays.stream(method.getAnnotatedParameterTypes()).forEach(type ->
					Assertions.assertTrue(type.isAnnotationPresent(NonNull.class),
							method::toString));
		}
		Assertions.assertEquals("sokletConfig",
				withConfig.getParameters()[0].getName());
		Assertions.assertEquals("sokletConfig",
				fromConfig.getParameters()[0].getName());
		Assertions.assertEquals(List.of("sokletConfig", "simulation"),
				Arrays.stream(directRun.getParameters()).map(
						java.lang.reflect.Parameter::getName).toList());
		Assertions.assertEquals("mcpServerConfigurer",
				configureMcp.getParameters()[0].getName());
		Assertions.assertThrows(NoSuchMethodException.class,
				() -> SokletSimulator.class.getDeclaredMethod("run",
						SokletConfig.class, SimulatorOptions.class,
						SokletSimulator.Simulation.class));
		Assertions.assertThrows(NoSuchMethodException.class,
				() -> SimulatorConfig.Builder.class.getDeclaredMethod("mcpServer",
						Consumer.class));
		Assertions.assertThrows(NoSuchMethodException.class,
				() -> SimulatorConfig.Builder.class.getDeclaredMethod("mcpServer",
						Integer.class));
		Assertions.assertThrows(NoSuchMethodException.class,
				() -> SimulatorConfig.Builder.class.getDeclaredMethod("mcpServer",
						Integer.class, Consumer.class));
		Assertions.assertThrows(NoSuchMethodException.class,
				() -> SimulatorConfig.Builder.class.getDeclaredMethod("mcpServer",
						Integer.class, McpEndpointRegistry.class,
						McpAdmissionController.class));
		Assertions.assertThrows(NoSuchMethodException.class,
				() -> SimulatorConfig.Builder.class.getDeclaredMethod("mcpServer",
						Integer.class, McpEndpointRegistry.class,
						McpAdmissionController.class, Consumer.class));
	}

	private static void assertAttachmentMethod(Class<?> contextType,
			Class<?> delegateType, Class<?> handlerType) throws Exception {
		Method replacement = contextType.getDeclaredMethod(
				"attachTerminationOwningDelegate", delegateType, handlerType);
		Assertions.assertEquals(TransportDelegateAttachment.class,
				replacement.getReturnType());
		Assertions.assertThrows(NoSuchMethodException.class,
				() -> contextType.getDeclaredMethod(
						"attachLifecycleOwningDelegate", delegateType, handlerType));
	}

	private static void assertVoidMethod(Class<?> owner, String name,
			Class<?> parameterType) throws Exception {
		Assertions.assertEquals(void.class,
				owner.getDeclaredMethod(name, parameterType).getReturnType());
	}
}
