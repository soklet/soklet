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

package examples.mcp;

import com.soklet.McpCompleteResult;
import com.soklet.McpJsonRpcError;
import com.soklet.McpJsonRpcException;
import com.soklet.McpResourceDescriptor;
import com.soklet.McpResourceOutput;
import com.soklet.McpResourcePage;
import com.soklet.McpTextResourceContents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Public-API-only examples for application-owned MCP resource and cursor
 * security policy.
 *
 * <p>Soklet transports validated resource URIs and opaque cursors. The
 * application still owns filesystem containment and authorization, the URI
 * schemes its clients can actually consume, and stable cursor integrity,
 * identity, snapshot, revision, and expiry bindings.
 *
 * <p>The file handler demonstrates canonical containment and authorization
 * ordering, not a universal race-free filesystem mapper. Production code must
 * add deployment-specific handle- or descriptor-based controls for races that
 * can occur between canonicalization, authorization, and opening a file.
 *
 * <p>The fixed HMAC key is deterministic test material standing in for a
 * managed, rotated production key. The snapshot repository is deliberately
 * one-process test storage: it proves neither MCP-PAGE-006 cross-instance
 * portability nor SOK-L10N-007 locale/localization-revision binding.
 */
public class McpResourceCursorApplicationPatternsTests {
	private static final String RESOURCE_ERROR_MESSAGE =
			"The requested resource is unavailable.";
	private static final String CURSOR_ERROR_MESSAGE =
			"The resource-list cursor is invalid.";
	private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");
	private static final byte[] CURSOR_SECRET =
			"0123456789abcdef0123456789abcdef"
					.getBytes(StandardCharsets.US_ASCII);

	@Test
	void filesystemHandlerCanonicalizesAuthorizesThenReadsAndFailsNeutrally(
			@TempDir Path temporaryDirectory) throws Exception {
		Path boundary = Files.createDirectory(
				temporaryDirectory.resolve("published"));
		Path readable = Files.writeString(boundary.resolve("guide.txt"),
				"safe resource", StandardCharsets.UTF_8);
		Path outside = Files.writeString(temporaryDirectory.resolve("outside.txt"),
				"outside secret", StandardCharsets.UTF_8);
		List<String> steps = new ArrayList<>();
		List<Path> authorizationTargets = new ArrayList<>();
		SecuredFileResourceHandler handler = new SecuredFileResourceHandler(
				boundary,
				(principal, canonicalPath) -> {
					steps.add("authorize");
					authorizationTargets.add(canonicalPath);
					return "tenant-7:user-42".equals(principal);
				},
				path -> {
					steps.add("read");
					return Files.readString(path, StandardCharsets.UTF_8);
				});

		McpCompleteResult result = handler.read("tenant-7:user-42",
				readable.toUri());
		McpResourceOutput output = assertInstanceOf(McpResourceOutput.class,
				result.getPayload());
		McpTextResourceContents contents = assertInstanceOf(
				McpTextResourceContents.class, output.getContents().get(0));
		assertEquals("safe resource", contents.getText());
		assertEquals(readable.toRealPath().toUri(), contents.getUri());
		assertEquals(List.of("authorize", "read"), steps);
		assertEquals(List.of(readable.toRealPath()), authorizationTargets,
				"Authorization must receive the canonical contained path.");

		steps.clear();
		authorizationTargets.clear();
		assertNeutralResourceFailure(() -> handler.read("tenant-7:user-99",
				readable.toUri()));
		assertEquals(List.of("authorize"), steps,
				"Authorization must finish before any filesystem read.");
		assertEquals(List.of(readable.toRealPath()), authorizationTargets);

		steps.clear();
		authorizationTargets.clear();
		URI traversal = URI.create(boundary.toUri().toASCIIString()
				+ "../outside.txt");
		assertNeutralResourceFailure(() -> handler.read("tenant-7:user-42",
				traversal));
		assertNeutralResourceFailure(() -> handler.read("tenant-7:user-42",
				outside.toUri()));
		assertNeutralResourceFailure(() -> handler.read("tenant-7:user-42",
				boundary.resolve("missing.txt").toUri()));
		assertNeutralResourceFailure(() -> handler.read("tenant-7:user-42",
				URI.create(readable.toUri() + "?version=secret")));
		assertEquals(List.of(), steps,
				"Invalid or escaping paths must fail before authorization and read.");
		assertEquals(List.of(), authorizationTargets);
	}

