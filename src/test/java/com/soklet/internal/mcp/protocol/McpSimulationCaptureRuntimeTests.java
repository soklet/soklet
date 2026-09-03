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

import com.soklet.McpRequestOutcome;
import com.soklet.McpSimulationBodyType;
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
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.NotThreadSafe;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@NotThreadSafe
@Timeout(60)
public class McpSimulationCaptureRuntimeTests {
	@NonNull
	private static final McpJsonRpcEnvelopeCodec ENVELOPE_CODEC =
			new McpJsonRpcEnvelopeCodec(
					new McpJsonCodec(McpJsonLimits.productionDefaults()));
	private static final byte @NonNull [] MESSAGE_PREFIX =
			"data: ".getBytes(StandardCharsets.US_ASCII);
	private static final byte @NonNull [] MESSAGE_SUFFIX =
			"\n\n".getBytes(StandardCharsets.US_ASCII);

	@Test
	public void pendingItemCapacityRefundsOnlyDequeuedSlots() throws Exception {
		SseCapture refunded = openSse(options(1, 1_024));
		McpRequestSseStream.Frame first = frame("first");
		McpRequestSseStream.Frame second = frame("second");

		Assertions.assertEquals(McpOutboundChannel.OfferResult.ACCEPTED,
				refunded.runtime().offer(first));
		assertEncodedBytes(first, refunded.runtime().awaitStreamItem(
				Duration.ZERO).orElseThrow());
		Assertions.assertEquals(McpOutboundChannel.OfferResult.ACCEPTED,
				refunded.runtime().offer(second),
				"Removing the first item must release its pending queue slot.");
		assertEncodedBytes(second, refunded.runtime().awaitStreamItem(
				Duration.ZERO).orElseThrow());
		Assertions.assertTrue(refunded.runtime().fail(
				StreamTerminationReason.CLIENT_DISCONNECTED, null));
		refunded.runtime().didFinishRequest(
				McpRequestOutcome.CLIENT_DISCONNECTED, List.of());

		McpRequestSseStream.Frame retained = frame("retained");
		McpRequestSseStream.Frame excluded = frame("excluded");
		SseCapture precedence = openSse(options(1,
				retained.encodedBytes().length));
		Assertions.assertEquals(McpOutboundChannel.OfferResult.ACCEPTED,
				precedence.runtime().offer(retained));
		Assertions.assertEquals(McpOutboundChannel.OfferResult.CLOSED,
				precedence.runtime().offer(excluded));
		precedence.listener().assertTermination(
				StreamTerminationReason.SIMULATOR_LIMIT_EXCEEDED,
				McpStreamTerminationReason.SIMULATOR_CAPTURE_ITEM_LIMIT_EXCEEDED);

		precedence.runtime().didFinishRequest(McpRequestOutcome.CANCELED, List.of());
		Assertions.assertEquals(
				McpStreamTerminationReason.SIMULATOR_CAPTURE_ITEM_LIMIT_EXCEEDED,
				completion(precedence).getReason(),
				"The pending-item check must win when both limits are exceeded.");
		assertEncodedBytes(retained, precedence.runtime().awaitStreamItem(
				Duration.ZERO).orElseThrow());
		Assertions.assertTrue(precedence.runtime().awaitStreamItem(
				Duration.ZERO).isEmpty(), "The offending frame must not be retained.");

		SseCapture staged = newSseCapture(options(1, 1_024));
		Object coalescingKey = new Object();
		McpRequestSseStream.Frame coalesced = frame("coalesced");
		Assertions.assertEquals(McpOutboundChannel.OfferResult.ACCEPTED,
				staged.runtime().offerCoalescing(coalesced, coalescingKey));
		Assertions.assertEquals(McpOutboundChannel.OfferResult.ACCEPTED,
				staged.runtime().offerCoalescing(coalesced, coalescingKey));
		staged.runtime().acceptResponse(staged.runtime().response(List.of()));
		assertEncodedBytes(coalesced, staged.runtime().awaitStreamItem(
				Duration.ZERO).orElseThrow());
		Assertions.assertTrue(staged.runtime().awaitStreamItem(
				Duration.ZERO).isEmpty(),
				"The staged duplicate must remain coalesced during transfer.");
		Assertions.assertEquals(McpOutboundChannel.OfferResult.ACCEPTED,
				staged.runtime().offerCoalescing(coalesced, coalescingKey),
				"Dequeuing must release the staged coalescing key.");
		assertEncodedBytes(coalesced, staged.runtime().awaitStreamItem(
				Duration.ZERO).orElseThrow());
	}

