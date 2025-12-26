/*
 * Copyright 2022-2025 Revetware LLC.
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

import javax.annotation.Nonnull;
import java.time.Instant;
import java.util.Optional;

/**
 * Represents characteristics of a long-running connection that has been established with a {@link ServerSentEventServer}.
 * <p>
 * Instances are exposed via {@link LifecycleObserver}, which enables you to monitor events that occur on the connection over time (established, SSE payload written, terminated, etc.)
 * <p>
 * See <a href="https://www.soklet.com/docs/server-sent-events">https://www.soklet.com/docs/server-sent-events</a> for general Server-Sent Event documentation and <a href="https://www.soklet.com/docs/request-lifecycle#server-sent-events">https://www.soklet.com/docs/request-lifecycle#server-sent-events</a> for lifecycle-specific documentation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public interface ServerSentEventConnection {
	/**
	 * The request made by the client to the <em>Event Source Method</em> which accepted the SSE handshake and established the connection.
	 *
	 * @return the request made by the client to the <em>Event Source Method</em>
	 */
	@Nonnull
	Request getRequest();

	/**
	 * Returns the <em>Event Source Method</em> that provided the accepted handshake for this connection.
	 *
	 * @return the <em>Event Source Method</em> that provided the accepted handshake for this connection
	 */
	@Nonnull
	ResourceMethod getResourceMethod();

	/**
	 * Returns the moment at which this connection was established.
	 *
	 * @return the moment at which this connection was established.
	 */
	@Nonnull
	Instant getEstablishedAt();

	/**
	 * Returns the connection-specific context as provided by an accepted handshake.
	 * <p>
	 * This will always be the value returned by {@link HandshakeResult.Accepted#getClientContext()}.
	 *
	 * @return the connection-specific context, or {@link Optional#empty()} if none was specified
	 */
	@Nonnull
	Optional<Object> getClientContext();

	/**
	 * Categorizes why a Server-Sent Event connection terminated.
	 */
	enum TerminationReason {
		/**
		 * Connection was closed due to backpressure (write queue at capacity).
		 */
		BACKPRESSURE,
		/**
		 * Connection was closed during server shutdown.
		 */
		SERVER_STOP,
		/**
		 * Connection ended due to an error while processing or writing.
		 */
		ERROR,
		/**
		 * Connection ended because the remote peer closed the socket.
		 */
		REMOTE_CLOSE,
		/**
		 * Connection ended for an unspecified reason.
		 */
		UNKNOWN
	}
}
