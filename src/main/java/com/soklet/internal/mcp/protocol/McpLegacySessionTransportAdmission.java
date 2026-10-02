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

package com.soklet.internal.mcp.protocol;

import com.soklet.Request;
import org.jspecify.annotations.NonNull;
import com.soklet.internal.microhttp.Header;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Internal HTTP-only projection; it never fabricates a JSON-RPC request.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@FunctionalInterface
interface McpLegacySessionTransportAdmission {
	@NonNull
	Decision admit(@NonNull Request request, @NonNull String endpointPath,
			@NonNull String revision,
			@NonNull Set<@NonNull McpResourceNotificationType> offeredTypes,
			boolean reauthorization, @NonNull Instant deadline,
			@NonNull McpApplicationCancellation cancellation) throws Exception;

	sealed interface Decision permits Accepted, Rejected {}

	record Accepted(@NonNull McpAdmissionIdentity identity,
			@NonNull Instant validUntil,
			@NonNull Set<@NonNull McpResourceNotificationType> notificationTypes)
			implements Decision {
		public Accepted {
			requireNonNull(identity);
			requireNonNull(validUntil);
			notificationTypes = Set.copyOf(requireNonNull(notificationTypes));
		}

		@Override public String toString() {
			return "Accepted[identity=<redacted>, validUntil=<redacted>, notificationTypeCount=" + notificationTypes.size() + "]";
		}
	}

	record Rejected(int statusCode, @NonNull List<@NonNull Header> headers)
			implements Decision {
		public Rejected {
			headers = List.copyOf(requireNonNull(headers));
		}

		@Override public String toString() {
			return "Rejected[statusCode=" + statusCode + ", headers=<redacted>]";
		}
	}
}
