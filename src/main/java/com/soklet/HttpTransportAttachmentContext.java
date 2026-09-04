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

import static java.util.Objects.requireNonNull;

/**
 * Framework-owned HTTP attachment capability borrowed by one
 * {@link HttpServer#attach(HttpTransportAttachmentContext, StartupContext)}
 * invocation. The context itself must not be retained after that invocation
 * returns. Delegate attachment is unary, same-thread, and valid only during the
 * dynamic extent of the enclosing call.
 * <p>
 * The type is thread-safe so its immutable values can be read safely, but that
 * does not relax the thread confinement or lifetime of the delegate-attachment
 * methods. The request handler and termination signal obtained from this
 * context may be retained for the attached runtime according to their
 * individual contracts.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class HttpTransportAttachmentContext {
	@NonNull
	private final InternalTransportAttachmentContext<HttpServer.RequestHandler>
			internalContext;

	HttpTransportAttachmentContext(
			@NonNull InternalTransportAttachmentContext<HttpServer.RequestHandler>
					internalContext) {
		this.internalContext = requireNonNull(internalContext);
	}

	/**
	 * @return the configuration that owns this transport graph; the stable
	 * reference may be retained by the attached runtime
	 */
	@NonNull
	public SokletConfig getSokletConfig() {
		return (SokletConfig) this.internalContext.configuration();
	}

	/**
	 * @return the framework admission-fenced request handler, which may be
	 * retained and invoked by the attached runtime while admission remains open
	 */
	public HttpServer.@NonNull RequestHandler getAdmissionFencedRequestHandler() {
		return this.internalContext.requestHandler();
	}

	/**
	 * Acquires this transport member's framework-owned termination capability.
	 * The attached runtime may retain the signal after {@code attach(...)}
	 * returns and must use it to report honest failure and termination evidence.
	 *
	 * @return this member's termination signal
	 */
	@NonNull
	public TransportTerminationSignal getTerminationSignal() {
		return this.internalContext.terminationSignal().publicSignal();
	}

	/**
	 * Attaches one transparent delegate using this member's identity and exact
	 * termination signal. The delegate does not receive an independently
	 * terminating child member. The enclosing transport must return the delegate's
	 * runtime and must not drive a separate runtime or signal termination itself;
	 * the transparent delegate owns the shared member's lifecycle and signal.
	 *
	 * @param delegate delegate transport
	 * @param delegateRequestHandler handler supplied to the delegate
	 * @return the exact runtime returned by the delegate
	 */
	@NonNull
	@CheckReturnValue
	public TransportRuntime attachTransparentDelegate(@NonNull HttpServer delegate,
			HttpServer.@NonNull RequestHandler delegateRequestHandler) {
		return this.internalContext.attachTransparentHttpDelegate(delegate,
				delegateRequestHandler);
	}

	/**
	 * Attaches one independently terminating delegate child. Soklet owns the
	 * child's completion state; the enclosing transport may observe the returned
	 * stage but cannot complete or otherwise mutate it.
	 *
	 * @param delegate delegate transport
	 * @param delegateRequestHandler handler supplied to the delegate
	 * @return the child runtime and commit-gated subtree proof
	 */
	@NonNull
	@CheckReturnValue
	public TransportDelegateAttachment attachTerminationOwningDelegate(
			@NonNull HttpServer delegate,
			HttpServer.@NonNull RequestHandler delegateRequestHandler) {
		return this.internalContext.attachLifecycleOwningHttpDelegate(delegate,
				delegateRequestHandler);
	}

	@NonNull
	InternalTransportAttachmentContext<HttpServer.RequestHandler> internalContext() {
		return this.internalContext;
	}
}
