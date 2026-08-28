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
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/** Regression coverage for retained protected one-shot lifecycle projections. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class SokletProtectedLifecycleCompatibilityTests {
	@Test
	void protectedLifecycleProjectionDescriptorsRemainExact() throws Exception {
		Method getLock = Soklet.class.getDeclaredMethod("getLock");
		Assertions.assertTrue(Modifier.isProtected(getLock.getModifiers()));
		Assertions.assertEquals(ReentrantLock.class, getLock.getReturnType());
		Assertions.assertEquals(0, getLock.getExceptionTypes().length);
		Assertions.assertTrue(getLock.getAnnotatedReturnType()
				.isAnnotationPresent(NonNull.class));

		Method getLatchReference = Soklet.class.getDeclaredMethod(
				"getAwaitShutdownLatchReference");
		Assertions.assertTrue(Modifier.isProtected(
				getLatchReference.getModifiers()));
		Assertions.assertEquals(AtomicReference.class,
				getLatchReference.getReturnType());
		Assertions.assertEquals(0, getLatchReference.getExceptionTypes().length);
		AnnotatedType returnType = getLatchReference.getAnnotatedReturnType();
		Assertions.assertTrue(returnType.isAnnotationPresent(NonNull.class));
		AnnotatedParameterizedType parameterized = Assertions.assertInstanceOf(
				AnnotatedParameterizedType.class, returnType);
		AnnotatedType latchType = parameterized
				.getAnnotatedActualTypeArguments()[0];
		Assertions.assertEquals(CountDownLatch.class, latchType.getType());
		Assertions.assertTrue(latchType.isAnnotationPresent(NonNull.class));
	}

	@Test
	void lifecycleExposesStablePassiveLockAndReleasesItsSingleTerminalLatch() {
		Soklet soklet = Soklet.fromConfig(SokletConfig
				.withHttpServer(HttpServer.fromPort(0)).build());
		ReentrantLock lock = soklet.getLock();
		AtomicReference<@NonNull CountDownLatch> latchReference =
				soklet.getAwaitShutdownLatchReference();
		CountDownLatch latch = latchReference.get();

		Assertions.assertSame(lock, soklet.getLock());
		Assertions.assertSame(latchReference,
				soklet.getAwaitShutdownLatchReference());
		Assertions.assertEquals(1L, latch.getCount());

		soklet.close();

		Assertions.assertSame(latch, latchReference.get(),
				"The one-shot lifecycle must not install another generation latch");
		Assertions.assertEquals(0L, latch.getCount());
	}

	@Test
	void holdingProtectedLockProjectionCannotBlockShutdown() throws Exception {
		Soklet soklet = Soklet.fromConfig(SokletConfig
				.withHttpServer(HttpServer.fromPort(0)).build());
		ReentrantLock projectionLock = soklet.getLock();
		CountDownLatch projectionHeld = new CountDownLatch(1);
		CountDownLatch releaseProjection = new CountDownLatch(1);
		CountDownLatch shutdownReturned = new CountDownLatch(1);
		AtomicReference<Throwable> holderFailure = new AtomicReference<>();
		AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();

		Thread holder = new Thread(() -> {
			projectionLock.lock();
			try {
				projectionHeld.countDown();
				Assertions.assertTrue(releaseProjection.await(10,
						TimeUnit.SECONDS),
						"Timed out waiting to release the protected projection");
			} catch (Throwable failure) {
				holderFailure.set(failure);
				if (failure instanceof InterruptedException)
					Thread.currentThread().interrupt();
			} finally {
				projectionLock.unlock();
			}
		}, "soklet-protected-lock-holder");
		holder.setDaemon(true);

		Thread shutdownCaller = new Thread(() -> {
			try {
				soklet.shutdown();
			} catch (Throwable failure) {
				shutdownFailure.set(failure);
			} finally {
				shutdownReturned.countDown();
			}
		}, "soklet-shutdown-with-protected-lock-held");
		shutdownCaller.setDaemon(true);

		holder.start();
		Assertions.assertTrue(projectionHeld.await(2, TimeUnit.SECONDS),
				"The compatibility-lock holder did not start");
		boolean returnedWhileProjectionHeld;
		try {
			shutdownCaller.start();
			returnedWhileProjectionHeld = shutdownReturned.await(2,
					TimeUnit.SECONDS);
		} finally {
			releaseProjection.countDown();
			holder.join(2_000L);
			shutdownCaller.join(2_000L);
		}

		Assertions.assertTrue(returnedWhileProjectionHeld,
				"Holding the protected compatibility lock blocked shutdown()");
		Assertions.assertNull(holderFailure.get());
		Assertions.assertNull(shutdownFailure.get());
		Assertions.assertFalse(holder.isAlive());
		Assertions.assertFalse(shutdownCaller.isAlive());
		Assertions.assertSame(soklet.getShutdownResult().orElseThrow(),
				soklet.awaitShutdown());
	}

	@Test
	void replacingProtectedLatchProjectionCannotRedirectTerminalRelease() {
		Soklet soklet = Soklet.fromConfig(SokletConfig
				.withHttpServer(HttpServer.fromPort(0)).build());
		AtomicReference<@NonNull CountDownLatch> latchReference =
				soklet.getAwaitShutdownLatchReference();
		CountDownLatch originalLatch = latchReference.get();
		CountDownLatch replacementLatch = new CountDownLatch(1);
		latchReference.set(replacementLatch);

		soklet.close();

		Assertions.assertSame(replacementLatch, latchReference.get(),
				"Lifecycle publication must not rewrite the passive projection");
		Assertions.assertEquals(0L, originalLatch.getCount(),
				"The originally published one-shot latch must be released");
		Assertions.assertEquals(1L, replacementLatch.getCount(),
				"A caller replacement must not redirect lifecycle publication");
	}
}
