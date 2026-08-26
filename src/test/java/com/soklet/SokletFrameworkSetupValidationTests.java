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
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Focused startup-scan coverage for removed HttpServer injection. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
final class SokletFrameworkSetupValidationTests {
	@Test
	void rejectsDirectHttpServerBeforeInstanceProvisioning() throws Exception {
		assertRejectedBeforeInstanceProvisioning(
				InvalidInjectionResource.class.getDeclaredMethod(
						"direct", HttpServer.class));
	}

	@Test
	void rejectsOptionalHttpServerBeforeInstanceProvisioning() throws Exception {
		assertRejectedBeforeInstanceProvisioning(
				InvalidInjectionResource.class.getDeclaredMethod(
						"optional", Optional.class));
	}

	@Test
	void rejectsHttpServerBoundTypeVariableBeforeInstanceProvisioning()
			throws Exception {
		assertRejectedBeforeInstanceProvisioning(
				InvalidInjectionResource.class.getDeclaredMethod(
						"typeVariable", HttpServer.class));
	}

	@Test
	void rejectsOptionalHttpServerBoundTypeVariableBeforeInstanceProvisioning()
			throws Exception {
		assertRejectedBeforeInstanceProvisioning(
				InvalidInjectionResource.class.getDeclaredMethod(
						"optionalTypeVariable", Optional.class));
	}

	private static void assertRejectedBeforeInstanceProvisioning(
			@NonNull Method invalidMethod) {
		CountingRejectingInstanceProvider instanceProvider =
				new CountingRejectingInstanceProvider();
		HttpServer httpServer = HttpServer.withPort(0).build();
		SokletConfig config = SokletConfig.withHttpServer(httpServer)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(
						Set.of(invalidMethod)))
				.instanceProvider(instanceProvider)
				.build();

		Assertions.assertEquals(0, instanceProvider.provisionCalls());
		try (Soklet soklet = Soklet.fromConfig(config)) {
			Assertions.assertEquals(0, instanceProvider.provisionCalls());
			SokletStartupException failure = Assertions.assertThrows(
					SokletStartupException.class, soklet::start);

			Assertions.assertInstanceOf(IllegalStateException.class,
					failure.getCause());
			Assertions.assertTrue(failure.getCause().getMessage().contains(
					"HttpServer resource-method injection was removed in Soklet 4.0"));
			Assertions.assertTrue(failure.getCause().getMessage().contains(
					invalidMethod.getName()));
			Assertions.assertEquals(0, instanceProvider.provisionCalls(),
					"Startup validation must run before any InstanceProvider call");
			Assertions.assertFalse(httpServer.isStarted(),
					"Rejected injection must not reach transport startup");
		}
	}

	public static final class InvalidInjectionResource {
		@GET("/invalid/direct")
		public String direct(@NonNull HttpServer httpServer) {
			return httpServer.toString();
		}

		@GET("/invalid/optional")
		public String optional(@NonNull Optional<HttpServer> httpServer) {
			return httpServer.toString();
		}

		@GET("/invalid/type-variable")
		public <T extends HttpServer> String typeVariable(@NonNull T httpServer) {
			return httpServer.toString();
		}

		@GET("/invalid/optional-type-variable")
		public <T extends HttpServer> String optionalTypeVariable(
				@NonNull Optional<T> httpServer) {
			return httpServer.toString();
		}
	}

	private static final class CountingRejectingInstanceProvider
			implements InstanceProvider {
		@NonNull
		private final AtomicInteger provisionCalls = new AtomicInteger();

		@Override
		@NonNull
		public <T> T provide(@NonNull Class<T> instanceClass) {
			this.provisionCalls.incrementAndGet();
			throw new AssertionError("InstanceProvider must not run before validation");
		}

		@Override
		@NonNull
		public <T> T provide(@NonNull Parameter parameter) {
			this.provisionCalls.incrementAndGet();
			throw new AssertionError("InstanceProvider must not run before validation");
		}

		int provisionCalls() {
			return this.provisionCalls.get();
		}
	}
}
