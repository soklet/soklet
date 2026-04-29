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

import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Internal lifecycle observer fan-out utilities.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class LifecycleObservers {
	@NonNull
	private static final LifecycleObserver SILENT_INSTANCE = new LifecycleObserver() {
		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
			requireNonNull(logEvent);
			// No-op
		}
	};

	private LifecycleObservers() {
		// Static utility class
	}

	@NonNull
	public static LifecycleObserver aggregate(@NonNull List<LifecycleObserver> lifecycleObservers) {
		requireNonNull(lifecycleObservers);

		List<LifecycleObserver> lifecycleObserversSnapshot = List.copyOf(lifecycleObservers);

		if (lifecycleObserversSnapshot.isEmpty())
			return SILENT_INSTANCE;

		if (lifecycleObserversSnapshot.size() == 1)
			return lifecycleObserversSnapshot.get(0);

		return (LifecycleObserver) Proxy.newProxyInstance(LifecycleObserver.class.getClassLoader(),
				new Class<?>[]{LifecycleObserver.class},
				(proxy, method, args) -> invoke(proxy, method, args, lifecycleObserversSnapshot));
	}

	@NonNull
	private static Object invoke(@NonNull Object proxy,
															 @NonNull Method method,
															 Object[] args,
															 @NonNull List<LifecycleObserver> lifecycleObservers) throws Throwable {
		requireNonNull(proxy);
		requireNonNull(method);
		requireNonNull(lifecycleObservers);

		if (method.getDeclaringClass() == Object.class)
			return invokeObjectMethod(proxy, method, args);

		Throwable firstThrowable = null;

		for (LifecycleObserver lifecycleObserver : lifecycleObservers) {
			try {
				method.invoke(lifecycleObserver, args);
			} catch (InvocationTargetException e) {
				firstThrowable = collectThrowable(firstThrowable, e.getCause());
			} catch (Throwable t) {
				firstThrowable = collectThrowable(firstThrowable, t);
			}
		}

		if (firstThrowable != null)
			throw firstThrowable;

		return null;
	}

	@NonNull
	private static Object invokeObjectMethod(@NonNull Object proxy,
																					 @NonNull Method method,
																					 Object[] args) {
		requireNonNull(proxy);
		requireNonNull(method);

		return switch (method.getName()) {
			case "equals" -> proxy == args[0];
			case "hashCode" -> System.identityHashCode(proxy);
			case "toString" -> LifecycleObserver.class.getSimpleName() + "[aggregate=true]";
			default -> throw new UnsupportedOperationException(method.toString());
		};
	}

	@NonNull
	private static Throwable collectThrowable(Throwable firstThrowable,
																						Throwable throwable) {
		if (throwable == null)
			throwable = new NullPointerException("Lifecycle observer invocation failed without an underlying cause");

		if (firstThrowable == null)
			return throwable;

		firstThrowable.addSuppressed(throwable);
		return firstThrowable;
	}
}
