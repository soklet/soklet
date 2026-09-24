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

import com.soklet.internal.microhttp.StreamLifecycleCoordinator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Tests the same registration contract in the HTTP and simulator token implementations. */
public class StreamingCallbackRegistrationTests {

	@Test
	public void duplicate_callback_registrations_are_independent_and_removal_is_idempotent() throws Exception {
		for (TokenKind kind : TokenKind.values()) {
			TokenFixture fixture = kind.create(ignored -> {});
			AtomicInteger invocations = new AtomicInteger();
			Runnable callback = invocations::incrementAndGet;
			CallbackRegistration removed = fixture.token.onCancel(callback);
			CallbackRegistration retained = fixture.token.onCancel(callback);

			removed.close();
			removed.close();
			fixture.cancelAndRun();
			fixture.cancelAndRun();
			retained.close();
			retained.close();

			Assertions.assertEquals(1, invocations.get(), kind.name());
		}
	}

	@Test
	public void removal_after_batch_detachment_suppresses_unclaimed_callbacks_without_waiting() throws Exception {
		for (TokenKind kind : TokenKind.values()) {
			AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
			TokenFixture fixture = kind.create(callbackFailure::set);
			AtomicInteger invocations = new AtomicInteger();
			CountDownLatch claimed = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			ExecutorService executor = Executors.newFixedThreadPool(2);
			Runnable callback = () -> {
				if (invocations.incrementAndGet() == 1) {
					claimed.countDown();
					try {
						Assertions.assertTrue(release.await(5, TimeUnit.SECONDS), "Callback was not released");
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
						throw new AssertionError(exception);
					}
				}
			};
			CallbackRegistration first = fixture.token.onCancel(callback);
			CallbackRegistration second = fixture.token.onCancel(callback);

			try {
				var cancelation = executor.submit(() -> {
					fixture.cancelAndRun();
					return null;
				});
				Assertions.assertTrue(claimed.await(2, TimeUnit.SECONDS), kind.name());
				// Both registrations use the same callback. Whichever was claimed may finish;
				// removal must still suppress the other entry in the detached batch.
				executor.submit(() -> {
					first.close();
					second.close();
				}).get(2, TimeUnit.SECONDS);
				Assertions.assertFalse(cancelation.isDone(), "Removal must not wait for or stop the claimed callback");
				release.countDown();
				cancelation.get(2, TimeUnit.SECONDS);
				Assertions.assertEquals(1, invocations.get(), kind.name());
				Assertions.assertNull(callbackFailure.get(), kind.name());
			} finally {
				release.countDown();
				executor.shutdownNow();
				Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), kind.name());
			}
		}
	}

	@Test
	public void callback_and_diagnostic_failures_do_not_suppress_independent_callbacks() throws Exception {
		for (TokenKind kind : TokenKind.values()) {
			AtomicInteger diagnostics = new AtomicInteger();
			AtomicInteger successfulCallbacks = new AtomicInteger();
			AtomicReference<Throwable> reportedFailure = new AtomicReference<>();
			AssertionError expectedFailure = new AssertionError("Callback failed");
			TokenFixture fixture = kind.create(throwable -> {
				reportedFailure.set(throwable);
				diagnostics.incrementAndGet();
				throw new IllegalStateException("Diagnostic failed");
			});
			fixture.token.onCancel(() -> {
				throw expectedFailure;
			});
			fixture.token.onCancel(successfulCallbacks::incrementAndGet);

			fixture.cancelAndRun();

			Assertions.assertEquals(1, diagnostics.get(), kind.name());
			Assertions.assertSame(expectedFailure, reportedFailure.get(), kind.name());
			Assertions.assertEquals(1, successfulCallbacks.get(), kind.name());
		}
	}

	@Test
	public void reentrant_and_late_registrations_each_run_once() throws Exception {
		for (TokenKind kind : TokenKind.values()) {
			AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
			TokenFixture fixture = kind.create(callbackFailure::set);
			AtomicInteger outerInvocations = new AtomicInteger();
			AtomicInteger nestedAndLateInvocations = new AtomicInteger();
			AtomicReference<CallbackRegistration> outer = new AtomicReference<>();
			Runnable nestedAndLateCallback = nestedAndLateInvocations::incrementAndGet;
			outer.set(fixture.token.onCancel(() -> {
				outerInvocations.incrementAndGet();
				outer.get().close();
				fixture.token.onCancel(nestedAndLateCallback).close();
			}));

			fixture.cancelAndRun();
			CallbackRegistration late = fixture.token.onCancel(nestedAndLateCallback);
			late.close();
			late.close();
			fixture.cancelAndRun();

			Assertions.assertEquals(1, outerInvocations.get(), kind.name());
			Assertions.assertEquals(2, nestedAndLateInvocations.get(), kind.name());
			Assertions.assertNull(callbackFailure.get(), kind.name());
		}
	}

	@Test
	public void normal_completion_releases_callbacks_and_makes_late_registrations_inert() throws Exception {
		for (TokenKind kind : TokenKind.values()) {
			TokenFixture fixture = kind.create(ignored -> {});
			AtomicInteger invocations = new AtomicInteger();
			Runnable callback = invocations::incrementAndGet;
			CallbackRegistration registration = fixture.token.onCancel(callback);
			Assertions.assertSame(callback, callbackReference(registration), kind.name());

			fixture.complete();
			fixture.complete();
			Assertions.assertNull(callbackReference(registration), kind.name());
			CallbackRegistration late = fixture.token.onCancel(callback);
			Assertions.assertNull(callbackReference(late), kind.name());
			fixture.cancelAndRun();
			registration.close();
			late.close();

			Assertions.assertFalse(fixture.token.isCanceled(), kind.name());
			Assertions.assertTrue(fixture.token.getCancelationReason().isEmpty(), kind.name());
			Assertions.assertTrue(fixture.token.getCancelationCause().isEmpty(), kind.name());
			Assertions.assertEquals(0, invocations.get(), kind.name());
		}
	}

	private static Object callbackReference(CallbackRegistration registration) throws Exception {
		Field field = registration.getClass().getDeclaredField("callback");
		field.setAccessible(true);
		return field.get(registration);
	}

	private enum TokenKind {
		HTTP("com.soklet.internal.microhttp.StreamingMicrohttpResponses$DefaultCancelationToken", "reserveCancelation"),
		SIMULATOR("com.soklet.Soklet$SimulatorCancelationToken", "cancel");

		private final String className;
		private final String cancelMethodName;

		TokenKind(String className, String cancelMethodName) {
			this.className = className;
			this.cancelMethodName = cancelMethodName;
		}

		private TokenFixture create(Consumer<Throwable> failureConsumer) throws Exception {
			Class<?> tokenClass = Class.forName(this.className);
			Constructor<?> constructor = tokenClass.getDeclaredConstructor(Consumer.class,
					StreamLifecycleCoordinator.Reservation.class);
			constructor.setAccessible(true);
			CancelationToken token = (CancelationToken) constructor.newInstance(failureConsumer, null);
			Method cancel = tokenClass.getDeclaredMethod(this.cancelMethodName, StreamTerminationReason.class, Throwable.class);
			Method complete = tokenClass.getDeclaredMethod("complete");
			cancel.setAccessible(true);
			complete.setAccessible(true);
			return new TokenFixture(token, cancel, complete);
		}
	}

	private static final class TokenFixture {
		private final CancelationToken token;
		private final Method cancel;
		private final Method complete;

		private TokenFixture(CancelationToken token, Method cancel, Method complete) {
			this.token = token;
			this.cancel = cancel;
			this.complete = complete;
		}

		private void cancelAndRun() throws Exception {
			Object result = invoke(this.cancel, StreamTerminationReason.APPLICATION_CANCELED, null);
			if (result instanceof Runnable callbacks)
				callbacks.run();
		}

		private void complete() throws Exception {
			Object result = invoke(this.complete);
			if (result instanceof Runnable release)
				release.run();
		}

		private Object invoke(Method method, Object... arguments) throws Exception {
			try {
				return method.invoke(this.token, arguments);
			} catch (InvocationTargetException exception) {
				Throwable cause = exception.getCause();
				if (cause instanceof Exception checked)
					throw checked;
				if (cause instanceof Error error)
					throw error;
				throw new AssertionError(cause);
			}
		}
	}
}