	@Test
	void filesystemHandlerRejectsASymlinkEscape(@TempDir Path temporaryDirectory)
			throws Exception {
		Path boundary = Files.createDirectory(
				temporaryDirectory.resolve("published"));
		Path outside = Files.writeString(temporaryDirectory.resolve("outside.txt"),
				"outside secret", StandardCharsets.UTF_8);
		Path escape = Files.createSymbolicLink(boundary.resolve("escape.txt"),
				outside);
		List<Path> reads = new ArrayList<>();
		List<Path> authorized = new ArrayList<>();
		SecuredFileResourceHandler handler = new SecuredFileResourceHandler(
				boundary, (principal, canonicalPath) -> {
					authorized.add(canonicalPath);
					return true;
				}, path -> {
					reads.add(path);
					return Files.readString(path, StandardCharsets.UTF_8);
				});

		assertTrue(Files.isSymbolicLink(escape));
		assertNeutralResourceFailure(() -> handler.read("tenant-7:user-42",
				escape.toUri()));
		assertEquals(List.of(), authorized,
				"An escaping canonical path must fail before authorization.");
		assertEquals(List.of(), reads,
				"The canonical escape must fail before content is opened.");
	}

	@Test
	void resourceSchemesDistinguishDirectHttpsFromHandlerOnlyCustomUris() {
		URI directHttps = URI.create(
				"https://cdn.example/resources/guide.txt");
		URI handlerOnly = URI.create(
				"app-resource://catalog/guides/42");

		assertEquals(ResourceDelivery.DIRECT_CLIENT_LOAD,
				requireSupportedAddress(directHttps, true));
		assertEquals(ResourceDelivery.APPLICATION_HANDLER,
				requireSupportedAddress(handlerOnly, false));
		assertEquals(directHttps, McpResourceDescriptor
				.withUriAndName(directHttps, "Direct guide").build().getUri());
		assertEquals(handlerOnly, McpResourceDescriptor
				.withUriAndName(handlerOnly, "Handled guide").build().getUri());

		assertThrows(IllegalArgumentException.class,
				() -> requireSupportedAddress(directHttps, false));
		assertThrows(IllegalArgumentException.class,
				() -> requireSupportedAddress(handlerOnly, true));
		for (URI unsafeOrUnsupported : List.of(
				URI.create("http://cdn.example/resources/guide.txt"),
				URI.create("file:///etc/passwd"),
				URI.create("data:text/plain,secret"),
				URI.create("javascript:alert(1)"),
				URI.create("ftp://cdn.example/resources/guide.txt"),
				URI.create("https://credential@cdn.example/guide.txt"),
				URI.create("https://cdn.example/a/../guide.txt"),
				URI.create("relative/guide.txt")))
			assertThrows(IllegalArgumentException.class,
					() -> requireSupportedAddress(unsafeOrUnsupported, true),
					unsafeOrUnsupported.toString());
	}