	@Test
	public void cumulativeByteBudgetNeverRefundsAndAllowsExactEquality()
			throws Exception {
		McpRequestSseStream.Frame first = frame("first");
		McpRequestSseStream.Frame boundary = frame("boundary");
		McpRequestSseStream.Frame excluded = frame("excluded");
		SseCapture capture = openSse(options(2,
				first.encodedBytes().length + boundary.encodedBytes().length));

		Assertions.assertEquals(McpOutboundChannel.OfferResult.ACCEPTED,
				capture.runtime().offer(first));
		assertEncodedBytes(first, capture.runtime().awaitStreamItem(
				Duration.ZERO).orElseThrow());
		Assertions.assertEquals(McpOutboundChannel.OfferResult.ACCEPTED,
				capture.runtime().offer(boundary),
				"A cumulative byte count equal to the limit must be accepted.");
		Assertions.assertEquals(McpOutboundChannel.OfferResult.CLOSED,
				capture.runtime().offer(excluded),
				"Dequeuing must not refund cumulative captured bytes.");
		capture.listener().assertTermination(
				StreamTerminationReason.SIMULATOR_LIMIT_EXCEEDED,
				McpStreamTerminationReason.SIMULATOR_CAPTURE_BYTE_LIMIT_EXCEEDED);

		capture.runtime().didFinishRequest(McpRequestOutcome.CANCELED, List.of());
		Assertions.assertEquals(
				McpStreamTerminationReason.SIMULATOR_CAPTURE_BYTE_LIMIT_EXCEEDED,
				completion(capture).getReason());
		assertEncodedBytes(boundary, capture.runtime().awaitStreamItem(
				Duration.ZERO).orElseThrow());
		Assertions.assertTrue(capture.runtime().awaitStreamItem(
				Duration.ZERO).isEmpty(), "The byte-overflowing frame must be excluded.");
	}

	@Test
	public void preResponseByteLimitPublishesHeadBeforeExactCompletion()
			throws Exception {
		SseCapture capture = newSseCapture(options(2, 1));
		McpRequestSseStream.Frame excluded = frame("pre-response");

		Assertions.assertEquals(McpOutboundChannel.OfferResult.ACCEPTED,
				capture.runtime().offer(excluded),
				"A pre-response producer must be allowed to commit the SSE head.");
		Assertions.assertTrue(capture.runtime().awaitResponse(
				Duration.ZERO).isEmpty());
		Assertions.assertTrue(capture.runtime().awaitStreamItem(
				Duration.ZERO).isEmpty());
		capture.runtime().acceptResponse(capture.runtime().response(List.of(
				new Header("X-Pre-Response", "published"))));

		McpSimulationResponse response = capture.runtime().awaitResponse(
				Duration.ZERO).orElseThrow();
		Assertions.assertEquals(McpSimulationBodyType.SSE,
				response.getBodyType());
		Assertions.assertEquals(Set.of("published"),
				response.getHeaders().get("X-Pre-Response"));
		capture.listener().assertTermination(
				StreamTerminationReason.SIMULATOR_LIMIT_EXCEEDED,
				McpStreamTerminationReason.SIMULATOR_CAPTURE_BYTE_LIMIT_EXCEEDED);
		capture.runtime().didFinishRequest(McpRequestOutcome.CANCELED, List.of());
		Assertions.assertEquals(
				McpStreamTerminationReason.SIMULATOR_CAPTURE_BYTE_LIMIT_EXCEEDED,
				completion(capture).getReason());
		Assertions.assertTrue(capture.runtime().awaitStreamItem(
				Duration.ZERO).isEmpty(), "The offending staged frame must stay absent.");
	}

