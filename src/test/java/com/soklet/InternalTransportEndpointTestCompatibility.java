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

/** Temporary test-only bridge while unrelated suites migrate local stubs. */
interface InternalHttpTransportEndpoint
		extends HttpServer,
		InternalTransportEndpoint<HttpServer.RequestHandler> {
	@Override
	default @NonNull TransportIdentity getTransportIdentity() {
		return identity().publicIdentity();
	}

	@Override
	default @NonNull TransportRuntime attach(
			@NonNull HttpTransportAttachmentContext attachmentContext,
			@NonNull StartupContext startupContext) {
		InternalTransportRuntime runtime = attach(attachmentContext.internalContext(),
				(InternalStartupContext) startupContext);
		return publicRuntime(runtime);
	}

	void start();
	void stop();
	@NonNull Boolean isStarted();
	void initialize(@NonNull SokletConfig config,
			HttpServer.@NonNull RequestHandler requestHandler);

	private static TransportRuntime publicRuntime(
			@NonNull InternalTransportRuntime runtime) {
		InternalTransportRuntime exactRuntime = java.util.Objects.requireNonNull(runtime);
		return new TransportRuntime() {
			@Override
			public void start(@NonNull StartupContext context) {
				exactRuntime.start((InternalStartupContext) context);
			}

			@Override
			public void quiesce(@NonNull ShutdownContext context) {
				exactRuntime.quiesce((InternalShutdownContext) context);
			}

			@Override
			public void force(@NonNull ShutdownContext context) {
				exactRuntime.force((InternalShutdownContext) context);
			}
		};
	}
}

/** Temporary test-only bridge while unrelated suites migrate local stubs. */
interface InternalSseTransportEndpoint
		extends SseServer,
		InternalTransportEndpoint<SseServer.RequestHandler> {
	@Override
	default @NonNull TransportIdentity getTransportIdentity() {
		return identity().publicIdentity();
	}

	@Override
	default @NonNull TransportRuntime attach(
			@NonNull SseTransportAttachmentContext attachmentContext,
			@NonNull StartupContext startupContext) {
		InternalTransportRuntime runtime = attach(attachmentContext.internalContext(),
				(InternalStartupContext) startupContext);
		return publicRuntime(runtime);
	}

	void start();
	void stop();
	@NonNull Boolean isStarted();
	void initialize(@NonNull SokletConfig config,
			SseServer.@NonNull RequestHandler requestHandler);

	private static TransportRuntime publicRuntime(
			@NonNull InternalTransportRuntime runtime) {
		InternalTransportRuntime exactRuntime = java.util.Objects.requireNonNull(runtime);
		return new TransportRuntime() {
			@Override
			public void start(@NonNull StartupContext context) {
				exactRuntime.start((InternalStartupContext) context);
			}

			@Override
			public void quiesce(@NonNull ShutdownContext context) {
				exactRuntime.quiesce((InternalShutdownContext) context);
			}

			@Override
			public void force(@NonNull ShutdownContext context) {
				exactRuntime.force((InternalShutdownContext) context);
			}
		};
	}
}
