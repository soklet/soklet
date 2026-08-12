/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet.internal.mcp.protocol;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.soklet.McpRequestOutcome;
import com.soklet.McpSimulationBodyMode;
import com.soklet.McpSimulationCompletion;
import com.soklet.McpSimulationOptions;
import com.soklet.McpSimulationResponse;
import com.soklet.McpSimulationStreamItem;
import com.soklet.McpSimulationStreamItemType;
import com.soklet.McpStreamTerminationReason;
import com.soklet.StreamTerminationReason;
import com.soklet.internal.mcp.transport.McpOutboundChannel;
import com.soklet.internal.microhttp.Header;
import com.soklet.internal.microhttp.MicrohttpResponse;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.requireNonNull;

/**
 * Coverage-guided state-machine checks for bounded off-network MCP capture.
 */
@ThreadSafe
public class McpSimulationCaptureFuzzTest {
	private static final int MAXIMUM_INPUT_BYTES = 65_536;
	private static final int MAXIMUM_ACTIONS = 64;
	private static final int MAXIMUM_ACTION_PAYLOAD_BYTES = 256;
	private static final int MAXIMUM_ITEM_CAPACITY = 16;
	private static final int MAXIMUM_BYTE_CAPACITY = 4_096;
	private static final byte @NonNull [] KEEP_ALIVE_BYTES =
			": keepalive\n\n".getBytes(StandardCharsets.US_ASCII);
	private static final List<@NonNull String> CURATED_SEEDS = List.of(
			"json-complete.actions",
			"sse-terminal.actions",
			"item-limit.actions",
			"byte-limit.actions",
			"cancel.actions",
			"duplicate-terminal.actions");

	@FuzzTest(maxDuration = "2m")
	public void captureStateMachineRemainsBoundedTerminalAndIdempotent(
			byte[] input) throws InterruptedException {
		replay(parse(input));
	}

	@Test
	public void curatedSeedsReachJsonSseLimitCancelAndCompletionBranches()
			throws Exception {
		ReplayReport json = replay(parse(readSeed("json-complete.actions")));
		Assertions.assertTrue(json.jsonCompletion());
		Assertions.assertTrue(json.responseDefensiveCopy());

		ReplayReport sse = replay(parse(readSeed("sse-terminal.actions")));
		Assertions.assertTrue(sse.sseTerminal());
		Assertions.assertTrue(sse.coalescing());
		Assertions.assertTrue(sse.terminalDuplicated());

		ReplayReport itemLimit = replay(parse(readSeed("item-limit.actions")));
		Assertions.assertTrue(itemLimit.itemLimit());
		Assertions.assertFalse(itemLimit.byteLimit());

		ReplayReport byteLimit = replay(parse(readSeed("byte-limit.actions")));
		Assertions.assertTrue(byteLimit.byteLimit());
		Assertions.assertEquals(MAXIMUM_BYTE_CAPACITY,
				byteLimit.maximumAcceptedBytes());

		ReplayReport cancel = replay(parse(readSeed("cancel.actions")));
		Assertions.assertTrue(cancel.cancelBeforeResponse());
		Assertions.assertTrue(cancel.cancelAfterResponse());

		ReplayReport duplicate = replay(parse(
				readSeed("duplicate-terminal.actions")));
		Assertions.assertTrue(duplicate.duplicateTerminal());
		Assertions.assertTrue(duplicate.stableCompletion());
	}

	@NonNull
	private static ReplayReport replay(@NonNull Program program)
			throws InterruptedException {
		ReplayReport.Mutable report = new ReplayReport.Mutable();
		Session session = new Session(program.itemCapacity(),
				program.byteCapacity(), report);
		for (Action action : program.actions()) {
			if (action.type() == ActionType.RESET) {
				session.finishAndVerify();
				session = new Session(program.itemCapacity(),
						program.byteCapacity(), report);
			} else {
				session.apply(action);
				session.assertBounds();
			}
		}
		session.finishAndVerify();
		return report.freeze();
	}

	@NonNull
	private static Program parse(byte[] input) {
		byte[] bounded = Arrays.copyOf(requireNonNull(input),
				Math.min(input.length, MAXIMUM_INPUT_BYTES));
		String text = new String(bounded, StandardCharsets.US_ASCII);
		if (text.startsWith("limits "))
			return parseText(text, bounded);
		return parseBinary(bounded);
	}