	@Test
	void signedCursorRetainsItsSnapshotAndEveryInvalidityHasOneSafeError() {
		SignedCursorCodec codec = new SignedCursorCodec(CURSOR_SECRET);
		CatalogSnapshot original = new CatalogSnapshot("snapshot-2026-08-21",
				"catalog-revision-7", List.of(
				record("alpha"), record("bravo"), record("charlie"),
				record("delta"), record("echo")));
		CatalogSnapshotRepository snapshots = new CatalogSnapshotRepository();
		snapshots.activate(original);
		CatalogApplication application = new CatalogApplication(snapshots, 2,
				Duration.ofMinutes(5), codec);

		McpResourcePage first = application.page(Optional.empty(),
				"tenant-7:user-42", NOW);
		McpResourcePage repeated = application.page(Optional.empty(),
				"tenant-7:user-42", NOW);
		assertEquals(List.of("alpha", "bravo"), names(first));
		String firstCursor = first.getNextCursor().orElseThrow();
		assertEquals(firstCursor, repeated.getNextCursor().orElseThrow(),
				"Equal snapshot claims must produce a stable cursor.");
		assertFalse(new String(cursorPayload(firstCursor),
				StandardCharsets.ISO_8859_1).contains("tenant-7:user-42"),
				"The admitted principal belongs in HMAC AAD, not cursor payload.");

		snapshots.activate(new CatalogSnapshot("snapshot-2026-08-22",
				"catalog-revision-8", List.of(record("foxtrot"), record("golf"))));
		McpResourcePage activeFirst = application.page(Optional.empty(),
				"tenant-7:user-42", NOW.plusSeconds(30));
		assertEquals(List.of("foxtrot", "golf"), names(activeFirst));

		McpResourcePage second = application.page(Optional.of(firstCursor),
				"tenant-7:user-42", NOW.plusSeconds(30));
		McpResourcePage replayedSecond = application.page(Optional.of(firstCursor),
				"tenant-7:user-42", NOW.plusSeconds(60));
		assertEquals(List.of("charlie", "delta"), names(second));
		assertEquals(names(second), names(replayedSecond));
		assertEquals(second.getNextCursor(), replayedSecond.getNextCursor(),
				"A retained snapshot must continue deterministically.");
		McpResourcePage third = application.page(second.getNextCursor(),
				"tenant-7:user-42", NOW.plusSeconds(90));
		assertEquals(List.of("echo"), names(third));
		assertTrue(third.getNextCursor().isEmpty());

		List<McpJsonRpcError> failures = new ArrayList<>(List.of(
				cursorFailure(() -> application.page(
						Optional.of(tamper(firstCursor)), "tenant-7:user-42", NOW)),
				cursorFailure(() -> application.page(Optional.of(""),
						"tenant-7:user-42", NOW)),
				cursorFailure(() -> application.page(Optional.of("A".repeat(4_097)),
						"tenant-7:user-42", NOW)),
				cursorFailure(() -> application.page(Optional.of(firstCursor),
						"tenant-7:user-42", NOW.plus(Duration.ofMinutes(5)))),
				cursorFailure(() -> application.page(Optional.of(firstCursor),
						"tenant-7:user-99", NOW))));

		snapshots.evict(original.id());
		failures.add(cursorFailure(() -> application.page(Optional.of(firstCursor),
				"tenant-7:user-42", NOW)));
		snapshots.restore(new CatalogSnapshot(original.id(),
				"catalog-revision-corrupt", original.records()));
		failures.add(cursorFailure(() -> application.page(Optional.of(firstCursor),
				"tenant-7:user-42", NOW)));
		for (McpJsonRpcError error : failures) {
			assertEquals(-32602, error.getCode());
			assertEquals(CURSOR_ERROR_MESSAGE, error.getMessage());
			assertTrue(error.getData().isEmpty(),
					"Cursor failures must not reveal their classification.");
		}
	}

	private static ResourceRecord record(String name) {
		return new ResourceRecord(
				URI.create("app-resource://catalog/guides/" + name), name);
	}

	private static List<String> names(McpResourcePage page) {
		return page.getResources().stream()
				.map(McpResourceDescriptor::getName)
				.toList();
	}

	private static void assertNeutralResourceFailure(ThrowingAction action) {
		McpJsonRpcException exception = assertThrows(McpJsonRpcException.class,
				action::run);
		assertEquals(-32602, exception.getError().getCode());
		assertEquals(RESOURCE_ERROR_MESSAGE, exception.getError().getMessage());
		assertTrue(exception.getError().getData().isEmpty());
		assertNull(exception.getCause());
	}

	private static McpJsonRpcError cursorFailure(ThrowingAction action) {
		McpJsonRpcException exception = assertThrows(McpJsonRpcException.class,
				action::run);
		assertNull(exception.getCause());
		return exception.getError();
	}

	private static String tamper(String cursor) {
		int index = cursor.indexOf('.') + 1;
		char original = cursor.charAt(index);
		char replacement = original == 'A' ? 'B' : 'A';
		return cursor.substring(0, index) + replacement
				+ cursor.substring(index + 1);
	}

	private static byte[] cursorPayload(String cursor) {
		return Base64.getUrlDecoder().decode(
				cursor.substring(0, cursor.indexOf('.')));
	}

