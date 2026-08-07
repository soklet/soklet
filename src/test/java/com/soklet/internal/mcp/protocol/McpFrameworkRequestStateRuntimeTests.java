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

import com.soklet.McpRequestStateProtectionException;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RequestStateProtectionAdapter;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RequestStateProtectionInput;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.RequestStateProtectionPlan;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.NotThreadSafe;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

public class McpFrameworkRequestStateRuntimeTests {
	private static final int MAXIMUM_ENCODED_BYTES = 4_096;
	private static final int MAXIMUM_DECODED_BYTES = 4_096;
	private static final Duration MAXIMUM_LIFETIME = Duration.ofSeconds(10L);
	private static final int MAXIMUM_ROUNDS = 2;
	private static final String ENDPOINT_PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String METHOD = "tools/call";
	private static final Optional<String> AUTHORIZATION_PARTITION =
			Optional.of("tenant-α");
	private static final Instant INITIAL_TIME =
			Instant.ofEpochSecond(1_700_000_000L, 123_456_789);

	@Test
	public void initialSealAndOpenUseTheExactBindingAndContinuation() throws Exception {
		MutableClock clock = new MutableClock(INITIAL_TIME);
		RecordingAdapter adapter = new RecordingAdapter();
		McpFrameworkRequestStateRuntime runtime = runtime(clock, adapter);
		McpJsonValue state = new McpJsonObject(Map.of(
				"value", new McpJsonString("first")));

		String protectedState = seal(runtime, stringId("request-1"), state,
				Optional.empty());
		Assertions.assertEquals("state-1", protectedState);
		RequestStateProtectionInput sealInput = adapter.lastSealInput;
		assertExactInput(sealInput);
		McpRequestStateBinding expectedBinding = McpRequestStateBinding.create(
				ENDPOINT_PATH, PROTOCOL_VERSION, METHOD,
				AUTHORIZATION_PARTITION, parameters());
		Assertions.assertArrayEquals(expectedBinding.bytes(),
				sealInput.associatedData());
		Assertions.assertTrue(McpRequestStateCanonicalJson.parseCanonical(
				adapter.lastSealedPlaintext, MAXIMUM_DECODED_BYTES)
				instanceof McpJsonObject);

		clock.set(INITIAL_TIME.plusSeconds(1L));
		McpFrameworkRequestStateRuntime.OpenedState opened = open(
				runtime, stringId("request-2"), protectedState);
		assertExactInput(adapter.lastOpenInput);
		Assertions.assertEquals(state, opened.state());
		McpFrameworkRequestStateContinuation continuation = opened.continuation();
		Assertions.assertEquals(McpRequestStateTimestamp.fromInstant(INITIAL_TIME),
				continuation.issuedAt());
		Assertions.assertEquals(McpRequestStateTimestamp.fromInstant(
				INITIAL_TIME.plus(MAXIMUM_LIFETIME)), continuation.expiresAt());
		Assertions.assertEquals(1, continuation.round());
		Assertions.assertEquals(stringId("request-1"),
				continuation.originatingRequestId());
	}

