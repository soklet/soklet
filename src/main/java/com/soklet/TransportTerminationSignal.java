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

import static java.util.Objects.requireNonNull;

/**
 * Framework-owned capability for recording one transport member's independent
 * failure and affirmative termination proof.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class TransportTerminationSignal {
	@NonNull
	private final InternalTransportTerminationSignal internalSignal;

	TransportTerminationSignal(
			@NonNull InternalTransportTerminationSignal internalSignal) {
		this.internalSignal = requireNonNull(internalSignal);
	}

	/**
	 * Records affirmative proof after every activity owned by this member ends.
	 * Repeated calls are idempotent. This remains legal after
	 * {@link #signalTerminationFailure(Throwable)} so a failure can later be
	 * paired with definitive termination proof.
	 */
	public void signalTerminated() {
		this.internalSignal.signalTerminated();
	}

	/**
	 * Records the first failure without claiming termination proof. Repeated
	 * calls are safe and do not replace the first recorded failure; callers may
	 * subsequently invoke {@link #signalTerminated()} when proof becomes true.
	 *
	 * @param cause exact failure cause
	 */
	public void signalTerminationFailure(@NonNull Throwable cause) {
		this.internalSignal.signalTerminationFailure(requireNonNull(cause));
	}
}
