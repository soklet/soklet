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
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Optional;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Encapsulates a Server-Sent Event comment payload and its comment type.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class ServerSentEventComment {
	@NonNull
	private static final ServerSentEventComment HEARTBEAT_INSTANCE;

	static {
		HEARTBEAT_INSTANCE = new ServerSentEventComment(null, CommentType.HEARTBEAT);
	}

	@Nullable
	private final String comment;
	@NonNull
	private final CommentType commentType;

	/**
	 * Types of Server-Sent Event comments.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	public enum CommentType {
		/**
		 * Application-provided comment.
		 */
		COMMENT,
		/**
		 * Keep-alive/heartbeat comment.
		 */
		HEARTBEAT
	}

	/**
	 * Acquires a {@link ServerSentEventComment} instance with a {@code comment} payload.
	 *
	 * @param comment the comment payload for the instance
	 * @return the comment instance
	 */
	@NonNull
public static ServerSentEventComment fromComment(@NonNull String comment) {
		return new ServerSentEventComment(requireNonNull(comment), CommentType.COMMENT);
	}

	/**
	 * Acquires a shared heartbeat comment instance.
	 * <p>
	 * Heartbeat comments do not carry a payload; {@link #getComment()} will be empty.
	 *
	 * @return a shared heartbeat comment instance
	 */
	@NonNull
	public static ServerSentEventComment heartbeatInstance() {
		return HEARTBEAT_INSTANCE;
	}

	private ServerSentEventComment(@Nullable String comment,
																 @NonNull CommentType commentType) {
		this.comment = comment;
		this.commentType = requireNonNull(commentType);

		if (this.commentType == CommentType.COMMENT && this.comment == null)
			throw new IllegalArgumentException(format("%s 'comment' values must not be null for %s comments",
					ServerSentEventComment.class.getSimpleName(), CommentType.COMMENT));

		if (this.commentType == CommentType.HEARTBEAT && this.comment != null)
			throw new IllegalArgumentException(format("%s 'comment' values must be null for %s comments",
					ServerSentEventComment.class.getSimpleName(), CommentType.HEARTBEAT));
	}

	/**
	 * The comment payload.
	 * <p>
	 * Heartbeat comments return {@link Optional#empty()}.
	 *
	 * @return the comment payload
	 */
	@NonNull
	public Optional<String> getComment() {
		return Optional.ofNullable(this.comment);
	}

	/**
	 * The comment type.
	 *
	 * @return the comment type
	 */
	@NonNull
	public CommentType getCommentType() {
		return this.commentType;
	}

	@Override
	@NonNull
	public String toString() {
		return format("%s{commentType=%s, comment=%s}", getClass().getSimpleName(),
				getCommentType(), getComment().orElse(""));
	}
}
