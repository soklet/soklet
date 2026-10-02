/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet.internal.mcp.protocol;

import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static java.util.Objects.requireNonNull;

/**
 * Immutable startup navigation indexes for framework-owned 2025 catalogs.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpLegacyCatalogPager {
	static final int MAXIMUM_CURSOR_BYTES = 2_048;
	private static final int FORMAT_VERSION = 1;
	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

	enum Kind {
		TOOLS("tools", "name", McpProfileFrameworkResultKind.TOOLS_LIST,
				McpRuntimeCatalogLocalizer.ResponseKind.TOOLS_LIST),
		PROMPTS("prompts", "name", McpProfileFrameworkResultKind.PROMPTS_LIST,
				McpRuntimeCatalogLocalizer.ResponseKind.PROMPTS_LIST),
		RESOURCES("resources", "uri", McpProfileFrameworkResultKind.RESOURCES_LIST,
				McpRuntimeCatalogLocalizer.ResponseKind.RESOURCES_LIST),
		TEMPLATES("resourceTemplates", "uriTemplate",
				McpProfileFrameworkResultKind.RESOURCE_TEMPLATES_LIST,
				McpRuntimeCatalogLocalizer.ResponseKind.RESOURCE_TEMPLATES_LIST);

		final String member;
		final String identityMember;
		final McpProfileFrameworkResultKind profileKind;
		final McpRuntimeCatalogLocalizer.ResponseKind responseKind;

		Kind(String member, String identityMember, McpProfileFrameworkResultKind profileKind,
				McpRuntimeCatalogLocalizer.ResponseKind responseKind) {
			this.member = member;
			this.identityMember = identityMember;
			this.profileKind = profileKind;
			this.responseKind = responseKind;
		}

		static Optional<Kind> forMethod(String method) {
			return switch (method) {
				case "tools/list" -> Optional.of(TOOLS);
				case "prompts/list" -> Optional.of(PROMPTS);
				case "resources/list" -> Optional.of(RESOURCES);
				case "resources/templates/list" -> Optional.of(TEMPLATES);
				default -> Optional.empty();
			};
		}
	}

	record Entry(String ownerId, String key, McpJsonObject descriptor,
			long encodedBytes, long nodes, int index) {
		@Override public String toString() { return "Entry{<redacted>}"; }
	}

	record Cursor(int kind, String fingerprint, String key, String locale) {
		@Override public String toString() { return "Cursor{<redacted>}"; }
	}

	record Page(List<Entry> entries, boolean more, boolean invalidCursor,
			boolean oversizedEntry) {
		Page { entries = List.copyOf(entries); }
		@Override public String toString() { return "Page{entryCount=" + entries.size() + "}"; }
	}

	@FunctionalInterface
	interface Access {
		boolean permits(String ownerId) throws Exception;
	}

	private final Kind kind;
	private final List<Entry> entries;
	private final Map<String, Integer> indexesByKey;
	private final String fingerprint;
	private final boolean localizableOwners;

	McpLegacyCatalogPager(String path, McpProtocolProfile profile, Kind kind,
			McpWireResult canonicalResult, McpJsonCodec jsonCodec) {
		this(path, profile, kind, canonicalResult, jsonCodec, owner -> 0);
	}

	McpLegacyCatalogPager(String path, McpProtocolProfile profile, Kind kind,
			McpWireResult canonicalResult, McpJsonCodec jsonCodec,
			java.util.function.ToIntFunction<String> slotCount) {
		this.kind = requireNonNull(kind);
		if (!McpLegacyHttpWire.isLegacyRevision(profile.revision()))
			throw new IllegalArgumentException("Framework pagination requires a 2025 profile.");
		McpWireResult projected = profile.renderFrameworkResult(kind.profileKind, canonicalResult);
		McpJsonArray catalog = (McpJsonArray) projected.fields().members().get(kind.member);
		List<McpJsonObject> descriptors = catalog.values().stream()
				.map(value -> (McpJsonObject) value)
				.sorted(Comparator.comparing(value -> identity(value, kind))).toList();
		MessageDigest digest = digest();
		update(digest, requireNonNull(path).getBytes(StandardCharsets.UTF_8));
		update(digest, profile.revision().getBytes(StandardCharsets.US_ASCII));
		update(digest, kind.member.getBytes(StandardCharsets.US_ASCII));
		List<Entry> compiled = new ArrayList<>(descriptors.size());
		Map<String, Integer> indexes = new LinkedHashMap<>();
		boolean localizable = false;
		for (McpJsonObject descriptor : descriptors) {
			String ownerId = identity(descriptor, kind);
			localizable |= slotCount.applyAsInt(ownerId) > 0;
			byte[] keyBytes = digest().digest(ownerId.getBytes(StandardCharsets.UTF_8));
			String key = ENCODER.encodeToString(keyBytes);
			if (indexes.putIfAbsent(key, compiled.size()) != null)
				throw new IllegalArgumentException("Duplicate framework catalog navigation key.");
			byte[] descriptorBytes = jsonCodec.toUtf8Bytes(descriptor);
			// Check the descriptor at its actual envelope depth, even on paged views.
			jsonCodec.toUtf8Bytes(envelope(new McpJsonRpcId.IntegerId(java.math.BigInteger.ZERO),
					new McpJsonObject(Map.of(kind.member, new McpJsonArray(List.of(descriptor))))));
			update(digest, jsonCodec.toUtf8Bytes(canonical(descriptor)));
			compiled.add(new Entry(ownerId, key, descriptor, descriptorBytes.length,
					nodes(descriptor), compiled.size()));
		}
		this.entries = List.copyOf(compiled);
		this.indexesByKey = Map.copyOf(indexes);
		this.fingerprint = HexFormat.of().formatHex(digest.digest());
		this.localizableOwners = localizable;
	}

	Kind kind() { return kind; }
	boolean hasLocalizableOwners() { return localizableOwners; }

	/** Cheap framing checks only; catalog, owner and locale checks follow admission. */
	static Optional<Cursor> decode(String token) {
		if (token.isEmpty() || token.length() > MAXIMUM_CURSOR_BYTES
				|| !token.matches("[A-Za-z0-9_-]+"))
			return Optional.empty();
		try {
			byte[] bytes = DECODER.decode(token);
			if (bytes.length < 68 || bytes.length > 323
					|| !ENCODER.encodeToString(bytes).equals(token))
				return Optional.empty();
			ByteBuffer input = ByteBuffer.wrap(bytes);
			if (Byte.toUnsignedInt(input.get()) != FORMAT_VERSION)
				return Optional.empty();
			int kind = Byte.toUnsignedInt(input.get());
			if (kind >= Kind.values().length)
				return Optional.empty();
			byte[] fingerprint = new byte[32];
			byte[] key = new byte[32];
			input.get(fingerprint).get(key);
			int localeLength = Short.toUnsignedInt(input.getShort());
			if (localeLength > 255 || input.remaining() != localeLength)
				return Optional.empty();
			byte[] locale = new byte[localeLength];
			input.get(locale);
			for (byte character : locale)
				if (!(character >= 'A' && character <= 'Z'
						|| character >= 'a' && character <= 'z'
						|| character >= '0' && character <= '9' || character == '-'))
					return Optional.empty();
			return Optional.of(new Cursor(kind, HexFormat.of().formatHex(fingerprint),
					ENCODER.encodeToString(key), new String(locale, StandardCharsets.US_ASCII)));
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	Page select(@Nullable Cursor cursor, String locale, long baseBytes, long baseNodes,
			long maximumBytes, long maximumNodes, int maximumSlots,
			java.util.function.ToIntFunction<String> slotCount, Access access) throws Exception {
		int start = 0;
		if (cursor != null) {
			Integer index = indexesByKey.get(cursor.key());
			if (cursor.kind() != kind.ordinal() || !fingerprint.equals(cursor.fingerprint())
					|| !locale.equals(cursor.locale()) || index == null
					|| !access.permits(entries.get(index).ownerId()))
				return new Page(List.of(), false, true, false);
			start = index + 1;
		}
		List<Entry> selected = new ArrayList<>();
		long bytes = baseBytes;
		long nodes = baseNodes;
		long slots = 0;
		for (int index = start; index < entries.size(); ++index) {
			Entry candidate = entries.get(index);
			if (!access.permits(candidate.ownerId()))
				continue;
			int candidateSlots = slotCount.applyAsInt(candidate.ownerId());
			long nextBytes = bytes + candidate.encodedBytes() + (selected.isEmpty() ? 0 : 1);
			if (nextBytes > maximumBytes || nodes + candidate.nodes() > maximumNodes
					|| slots + candidateSlots > maximumSlots)
				return continuingPage(selected, locale, bytes, nodes, maximumBytes, maximumNodes);
			selected.add(candidate);
			bytes = nextBytes;
			nodes += candidate.nodes();
			slots += candidateSlots;
		}
		return new Page(selected, false, false, false);
	}

	private Page continuingPage(List<Entry> selected, String locale, long bytes, long nodes,
			long maximumBytes, long maximumNodes) {
		if (selected.isEmpty())
			return new Page(selected, true, false, true);
		// A final single page needs no cursor reservation. Once another visible
		// entry requires continuation, reserve the exact ASCII member/token and
		// its JSON node before localization; only the already-authorized suffix
		// is trimmed. Token length is fixed for this locale regardless of anchor.
		long cursorBytes = ",\"nextCursor\":\"\"".length()
				+ encode(selected.get(selected.size() - 1), locale).length();
		while (!selected.isEmpty() && (bytes + cursorBytes > maximumBytes
				|| nodes + 1 > maximumNodes)) {
			Entry removed = selected.remove(selected.size() - 1);
			bytes -= removed.encodedBytes() + (selected.isEmpty() ? 0 : 1);
			nodes -= removed.nodes();
		}
		return new Page(selected, true, false, selected.isEmpty());
	}

	McpJsonObject document(List<Entry> selected, boolean more, String locale) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		fields.put(kind.member, new McpJsonArray(selected.stream()
				.map(entry -> (McpJsonValue) entry.descriptor()).toList()));
		if (more) {
			if (selected.isEmpty())
				throw new IllegalArgumentException("An empty catalog page cannot continue.");
			fields.put("nextCursor", new McpJsonString(encode(selected.get(selected.size() - 1), locale)));
		}
		return new McpJsonObject(fields);
	}

	private String encode(Entry anchor, String locale) {
		byte[] localeBytes = locale.getBytes(StandardCharsets.US_ASCII);
		if (localeBytes.length > 255)
			throw new IllegalArgumentException("Invalid catalog locale.");
		ByteBuffer output = ByteBuffer.allocate(68 + localeBytes.length);
		output.put((byte) FORMAT_VERSION).put((byte) kind.ordinal())
				.put(HexFormat.of().parseHex(fingerprint)).put(DECODER.decode(anchor.key()))
				.putShort((short) localeBytes.length).put(localeBytes);
		return ENCODER.encodeToString(output.array());
	}

	static McpJsonObject envelope(McpJsonRpcId requestId, McpJsonObject document) {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>();
		fields.put("jsonrpc", new McpJsonString("2.0"));
		fields.put("id", requestId.toJsonValue());
		fields.put("result", document);
		return new McpJsonObject(fields);
	}

	static long nodes(McpJsonValue value) {
		long count = 1;
		if (value instanceof McpJsonObject object)
			for (McpJsonValue member : object.members().values()) count += nodes(member);
		else if (value instanceof McpJsonArray array)
			for (McpJsonValue member : array.values()) count += nodes(member);
		return count;
	}

	private static String identity(McpJsonObject descriptor, Kind kind) {
		return ((McpJsonString) descriptor.members().get(kind.identityMember)).value();
	}

	private static McpJsonValue canonical(McpJsonValue value) {
		if (value instanceof McpJsonObject object) {
			Map<String, McpJsonValue> members = new TreeMap<>();
			object.members().forEach((name, member) -> members.put(name, canonical(member)));
			return new McpJsonObject(members);
		}
		if (value instanceof McpJsonArray array)
			return new McpJsonArray(array.values().stream().map(McpLegacyCatalogPager::canonical).toList());
		if (value instanceof McpJsonNumber number)
			return new McpJsonNumber(number.value().stripTrailingZeros());
		return value;
	}

	private static void update(MessageDigest digest, byte[] bytes) {
		digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
		digest.update(bytes);
	}

	private static MessageDigest digest() {
		try { return MessageDigest.getInstance("SHA-256"); }
		catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable.", exception); }
	}
}
