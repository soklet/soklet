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

import com.soklet.exception.IllegalRequestBodyException;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;

import static java.util.Objects.requireNonNull;

/** Internal decoding shared by request text accessors. */
@ThreadSafe
final class RequestTextDecoder {
	private RequestTextDecoder() {}

	@NonNull
	static String decode(byte @NonNull [] bytes, @NonNull Charset charset) throws CharacterCodingException {
		return requireNonNull(charset).newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(requireNonNull(bytes))).toString();
	}

	@NonNull
	static String decodeBody(byte @NonNull [] bytes, @NonNull Charset charset) {
		try {
			return decode(bytes, charset);
		} catch (CharacterCodingException ignored) {
			// Do not retain a decoder exception or client bytes in the diagnostic.
			throw new IllegalRequestBodyException("Invalid character encoding in request body.");
		}
	}
}
