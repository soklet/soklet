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
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultLifecycleObserver implements LifecycleObserver {
	@NonNull
	private static final DefaultLifecycleObserver DEFAULT_INSTANCE;

	static {
		DEFAULT_INSTANCE = new DefaultLifecycleObserver();
	}

	private DefaultLifecycleObserver() {
		// Singleton
	}

	@NonNull
	public static DefaultLifecycleObserver defaultInstance() {
		return DEFAULT_INSTANCE;
	}

	@Override
	public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
		String message = logEvent.getMessage();
		Throwable throwable = logEvent.getThrowable().orElse(null);

		if (throwable == null) {
			System.err.printf("%s::didReceiveLogEvent [%s]: %s%n", LifecycleObserver.class.getSimpleName(), logEvent.getLogEventType().name(), message);
		} else {
			StringWriter stringWriter = new StringWriter();
			PrintWriter printWriter = new PrintWriter(stringWriter);
			throwable.printStackTrace(printWriter);
			String throwableWithStackTrace = stringWriter.toString();

			System.err.printf("%s::didReceiveLogEvent [%s]: %s\n%s\n", LifecycleObserver.class.getSimpleName(), logEvent.getLogEventType().name(), message, throwableWithStackTrace);
		}
	}
}
