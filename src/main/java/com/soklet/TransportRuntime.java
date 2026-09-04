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

/**
 * One-shot lifecycle runtime returned by an HTTP or SSE transport attachment.
 * One lifecycle owner invokes this object: Soklet drives the configured outer
 * runtime, while an enclosing transport drives a termination-owning child
 * runtime obtained from {@link TransportDelegateAttachment}. Other callers
 * must not invoke the runtime independently.
 * The transport implementation continues to own every external resource and
 * asynchronous activity started by those calls. It reports failures and final
 * termination proof through the {@link TransportTerminationSignal} borrowed
 * from its attachment context.
 * <p>
 * Implementations must make shutdown phase methods prompt, nonblocking,
 * idempotent, thread-safe, and monotonic. A shutdown method initiates work; it
 * does not itself constitute termination proof.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface TransportRuntime {
	/**
	 * Binds the transport and returns only after it is ready. The runtime owner
	 * invokes this method at most once.
	 *
	 * @param startupContext startup timing and cancelation information
	 */
	void start(@NonNull StartupContext startupContext);

	/**
	 * Stops admission and initiates graceful wind-up without waiting for it.
	 * The transport must signal termination only after all runtime-owned activity
	 * has ended.
	 *
	 * @param shutdownContext graceful shutdown timing information
	 */
	void shutdownGracefully(@NonNull ShutdownContext shutdownContext);

	/**
	 * Subsumes graceful shutdown and interrupts or cancels only runtime-owned
	 * execution.
	 * The transport must signal termination only after all runtime-owned activity
	 * has ended.
	 *
	 * @param shutdownContext forced shutdown timing information
	 */
	void shutdownForcibly(@NonNull ShutdownContext shutdownContext);
}
