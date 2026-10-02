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
import java.time.Instant;
import java.util.Set;

/**
 * Immutable HTTP admission context for GET delivery and DELETE cleanup at
 * session-enabled {@code 2025-06-18} and {@code 2025-11-25} endpoints.
 * Soklet's {@code 2026-07-28} implementation does not use this context.
 * <p>
 * These HTTP operations have no JSON-RPC method or request ID. The original
 * HTTP method remains available through {@link Request#getHttpMethod()}.
 * Reauthorization retains the original immutable GET request as historical
 * credential input; the application must evaluate current authority again.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpSessionTransportAdmissionContext {
	/** @return the original immutable Soklet HTTP request */
	@NonNull Request getRequest();

	/** @return the selected session-enabled MCP endpoint */
	@NonNull McpEndpoint getEndpoint();

	/** @return the explicitly selected eligible MCP protocol revision */
	@NonNull McpProtocolVersion getProtocolVersion();

	/**
	 * Returns the effective notification families offered by this endpoint at
	 * the selected revision, including eligible localization invalidations.
	 * An accepted decision may select a subset of these families. DELETE has
	 * no delivery selection and always supplies the empty set.
	 *
	 * @return immutable offered notification-family set
	 */
	@NonNull Set<@NonNull McpSubscriptionNotificationType> getNotificationTypes();

	/** @return whether an existing GET authorization is being reevaluated */
	@NonNull Boolean isReauthorization();

	/**
	 * Returns the queue-inclusive deadline for this callback. This bounds
	 * application execution; it is distinct from the accepted grant's expiry.
	 * A callback that ignores cancellation retains its physical execution
	 * reservation until it exits.
	 *
	 * @return callback execution deadline
	 */
	@NonNull Instant getDeadline();
}