	@Test
	public void terminalFrameIsCountedOnceAndRepeatedInCompletion()
			throws Exception {
		McpJsonRpcMessage.Notification progress = notification(
				"notifications/progress");
		McpJsonRpcMessage.Notification terminal = notification(
				"notifications/terminal");
		byte[] progressBytes = encodedFrame(progress);
		byte[] terminalBytes = encodedFrame(terminal);
		SseCapture capture = newSseCapture(options(2,
				progressBytes.length + terminalBytes.length));
		McpRequestSseStream stream = new McpRequestSseStream(
				ENVELOPE_CODEC, capture.runtime());
		capture.runtime().acceptResponse(stream.response(
				List.of(new Header("X-Simulation", "terminal"))));

		Assertions.assertEquals(McpOutboundChannel.OfferResult.ACCEPTED,
				stream.offerMessage(progress));
		Assertions.assertTrue(stream.completeMessage(terminal),
				"The terminal frame must fit when the exact cumulative limit is met.");
		capture.listener().assertTermination(StreamTerminationReason.COMPLETED,
				McpStreamTerminationReason.COMPLETED);
		capture.runtime().didFinishRequest(McpRequestOutcome.COMPLETE, List.of());

		McpSimulationStreamItem progressItem = capture.runtime().awaitStreamItem(
				Duration.ZERO).orElseThrow();
		McpSimulationStreamItem terminalItem = capture.runtime().awaitStreamItem(
				Duration.ZERO).orElseThrow();
		Assertions.assertArrayEquals(progressBytes, progressItem.getEncodedBytes());
		Assertions.assertArrayEquals(terminalBytes, terminalItem.getEncodedBytes());
		Assertions.assertEquals(McpSimulationStreamItemType.JSON_MESSAGE,
				terminalItem.getType());

		McpSimulationCompletion completion = completion(capture);
		Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
				completion.getReason());
		Assertions.assertEquals(
				publicObject(terminalItem.getMessage().orElseThrow()).getMembers(),
				publicObject(completion.getTerminalMessage().orElseThrow()).getMembers(),
				"The already-counted terminal JSON must be repeated in completion.");
		Assertions.assertEquals(1, capture.completionCallbacks().get());
		capture.runtime().didFinishRequest(McpRequestOutcome.INTERNAL_ERROR,
				List.of(new AssertionError("late")));
		Assertions.assertSame(completion, completion(capture));
		Assertions.assertEquals(1, capture.completionCallbacks().get());
	}

	@Test
	public void jsonBodyBudgetIsInclusiveAndOverflowRetainsResponseHead()
			throws Exception {
		byte[] body = "{\"answer\":42}".getBytes(StandardCharsets.UTF_8);
		McpSimulationRuntime exact = new McpSimulationRuntime(
				options(1, body.length), () -> {});
		exact.acceptResponse(new MicrohttpResponse(201, "Created", List.of(
				new Header("X-Mode", "exact"),
				new Header("x-mode", "second"),
				new Header("X-Order", "last")), body));
		body[0] ^= 1;
		McpSimulationResponse exactResponse = exact.awaitResponse(
				Duration.ZERO).orElseThrow();
		Assertions.assertEquals(201, exactResponse.getStatusCode());
		Assertions.assertEquals(McpSimulationBodyType.JSON,
				exactResponse.getBodyType());
		byte[] expectedBody = "{\"answer\":42}".getBytes(StandardCharsets.UTF_8);
		Assertions.assertArrayEquals(expectedBody,
				exactResponse.getBody().orElseThrow());
		Assertions.assertEquals(List.of("X-Mode", "X-Order"),
				new ArrayList<>(exactResponse.getHeaders().keySet()));
		Assertions.assertEquals(List.of("exact", "second"),
				new ArrayList<>(exactResponse.getHeaders().get("X-Mode")));
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> exactResponse.getHeaders().put("X-Mutation", Set.of("x")));
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> exactResponse.getHeaders().get("X-Mode").add("mutation"));
		byte[] returnedBody = exactResponse.getBody().orElseThrow();
		returnedBody[0] ^= 1;
		Assertions.assertArrayEquals(expectedBody,
				exactResponse.getBody().orElseThrow());
		Assertions.assertEquals(Optional.of(McpStreamTerminationReason.COMPLETED),
				exact.nonStreamingReason());
		exact.didFinishRequest(McpRequestOutcome.COMPLETE, List.of());
		Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
				exact.awaitCompletion(Duration.ZERO).orElseThrow().getReason());

		AtomicInteger overflowCallback = new AtomicInteger();
		McpSimulationRuntime overflow = new McpSimulationRuntime(
				options(1, expectedBody.length - 1),
				overflowCallback::incrementAndGet);
		overflow.acceptResponse(new MicrohttpResponse(207, "Multi-Status",
				List.of(new Header("X-Mode", "overflow")), expectedBody));
		McpSimulationResponse overflowResponse = overflow.awaitResponse(
				Duration.ZERO).orElseThrow();
		Assertions.assertEquals(207, overflowResponse.getStatusCode());
		Assertions.assertEquals(Map.of("X-Mode", Set.of("overflow")),
				overflowResponse.getHeaders());
		Assertions.assertEquals(McpSimulationBodyType.JSON,
				overflowResponse.getBodyType());
		Assertions.assertTrue(overflowResponse.getBody().isEmpty(),
				"JSON overflow must retain the head but omit the body bytes.");
		Assertions.assertEquals(Optional.of(
				McpStreamTerminationReason.SIMULATOR_CAPTURE_BYTE_LIMIT_EXCEEDED),
				overflow.nonStreamingReason());
		overflow.didFinishRequest(McpRequestOutcome.CANCELED, List.of());
		Assertions.assertEquals(
				McpStreamTerminationReason.SIMULATOR_CAPTURE_BYTE_LIMIT_EXCEEDED,
				overflow.awaitCompletion(Duration.ZERO).orElseThrow().getReason());
		Assertions.assertEquals(1, overflowCallback.get());

		McpSimulationRuntime empty = new McpSimulationRuntime(
				options(1, 1), () -> {});
		empty.acceptResponse(new MicrohttpResponse(204, "No Content",
				List.of(), new byte[0]));
		McpSimulationResponse emptyResponse = empty.awaitResponse(
				Duration.ZERO).orElseThrow();
		Assertions.assertEquals(McpSimulationBodyType.EMPTY,
				emptyResponse.getBodyType());
		Assertions.assertArrayEquals(new byte[0],
				emptyResponse.getBody().orElseThrow());
	}

	@Test
	public void closePublishesClientDisconnectedAtEverySimulationBoundary()
			throws Exception {
		SseCapture capture = openSse(options(2, 128));
		AtomicReference<StreamTerminationReason> controllerReason =
				new AtomicReference<>();
		capture.runtime().bindController(reason -> {
			controllerReason.set(reason);
			boolean won = capture.runtime().fail(reason, null);
			capture.runtime().didFinishRequest(
					McpRequestOutcome.CLIENT_DISCONNECTED, List.of());
			return won;
		});

		capture.runtime().close();
		Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED,
				controllerReason.get());
		capture.listener().assertTermination(
				StreamTerminationReason.CLIENT_DISCONNECTED,
				McpStreamTerminationReason.CLIENT_DISCONNECTED);
		Assertions.assertEquals(McpStreamTerminationReason.CLIENT_DISCONNECTED,
				completion(capture).getReason());
		Assertions.assertEquals(1, capture.completionCallbacks().get());

		capture.runtime().close();
		capture.runtime().close();
		Assertions.assertEquals(1, capture.listener().terminationCount());
		Assertions.assertEquals(1, capture.completionCallbacks().get());

		SseCapture beforeResponse = newSseCapture(options(2, 128));
		beforeResponse.runtime().bindController(reason -> {
			boolean won = beforeResponse.runtime().fail(reason, null);
			beforeResponse.runtime().didFinishRequest(
					McpRequestOutcome.CLIENT_DISCONNECTED, List.of());
			return won;
		});
		beforeResponse.runtime().close();
		Assertions.assertTrue(beforeResponse.runtime().awaitResponse(
				Duration.ZERO).isEmpty());
		beforeResponse.listener().assertTermination(
				StreamTerminationReason.CLIENT_DISCONNECTED,
				McpStreamTerminationReason.CLIENT_DISCONNECTED);
		Assertions.assertEquals(McpStreamTerminationReason.CLIENT_DISCONNECTED,
				completion(beforeResponse).getReason());
	}

	@Test
	public void closeAndTerminalRacePublishesOneCoherentFirstWinner()
			throws Exception {
		SseCapture capture = newSseCapture(options(2, 1_024));
		McpRequestSseStream stream = new McpRequestSseStream(
				ENVELOPE_CODEC, capture.runtime());
		capture.runtime().acceptResponse(stream.response(List.of()));
		capture.runtime().bindController(reason -> {
			boolean won = capture.runtime().fail(reason, null);
			if (won)
				capture.runtime().didFinishRequest(
						McpRequestOutcome.CLIENT_DISCONNECTED, List.of());
			return won;
		});
		CyclicBarrier barrier = new CyclicBarrier(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<?> cancellation = executor.submit(() -> {
				awaitBarrier(barrier);
				capture.runtime().close();
			});
			Future<Boolean> terminal = executor.submit(() -> {
				awaitBarrier(barrier);
				return stream.completeMessage(notification("notifications/terminal"));
			});
			cancellation.get(5, TimeUnit.SECONDS);
			boolean terminalWon = terminal.get(5, TimeUnit.SECONDS);
			if (terminalWon)
				capture.runtime().didFinishRequest(McpRequestOutcome.COMPLETE, List.of());

			McpSimulationCompletion completion = completion(capture);
			Assertions.assertEquals(terminalWon
						? McpStreamTerminationReason.COMPLETED
						: McpStreamTerminationReason.CLIENT_DISCONNECTED,
					completion.getReason());
			Assertions.assertEquals(terminalWon,
					completion.getTerminalMessage().isPresent());
			Assertions.assertEquals(1, capture.listener().terminationCount());
			Assertions.assertEquals(1, capture.completionCallbacks().get());
		} finally {
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void nonStreamingResponseWinsWhileCancellationDecisionIsInFlight()
			throws Exception {
		AtomicInteger completionCallbacks = new AtomicInteger();
		McpSimulationRuntime runtime = new McpSimulationRuntime(
				options(1, 1_024), completionCallbacks::incrementAndGet);
		CountDownLatch controllerEntered = new CountDownLatch(1);
		CountDownLatch releaseController = new CountDownLatch(1);
		runtime.bindController(reason -> {
			controllerEntered.countDown();
			awaitLatch(releaseController);
			return false;
		});
		ExecutorService executor = Executors.newSingleThreadExecutor();

		try {
			Future<?> cancellation = executor.submit(() -> runtime.close());
			awaitLatch(controllerEntered);
			byte[] body = "{\"winner\":\"response\"}"
					.getBytes(StandardCharsets.UTF_8);
			runtime.acceptResponse(new MicrohttpResponse(200, "OK", List.of(), body));
			runtime.didFinishRequest(McpRequestOutcome.COMPLETE, List.of());
			releaseController.countDown();
			cancellation.get(5, TimeUnit.SECONDS);

			Assertions.assertArrayEquals(body, runtime.awaitResponse(
					Duration.ZERO).orElseThrow().getBody().orElseThrow());
			Assertions.assertEquals(McpStreamTerminationReason.COMPLETED,
					runtime.awaitCompletion(Duration.ZERO).orElseThrow().getReason());
			Assertions.assertEquals(1, completionCallbacks.get());
		} finally {
			releaseController.countDown();
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void keepAliveAndDeadlineReplayUseSuppliedMonotonicTime()
			throws Exception {
		byte[] keepAlive = ": keepalive\n\n".getBytes(StandardCharsets.US_ASCII);
		SseCapture capture = newSseCapture(options(1, keepAlive.length));
		McpRequestSseStream stream = new McpRequestSseStream(
				ENVELOPE_CODEC, capture.runtime());
		capture.runtime().acceptResponse(stream.response(List.of()));

		Assertions.assertEquals(McpOutboundChannel.OfferResult.ACCEPTED,
				stream.offerKeepAlive());
		McpSimulationStreamItem item = capture.runtime().awaitStreamItem(
				Duration.ZERO).orElseThrow();
		Assertions.assertEquals(McpSimulationStreamItemType.KEEP_ALIVE_COMMENT,
				item.getType());
		Assertions.assertEquals(Optional.of("keepalive"), item.getComment());
		Assertions.assertTrue(item.getMessage().isEmpty());
		Assertions.assertArrayEquals(keepAlive, item.getEncodedBytes());

		long deadlineNanos = 5_000L;
		Assertions.assertFalse(stream.failIfDeadlineExpired(
				deadlineNanos - 1L, deadlineNanos,
				StreamTerminationReason.RESPONSE_TIMEOUT, null));
		Assertions.assertEquals(0, capture.listener().terminationCount());
		Assertions.assertTrue(stream.failIfDeadlineExpired(
				deadlineNanos, deadlineNanos,
				StreamTerminationReason.RESPONSE_TIMEOUT, null),
				"Equality at the supplied monotonic deadline must expire.");
		capture.listener().assertTermination(
				StreamTerminationReason.RESPONSE_TIMEOUT,
				McpStreamTerminationReason.DEADLINE_EXCEEDED);
		capture.runtime().didFinishRequest(
				McpRequestOutcome.DEADLINE_EXCEEDED, List.of());
		Assertions.assertEquals(McpStreamTerminationReason.DEADLINE_EXCEEDED,
				completion(capture).getReason());
		Assertions.assertFalse(stream.failIfDeadlineExpired(
				deadlineNanos + 1L, deadlineNanos,
				StreamTerminationReason.RESPONSE_TIMEOUT, null));
		Assertions.assertEquals(1, capture.listener().terminationCount());
	}

	@Test
	public void offNetworkCaptureNeverArmsWriteIdleAndCaptureLimitsRemainFirstWinner()
			throws Exception {
		AtomicInteger writeIdleReservations = new AtomicInteger();
		McpRequestSseStream.setTestHooks(new McpRequestSseStream.TestHooks() {
			@Override
			public void beforeTerminalReservation() {
				// This proof exercises capture overflow, not normal completion.
			}

			@Override
			public void beforeWriteIdleFailureAttempt(
					@NonNull Runnable competingTermination) {
				writeIdleReservations.incrementAndGet();
			}
		});

		try {
			List<McpRequestSseStream.Frame> itemRetained = List.of(
					frame("item-retained-first"),
					frame("item-retained-second"));
			assertNonDrainingCaptureLimitFirstWinner(
					options(itemRetained.size(), Integer.MAX_VALUE),
					itemRetained, frame("item-offender"),
					McpStreamTerminationReason
							.SIMULATOR_CAPTURE_ITEM_LIMIT_EXCEEDED,
					writeIdleReservations);

			List<McpRequestSseStream.Frame> byteRetained = List.of(
					frame("byte-retained-first"),
					frame("byte-retained-boundary"));
			int exactByteLimit = byteRetained.stream()
					.mapToInt(frame -> frame.encodedBytes().length).sum();
			assertNonDrainingCaptureLimitFirstWinner(
					options(byteRetained.size() + 1, exactByteLimit),
					byteRetained, frame("byte-offender"),
					McpStreamTerminationReason
							.SIMULATOR_CAPTURE_BYTE_LIMIT_EXCEEDED,
					writeIdleReservations);
			Assertions.assertEquals(0, writeIdleReservations.get(),
					"Off-network capture must never attempt the live write-idle terminal reservation.");
		} finally {
			McpRequestSseStream.setTestHooks(null);
		}
	}

	private static void assertNonDrainingCaptureLimitFirstWinner(
			@NonNull McpSimulationOptions options,
			@NonNull List<McpRequestSseStream.@NonNull Frame> retainedFrames,
			McpRequestSseStream.@NonNull Frame offender,
			@NonNull McpStreamTerminationReason expectedReason,
			@NonNull AtomicInteger writeIdleReservations) throws Exception {
		SseCapture capture = newSseCapture(options);
		McpRequestSseStream stream = new McpRequestSseStream(
				ENVELOPE_CODEC, capture.runtime());
		capture.runtime().acceptResponse(stream.response(List.of()));
		long writeIdleTimeoutNanos = 1_000L;

		Assertions.assertEquals(Long.MAX_VALUE,
				capture.runtime().responseWriteIdleDeadlineNanos(
						writeIdleTimeoutNanos));
		Assertions.assertTrue(stream.snapshot().isEmpty(),
				"Simulation capture must not expose a live outbound-channel reservation.");
		Assertions.assertFalse(stream.failIfWriteIdleExpired(
				writeIdleTimeoutNanos, writeIdleTimeoutNanos,
				StreamTerminationReason.RESPONSE_IDLE_TIMEOUT, null),
				"The off-network channel must not expire write idle at equality.");
		Assertions.assertFalse(stream.failIfWriteIdleExpired(
				writeIdleTimeoutNanos + 1L, writeIdleTimeoutNanos,
				StreamTerminationReason.RESPONSE_IDLE_TIMEOUT, null),
				"The off-network channel must not expire write idle after equality.");
		Assertions.assertEquals(0, writeIdleReservations.get());
		Assertions.assertEquals(0, capture.listener().terminationCount());
		Assertions.assertEquals(0, capture.completionCallbacks().get());
		Assertions.assertTrue(capture.runtime().awaitCompletion(
				Duration.ZERO).isEmpty());

		for (McpRequestSseStream.Frame retainedFrame : retainedFrames)
			Assertions.assertEquals(McpOutboundChannel.OfferResult.ACCEPTED,
					capture.runtime().offer(retainedFrame));
		Assertions.assertEquals(McpOutboundChannel.OfferResult.CLOSED,
				capture.runtime().offer(offender));
		capture.listener().assertTermination(
				StreamTerminationReason.SIMULATOR_LIMIT_EXCEEDED,
				expectedReason);

		Assertions.assertFalse(stream.failIfWriteIdleExpired(
				writeIdleTimeoutNanos + 2L, writeIdleTimeoutNanos,
				StreamTerminationReason.RESPONSE_IDLE_TIMEOUT, null));
		Assertions.assertFalse(stream.failIfDeadlineExpired(2_000L, 2_000L,
				StreamTerminationReason.RESPONSE_TIMEOUT, null));
		Assertions.assertFalse(stream.fail(
				StreamTerminationReason.CLIENT_DISCONNECTED, null));
		capture.runtime().close();
		capture.runtime().didFinishRequest(McpRequestOutcome.CANCELED, List.of());

		McpSimulationCompletion completion = completion(capture);
		Assertions.assertEquals(expectedReason, completion.getReason());
		Assertions.assertTrue(completion.getTerminalMessage().isEmpty());
		Assertions.assertTrue(completion.getThrowables().isEmpty());
		Assertions.assertEquals(1, capture.listener().terminationCount());
		Assertions.assertEquals(1, capture.completionCallbacks().get());
		Assertions.assertEquals(0, writeIdleReservations.get());

		capture.runtime().close();
		capture.runtime().didFinishRequest(McpRequestOutcome.DEADLINE_EXCEEDED,
				List.of(new AssertionError("late terminal must be ignored")));
		Assertions.assertSame(completion, completion(capture));
		Assertions.assertEquals(1, capture.listener().terminationCount());
		Assertions.assertEquals(1, capture.completionCallbacks().get());
		Assertions.assertEquals(0, writeIdleReservations.get());

		for (McpRequestSseStream.Frame retainedFrame : retainedFrames)
			assertEncodedBytes(retainedFrame, capture.runtime().awaitStreamItem(
					Duration.ZERO).orElseThrow());
		Assertions.assertTrue(capture.runtime().awaitStreamItem(
				Duration.ZERO).isEmpty(),
				"The first frame beyond the exact capture limit must be omitted.");
	}

	@NonNull
	private static McpSimulationOptions options(int itemCapacity,
			int byteCapacity) {
		return McpSimulationOptions.builder()
				.streamItemQueueCapacity(itemCapacity)
				.maximumCapturedSizeInBytes(byteCapacity)
				.build();
	}

	@NonNull
	private static SseCapture openSse(@NonNull McpSimulationOptions options)
			throws Exception {
		SseCapture capture = newSseCapture(options);
		capture.runtime().acceptResponse(capture.runtime().response(List.of()));
		Assertions.assertEquals(McpSimulationBodyType.SSE,
				capture.runtime().awaitResponse(Duration.ZERO).orElseThrow()
						.getBodyType());
		return capture;
	}

	@NonNull
	private static SseCapture newSseCapture(
			@NonNull McpSimulationOptions options) {
		AtomicInteger completionCallbacks = new AtomicInteger();
		CaptureListener listener = new CaptureListener();
		McpSimulationRuntime runtime = new McpSimulationRuntime(options,
				completionCallbacks::incrementAndGet);
		runtime.openChannel(listener);
		return new SseCapture(runtime, listener, completionCallbacks);
	}

	@NonNull
	private static McpSimulationCompletion completion(
			@NonNull SseCapture capture) throws Exception {
		return capture.runtime().awaitCompletion(Duration.ZERO).orElseThrow();
	}

	private static McpRequestSseStream.Frame frame(
			@NonNull String method) {
		McpJsonRpcMessage.Notification message = notification(
				"notifications/" + method);
		return new McpRequestSseStream.Frame(
				McpRequestSseStream.FrameType.JSON_MESSAGE,
				message, encodedFrame(message));
	}

	private static McpJsonRpcMessage.Notification notification(
			@NonNull String method) {
		return new McpJsonRpcMessage.Notification(method, Optional.empty(),
				McpJsonObject.empty());
	}

	private static byte @NonNull [] encodedFrame(
			McpJsonRpcMessage.Notification message) {
		byte[] json = ENVELOPE_CODEC.encode(message);
		byte[] encoded = Arrays.copyOf(MESSAGE_PREFIX,
				MESSAGE_PREFIX.length + json.length + MESSAGE_SUFFIX.length);
		System.arraycopy(json, 0, encoded, MESSAGE_PREFIX.length, json.length);
		System.arraycopy(MESSAGE_SUFFIX, 0, encoded,
				MESSAGE_PREFIX.length + json.length, MESSAGE_SUFFIX.length);
		return encoded;
	}

	private static void assertEncodedBytes(
			McpRequestSseStream.Frame expected,
			@NonNull McpSimulationStreamItem actual) {
		Assertions.assertArrayEquals(expected.encodedBytes(),
				actual.getEncodedBytes());
	}

	private static void awaitBarrier(@NonNull CyclicBarrier barrier) {
		try {
			barrier.await(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	private static void awaitLatch(@NonNull CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS))
				throw new AssertionError("Timed out awaiting simulator race latch.");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private static com.soklet.McpJsonObject publicObject(
			com.soklet.McpJsonValue value) {
		return Assertions.assertInstanceOf(com.soklet.McpJsonObject.class, value);
	}

	private record SseCapture(@NonNull McpSimulationRuntime runtime,
			@NonNull CaptureListener listener,
			@NonNull AtomicInteger completionCallbacks) {
	}

	private static final class CaptureListener
			implements McpRequestSseStream.Listener {
		private int terminationCount;
		private @Nullable StreamTerminationReason reason;
		private @Nullable McpStreamTerminationReason observationReason;
		private @Nullable Throwable cause;

		@Override
		public void didTerminate(@NonNull StreamTerminationReason reason,
				@Nullable McpStreamTerminationReason observationReason,
				@Nullable Throwable cause) {
			this.terminationCount++;
			this.reason = reason;
			this.observationReason = observationReason;
			this.cause = cause;
		}

		private void assertTermination(@NonNull StreamTerminationReason reason,
				@NonNull McpStreamTerminationReason observationReason) {
			Assertions.assertEquals(1, this.terminationCount);
			Assertions.assertEquals(reason, this.reason);
			Assertions.assertEquals(observationReason, this.observationReason);
			Assertions.assertNull(this.cause);
		}

		private int terminationCount() {
			return this.terminationCount;
		}
	}
}
