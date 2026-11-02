package com.soklet;

import com.soklet.exception.IllegalRequestException;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FormCarrierCharsetRegressionTests {
	@Test
	void rawUtf16ComponentsAreRejectedInsteadOfBeingSplitAsAscii() {
		for (String charsetName : List.of("UTF-16", "UTF-16BE", "UTF-16LE", "UnicodeBigUnmarked", "UnicodeLittleUnmarked")) {
			Charset charset = Charset.forName(charsetName);
			assertCarrierRejected("a=Α".getBytes(charset), charsetName);
			assertCarrierRejected("a=secret".getBytes(StandardCharsets.US_ASCII), charsetName);
			assertCarrierRejected("%00%61=+".getBytes(StandardCharsets.US_ASCII), charsetName);
			assertEquals(Map.of("a", List.of("Α &+=secret")),
					form(encodedPair("a", "Α &+=secret", charset), charsetName).getFormParameters());
		}
	}

	@Test
	void statefulShiftAndNonAsciiBytesAreRejectedBeforeDelimiterParsing() {
		for (String name : statefulCharsetNames()) {
			for (int prohibited : List.of(0x1b, 0x0e, 0x0f, 0x80, 0xff)) {
				byte[] body = new byte[]{'a', '=', (byte) prohibited, '&', 's', 'e', 'c', 'r', 'e', 't', '=', 'x'};
				assertCarrierRejected(body, name);
			}
			assertEquals(Map.of("a", List.of("ASCII text")),
					form("a=ASCII+text".getBytes(StandardCharsets.US_ASCII), name).getFormParameters());
		}
	}

	@Test
	void rawIso2022JapaneseCharacterCannotCreatePhantomParameters() {
		for (String name : List.of("ISO-2022-JP", "jis", "csISO2022JP", "cp50220", "cp50221", "windows-iso2022jp")) {
			if (!Charset.isSupported(name)) continue;
			assertCarrierRejected("a=Α".getBytes(Charset.forName(name)), name);
		}
	}

	@Test
	void percentEncodedStatefulComponentsRetainTheirSelectedCharset() {
		for (String name : statefulCharsetNames()) {
			Charset charset = Charset.forName(name);
			if (!charset.canEncode()) continue;
			String value = List.of("日本語 &+=secret", "한국어 &+=secret", "中文 &+=secret", "Α &+=secret").stream()
					.filter(candidate -> charset.newEncoder().canEncode(candidate)).findFirst().orElseThrow();
			assertEquals(Map.of("a", List.of(value)), form(encodedPair("a", value, charset), name).getFormParameters(), name);
			String canonicalName = charset.name().toUpperCase(Locale.ROOT);
			if (canonicalName.contains("JP") || canonicalName.startsWith("X-WINDOWS-5022")) {
				// JP encoders reset shifts at the end of each run. KR/CN URLEncoder
				// runs can leave shifts active across raw ASCII suffixes, so they are
				// not valid whole-component byte streams; encode the complete value above.
				String ordinaryCarrier = "a=" + URLEncoder.encode("prefix " + value + " suffix", charset);
				assertEquals(Map.of("a", List.of("prefix " + value + " suffix")),
						form(ordinaryCarrier.getBytes(StandardCharsets.US_ASCII), name).getFormParameters(), name);
			}
		}
		// The JDK's ISO-2022-CN decoder intentionally has no encoder.
		if (Charset.isSupported("ISO-2022-CN") && Charset.isSupported("x-ISO-2022-CN-GB"))
			assertEquals(Map.of("a", List.of("中文 &+=secret")),
					form(encodedPair("a", "中文 &+=secret", Charset.forName("x-ISO-2022-CN-GB")), "ISO-2022-CN").getFormParameters());
	}

	@Test
	void statelessAsciiCompatibleFormsKeepRawAndMixedEscapedBytes() {
		for (String name : List.of("UTF-8", "ISO-8859-1", "Shift_JIS")) {
			Charset charset = Charset.forName(name);
			String value = name.equals("Shift_JIS") ? "日本語" : "café";
			assertEquals(Map.of("a", List.of(value)), form(("a=" + value).getBytes(charset), name).getFormParameters());
		}
		for (byte[] body : List.of(new byte[]{'a', '=', '%', 'C', '3', (byte) 0xa9},
				new byte[]{'a', '=', (byte) 0xc3, '%', 'A', '9'}))
			assertEquals(Map.of("a", List.of("é")), form(body, "UTF-8").getFormParameters());
	}

	private static List<String> statefulCharsetNames() {
		List<String> names = new ArrayList<>();
		for (String candidate : List.of("ISO-2022-JP", "ISO-2022-JP-2", "ISO-2022-KR", "ISO-2022-CN",
				"x-ISO-2022-CN-CNS", "x-ISO-2022-CN-GB", "x-windows-50220", "x-windows-50221", "x-windows-iso2022jp",
				"iso2022jp", "csISO2022KR", "ISO2022CN", "cp50220", "ms50221"))
			if (Charset.isSupported(candidate)) names.add(candidate);
		assertFalse(names.isEmpty());
		return names;
	}

	private static byte[] encodedPair(String name, String value, Charset charset) {
		return (percentEncodeAll(name.getBytes(charset)) + "=" + percentEncodeAll(value.getBytes(charset)))
				.getBytes(StandardCharsets.US_ASCII);
	}

	private static String percentEncodeAll(byte[] bytes) {
		StringBuilder encoded = new StringBuilder();
		for (byte value : bytes) {
			encoded.append('%').append(Character.forDigit((value & 0xff) >>> 4, 16))
					.append(Character.forDigit(value & 0xf, 16));
		}
		return encoded.toString();
	}

	private static void assertCarrierRejected(byte[] body, String charset) {
		IllegalRequestException failure = assertThrows(IllegalRequestException.class,
				() -> form(body, charset).getFormParameters(), charset);
		assertFalse(failure.getMessage().contains("secret"));
		assertNull(failure.getCause());
	}

	private static Request form(byte[] body, String charset) {
		return Request.withRawUrl(HttpMethod.POST, "/form")
				.headers(Map.of("Content-Type", List.of("application/x-www-form-urlencoded; charset=" + charset)))
				.body(body).build();
	}
}
