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
import java.util.function.Consumer;

/**
 * Connection-owned SSE handle, including pending initialization. Acquisition
 * and unicast reject a terminal connection. Returning from the initializer does
 * not close resources. There is no enduring HTTP producer thread or lexical
 * ownership stack; no own/using or separate-abort operation is advertised here.
 */
@ThreadSafe
public interface SseUnicaster {
	void unicastEvent(@NonNull SseEvent sseEvent);
	void unicastComment(@NonNull SseComment sseComment);
	@NonNull ResourcePath getResourcePath();
	@NonNull Request getRequest();
	/** True while application writes can be queued, including before activation; not delivery acknowledgment. */
	@NonNull Boolean isOpen();
	/**
	 * Owns a provider subscription/resource until connection termination, including
	 * establishment failure. The provider must support close concurrent with use
	 * or callbacks. One coordinated close attempt runs on managed cleanup; no
	 * initializer-thread finalization affinity is promised. Late acquisition is
	 * disposed and accounted for before it can be used on a terminal connection.
	 */
	@NonNull <T extends AutoCloseable> T open(
			@NonNull StreamResourceFactory<? extends T> streamResourceFactory) throws Exception;
	/**
	 * Observes transport termination, not completion of all physical cleanup.
	 * Late registration may replay on the registering application thread.
	 */
	@NonNull CallbackRegistration onTermination(
			@NonNull Consumer<@NonNull StreamTermination> streamTerminationConsumer);
}
