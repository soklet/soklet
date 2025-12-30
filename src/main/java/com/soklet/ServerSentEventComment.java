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

import javax.annotation.concurrent.NotThreadSafe;
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
	 * Acquires a builder for {@link ServerSentEventComment} instances, seeded with a {@code comment} value.
	 *
	 * @param comment the comment payload for the instance
	 * @return the builder
	 */
	@NonNull
	public static Builder withComment(@NonNull String comment) {
		return new Builder().comment(comment);
	}

	/**
	 * Acquires an "empty" builder for {@link ServerSentEventComment} instances.
	 * <p>
	 * Note that {@code comment} is required unless {@code commentType} is {@link CommentType#HEARTBEAT}.
	 *
	 * @return the builder
	 */
	@NonNull
	public static Builder withDefaults() {
		return new Builder();
	}

	/**
	 * Acquires a builder for {@link ServerSentEventComment} instances, seeded with a heartbeat comment.
	 * <p>
	 * Heartbeat comments do not carry a payload; {@link #getComment()} will be empty.
	 *
	 * @return the builder
	 */
	@NonNull
	public static Builder withHeartbeat() {
		return new Builder().commentType(CommentType.HEARTBEAT);
	}

	protected ServerSentEventComment(@NonNull Builder builder) {
		requireNonNull(builder);

		this.comment = builder.comment;
		this.commentType = builder.commentType != null ? builder.commentType : CommentType.COMMENT;

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
	public Optional<@NonNull String> getComment() {
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

	/**
	 * Builder used to construct instances of {@link ServerSentEventComment}.
	 * <p>
	 * This class is intended for use by a single thread.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@Nullable
		private String comment;
		@Nullable
		private CommentType commentType;

		protected Builder() {
			// Nothing to do
		}

		@NonNull
		public Builder comment(@NonNull String comment) {
			this.comment = requireNonNull(comment);
			return this;
		}

		@NonNull
		public Builder commentType(@NonNull CommentType commentType) {
			this.commentType = requireNonNull(commentType);
			return this;
		}

		@NonNull
		public ServerSentEventComment build() {
			return new ServerSentEventComment(this);
		}
	}

	@Override
	@NonNull
	public String toString() {
		return format("%s{commentType=%s, comment=%s}", getClass().getSimpleName(), this.commentType, this.comment);
	}
}
