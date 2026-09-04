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
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contracts for discoverable built-in MCP invocation features and the
 * framework-owned request context.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
class McpInvocationFeaturesContractTests {
	@Test
	void builtInAccessorsUseTheExtensibleExactClassLookup() {
		CancelationToken cancelationToken = uncanceledToken();
		McpProgressReporter progressReporter = update -> {};
		Feature customFeature = new Feature();
		McpInvocationFeatures features = McpInvocationFeatures.fromFeatures(
				Map.of(CancelationToken.class, cancelationToken,
						McpProgressReporter.class, progressReporter,
						FeatureContract.class, customFeature));

		assertSame(cancelationToken, features.getCancelationToken());
		assertSame(cancelationToken,
				features.require(CancelationToken.class));
		assertSame(progressReporter,
				features.getProgressReporter().orElseThrow());
		assertSame(progressReporter,
				features.find(McpProgressReporter.class).orElseThrow());
		assertSame(customFeature,
				features.require(FeatureContract.class));

		McpInvocationFeatures noProgress = McpInvocationFeatures.fromFeatures(
				Map.of(CancelationToken.class, cancelationToken));
		assertTrue(noProgress.getProgressReporter().isEmpty());
		assertThrows(IllegalStateException.class,
				() -> McpInvocationFeatures.fromFeatures(Map.of())
						.getCancelationToken());
	}

	@Test
	void builtInAccessorsAreNonNullDefaultMethods() throws Exception {
		Method cancelationAccessor = McpInvocationFeatures.class.getMethod(
				"getCancelationToken");
		assertTrue(cancelationAccessor.isDefault());
		assertEquals(CancelationToken.class,
				cancelationAccessor.getReturnType());
		assertNotNull(cancelationAccessor.getAnnotatedReturnType()
				.getAnnotation(NonNull.class));

		Method progressAccessor = McpInvocationFeatures.class.getMethod(
				"getProgressReporter");
		assertTrue(progressAccessor.isDefault());
		assertEquals(Optional.class, progressAccessor.getReturnType());
		assertNotNull(progressAccessor.getAnnotatedReturnType()
				.getAnnotation(NonNull.class));
		AnnotatedParameterizedType progressReturn = assertInstanceOf(
				AnnotatedParameterizedType.class,
				progressAccessor.getAnnotatedReturnType());
		assertEquals(McpProgressReporter.class,
				progressReturn.getAnnotatedActualTypeArguments()[0].getType());
		assertNotNull(progressReturn.getAnnotatedActualTypeArguments()[0]
				.getAnnotation(NonNull.class));

		assertFalse(McpInvocationFeatures.class
				.getMethod("find", Class.class).isDefault());
		assertTrue(McpInvocationFeatures.class
				.getMethod("require", Class.class).isDefault());
	}

	@Test
	void requestStateAccessorsAreAbstractAndNonNull() throws Exception {
		for (String methodName : new String[] {"getInputResponses",
				"getFrameworkRequestState", "getApplicationRequestState"}) {
			Method method = McpRequestContext.class.getMethod(methodName);
			assertTrue(Modifier.isAbstract(method.getModifiers()), methodName);
			assertFalse(method.isDefault(), methodName);
			assertNotNull(method.getAnnotatedReturnType()
					.getAnnotation(NonNull.class), methodName);
		}
	}

	private static CancelationToken uncanceledToken() {
		return new CancelationToken() {
			@Override
			public Boolean isCanceled() {
				return false;
			}

			@Override
			public Optional<StreamTerminationReason> getCancelationReason() {
				return Optional.empty();
			}

			@Override
			public Optional<Throwable> getCancelationCause() {
				return Optional.empty();
			}

			@Override
			public AutoCloseable onCancel(Runnable callback) {
				requireNonNull(callback);
				return () -> {};
			}
		};
	}

	private interface FeatureContract {
	}

	private static final class Feature implements FeatureContract {
	}
}