	private static ResourceDelivery requireSupportedAddress(URI uri,
			boolean clientCanLoadDirectly) {
		requireNonNull(uri);
		if (!uri.isAbsolute() || !uri.equals(uri.normalize())
				|| !uri.toString().equals(uri.toASCIIString()))
			throw new IllegalArgumentException("Unsupported resource URI.");
		String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
		if ("https".equals(scheme)) {
			if (!clientCanLoadDirectly || uri.isOpaque() || uri.getHost() == null
					|| uri.getUserInfo() != null || uri.getFragment() != null)
				throw new IllegalArgumentException("Unsupported resource URI.");
			return ResourceDelivery.DIRECT_CLIENT_LOAD;
		}
		if ("app-resource".equals(scheme)) {
			if (clientCanLoadDirectly || uri.isOpaque() || uri.getHost() == null
					|| uri.getUserInfo() != null || uri.getQuery() != null
					|| uri.getFragment() != null)
				throw new IllegalArgumentException("Unsupported resource URI.");
			return ResourceDelivery.APPLICATION_HANDLER;
		}
		throw new IllegalArgumentException("Unsupported resource URI.");
	}

	private static McpJsonRpcException invalidResource() {
		return new McpJsonRpcException(
				McpJsonRpcError.fromInvalidParameters(RESOURCE_ERROR_MESSAGE));
	}

	private static McpJsonRpcException invalidCursor() {
		return new McpJsonRpcException(
				McpJsonRpcError.fromInvalidParameters(CURSOR_ERROR_MESSAGE));
	}

	private enum ResourceDelivery {
		DIRECT_CLIENT_LOAD,
		APPLICATION_HANDLER
	}

	@FunctionalInterface
	private interface ResourceAuthorizer {
		boolean mayRead(String principal, Path canonicalPath);
	}

	@FunctionalInterface
	private interface TextFileReader {
		String read(Path path) throws IOException;
	}

	@FunctionalInterface
	private interface ThrowingAction {
		void run() throws Exception;
	}

	private static final class SecuredFileResourceHandler {
		private final Path boundary;
		private final ResourceAuthorizer authorizer;
		private final TextFileReader reader;

		private SecuredFileResourceHandler(Path boundary,
				ResourceAuthorizer authorizer, TextFileReader reader)
				throws IOException {
			this.boundary = requireNonNull(boundary).toRealPath();
			this.authorizer = requireNonNull(authorizer);
			this.reader = requireNonNull(reader);
		}

		private McpCompleteResult read(String principal, URI requestedUri) {
			requireNonNull(principal);
			requireNonNull(requestedUri);
			try {
				if (!"file".equals(requestedUri.getScheme())
						|| requestedUri.getAuthority() != null
						|| requestedUri.getQuery() != null
						|| requestedUri.getFragment() != null)
					throw invalidResource();
				Path canonical = Path.of(requestedUri).toRealPath();
				if (!canonical.startsWith(this.boundary))
					throw invalidResource();
				if (!this.authorizer.mayRead(principal, canonical))
					throw invalidResource();
				String text = this.reader.read(canonical);
				return McpCompleteResult.fromResourceOutput(
						McpResourceOutput.withContent(McpTextResourceContents
										.withUriAndText(canonical.toUri(), text)
										.mimeType("text/plain; charset=utf-8")
										.build())
								.build());
			} catch (IOException | IllegalArgumentException | SecurityException ignored) {
				throw invalidResource();
			}
		}
	}

	private record ResourceRecord(URI uri, String name) {
		private ResourceRecord {
			requireNonNull(uri);
			if (requireNonNull(name).isBlank())
				throw new IllegalArgumentException("Resource name must not be blank.");
			requireSupportedAddress(uri, false);
		}
	}

	private record CatalogSnapshot(String id, String revision,
			List<ResourceRecord> records) {
		private CatalogSnapshot {
			id = requireClaimText(id);
			revision = requireClaimText(revision);
			records = List.copyOf(requireNonNull(records));
		}
	}

	private static final class CatalogSnapshotRepository {
		private final Map<String, CatalogSnapshot> retained = new LinkedHashMap<>();
		private String activeId;

		private synchronized void activate(CatalogSnapshot snapshot) {
			CatalogSnapshot requiredSnapshot = requireNonNull(snapshot);
			if (this.retained.putIfAbsent(requiredSnapshot.id(),
					requiredSnapshot) != null)
				throw new IllegalArgumentException("Snapshot IDs must be unique.");
			this.activeId = requiredSnapshot.id();
		}

		private synchronized void restore(CatalogSnapshot snapshot) {
			CatalogSnapshot requiredSnapshot = requireNonNull(snapshot);
			if (this.retained.putIfAbsent(requiredSnapshot.id(),
					requiredSnapshot) != null)
				throw new IllegalArgumentException("Snapshot IDs must be unique.");
		}

