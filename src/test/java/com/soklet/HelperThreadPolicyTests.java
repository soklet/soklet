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

import com.soklet.internal.HelperThreads;
import com.soklet.internal.microhttp.StreamLifecycleCoordinator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Timeout(30)
class HelperThreadPolicyTests {
	@Test
	void platformHelperSkipsApplicationValuesAndPreservesThreadAttributes() throws Exception {
		verifyFactory(false);
	}

	@Test
	void virtualHelperSkipsApplicationValuesAndPreservesContextLoader() throws Exception {
		verifyFactory(true);
	}

	@Test
	void streamingAuxiliaryFactoryPreservesTheConstructingContextLoader() throws Exception {
		ClassLoader original = Thread.currentThread().getContextClassLoader();
		int originalPriority = Thread.currentThread().getPriority();
		ClassLoader intended = new ClassLoader(original) {};
		AtomicReference<ClassLoader> observed = new AtomicReference<>();
		Thread worker = null;
		try {
			var method = StreamLifecycleCoordinator.class.getDeclaredMethod("threadFactory", String.class);
			method.setAccessible(true);
			ThreadFactory factory = (ThreadFactory) method.invoke(null, "stream-loader-policy");
			Thread.currentThread().setContextClassLoader(intended);
			Thread.currentThread().setPriority(Thread.NORM_PRIORITY + 1);
			worker = factory.newThread(() -> observed.set(Thread.currentThread().getContextClassLoader()));
			Assertions.assertEquals(Thread.State.NEW, worker.getState());
			Assertions.assertTrue(worker.isDaemon());
			Assertions.assertFalse(worker.isVirtual());
			Assertions.assertEquals(Math.min(Thread.NORM_PRIORITY,
					Thread.currentThread().getThreadGroup().getMaxPriority()), worker.getPriority());
			Assertions.assertSame(intended, worker.getContextClassLoader());
			Thread.currentThread().setContextClassLoader(original);
			worker.start();
			worker.join(5_000);
			Assertions.assertFalse(worker.isAlive());
			Assertions.assertSame(intended, observed.get());
		} finally {
			Thread.currentThread().setContextClassLoader(original);
			Thread.currentThread().setPriority(originalPriority);
			if (worker != null && worker.isAlive()) {
				worker.interrupt();
				worker.join(5_000);
			}
		}
	}

	private static void verifyFactory(boolean virtual) throws Exception {
		AtomicInteger inherited = new AtomicInteger();
		InheritableThreadLocal<String> local = new InheritableThreadLocal<>() {
			@Override
			protected String childValue(String value) {
				inherited.incrementAndGet();
				throw new AssertionError("Internal helpers must not invoke application childValue");
			}
		};
		AtomicReference<Throwable> failure = new AtomicReference<>();
		AtomicReference<Thread> created = new AtomicReference<>();
		CountDownLatch completed = new CountDownLatch(1);
		Thread parent = Thread.ofPlatform().inheritInheritableThreadLocals(false)
				.daemon(true).name("helper-policy-parent").unstarted(() -> {
			ClassLoader original = Thread.currentThread().getContextClassLoader();
			ClassLoader intended = new ClassLoader(original) {};
			try {
				Thread.currentThread().setContextClassLoader(intended);
				local.set("application context");
				Runnable task = () -> {
					try {
						Assertions.assertNull(local.get());
						Assertions.assertSame(intended, Thread.currentThread().getContextClassLoader());
					} catch (Throwable problem) {
						failure.compareAndSet(null, problem);
					} finally {
						completed.countDown();
					}
				};
				Thread child = virtual ? HelperThreads.virtual(task, "isolated-virtual")
						: HelperThreads.platform(task, "isolated-platform");
				created.set(child);
				Assertions.assertEquals(Thread.State.NEW, child.getState());
				Assertions.assertEquals(virtual ? "isolated-virtual" : "isolated-platform", child.getName());
				Assertions.assertEquals(virtual, child.isVirtual());
				Assertions.assertTrue(child.isDaemon());
				Assertions.assertSame(intended, child.getContextClassLoader());
				if (!virtual) {
					Assertions.assertSame(Thread.currentThread().getThreadGroup(), child.getThreadGroup());
					Assertions.assertEquals(Thread.currentThread().getPriority(), child.getPriority());
				}
				// The loader is captured at construction, not when the thread starts.
				Thread.currentThread().setContextClassLoader(original);
				// Starting a cold virtual scheduler may create JDK carrier threads.
				// This test isolates the helper constructor's inheritance policy.
				local.remove();
				child.start();
				child.join(5_000);
				Assertions.assertFalse(child.isAlive());
			} catch (Throwable problem) {
				failure.compareAndSet(null, problem);
				completed.countDown();
			} finally {
				local.remove();
				Thread.currentThread().setContextClassLoader(original);
			}
		});
		parent.setPriority(Thread.NORM_PRIORITY - 1);
		try {
			parent.start();
			Assertions.assertTrue(completed.await(5, TimeUnit.SECONDS));
			parent.join(5_000);
			Assertions.assertFalse(parent.isAlive());
			if (failure.get() != null)
				throw new AssertionError("Helper construction or execution failed", failure.get());
			Assertions.assertEquals(0, inherited.get());
		} finally {
			Thread child = created.get();
			if (child != null && child.isAlive()) {
				child.interrupt();
				child.join(5_000);
			}
			if (parent.isAlive()) {
				parent.interrupt();
				parent.join(5_000);
			}
		}
	}
}
