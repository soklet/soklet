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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

public class McpAppMimeTypeTests {

	@Test
	public void exact_apps_profile_accepts_case_spacing_and_quoted_value_variants() {
		for (String value : List.of(
				"text/html;profile=mcp-app",
				"TEXT/HTML; PROFILE=mcp-app",
				"text/html;profile=\"mcp-app\"",
				" TeXt / HtMl ; PrOfIlE = \"mcp-app\" ",
				"text/html;profile=\"mcp\\-app\"",
				"text/html;profile=\"\\m\\c\\p\\-\\a\\p\\p\""))
			Assertions.assertTrue(McpAppMimeType.isAppsProfile(value), value);
	}

	@Test
	public void valid_non_apps_media_types_do_not_match_the_exact_profile() {
		for (String value : List.of(
				"application/json",
				"text/plain;profile=mcp-app",
				"application/html;profile=mcp-app",
				"text/html",
				"text/html;profile=MCP-APP",
				"text/html;profile=\" mcp-app\"",
				"text/html;profile=\"mcp-app \"",
				"text/html;profile=\"\"",
				"text/html;profile=other",
				"text/html;other=mcp-app",
				"text/html;profile=mcp-app;charset=utf-8",
				"text/html;charset=utf-8;profile=mcp-app",
				"text/html;profile=mcp-app;other=\"\"",
				"application/vnd.example+json;version=2",
				"text/html;profile=\"mcp\\\"-app\"",
				"text/html;profile=\"mcp\\\\-app\"",
				"text/html;profile=\"quoted;separators=are/values\""))
			Assertions.assertFalse(McpAppMimeType.isAppsProfile(value), value);
	}

	@Test
	public void every_token_punctuation_character_is_valid() {
		String token = "!#$%&'*+-.^_`|~0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
		Assertions.assertFalse(McpAppMimeType.isAppsProfile(token + "/" + token));
		Assertions.assertFalse(McpAppMimeType.isAppsProfile("text/html;" + token + "=" + token));
	}

	@Test
	public void syntax_requires_complete_tokens_and_separators() {
		for (String value : List.of(
				"", " ", "text", "/html", "text/", "text//html", "text/html/other",
				"te xt/html", "text/ht ml", "text/html;", "text/html;;profile=mcp-app",
				"text/html;=mcp-app", "text/html;profile", "text/html;profile=",
				"text/html;profile=mcp app", "text/html;profile:mcp-app",
				"text/html;profile==mcp-app", "text/html;profile=mcp-app, text/html",
				"text/html (comment);profile=mcp-app", "\"text\"/html;profile=mcp-app",
				"text/html;\"profile\"=mcp-app", "text/html;profile=mcp@app",
				"text/html;profile=mcp\\-app"))
			assertInvalid(value);
	}

	@Test
	public void quotes_must_close_and_quoted_values_cannot_have_trailing_junk() {
		for (String value : List.of(
				"text/html;profile=\"mcp-app", "text/html;profile=\"mcp-app\\",
				"text/html;profile=\"mcp-app\\\"", "text/html;profile=\"mcp-app\"junk",
				"text/html;profile=\"mcp-app\"\"", "text/html;profile=mcp-app\""))
			assertInvalid(value);
	}

	@Test
	public void duplicate_parameters_are_rejected_case_insensitively_for_all_media_types() {
		for (String value : List.of(
				"text/html;profile=mcp-app;profile=mcp-app",
				"text/html;PROFILE=mcp-app;profile=other",
				"text/html;profile=mcp-app;other=first;OTHER=second",
				"application/json;x=\"\";X=\"\"",
				"application/json;PROFILE=other;profile=other"))
			assertInvalid(value);
	}

	@Test
	public void controls_are_rejected_even_in_spaces_quotes_and_escapes() {
		for (int codePoint = 0; codePoint <= 0x7F; codePoint++) {
			if (codePoint >= 0x20 && codePoint != 0x7F)
				continue;
			char control = (char) codePoint;
			assertInvalid(control + "text/html;profile=mcp-app");
			assertInvalid("text/html;" + control + "profile=mcp-app");
			assertInvalid("text/html;profile=\"mcp" + control + "-app\"");
			assertInvalid("text/html;profile=\"mcp\\" + control + "-app\"");
		}
	}

	@Test
	public void non_ascii_characters_do_not_create_tokens_or_optional_whitespace() {
		for (String value : List.of(
				"tëxt/html;profile=mcp-app",
				"text/htmℓ;profile=mcp-app",
				"text/html;profıle=mcp-app",
				"text/html;profile=mcp-ápp",
				"text/html;\u00a0profile=mcp-app",
				"text/html;profile=\"mcp-ápp\"",
				"text/html;profile=\"mcp-😀\""))
			assertInvalid(value);
	}

	@Test
	public void malformed_non_apps_media_types_still_fail_validation() {
		for (String value : List.of(
				"application/json;profile=\"unterminated",
				"application/json;profile=",
				"image/png;invalid@name=value",
				"text/plain;profile=mcp-app;profile=mcp-app"))
			assertInvalid(value);
	}

	@Test
	public void matching_does_not_depend_on_the_process_locale() {
		Locale previous = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			Assertions.assertTrue(McpAppMimeType.isAppsProfile("TEXT/HTML;PROFILE=mcp-app"));
			assertInvalid("text/html;PROFILE=mcp-app;profile=mcp-app");
		} finally {
			Locale.setDefault(previous);
		}
	}

	@Test
	public void malformed_values_never_retain_sensitive_input_in_messages_or_causes() {
		for (String value : List.of(
				"private-token-secret/html;profile=\"unterminated",
				"text/html;profile=private-token-secret;PROFILE=private-token-secret",
				"text/html;profile=\"private-token-secret\n\""))
			assertInvalid(value);
	}

	@Test
	public void null_input_fails_without_a_parser_error_or_echoed_value() {
		Assertions.assertThrows(NullPointerException.class, () -> McpAppMimeType.isAppsProfile(null));
	}

	private void assertInvalid(String value) {
		IllegalArgumentException exception = Assertions.assertThrows(
				IllegalArgumentException.class, () -> McpAppMimeType.isAppsProfile(value));
		Assertions.assertEquals("Invalid MCP Apps MIME type.", exception.getMessage());
		Assertions.assertNull(exception.getCause());
	}
}
