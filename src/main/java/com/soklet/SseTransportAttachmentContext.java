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

import static java.util.Objects.requireNonNull;

/**
 * Framework-created SSE attachment capability. Delegate attachment is unary,
 * same-thread, and valid only during the dynamic extent of the enclosing
 * {@link SseServer#attach(SseTransportAttachmentContext, StartupContext)} call.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public final class SseTransportAttachmentContext {
	@NonNull
	private final InternalTransportAttachmentContext<SseServer.RequestHandler>
			internalContext;

	SseTransportAttachmentContext(
			@NonNull InternalTransportAttachmentContext<SseServer.RequestHandler>
					internalContext) {
		this.internalContext = requireNonNull(internalContext);
	}

	/** @return the configuration that owns this transport graph */
	@NonNull
	public SokletConfig getSokletConfig() {
		return (SokletConfig) this.internalContext.configuration();
	}

	/** @return the framework admission-fenced request handler */
	public SseServer.@NonNull RequestHandler getAdmissionFencedRequestHandler() {
		return this.internalContext.requestHandler();
	}

	/** @return this member's framework-owned termination capability */
	@NonNull
	public TransportTerminationSignal getTerminationSignal() {
		return this.internalContext.terminationSignal().publicSignal();
	}

	/**
	 * Attaches one transparent delegate using this member and signal.
	 *
	 * @param delegate delegate transport
	 * @param delegateRequestHandler handler supplied to the delegate
	 * @return the exact runtime returned by the delegate
	 */
	@NonNull
	@CheckReturnValue
	public TransportRuntime attachTransparentDelegate(@NonNull SseServer delegate,
			SseServer.@NonNull RequestHandler delegateRequestHandler) {
		return this.internalContext.attachTransparentSseDelegate(delegate,
				delegateRequestHandler);
	}

	/**
	 * Attaches one independently terminating delegate child.
	 *
	 * @param delegate delegate transport
	 * @param delegateRequestHandler handler supplied to the delegate
	 * @return the child runtime and commit-gated subtree proof
	 */
	@NonNull
	@CheckReturnValue
	public TransportDelegateAttachment attachLifecycleOwningDelegate(
			@NonNull SseServer delegate,
			SseServer.@NonNull RequestHandler delegateRequestHandler) {
		return this.internalContext.attachLifecycleOwningSseDelegate(delegate,
				delegateRequestHandler);
	}

	@NonNull
	InternalTransportAttachmentContext<SseServer.RequestHandler> internalContext() {
		return this.internalContext;
	}
}