		private synchronized CatalogSnapshot active() {
			if (this.activeId == null)
				throw new IllegalStateException("No catalog snapshot is active.");
			CatalogSnapshot snapshot = this.retained.get(this.activeId);
			if (snapshot == null)
				throw new IllegalStateException(
						"The active catalog snapshot was evicted.");
			return snapshot;
		}

		private synchronized Optional<CatalogSnapshot> find(String id) {
			return Optional.ofNullable(this.retained.get(requireNonNull(id)));
		}

		private synchronized void evict(String id) {
			this.retained.remove(requireNonNull(id));
		}
	}

	private static final class CatalogApplication {
		private final CatalogSnapshotRepository snapshots;
		private final int pageSize;
		private final Duration cursorLifetime;
		private final SignedCursorCodec codec;

		private CatalogApplication(CatalogSnapshotRepository snapshots, int pageSize,
				Duration cursorLifetime,
				SignedCursorCodec codec) {
			this.snapshots = requireNonNull(snapshots);
			if (pageSize < 1)
				throw new IllegalArgumentException("Page size must be positive.");
			this.pageSize = pageSize;
			this.cursorLifetime = requireNonNull(cursorLifetime);
			if (cursorLifetime.isZero() || cursorLifetime.isNegative())
				throw new IllegalArgumentException(
						"Cursor lifetime must be positive.");
			this.codec = requireNonNull(codec);
		}

		private McpResourcePage page(Optional<String> cursor, String principal,
				Instant now) {
			requireNonNull(cursor);
			String boundPrincipal = requireClaimText(principal);
			requireNonNull(now);
			CatalogSnapshot snapshot;
			CursorClaims claims;
			if (cursor.isEmpty()) {
				snapshot = this.snapshots.active();
				claims = new CursorClaims(snapshot.id(), snapshot.revision(),
						now.plus(this.cursorLifetime).getEpochSecond(), 0);
			} else {
				claims = this.codec.verify(cursor.orElseThrow(), boundPrincipal, now);
				snapshot = this.snapshots.find(claims.snapshot()).orElseThrow(
						McpResourceCursorApplicationPatternsTests::invalidCursor);
				if (!snapshot.revision().equals(claims.revision()))
					throw invalidCursor();
			}
			if (claims.offset() > snapshot.records().size())
				throw invalidCursor();

			int end = Math.min(snapshot.records().size(),
					Math.addExact(claims.offset(), this.pageSize));
			McpResourcePage.Builder page = McpResourcePage.builder();
			for (ResourceRecord record
					: snapshot.records().subList(claims.offset(), end))
				page.addResource(McpResourceDescriptor
						.withUriAndName(record.uri(), record.name())
						.build());
			if (end < snapshot.records().size())
				page.nextCursor(this.codec.issue(
						claims.withOffset(end), boundPrincipal));
			return page.build();
		}
	}

	private static final class SignedCursorCodec {
		private static final int FORMAT_VERSION = 1;
		private static final int SIGNATURE_BYTES = 32;
		private static final int MAXIMUM_PAYLOAD_BYTES = 2_048;
		private static final int MAXIMUM_TEXT_BYTES = 256;
		private static final int MAXIMUM_CURSOR_CHARACTERS = 4_096;
		private static final byte[] HMAC_DOMAIN =
				"soklet-example-resource-cursor-v1"
						.getBytes(StandardCharsets.US_ASCII);
		private final byte[] secret;

		private SignedCursorCodec(byte[] secret) {
			requireNonNull(secret);
			if (secret.length < 32)
				throw new IllegalArgumentException(
						"Cursor signing keys require at least 256 bits.");
			this.secret = secret.clone();
		}

		private String issue(CursorClaims claims, String principal) {
			byte[] payload = encode(requireNonNull(claims));
			String boundPrincipal = requireClaimText(principal);
			Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
			return encoder.encodeToString(payload) + "."
					+ encoder.encodeToString(hmac(payload, boundPrincipal));
		}

