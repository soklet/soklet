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
import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * An immutable point-in-time snapshot of MCP server diagnostics.
 * <p>
 * A retained snapshot never changes. Obtain a new snapshot to observe a later
 * lifecycle state.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpServerDiagnostics {
	/**
	 * The lifecycle status captured by this snapshot.
	 *
	 * @return the server status
	 */
	@NonNull
	McpServerStatus getStatus();

	/**
	 * The effective bound address captured by this snapshot.
	 * <p>
	 * The address is present only when {@link #getStatus()} is
	 * {@link McpServerStatus#STARTED}. It includes the operating-system-assigned
	 * port when ephemeral port {@code 0} was configured.
	 *
	 * @return the effective bound address, or the empty optional when stopped
	 */
	@NonNull
	Optional<@NonNull InetSocketAddress> getBoundAddress();
}
