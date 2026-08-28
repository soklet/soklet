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

import static java.util.Objects.requireNonNull;

/** Indicates that the sole startup attempt ended before readiness. */
public final class SokletStartupException extends SokletLifecycleException {
	@NonNull
	private final StartupDisposition startupDisposition;

	SokletStartupException(@NonNull ShutdownResult shutdownResult) {
		this(shutdownResult, null);
	}

	SokletStartupException(@NonNull ShutdownResult shutdownResult,
			Throwable cause) {
		super(message(requireNonNull(shutdownResult).getStartupDisposition()),
				shutdownResult, cause);
		this.startupDisposition = shutdownResult.getStartupDisposition();
		if (this.startupDisposition == StartupDisposition.READY)
			throw new IllegalArgumentException(
					"SokletStartupException requires a non-ready result");
	}

	SokletStartupException(
			@NonNull InternalStartupDisposition startupDisposition,
			@NonNull InternalShutdownResult shutdownResult) {
		this(ShutdownResult.fromInternal(requireNonNull(shutdownResult)));
		verifyInternalDisposition(startupDisposition, shutdownResult);
	}

	SokletStartupException(
			@NonNull InternalStartupDisposition startupDisposition,
			@NonNull InternalShutdownResult shutdownResult,
			@NonNull Throwable cause) {
		this(ShutdownResult.fromInternal(requireNonNull(shutdownResult),
				requireNonNull(cause), null, null), cause);
		verifyInternalDisposition(startupDisposition, shutdownResult);
	}

	/** @return disposition that prevented readiness */
	@NonNull
	public StartupDisposition getStartupDisposition() {
		return this.startupDisposition;
	}

	@NonNull
	InternalStartupDisposition getInternalStartupDisposition() {
		return InternalStartupDisposition.valueOf(
				this.startupDisposition.name());
	}

	private static void verifyInternalDisposition(
			@NonNull InternalStartupDisposition startupDisposition,
			@NonNull InternalShutdownResult shutdownResult) {
		if (requireNonNull(shutdownResult).startupDisposition()
				!= requireNonNull(startupDisposition))
			throw new IllegalArgumentException(
					"Startup exception disposition must match its shutdown result");
	}

	@NonNull
	private static String message(@NonNull StartupDisposition disposition) {
		return "Soklet startup did not reach readiness: "
				+ requireNonNull(disposition);
	}
}
