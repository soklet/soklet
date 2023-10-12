/*
 * Copyright 2022 Revetware LLC.
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

package com.soklet.core;

import com.soklet.core.Cookie.SameSite;
import org.junit.Assert;
import org.junit.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class CookieTests {
	@Test
	public void setCookieHeaderRepresentationTests() {
		Cookie basicCookie = new Cookie.Builder("name", "value").build();
		Assert.assertEquals("name=value", basicCookie.toSetCookieHeaderRepresentation());

		Cookie valuelessCookie = new Cookie.Builder("name", null).build();
		Assert.assertEquals("name=", valuelessCookie.toSetCookieHeaderRepresentation());

		Cookie everythingCookie = new Cookie.Builder("name", "value")
				.domain("www.soklet.com")
				.path("/")
				.httpOnly(true)
				.secure(true)
				.maxAge(Duration.ofSeconds(3600))
				.sameSite(SameSite.STRICT)
				.build();

		Assert.assertEquals("name=value; Path=/; Domain=www.soklet.com; Max-Age=3600; Secure; HttpOnly; SameSite=Strict",
				everythingCookie.toSetCookieHeaderRepresentation());
	}
}