	@NonNull
	private static Program parseText(@NonNull String text, byte[] input) {
		String[] lines = text.split("\n", MAXIMUM_ACTIONS + 2);
		int itemCapacity = derivedItemCapacity(input, 0);
		int byteCapacity = derivedByteCapacity(input, 1);
		int firstAction = 0;
		if (lines.length > 0) {
			String[] limits = stripCarriageReturn(lines[0]).trim().split(" +", 3);
			if (limits.length == 3 && limits[0].equals("limits")) {
				itemCapacity = parseBoundedPositive(limits[1], itemCapacity,
						MAXIMUM_ITEM_CAPACITY);
				byteCapacity = parseBoundedPositive(limits[2], byteCapacity,
						MAXIMUM_BYTE_CAPACITY);
				firstAction = 1;
			}
		}

		List<Action> actions = new ArrayList<>();
		for (int i = firstAction; i < lines.length
				&& actions.size() < MAXIMUM_ACTIONS; ++i) {
			Action action = parseTextAction(stripCarriageReturn(lines[i]), i);
			if (action != null)
				actions.add(action);
		}
		return new Program(itemCapacity, byteCapacity, List.copyOf(actions));
	}

	@NonNull
	private static Program parseBinary(byte[] input) {
		int itemCapacity = derivedItemCapacity(input, 0);
		int byteCapacity = derivedByteCapacity(input, 1);
		List<Action> actions = new ArrayList<>();
		int cursor = Math.min(3, input.length);
		while (cursor < input.length && actions.size() < MAXIMUM_ACTIONS) {
			int opcode = Byte.toUnsignedInt(input[cursor++]);
			int requestedLength = cursor < input.length
					? 1 + Byte.toUnsignedInt(input[cursor++]) : 1;
			int length = Math.min(MAXIMUM_ACTION_PAYLOAD_BYTES,
					Math.min(requestedLength, input.length - cursor));
			byte[] payload = length == 0
					? new byte[] {(byte) opcode}
					: Arrays.copyOfRange(input, cursor, cursor + length);
			cursor += length;
			ActionType type = ActionType.values()[opcode
					% ActionType.values().length];
			String key = "key-" + (Byte.toUnsignedInt(payload[0]) % 4);
			McpRequestOutcome outcome = McpRequestOutcome.values()[
					Byte.toUnsignedInt(payload[0])
							% McpRequestOutcome.values().length];
			actions.add(new Action(type, payload, key, outcome));
		}
		return new Program(itemCapacity, byteCapacity, List.copyOf(actions));
	}

	private static @Nullable Action parseTextAction(@NonNull String line,
			int actionIndex) {
		String stripped = line.strip();
		if (stripped.isEmpty() || stripped.startsWith("#"))
			return null;
		int separator = stripped.indexOf(' ');
		String command = (separator == -1 ? stripped
				: stripped.substring(0, separator)).toLowerCase(Locale.ROOT);
		String remainder = separator == -1 ? ""
				: stripped.substring(separator + 1);
		byte[] payload = payload(remainder, actionIndex);
		return switch (command) {
			case "json" -> new Action(ActionType.JSON, payload, "", outcome(remainder));
			case "sse" -> new Action(ActionType.SSE, payload, "", outcome(remainder));
			case "offer" -> new Action(ActionType.OFFER, payload, "", outcome(remainder));
			case "offer-size" -> new Action(ActionType.OFFER,
					sizedPayload(remainder, actionIndex), "", outcome(remainder));
			case "coalesce" -> {
				int keyEnd = remainder.indexOf(' ');
				String key = keyEnd == -1 ? remainder : remainder.substring(0, keyEnd);
				String value = keyEnd == -1 ? remainder
						: remainder.substring(keyEnd + 1);
				yield new Action(ActionType.COALESCE,
						payload(value, actionIndex), key.isEmpty() ? "key" : key,
						outcome(value));
			}
			case "keepalive" -> new Action(ActionType.KEEPALIVE, payload,
					"", outcome(remainder));
			case "terminal", "complete" -> new Action(ActionType.TERMINAL,
					payload, "", outcome(remainder));
			case "fail" -> new Action(ActionType.FAIL, payload, "", outcome(remainder));
			case "cancel" -> new Action(ActionType.CANCEL, payload, "", outcome(remainder));
			case "close" -> new Action(ActionType.CLOSE, payload, "", outcome(remainder));
			case "finish" -> new Action(ActionType.FINISH, payload, "", outcome(remainder));
			case "poll" -> new Action(ActionType.POLL, payload, "", outcome(remainder));
			case "reset" -> new Action(ActionType.RESET, payload, "", outcome(remainder));
			default -> null;
		};
	}

