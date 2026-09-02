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

import com.google.errorprone.annotations.CheckReturnValue;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.util.concurrent.CompletionStage;

import static java.util.Objects.requireNonNull;

/**
 * Framework-created result of lifecycle-owning unary delegate attachment.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class TransportDelegateAttachment {
	@NonNull
	private final TransportRuntime transportRuntime;
	@NonNull
	private final InternalTransportDelegateAttachment internalAttachment;

	TransportDelegateAttachment(@NonNull TransportRuntime transportRuntime,
			@NonNull InternalTransportDelegateAttachment internalAttachment) {
		this.transportRuntime = requireNonNull(transportRuntime);
		this.internalAttachment = requireNonNull(internalAttachment);
	}

	/**
	 * Acquires the exact runtime returned by the immediate delegate.
	 *
	 * @return the delegate runtime
	 */
	@NonNull
	public TransportRuntime getTransportRuntime() {
		return this.transportRuntime;
	}

	/**
	 * Acquires the one cached, read-only minimal stage for the delegate's frozen
	 * subtree. The stage is observational and cannot be completed or otherwise
	 * mutated by callers. A transport must not synchronously wait for it from
	 * {@code attach}, {@link TransportRuntime#start(StartupContext)},
	 * {@link TransportRuntime#quiesce(ShutdownContext)},
	 * {@link TransportRuntime#force(ShutdownContext)}, or any callback/activity
	 * whose completion is part of that subtree's termination proof.
	 *
	 * @return a stage completed only after committed subtree termination proof
	 */
	@NonNull
	@CheckReturnValue
	public CompletionStage<Void> whenTerminated() {
		return this.internalAttachment.whenTerminated();
	}
}
