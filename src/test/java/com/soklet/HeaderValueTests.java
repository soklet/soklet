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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class HeaderValueTests {
	@Test
	public void quotedEscapesBackslashAndQuote() {
		String value = HeaderValue.with("attachment")
				.quoted("filename", "a\"b\\c")
				.stringValue();

		Assertions.assertEquals("attachment; filename=\"a\\\"b\\\\c\"", value);
	}

	@Test
	public void rfc8187EncodesUtf8() {
		String value = HeaderValue.with("attachment")
				.rfc8187("filename", "r\u00E9sum\u00E9.txt")
				.stringValue();

		Assertions.assertEquals("attachment; filename*=UTF-8''r%C3%A9sum%C3%A9.txt", value);
	}

	@Test
	public void rfc8187RejectsParameterNameWithAsterisk() {
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				HeaderValue.with("attachment")
						.rfc8187("filename*", "report.txt"));
	}

	@Test
	public void primaryValueRejectsSemicolon() {
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				HeaderValue.with("text/plain; charset=UTF-8").build());
	}

	@Test
	public void primaryValueRejectsNonLatin1() {
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				HeaderValue.with("ok\u2713").build());
	}
}