	private static byte @NonNull [] payload(@NonNull String value,
			int actionIndex) {
		byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
		if (bytes.length == 0)
			return new byte[] {(byte) ('a' + Math.floorMod(actionIndex, 26))};
		return Arrays.copyOf(bytes,
				Math.min(bytes.length, MAXIMUM_ACTION_PAYLOAD_BYTES));
	}

	private static byte @NonNull [] sizedPayload(@NonNull String value,
			int actionIndex) {
		int size = parseBoundedPositive(value.trim(), 1,
				MAXIMUM_ACTION_PAYLOAD_BYTES);
		byte[] payload = new byte[size];
		Arrays.fill(payload, (byte) ('a' + Math.floorMod(actionIndex, 26)));
		return payload;
	}

	private static McpRequestOutcome outcome(@NonNull String value) {
		try {
			return McpRequestOutcome.valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException ignored) {
			return McpRequestOutcome.COMPLETE;
		}
	}

	private static int parseBoundedPositive(@NonNull String value, int fallback,
			int maximum) {
		try {
			int parsed = Integer.parseInt(value);
			return parsed > 0 ? Math.min(parsed, maximum) : fallback;
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static int derivedItemCapacity(byte[] input, int index) {
		return 1 + unsignedAt(input, index) % MAXIMUM_ITEM_CAPACITY;
	}

	private static int derivedByteCapacity(byte[] input, int index) {
		int high = unsignedAt(input, index);
		int low = unsignedAt(input, index + 1);
		return 1 + ((high << 8) | low) % MAXIMUM_BYTE_CAPACITY;
	}

	private static int unsignedAt(byte[] input, int index) {
		return index < input.length ? Byte.toUnsignedInt(input[index]) : 0;
	}

	@NonNull
	private static String stripCarriageReturn(@NonNull String value) {
		return value.endsWith("\r")
				? value.substring(0, value.length() - 1) : value;
	}

	private static byte @NonNull [] readSeed(@NonNull String name)
			throws IOException {
		Assertions.assertTrue(CURATED_SEEDS.contains(name));
		String resource = "McpSimulationCaptureFuzzTestInputs/"
				+ "captureStateMachineRemainsBoundedTerminalAndIdempotent/" + name;
		try (InputStream stream = requireNonNull(
				McpSimulationCaptureFuzzTest.class.getResourceAsStream(resource))) {
			return stream.readAllBytes();
		}
	}

	private enum ActionType {
		JSON,
		SSE,
		OFFER,
		COALESCE,
		KEEPALIVE,
		TERMINAL,
		FAIL,
		CANCEL,
		CLOSE,
		FINISH,
		POLL,
		RESET
	}

	private enum Mode {
		NONE,
		JSON,
		SSE
	}

	private record Program(int itemCapacity, int byteCapacity,
			@NonNull List<@NonNull Action> actions) {
		private Program {
			Assertions.assertTrue(itemCapacity >= 1
					&& itemCapacity <= MAXIMUM_ITEM_CAPACITY);
			Assertions.assertTrue(byteCapacity >= 1
					&& byteCapacity <= MAXIMUM_BYTE_CAPACITY);
			actions = List.copyOf(requireNonNull(actions));
			Assertions.assertTrue(actions.size() <= MAXIMUM_ACTIONS);
		}
	}

	private record Action(@NonNull ActionType type, byte @NonNull [] payload,
			@NonNull String key, @NonNull McpRequestOutcome outcome) {
		private Action {
			requireNonNull(type);
			payload = Arrays.copyOf(requireNonNull(payload), payload.length);
			requireNonNull(key);
			requireNonNull(outcome);
			Assertions.assertTrue(payload.length >= 1
					&& payload.length <= MAXIMUM_ACTION_PAYLOAD_BYTES);
		}

		@Override
		public byte @NonNull [] payload() {
			return Arrays.copyOf(this.payload, this.payload.length);
		}
	}

	private static final class Session {
		private final int itemCapacity;
		private final int byteCapacity;
		private final ReplayReport.Mutable report;
		@NonNull
		private final AtomicInteger completionCallbacks;
		@NonNull
		private final McpSimulationRuntime runtime;
		@NonNull
		private final Queue<@NonNull ModelItem> pendingItems;
		@NonNull
		private final Set<@NonNull String> pendingCoalescingKeys;
		private @NonNull Mode mode;
		private @Nullable CaptureListener listener;
		private int capturedBytes;
		private int lastAssertedCapturedBytes;
		private boolean responsePublished;
		private boolean channelTerminal;
		private boolean requestFinished;
		private @Nullable McpStreamTerminationReason terminalReason;
		private @Nullable Throwable terminalThrowable;
		private boolean terminalItemAccepted;

		private Session(int itemCapacity, int byteCapacity,
				ReplayReport.Mutable report) {
			this.itemCapacity = itemCapacity;
			this.byteCapacity = byteCapacity;
			this.report = requireNonNull(report);
			this.completionCallbacks = new AtomicInteger();
			this.runtime = new McpSimulationRuntime(McpSimulationOptions.builder()
					.streamItemQueueCapacity(itemCapacity)
					.maximumCapturedBytes(byteCapacity)
					.build(), this.completionCallbacks::incrementAndGet);
			this.pendingItems = new ArrayDeque<>();
			this.pendingCoalescingKeys = new HashSet<>();
			this.mode = Mode.NONE;
			this.runtime.bindController(reason -> {
				boolean won;
				if (this.mode == Mode.SSE) {
					won = this.runtime.fail(reason, null);
					if (won) {
						this.channelTerminal = true;
						this.terminalReason =
								McpStreamTerminationReason.CLIENT_DISCONNECTED;
					}
				} else {
					this.runtime.reserveRuntimeReason(
							McpStreamTerminationReason.CLIENT_DISCONNECTED);
					won = true;
					this.terminalReason =
							McpStreamTerminationReason.CLIENT_DISCONNECTED;
				}
				if (won) {
					this.runtime.didFinishRequest(
							McpRequestOutcome.CLIENT_DISCONNECTED, List.of());
					this.requestFinished = true;
				}
				return won;
			});
		}

		private void apply(@NonNull Action action) throws InterruptedException {
			switch (action.type()) {
				case JSON -> acceptJson(action.payload());
				case SSE -> acceptSse();
				case OFFER -> offer(action.payload(), null);
				case COALESCE -> offer(action.payload(), action.key());
				case KEEPALIVE -> offerKeepAlive();
				case TERMINAL -> complete(action.payload());
				case FAIL -> fail();
				case CANCEL -> cancel(false);
				case CLOSE -> cancel(true);
				case FINISH -> finish(action.outcome());
				case POLL -> poll();
				case RESET -> throw new AssertionError("RESET is handled by replay.");
			}
		}

		private void acceptJson(byte @NonNull [] inputBody)
				throws InterruptedException {
			if (this.mode != Mode.NONE || this.requestFinished)
				return;
			this.mode = Mode.JSON;
			byte[] body = Arrays.copyOf(inputBody, inputBody.length);
			byte[] expected = Arrays.copyOf(body, body.length);
			this.runtime.acceptResponse(new MicrohttpResponse(200, "OK", List.of(
					new Header("X-Fuzz", "first"),
					new Header("x-fuzz", "second")), body));
			body[0] ^= 1;
			this.responsePublished = true;
			McpSimulationResponse response = this.runtime.awaitResponse(
					Duration.ZERO).orElseThrow();
			Assertions.assertEquals(McpSimulationBodyMode.JSON,
					response.getBodyMode());
			Assertions.assertEquals(List.of("first", "second"),
					new ArrayList<>(response.getHeaders().get("X-Fuzz")));
			Assertions.assertThrows(UnsupportedOperationException.class,
					() -> response.getHeaders().put("mutation", Set.of("x")));
			if (expected.length > this.byteCapacity) {
				Assertions.assertTrue(response.getBody().isEmpty());
				this.terminalReason = McpStreamTerminationReason
						.SIMULATOR_CAPTURE_BYTE_LIMIT_EXCEEDED;
				this.report.byteLimit = true;
			} else {
				assertBodyCopy(expected, response);
				this.capturedBytes = expected.length;
				this.terminalReason = McpStreamTerminationReason.COMPLETED;
				this.report.jsonCompletion = true;
				this.report.responseDefensiveCopy = true;
			}
			this.report.maximumAcceptedBytes = Math.max(
					this.report.maximumAcceptedBytes, this.capturedBytes);
		}

		private void acceptSse() throws InterruptedException {
			if (this.mode != Mode.NONE || this.requestFinished)
				return;
			this.mode = Mode.SSE;
			this.listener = new CaptureListener();
			this.runtime.openChannel(this.listener);
			this.runtime.acceptResponse(this.runtime.response(List.of(
					new Header("X-Fuzz", "sse"))));
			this.responsePublished = true;
			McpSimulationResponse response = this.runtime.awaitResponse(
					Duration.ZERO).orElseThrow();
			Assertions.assertEquals(McpSimulationBodyMode.SERVER_SENT_EVENTS,
					response.getBodyMode());
			Assertions.assertTrue(response.getBody().isEmpty());
		}

		private void offer(byte @NonNull [] source, @Nullable String key) {
			if (this.mode != Mode.SSE || !this.responsePublished)
				return;
			byte[] expected = Arrays.copyOf(source, source.length);
			McpRequestSseStream.Frame frame = jsonFrame(source);
			source[0] ^= 1;
			boolean duplicate = key != null
					&& this.pendingCoalescingKeys.contains(key);
			ExpectedOffer expectedOffer = expectedOffer(expected.length, duplicate);
			McpOutboundChannel.OfferResult actual = key == null
					? this.runtime.offer(frame)
					: this.runtime.offerCoalescing(frame, key);
			Assertions.assertEquals(expectedOffer.result(), actual);
			if (expectedOffer.reason() != null) {
				markLimit(expectedOffer.reason());
				return;
			}
			if (!duplicate && actual == McpOutboundChannel.OfferResult.ACCEPTED) {
				this.pendingItems.add(new ModelItem(
						McpSimulationStreamItemType.JSON_MESSAGE, expected, key, false));
				if (key != null) {
					this.pendingCoalescingKeys.add(key);
					this.report.coalescing = true;
				}
				this.capturedBytes += expected.length;
				this.report.maximumAcceptedBytes = Math.max(
						this.report.maximumAcceptedBytes, this.capturedBytes);
			}
		}

		private void offerKeepAlive() {
			if (this.mode != Mode.SSE || !this.responsePublished)
				return;
			ExpectedOffer expectedOffer = expectedOffer(
					KEEP_ALIVE_BYTES.length, false);
			McpOutboundChannel.OfferResult actual = this.runtime.offer(
					new McpRequestSseStream.Frame(
							McpRequestSseStream.FrameType.KEEP_ALIVE_COMMENT,
							null, KEEP_ALIVE_BYTES));
			Assertions.assertEquals(expectedOffer.result(), actual);
			if (expectedOffer.reason() != null) {
				markLimit(expectedOffer.reason());
				return;
			}
			if (actual == McpOutboundChannel.OfferResult.ACCEPTED) {
				this.pendingItems.add(new ModelItem(
						McpSimulationStreamItemType.KEEP_ALIVE_COMMENT,
						KEEP_ALIVE_BYTES, null, false));
				this.capturedBytes += KEEP_ALIVE_BYTES.length;
				this.report.maximumAcceptedBytes = Math.max(
						this.report.maximumAcceptedBytes, this.capturedBytes);
			}
		}

		private void complete(byte @NonNull [] source) {
			if (this.mode != Mode.SSE || !this.responsePublished)
				return;
			byte[] expected = Arrays.copyOf(source, source.length);
			McpRequestSseStream.Frame frame = jsonFrame(source);
			source[0] ^= 1;
			if (this.channelTerminal) {
				Assertions.assertFalse(this.runtime.complete(frame));
				this.report.duplicateTerminal = true;
				return;
			}
			ExpectedOffer expectedOffer = expectedOffer(expected.length, false);
			boolean accepted = this.runtime.complete(frame);
			if (expectedOffer.reason() != null) {
				Assertions.assertFalse(accepted);
				markLimit(expectedOffer.reason());
				return;
			}
			Assertions.assertTrue(accepted);
			this.pendingItems.add(new ModelItem(
					McpSimulationStreamItemType.JSON_MESSAGE, expected, null, true));
			this.capturedBytes += expected.length;
			this.channelTerminal = true;
			this.terminalReason = McpStreamTerminationReason.COMPLETED;
			this.terminalItemAccepted = true;
			this.report.sseTerminal = true;
			this.report.maximumAcceptedBytes = Math.max(
					this.report.maximumAcceptedBytes, this.capturedBytes);
		}

		private void fail() {
			if (this.mode != Mode.SSE || !this.responsePublished)
				return;
			if (this.channelTerminal) {
				Assertions.assertFalse(this.runtime.fail(
						StreamTerminationReason.INTERNAL_ERROR, null));
				return;
			}
			Assertions.assertTrue(this.runtime.fail(
					StreamTerminationReason.INTERNAL_ERROR, null));
			this.channelTerminal = true;
			this.terminalReason = McpStreamTerminationReason.INTERNAL_ERROR;
			this.terminalThrowable = new IllegalStateException("synthetic failure");
		}

		private void cancel(boolean close) {
			boolean beforeResponse = !this.responsePublished;
			if (close)
				this.runtime.close();
			else
				this.runtime.cancel();
			if (!this.requestFinished && this.terminalReason == null) {
				this.requestFinished = true;
				this.channelTerminal = this.mode == Mode.SSE;
				this.terminalReason =
						McpStreamTerminationReason.CLIENT_DISCONNECTED;
			}
			if (beforeResponse)
				this.report.cancelBeforeResponse = true;
			else
				this.report.cancelAfterResponse = true;
		}

		private void finish(@NonNull McpRequestOutcome outcome) {
			if (this.requestFinished || this.mode == Mode.NONE
					|| (this.mode == Mode.SSE && !this.channelTerminal)) {
				if (this.requestFinished)
					this.report.duplicateTerminal = true;
				return;
			}
			List<Throwable> throwables = this.terminalThrowable == null
					? List.of() : List.of(this.terminalThrowable);
			this.runtime.didFinishRequest(outcome, throwables);
			this.requestFinished = true;
		}

		private void poll() throws InterruptedException {
			Optional<McpSimulationStreamItem> actual = this.runtime.nextStreamItem(
					Duration.ZERO);
			ModelItem expected = this.pendingItems.poll();
			Assertions.assertEquals(expected != null, actual.isPresent());
			if (expected == null)
				return;
			if (expected.coalescingKey() != null)
				this.pendingCoalescingKeys.remove(expected.coalescingKey());
			assertItem(expected, actual.orElseThrow());
			if (expected.terminal())
				this.report.terminalItemObserved = true;
		}

		private void markLimit(@NonNull McpStreamTerminationReason reason) {
			this.channelTerminal = true;
			this.terminalReason = requireNonNull(reason);
			if (reason == McpStreamTerminationReason
					.SIMULATOR_CAPTURE_ITEM_LIMIT_EXCEEDED)
				this.report.itemLimit = true;
			else if (reason == McpStreamTerminationReason
					.SIMULATOR_CAPTURE_BYTE_LIMIT_EXCEEDED)
				this.report.byteLimit = true;
			else
				throw new AssertionError("Unexpected capture limit: " + reason);
		}

		@NonNull
		private ExpectedOffer expectedOffer(int byteCount, boolean duplicate) {
			if (this.channelTerminal)
				return new ExpectedOffer(McpOutboundChannel.OfferResult.CLOSED, null);
			if (duplicate)
				return new ExpectedOffer(McpOutboundChannel.OfferResult.ACCEPTED, null);
			if (this.pendingItems.size() >= this.itemCapacity)
				return new ExpectedOffer(McpOutboundChannel.OfferResult.CLOSED,
						McpStreamTerminationReason
								.SIMULATOR_CAPTURE_ITEM_LIMIT_EXCEEDED);
			if ((long) this.capturedBytes + byteCount > this.byteCapacity)
				return new ExpectedOffer(McpOutboundChannel.OfferResult.CLOSED,
						McpStreamTerminationReason
								.SIMULATOR_CAPTURE_BYTE_LIMIT_EXCEEDED);
			return new ExpectedOffer(McpOutboundChannel.OfferResult.ACCEPTED, null);
		}

		private void assertBounds() {
			Assertions.assertTrue(this.pendingItems.size() <= this.itemCapacity);
			Assertions.assertTrue(this.capturedBytes <= this.byteCapacity);
			Assertions.assertTrue(this.capturedBytes >= this.lastAssertedCapturedBytes,
					"Consuming captured items must never refund cumulative bytes.");
			this.lastAssertedCapturedBytes = this.capturedBytes;
			Assertions.assertTrue(this.completionCallbacks.get() <= 1);
			if (this.listener != null)
				Assertions.assertTrue(this.listener.terminationCount() <= 1);
		}

		private void finishAndVerify() throws InterruptedException {
			if (!this.requestFinished) {
				if (this.mode == Mode.NONE) {
					cancel(false);
				} else if (this.mode == Mode.JSON) {
					finish(this.terminalReason == McpStreamTerminationReason.COMPLETED
							? McpRequestOutcome.COMPLETE : McpRequestOutcome.CANCELED);
				} else {
					if (!this.channelTerminal)
						fail();
					finish(this.terminalReason == McpStreamTerminationReason.COMPLETED
							? McpRequestOutcome.COMPLETE
							: McpRequestOutcome.INTERNAL_ERROR);
				}
			}

			McpSimulationCompletion completion = this.runtime.awaitCompletion(
					Duration.ZERO).orElseThrow();
			Assertions.assertEquals(this.terminalReason, completion.getReason());
			if (this.terminalThrowable != null) {
				Assertions.assertEquals(1, completion.getThrowables().size());
				Assertions.assertSame(this.terminalThrowable,
						completion.getThrowables().get(0));
			}
			Assertions.assertThrows(UnsupportedOperationException.class,
					() -> completion.getThrowables().add(
							new AssertionError("mutation")));

			while (!this.pendingItems.isEmpty())
				poll();
			Assertions.assertTrue(this.runtime.nextStreamItem(
					Duration.ZERO).isEmpty());
			if (this.mode == Mode.SSE) {
				McpRequestSseStream.Frame late = jsonFrame(new byte[] {'z'});
				Assertions.assertEquals(McpOutboundChannel.OfferResult.CLOSED,
						this.runtime.offer(late));
				Assertions.assertFalse(this.runtime.complete(late));
				Assertions.assertEquals(1,
						requireNonNull(this.listener).terminationCount());
				Assertions.assertEquals(this.terminalReason,
						this.listener.observationReason());
			}

			McpSimulationCompletion retained = this.runtime.awaitCompletion(
					Duration.ZERO).orElseThrow();
			this.runtime.didFinishRequest(McpRequestOutcome.WRITE_FAILED,
					List.of(new AssertionError("late")));
			this.runtime.cancel();
			this.runtime.close();
			Assertions.assertSame(retained, this.runtime.awaitCompletion(
					Duration.ZERO).orElseThrow());
			Assertions.assertEquals(1, this.completionCallbacks.get());
			this.report.stableCompletion = true;

			if (this.terminalItemAccepted) {
				Assertions.assertTrue(this.report.terminalItemObserved);
				assertPublicNotification(
						completion.getTerminalMessage().orElseThrow());
				this.report.terminalDuplicated = true;
			} else {
				Assertions.assertTrue(completion.getTerminalMessage().isEmpty());
			}
			assertBounds();
		}

		private static McpRequestSseStream.Frame jsonFrame(
				byte @NonNull [] bytes) {
			return new McpRequestSseStream.Frame(
					McpRequestSseStream.FrameType.JSON_MESSAGE,
					new McpJsonRpcMessage.Notification("notifications/fuzz",
							Optional.empty(), McpJsonObject.empty()), bytes);
		}

		private static void assertBodyCopy(byte @NonNull [] expected,
				@NonNull McpSimulationResponse response) {
			byte[] first = response.getBody().orElseThrow();
			Assertions.assertArrayEquals(expected, first);
			first[0] ^= 1;
			Assertions.assertArrayEquals(expected,
					response.getBody().orElseThrow());
		}

		private static void assertItem(@NonNull ModelItem expected,
				@NonNull McpSimulationStreamItem actual) {
			Assertions.assertEquals(expected.type(), actual.getType());
			byte[] first = actual.getEncodedBytes();
			Assertions.assertArrayEquals(expected.encodedBytes(), first);
			first[0] ^= 1;
			Assertions.assertArrayEquals(expected.encodedBytes(),
					actual.getEncodedBytes());
			if (expected.type() == McpSimulationStreamItemType.JSON_MESSAGE) {
				Assertions.assertTrue(actual.getComment().isEmpty());
				assertPublicNotification(actual.getMessage().orElseThrow());
			} else {
				Assertions.assertTrue(actual.getMessage().isEmpty());
				Assertions.assertEquals(Optional.of("keepalive"),
						actual.getComment());
			}
		}

		private static void assertPublicNotification(
				com.soklet.McpJsonValue value) {
			com.soklet.McpJsonObject message = Assertions.assertInstanceOf(
					com.soklet.McpJsonObject.class, value);
			com.soklet.McpJsonValue method = message.getMembers().get("method");
			Assertions.assertEquals("notifications/fuzz",
					Assertions.assertInstanceOf(
							com.soklet.McpJsonString.class, method).value());
		}
	}

	private record ModelItem(@NonNull McpSimulationStreamItemType type,
			byte @NonNull [] encodedBytes, @Nullable String coalescingKey,
			boolean terminal) {
		private ModelItem {
			requireNonNull(type);
			encodedBytes = Arrays.copyOf(requireNonNull(encodedBytes),
					encodedBytes.length);
		}

		@Override
		public byte @NonNull [] encodedBytes() {
			return Arrays.copyOf(this.encodedBytes, this.encodedBytes.length);
		}
	}

	private record ExpectedOffer(
			McpOutboundChannel.@NonNull OfferResult result,
			@Nullable McpStreamTerminationReason reason) {
		private ExpectedOffer {
			requireNonNull(result);
		}
	}

	private static final class CaptureListener
			implements McpRequestSseStream.Listener {
		private int terminationCount;
		private @Nullable McpStreamTerminationReason observationReason;

		@Override
		public void didTerminate(@NonNull StreamTerminationReason reason,
				@Nullable McpStreamTerminationReason observationReason,
				@Nullable Throwable cause) {
			requireNonNull(reason);
			this.terminationCount++;
			Assertions.assertNotNull(observationReason);
			this.observationReason = observationReason;
			Assertions.assertNull(cause);
		}

		private int terminationCount() {
			return this.terminationCount;
		}

		private @Nullable McpStreamTerminationReason observationReason() {
			return this.observationReason;
		}
	}

	private record ReplayReport(boolean jsonCompletion, boolean sseTerminal,
			boolean coalescing, boolean itemLimit, boolean byteLimit,
			boolean cancelBeforeResponse, boolean cancelAfterResponse,
			boolean duplicateTerminal, boolean terminalDuplicated,
			boolean responseDefensiveCopy, boolean stableCompletion,
			int maximumAcceptedBytes) {
		private static final class Mutable {
			private boolean jsonCompletion;
			private boolean sseTerminal;
			private boolean coalescing;
			private boolean itemLimit;
			private boolean byteLimit;
			private boolean cancelBeforeResponse;
			private boolean cancelAfterResponse;
			private boolean duplicateTerminal;
			private boolean terminalDuplicated;
			private boolean terminalItemObserved;
			private boolean responseDefensiveCopy;
			private boolean stableCompletion;
			private int maximumAcceptedBytes;

			@NonNull
			private ReplayReport freeze() {
				return new ReplayReport(this.jsonCompletion, this.sseTerminal,
						this.coalescing, this.itemLimit, this.byteLimit,
						this.cancelBeforeResponse, this.cancelAfterResponse,
						this.duplicateTerminal, this.terminalDuplicated,
						this.responseDefensiveCopy, this.stableCompletion,
						this.maximumAcceptedBytes);
			}
		}
	}
}