	@Test
	public void reemissionPreservesTimesAndAdvancesRoundAndOriginatingId()
			throws Exception {
		MutableClock clock = new MutableClock(INITIAL_TIME);
		RecordingAdapter adapter = new RecordingAdapter();
		McpFrameworkRequestStateRuntime runtime = runtime(clock, adapter);
		String firstProtectedState = seal(runtime, stringId("request-1"),
				new McpJsonString("one"), Optional.empty());
		clock.set(INITIAL_TIME.plusSeconds(1L));
		McpFrameworkRequestStateContinuation firstContinuation = open(runtime,
				stringId("request-2"), firstProtectedState).continuation();

		String secondProtectedState = seal(runtime, stringId("request-2"),
				new McpJsonString("two"), Optional.of(firstContinuation));
		clock.set(INITIAL_TIME.plusSeconds(2L));
		McpFrameworkRequestStateRuntime.OpenedState second = open(runtime,
				stringId("request-3"), secondProtectedState);

		Assertions.assertEquals(new McpJsonString("two"), second.state());
		Assertions.assertEquals(firstContinuation.issuedAt(),
				second.continuation().issuedAt());
		Assertions.assertEquals(firstContinuation.expiresAt(),
				second.continuation().expiresAt());
		Assertions.assertEquals(2, second.continuation().round());
		Assertions.assertEquals(stringId("request-2"),
				second.continuation().originatingRequestId());

		int sealCalls = adapter.sealCalls;
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> seal(runtime, stringId("request-3"),
						new McpJsonString("three"),
						Optional.of(second.continuation())));
		Assertions.assertEquals(sealCalls, adapter.sealCalls);
	}

	@Test
	public void exactExpiryRejectsOpenAndReemissionBeforeAdapterSeal()
			throws Exception {
		MutableClock clock = new MutableClock(INITIAL_TIME);
		RecordingAdapter adapter = new RecordingAdapter();
		McpFrameworkRequestStateRuntime runtime = runtime(clock, adapter);
		String protectedState = seal(runtime, stringId("request-1"),
				new McpJsonString("state"), Optional.empty());
		clock.set(INITIAL_TIME.plusSeconds(1L));
		McpFrameworkRequestStateContinuation continuation = open(runtime,
				stringId("request-2"), protectedState).continuation();

		clock.set(INITIAL_TIME.plus(MAXIMUM_LIFETIME));
		Assertions.assertThrows(McpInvalidRequestStateException.class,
				() -> open(runtime, stringId("request-2"), protectedState));
		int sealCalls = adapter.sealCalls;
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> seal(runtime, stringId("request-2"),
						new McpJsonString("next"), Optional.of(continuation)));
		Assertions.assertEquals(sealCalls, adapter.sealCalls);
	}

	@Test
	public void mapsAdapterFailureReasonsAtEachCoordinatorBoundary() throws Exception {
		MutableClock clock = new MutableClock(INITIAL_TIME);
		RecordingAdapter adapter = new RecordingAdapter();
		McpFrameworkRequestStateRuntime runtime = runtime(clock, adapter);

		adapter.validateFailure = Failure.INVALID;
		Assertions.assertThrows(McpInvalidRequestStateException.class,
				() -> runtime.validateStructure("state"));
		adapter.validateFailure = Failure.UNAVAILABLE;
		Assertions.assertThrows(McpRequestStateUnavailableException.class,
				() -> runtime.validateStructure("state"));
		adapter.validateFailure = Failure.NONE;
		Assertions.assertThrows(McpInvalidRequestStateException.class,
				() -> runtime.validateStructure(""));
		Assertions.assertThrows(McpInvalidRequestStateException.class,
				() -> runtime.validateStructure(
						"x".repeat(MAXIMUM_ENCODED_BYTES + 1)));

		adapter.openFailure = Failure.INVALID;
		Assertions.assertThrows(McpInvalidRequestStateException.class,
				() -> open(runtime, stringId("request-2"), "state"));
		adapter.openFailure = Failure.UNAVAILABLE;
		Assertions.assertThrows(McpRequestStateUnavailableException.class,
				() -> open(runtime, stringId("request-2"), "state"));
		adapter.openFailure = Failure.NONE;

		adapter.sealFailure = Failure.UNAVAILABLE;
		Assertions.assertThrows(McpRequestStateUnavailableException.class,
				() -> seal(runtime, stringId("request-1"),
						McpJsonNull.INSTANCE, Optional.empty()));
		adapter.sealFailure = Failure.INVALID;
		Assertions.assertThrows(IllegalStateException.class,
				() -> seal(runtime, stringId("request-1"),
						McpJsonNull.INSTANCE, Optional.empty()));
	}

	@Test
	public void rejectsNoncanonicalOrOversizedAdapterPlaintext() throws Exception {
		MutableClock clock = new MutableClock(INITIAL_TIME);
		RecordingAdapter adapter = new RecordingAdapter();
		McpFrameworkRequestStateRuntime runtime = runtime(clock, adapter);
		String protectedState = seal(runtime, stringId("request-1"),
				new McpJsonString("state"), Optional.empty());
		clock.set(INITIAL_TIME.plusSeconds(1L));

		adapter.openTransformer = plaintext -> {
			byte[] noncanonical = new byte[plaintext.length + 1];
			noncanonical[0] = ' ';
			System.arraycopy(plaintext, 0, noncanonical, 1, plaintext.length);
			return noncanonical;
		};
		Assertions.assertThrows(McpInvalidRequestStateException.class,
				() -> open(runtime, stringId("request-2"), protectedState));

		adapter.openTransformer = ignored ->
				new byte[MAXIMUM_DECODED_BYTES + 1];
		Assertions.assertThrows(McpInvalidRequestStateException.class,
				() -> open(runtime, stringId("request-2"), protectedState));
	}

	@Test
	public void adapterNullEmptyAndOversizeOutputsRemainApplicationInvariants()
			throws Exception {
		MutableClock clock = new MutableClock(INITIAL_TIME);

		RecordingAdapter nullOpenAdapter = new RecordingAdapter();
		McpFrameworkRequestStateRuntime nullOpenRuntime =
				runtime(clock, nullOpenAdapter);
		String protectedState = seal(nullOpenRuntime, stringId("request-1"),
				McpJsonNull.INSTANCE, Optional.empty());
		nullOpenAdapter.returnNullOnOpen = true;
		Assertions.assertThrows(NullPointerException.class,
				() -> open(nullOpenRuntime, stringId("request-2"), protectedState));

		RecordingAdapter nullSealAdapter = new RecordingAdapter();
		nullSealAdapter.returnNullOnSeal = true;
		Assertions.assertThrows(NullPointerException.class,
				() -> seal(runtime(clock, nullSealAdapter), stringId("request-1"),
						McpJsonNull.INSTANCE, Optional.empty()));

		RecordingAdapter emptySealAdapter = new RecordingAdapter();
		emptySealAdapter.forcedSealOutput = "";
		Assertions.assertThrows(IllegalStateException.class,
				() -> seal(runtime(clock, emptySealAdapter), stringId("request-1"),
						McpJsonNull.INSTANCE, Optional.empty()));

		RecordingAdapter oversizeSealAdapter = new RecordingAdapter();
		oversizeSealAdapter.forcedSealOutput =
				"x".repeat(MAXIMUM_ENCODED_BYTES + 1);
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> seal(runtime(clock, oversizeSealAdapter), stringId("request-1"),
						McpJsonNull.INSTANCE, Optional.empty()));
	}

	private static McpFrameworkRequestStateRuntime runtime(
			Clock clock, RecordingAdapter adapter) {
		return new McpFrameworkRequestStateRuntime(Optional.of(
				new RequestStateProtectionPlan(MAXIMUM_ENCODED_BYTES,
						MAXIMUM_DECODED_BYTES, MAXIMUM_LIFETIME,
						MAXIMUM_ROUNDS, adapter)), clock);
	}

	private static String seal(McpFrameworkRequestStateRuntime runtime,
			McpJsonRpcId requestId, McpJsonValue state,
			Optional<McpFrameworkRequestStateContinuation> prior)
			throws McpRequestStateUnavailableException {
		return runtime.seal(ENDPOINT_PATH, PROTOCOL_VERSION, METHOD,
				AUTHORIZATION_PARTITION, parameters(), requestId, state, prior);
	}

	private static McpFrameworkRequestStateRuntime.OpenedState open(
			McpFrameworkRequestStateRuntime runtime, McpJsonRpcId requestId,
			String protectedState) throws McpInvalidRequestStateException,
			McpRequestStateUnavailableException {
		return runtime.open(ENDPOINT_PATH, PROTOCOL_VERSION, METHOD,
				AUTHORIZATION_PARTITION, parameters(), requestId, protectedState);
	}

	private static McpJsonObject parameters() {
		Map<String, McpJsonValue> metadata = new LinkedHashMap<>();
		metadata.put("stable", new McpJsonString("yes"));
		Map<String, McpJsonValue> parameters = new LinkedHashMap<>();
		parameters.put("name", new McpJsonString("echo"));
		parameters.put("_meta", new McpJsonObject(metadata));
		return new McpJsonObject(parameters);
	}

	private static McpJsonRpcId stringId(String value) {
		return new McpJsonRpcId.StringId(value);
	}

	private static void assertExactInput(RequestStateProtectionInput input) {
		Assertions.assertNotNull(input);
		Assertions.assertEquals(ENDPOINT_PATH, input.endpointPath());
		Assertions.assertEquals(PROTOCOL_VERSION, input.protocolVersion());
		Assertions.assertEquals(METHOD, input.method());
	}

	private enum Failure {
		NONE,
		INVALID,
		UNAVAILABLE
	}

	@NotThreadSafe
	private static final class RecordingAdapter
			implements RequestStateProtectionAdapter {
		private final AtomicInteger sequence = new AtomicInteger();
		private final Map<String, byte[]> plaintextByState = new HashMap<>();
		private Failure validateFailure = Failure.NONE;
		private Failure sealFailure = Failure.NONE;
		private Failure openFailure = Failure.NONE;
		private Function<byte[], byte[]> openTransformer = byte[]::clone;
		private boolean returnNullOnSeal;
		private boolean returnNullOnOpen;
		@Nullable
		private String forcedSealOutput;
		private int validateCalls;
		private int sealCalls;
		private int openCalls;
		@Nullable
		private RequestStateProtectionInput lastSealInput;
		@Nullable
		private RequestStateProtectionInput lastOpenInput;
		private byte @Nullable [] lastSealedPlaintext;

		@Override
		public void validateStructure(@NonNull String protectedState)
				throws McpRequestStateProtectionException {
			requireNonNull(protectedState);
			validateCalls++;
			throwIfConfigured(validateFailure);
		}

		@Override
		@NonNull
		public String seal(@NonNull RequestStateProtectionInput input,
				byte @NonNull [] canonicalPlaintext)
				throws McpRequestStateProtectionException {
			sealCalls++;
			lastSealInput = requireNonNull(input);
			lastSealedPlaintext = requireNonNull(canonicalPlaintext).clone();
			throwIfConfigured(sealFailure);
			if (returnNullOnSeal)
				return null;
			String protectedState = forcedSealOutput != null
					? forcedSealOutput
					: "state-" + sequence.incrementAndGet();
			plaintextByState.put(protectedState, canonicalPlaintext.clone());
			return protectedState;
		}

		@Override
		public byte @NonNull [] open(@NonNull RequestStateProtectionInput input,
				@NonNull String protectedState)
				throws McpRequestStateProtectionException {
			openCalls++;
			lastOpenInput = requireNonNull(input);
			requireNonNull(protectedState);
			throwIfConfigured(openFailure);
			if (returnNullOnOpen)
				return null;
			byte[] plaintext = plaintextByState.get(protectedState);
			if (plaintext == null)
				throw McpRequestStateProtectionException.fromInvalidState();
			return requireNonNull(openTransformer.apply(plaintext.clone()));
		}

		private static void throwIfConfigured(Failure failure)
				throws McpRequestStateProtectionException {
			if (failure == Failure.INVALID)
				throw McpRequestStateProtectionException.fromInvalidState();
			if (failure == Failure.UNAVAILABLE)
				throw McpRequestStateProtectionException.fromProtectorUnavailable();
		}
	}

	@NotThreadSafe
	private static final class MutableClock extends Clock {
		@NonNull
		private Instant instant;
		@NonNull
		private final ZoneId zone;

		private MutableClock(@NonNull Instant instant) {
			this(instant, ZoneOffset.UTC);
		}

		private MutableClock(@NonNull Instant instant, @NonNull ZoneId zone) {
			this.instant = requireNonNull(instant);
			this.zone = requireNonNull(zone);
		}

		private void set(@NonNull Instant instant) {
			this.instant = requireNonNull(instant);
		}

		@Override
		@NonNull
		public ZoneId getZone() {
			return zone;
		}

		@Override
		@NonNull
		public Clock withZone(@NonNull ZoneId zone) {
			return new MutableClock(instant, zone);
		}

		@Override
		@NonNull
		public Instant instant() {
			return instant;
		}
	}
}