		private CursorClaims verify(String cursor, String principal, Instant now) {
			String boundPrincipal = requireClaimText(principal);
			requireNonNull(now);
			try {
				if (cursor == null || cursor.length() < 3
						|| cursor.length() > MAXIMUM_CURSOR_CHARACTERS)
					throw invalidCursor();
				int separator = cursor.indexOf('.');
				if (separator < 1 || separator != cursor.lastIndexOf('.'))
					throw invalidCursor();
				Base64.Decoder decoder = Base64.getUrlDecoder();
				byte[] payload = decoder.decode(cursor.substring(0, separator));
				byte[] signature = decoder.decode(cursor.substring(separator + 1));
				if (payload.length < 1 || payload.length > MAXIMUM_PAYLOAD_BYTES
						|| signature.length != SIGNATURE_BYTES
						|| !MessageDigest.isEqual(signature,
								hmac(payload, boundPrincipal)))
					throw invalidCursor();
				CursorClaims claims = decode(payload);
				if (claims.expiresAtEpochSecond()
								<= requireNonNull(now).getEpochSecond())
					throw invalidCursor();
				return claims;
			} catch (IOException | IllegalArgumentException ignored) {
				throw invalidCursor();
			}
		}

		private byte[] encode(CursorClaims claims) {
			try {
				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				try (DataOutputStream output = new DataOutputStream(bytes)) {
					output.writeByte(FORMAT_VERSION);
					writeText(output, claims.snapshot());
					writeText(output, claims.revision());
					output.writeLong(claims.expiresAtEpochSecond());
					output.writeInt(claims.offset());
				}
				byte[] payload = bytes.toByteArray();
				if (payload.length > MAXIMUM_PAYLOAD_BYTES)
					throw new IllegalArgumentException("Cursor payload is too large.");
				return payload;
			} catch (IOException exception) {
				throw new IllegalStateException("Unable to encode cursor.", exception);
			}
		}

		private CursorClaims decode(byte[] payload) throws IOException {
			try (DataInputStream input = new DataInputStream(
					new ByteArrayInputStream(payload))) {
				if (input.readUnsignedByte() != FORMAT_VERSION)
					throw invalidCursor();
				CursorClaims claims = new CursorClaims(readText(input),
						readText(input), input.readLong(), input.readInt());
				if (input.read() != -1)
					throw invalidCursor();
				return claims;
			}
		}

		private static void writeText(DataOutputStream output, String value)
				throws IOException {
			byte[] encoded = claimBytes(value);
			output.writeInt(encoded.length);
			output.write(encoded);
		}

		private static String readText(DataInputStream input) throws IOException {
			int length = input.readInt();
			if (length < 1 || length > MAXIMUM_TEXT_BYTES)
				throw invalidCursor();
			byte[] encoded = input.readNBytes(length);
			if (encoded.length != length)
				throw invalidCursor();
			String decoded = new String(encoded, StandardCharsets.UTF_8);
			if (!Arrays.equals(encoded, decoded.getBytes(StandardCharsets.UTF_8)))
				throw invalidCursor();
			return requireClaimText(decoded);
		}

		private byte[] hmac(byte[] payload, String principal) {
			try {
				Mac mac = Mac.getInstance("HmacSHA256");
				mac.init(new SecretKeySpec(this.secret, "HmacSHA256"));
				byte[] principalBytes = claimBytes(principal);
				mac.update(HMAC_DOMAIN);
				mac.update((byte) (principalBytes.length >>> 24));
				mac.update((byte) (principalBytes.length >>> 16));
				mac.update((byte) (principalBytes.length >>> 8));
				mac.update((byte) principalBytes.length);
				mac.update(principalBytes);
				return mac.doFinal(payload);
			} catch (GeneralSecurityException exception) {
				throw new IllegalStateException(
						"HmacSHA256 is unavailable.", exception);
			}
		}

		private static byte[] claimBytes(String value) {
			byte[] encoded = requireClaimText(value)
					.getBytes(StandardCharsets.UTF_8);
			if (encoded.length > MAXIMUM_TEXT_BYTES)
				throw new IllegalArgumentException("Cursor claim is too large.");
			return encoded;
		}
	}

	private record CursorClaims(String snapshot, String revision,
			long expiresAtEpochSecond, int offset) {
		private CursorClaims {
			snapshot = requireClaimText(snapshot);
			revision = requireClaimText(revision);
			if (offset < 0)
				throw new IllegalArgumentException(
						"Cursor offset must not be negative.");
		}

		private CursorClaims withOffset(int nextOffset) {
			return new CursorClaims(this.snapshot, this.revision,
					this.expiresAtEpochSecond, nextOffset);
		}
	}

	private static String requireClaimText(String value) {
		requireNonNull(value);
		if (value.isBlank())
			throw new IllegalArgumentException("Cursor claims must not be blank.");
		return value;
	}
}
