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

import static java.util.Objects.requireNonNull;

/**
 * A known-length, finalized HTTP response body.
 * <p>
 * This type describes the body to write; it is not itself responsible for writing to a transport. Initial support is
 * byte-array-backed, with additional known-length body variants planned for future releases.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public sealed interface MarshaledResponseBody permits MarshaledResponseBody.Bytes {
	/**
	 * The number of bytes this body will write.
	 *
	 * @return the body length
	 */
	@NonNull
	Long getLength();

	/**
	 * A finalized response body backed by a byte array.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	final class Bytes implements MarshaledResponseBody {
		@NonNull
		private final byte[] bytes;

		public Bytes(@NonNull byte[] bytes) {
			this.bytes = requireNonNull(bytes);
		}

		/**
		 * The byte array backing this body.
		 * <p>
		 * For compatibility with prior {@link MarshaledResponse} behavior, this array is not defensively copied.
		 *
		 * @return the bytes to write
		 */
		@NonNull
		public byte[] getBytes() {
			return this.bytes;
		}

		@Override
		@NonNull
		public Long getLength() {
			return Long.valueOf(getBytes().length);
		}
	}
}
