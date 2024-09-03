/*
 * Copyright 2022-2024 Revetware LLC.
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

package com.soklet.core.impl;

import com.soklet.core.LogEvent;
import com.soklet.core.LogEventHandler;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.io.PrintWriter;
import java.io.StringWriter;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class DefaultLogEventHandler implements LogEventHandler {
	@Nonnull
	private static final DefaultLogEventHandler SHARED_INSTANCE;

	static {
		SHARED_INSTANCE = new DefaultLogEventHandler();
	}

	@Nonnull
	public static DefaultLogEventHandler sharedInstance() {
		return SHARED_INSTANCE;
	}

	@Override
	public void log(@Nonnull LogEvent logEvent) {
		requireNonNull(logEvent);

		Throwable throwable = logEvent.getThrowable().orElse(null);
		String message = logEvent.getMessage();

		if (throwable == null) {
			System.err.println(message);
		} else {
			StringWriter stringWriter = new StringWriter();
			PrintWriter printWriter = new PrintWriter(stringWriter);
			throwable.printStackTrace(printWriter);

			String throwableWithStackTrace = stringWriter.toString().trim();
			System.err.printf("%s\n%s\n", message, throwableWithStackTrace);
		}
	}
}
