/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.soklet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ValidationDiagnosticEscapingTests {
	@Test
	void headerNameDiagnosticsEscapeTheWholeUntrustedName() {
		String name = "bad name\r\nForged: value\u001B[31m\u0085\u2028\u2029";
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> Utilities.validateHeaderNameAndValue(name, "ok"));
		assertSingleLine(failure);
		assertTrue(failure.getMessage().contains("bad name\\r\\nForged: value\\u001B[31m\\u0085\\u2028\\u2029"));
	}

	@Test
	void headerValueDiagnosticsEscapeLiteralAndEncodedControlFailures() {
		for (String value : List.of("bad\r\nForged: value\u001B[31m", "bad%00\u0085", "bad\u2028line", "bad\u2029line")) {
			IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
					() -> Utilities.validateHeaderNameAndValue("X-Example", value));
			assertSingleLine(failure);
			assertTrue(failure.getMessage().contains("X-Example"));
			assertTrue(failure.getMessage().contains("\\"), "Keep an escaped representation rather than removing offending data");
		}
		assertDoesNotThrow(() -> Utilities.validateHeaderNameAndValue("X-Obs-Text", "\u0080\u0085\u00FF"));
		assertThrows(IllegalArgumentException.class, () -> Utilities.validateHeaderNameAndValue("X-Example", "%0a"));
	}

	@Test
	void cookieDiagnosticsEscapeNamesValuesDomainsAndEveryPathRejection() {
		String injected = "\r\nForged: value\u001B[31m\u0085\u2028\u2029";
		List<Executable> invalidCookies = List.of(
				() -> ResponseCookie.with("bad" + injected, "ok").build(),
				() -> ResponseCookie.with("bad name" + injected, "ok").build(),
				() -> ResponseCookie.with("bad\u0100" + injected, "ok").build(),
				() -> ResponseCookie.with("name", "\r").build(),
				() -> ResponseCookie.with("name", "\u001B").build(),
				() -> ResponseCookie.with("name", "ok").domain("-bad" + injected).build(),
				() -> ResponseCookie.with("name", "ok").domain("bad" + injected + ".example").build());
		for (Executable invalidCookie : invalidCookies)
			assertSingleLine(assertThrows(IllegalArgumentException.class, invalidCookie));
		for (String path : List.of("/../", "/?", "/#", "/\\", "/%2e", "no-leading-slash", "/%00", "/")) {
			assertSingleLine(assertThrows(IllegalArgumentException.class,
					() -> ResponseCookie.with("name", "ok").path(path + injected + "tail").build()));
		}
		assertEquals("name=value; Path=/", ResponseCookie.with("name", "value").path("/").build().toSetCookieHeaderRepresentation());
	}

	private static void assertSingleLine(IllegalArgumentException failure) {
		assertNotNull(failure.getMessage());
		for (char c : failure.getMessage().toCharArray()) {
			assertFalse(Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
					|| Character.getType(c) == Character.LINE_SEPARATOR || Character.getType(c) == Character.PARAGRAPH_SEPARATOR,
					"Diagnostic contains a terminal control or line separator");
		}
	}
}
