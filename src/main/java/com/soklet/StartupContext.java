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

import java.time.Duration;
import java.util.Optional;

/**
 * Advisory timing and cancellation information for transport startup.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public interface StartupContext {
	/**
	 * Acquires the time remaining before the active startup boundary.
	 *
	 * @return the remaining time, or empty when ordinary startup is unbounded
	 */
	@NonNull
	Optional<@NonNull Duration> getRemainingTime();

	/**
	 * Is shutdown cancellation currently requested?
	 *
	 * @return {@code true} if cancellation is requested, {@code false} otherwise
	 */
	boolean isCancellationRequested();
}
