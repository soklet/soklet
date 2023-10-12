/*
 * Copyright 2022-2023 Revetware LLC.
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

import org.junit.Assert;
import org.junit.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.Locale;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class UtilitiesTests {
	@Test
	public void normalizedPathForUrl() {
		Assert.assertEquals("/", Utilities.normalizedPathForUrl("https://www.google.com/"));
		Assert.assertEquals("/", Utilities.normalizedPathForUrl("https://www.google.com"));
		Assert.assertEquals("/", Utilities.normalizedPathForUrl(""));
		Assert.assertEquals("/", Utilities.normalizedPathForUrl("/"));
		Assert.assertEquals("/test", Utilities.normalizedPathForUrl("/test"));
		Assert.assertEquals("/test", Utilities.normalizedPathForUrl("/test/"));
		Assert.assertEquals("/test", Utilities.normalizedPathForUrl("/test//"));
	}

	@Test
	public void acceptLanguages() {
		String acceptLanguageHeaderValue = "fr-CH, fr;q=0.9, en;q=0.8, de;q=0.7, *;q=0.5";
		List<Locale> locales = Utilities.localesFromAcceptLanguageHeaderValue(acceptLanguageHeaderValue);

		Assert.assertEquals("Locales don't match", List.of(
				Locale.forLanguageTag("fr-CH"),
				Locale.forLanguageTag("fr"),
				Locale.forLanguageTag("en"),
				Locale.forLanguageTag("de")
		), locales);

		locales = Utilities.localesFromAcceptLanguageHeaderValue("");

		Assert.assertEquals("Blank locale string mishandled", List.of(), locales);

		locales = Utilities.localesFromAcceptLanguageHeaderValue("xxxx");

		Assert.assertEquals("Junk locale string mishandled", List.of(), locales);
	}
}
