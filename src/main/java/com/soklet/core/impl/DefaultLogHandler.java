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

import com.soklet.core.LogHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.PrintWriter;
import java.io.StringWriter;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class DefaultLogHandler implements LogHandler {
	@Nonnull
	private static final DefaultLogHandler SHARED_INSTANCE;

	static {
		SHARED_INSTANCE = new DefaultLogHandler();
	}

	@Nonnull
	public static DefaultLogHandler sharedInstance() {
		return SHARED_INSTANCE;
	}
	
	@Override
	public void logError(@Nonnull String message) {
		logError(message, null);
	}

	@Override
	public void logError(@Nonnull String message,
											 @Nullable Throwable throwable) {
		requireNonNull(message);

		if (throwable == null) {
			System.err.printf("ERROR: %s\n", message);
		} else {
			StringWriter stringWriter = new StringWriter();
			PrintWriter printWriter = new PrintWriter(stringWriter);
			throwable.printStackTrace(printWriter);

			String throwableWithStackTrace = stringWriter.toString();

			System.err.printf("ERROR: %s\n%s\n", message, throwableWithStackTrace);
		}
	}
}
