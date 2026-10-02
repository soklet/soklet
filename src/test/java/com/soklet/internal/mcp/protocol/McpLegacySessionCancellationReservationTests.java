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

import com.soklet.StreamTerminationReason;
import com.soklet.CorsAuthorizer;
import com.soklet.McpRequestOutcome;
import com.soklet.McpSessionConfig;
import com.soklet.internal.microhttp.Header;
import com.soklet.internal.microhttp.MicrohttpRequest;
import com.soklet.internal.microhttp.MicrohttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class McpLegacySessionCancellationReservationTests {
	@Test
	void reserved_response_wins_before_the_transport_writer_can_enter_request_control() throws Exception {
		for (McpProtocolProfile profile : List.of(Mcp2025ProtocolProfile.JUNE_18, Mcp2025ProtocolProfile.NOVEMBER_25)) {
			McpApplicationExecution execution = execution();
			MicrohttpRequest transport = transport();
			CountDownLatch writerEntered = new CountDownLatch(1);
			CountDownLatch releaseWriter = new CountDownLatch(1);
			AtomicReference<McpApplicationInvocation> invocation = new AtomicReference<>();
			AtomicInteger responses = new AtomicInteger();
			AtomicInteger cleanups = new AtomicInteger();
			try {
				execution.start();
				execution.dispatch(transport, request(profile), profile, identity(), call -> {
					invocation.set(call);
					return McpWireResult.complete(McpJsonObject.empty());
				}, System.nanoTime() + TimeUnit.SECONDS.toNanos(20), response -> {
					writerEntered.countDown();
					awaitUninterruptibly(releaseWriter);
					responses.incrementAndGet();
					return true;
				}, cleanups::incrementAndGet);
				assertTrue(writerEntered.await(5, TimeUnit.SECONDS));
				for (StreamTerminationReason reason : List.of(StreamTerminationReason.CLIENT_CANCELED,
						StreamTerminationReason.RESPONSE_TIMEOUT)) {
					McpApplicationCancellationReservation reservation = execution.tryReserveCancellation(transport, reason);
					assertTrue(reservation.registered());
					assertFalse(reservation.won(), "A router-reserved response must survive before its writer obtains the control lock.");
					reservation.complete().run();
				}
				assertEquals(Optional.empty(), invocation.get().cancellationReason());
				assertEquals(0, cleanups.get());
				assertEquals(1, execution.snapshot().retainedTransportLeases());
				releaseWriter.countDown();
				await(() -> execution.snapshot().retainedExchanges() == 0);
				assertEquals(1, responses.get());
				assertEquals(1, cleanups.get());
				assertEquals(1, execution.snapshot().terminalResponses());
				assertEquals(0, execution.snapshot().abandonedResponses());
			} finally {
				releaseWriter.countDown();
				execution.stop();
				assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
			}
		}
	}

	@Test
	void winning_cancellation_is_idempotent_and_keeps_the_worker_until_physical_exit() throws Exception {
		for (McpProtocolProfile profile : List.of(Mcp2025ProtocolProfile.JUNE_18, Mcp2025ProtocolProfile.NOVEMBER_25)) {
			McpApplicationExecution execution = execution();
			MicrohttpRequest transport = transport();
			CountDownLatch handlerEntered = new CountDownLatch(1);
			CountDownLatch releaseHandler = new CountDownLatch(1);
			AtomicReference<McpApplicationInvocation> invocation = new AtomicReference<>();
			AtomicInteger responses = new AtomicInteger();
			AtomicInteger cleanups = new AtomicInteger();
			AtomicInteger physicalExits = new AtomicInteger();
			try {
				execution.start();
				execution.dispatch(transport, request(profile), profile, identity(), call -> {
					invocation.set(call); handlerEntered.countDown();
					awaitUninterruptibly(releaseHandler);
					return McpWireResult.complete(McpJsonObject.empty());
				}, System.nanoTime() + TimeUnit.SECONDS.toNanos(20), new McpApplicationResponseWriter() {
					@Override public boolean write(McpApplicationResponse response) { responses.incrementAndGet(); return true; }
					@Override public void didFinishPhysicalWork() { physicalExits.incrementAndGet(); }
				}, cleanups::incrementAndGet);
				assertTrue(handlerEntered.await(5, TimeUnit.SECONDS));
				McpApplicationCancellationReservation cancellation = execution.tryReserveCancellation(transport,
						StreamTerminationReason.CLIENT_CANCELED);
				assertTrue(cancellation.registered()); assertTrue(cancellation.won());
				assertEquals(Optional.of(StreamTerminationReason.CLIENT_CANCELED), invocation.get().cancellationReason());
				McpApplicationCancellationReservation late = execution.tryReserveCancellation(transport,
						StreamTerminationReason.RESPONSE_TIMEOUT);
				assertTrue(late.registered()); assertFalse(late.won());
				late.complete().run();
				cancellation.complete().run(); cancellation.complete().run();
				await(() -> cleanups.get() == 1);
				assertEquals(1, execution.snapshot().activeHandlerSlots());
				assertEquals(1, execution.snapshot().retainedExchanges());
				assertEquals(0, physicalExits.get());
				assertEquals(0, responses.get());
				releaseHandler.countDown();
				await(() -> execution.snapshot().retainedExchanges() == 0);
				assertEquals(0, execution.snapshot().activeHandlerSlots());
				assertEquals(1, physicalExits.get());
				assertEquals(1, cleanups.get());
				assertEquals(0, responses.get());
				assertEquals(1, execution.snapshot().abandonedResponses());
			} finally {
				releaseHandler.countDown(); execution.stop();
				assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
			}
		}
	}

	@Test
	void reserved_callback_sidecar_survives_stop_before_cancellation_effects_are_completed() throws Exception {
		for (McpProtocolProfile profile : List.of(Mcp2025ProtocolProfile.JUNE_18, Mcp2025ProtocolProfile.NOVEMBER_25)) {
			McpApplicationExecution execution = execution();
			MicrohttpRequest transport = transport();
			CountDownLatch handlerEntered = new CountDownLatch(1);
			CountDownLatch releaseHandler = new CountDownLatch(1);
			CountDownLatch callbackEntered = new CountDownLatch(1);
			CountDownLatch releaseCallback = new CountDownLatch(1);
			CountDownLatch handlerReturned = new CountDownLatch(1);
			AtomicInteger callbacks = new AtomicInteger();
			AtomicInteger responses = new AtomicInteger();
			AtomicInteger cleanups = new AtomicInteger();
			AtomicInteger physicalExits = new AtomicInteger();
			McpApplicationCancellationReservation cancellation = null;
			try {
				execution.start();
				execution.dispatch(transport, request(profile), profile, identity(), call -> {
					call.cancelationToken().onCancel(() -> {
						callbacks.incrementAndGet(); callbackEntered.countDown();
						awaitUninterruptibly(releaseCallback);
					});
					handlerEntered.countDown();
					awaitUninterruptibly(releaseHandler);
					handlerReturned.countDown();
					return McpWireResult.complete(McpJsonObject.empty());
				}, System.nanoTime() + TimeUnit.SECONDS.toNanos(20), new McpApplicationResponseWriter() {
					@Override public boolean write(McpApplicationResponse response) { responses.incrementAndGet(); return true; }
					@Override public void didFinishPhysicalWork() { physicalExits.incrementAndGet(); }
				}, cleanups::incrementAndGet);
				assertTrue(handlerEntered.await(5, TimeUnit.SECONDS));
				cancellation = execution.tryReserveCancellation(transport, StreamTerminationReason.CLIENT_CANCELED);
				assertTrue(cancellation.registered()); assertTrue(cancellation.won());
				// The sidecar is already reserved, but callbacks cannot run until the
				// caller finishes offering its cancellation response.
				execution.stop();
				assertEquals(0, callbacks.get());
				assertEquals(0, cleanups.get());
				assertFalse(execution.awaitTermination(Duration.ofMillis(10)));
				cancellation.complete().run();
				assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
				await(() -> cleanups.get() == 1);
				releaseHandler.countDown();
				assertTrue(handlerReturned.await(5, TimeUnit.SECONDS));
				assertEquals(1, execution.snapshot().activeHandlerSlots());
				assertEquals(1, execution.snapshot().retainedExchanges());
				assertEquals(0, physicalExits.get());
				assertFalse(execution.awaitTermination(Duration.ofMillis(10)));
				releaseCallback.countDown();
				assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
				assertEquals(1, callbacks.get());
				assertEquals(1, cleanups.get());
				assertEquals(1, physicalExits.get());
				assertEquals(0, responses.get());
				assertEquals(0, execution.snapshot().activeHandlerSlots());
				assertEquals(0, execution.snapshot().retainedExchanges());
				assertEquals(1, execution.snapshot().abandonedResponses());
			} finally {
				if (cancellation != null) cancellation.complete().run();
				releaseHandler.countDown(); releaseCallback.countDown(); execution.stop();
				assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
			}
		}
	}

	@Test
	@Timeout(180)
	void canceled_body_that_never_starts_has_a_physical_deadline_and_preserves_its_token_reason() throws Exception {
		for (String revision : List.of("2025-06-18", "2025-11-25")) {
			AtomicLong now = new AtomicLong();
			CountDownLatch handlerEntered = new CountDownLatch(1);
			CountDownLatch releaseHandler = new CountDownLatch(1);
			CountDownLatch finished = new CountDownLatch(1);
			AtomicReference<McpApplicationInvocation> invocation = new AtomicReference<>();
			AtomicReference<McpRequestOutcome> outcome = new AtomicReference<>();
			AtomicReference<StreamTerminationReason> closed = new AtomicReference<>();
			AtomicInteger streamCloses = new AtomicInteger();
			McpRuntimeObservationSink observation = input -> new McpRuntimeRequestObservation() {
				@Override public Optional<com.soklet.McpRequestContext> publicContext() { return Optional.empty(); }
				@Override public void didCloseRequestStream(StreamTerminationReason reason, Duration duration) {
					closed.set(reason); streamCloses.incrementAndGet();
				}
				@Override public void didFinish(McpRequestOutcome result, McpJsonRpcError error,
						Duration duration, List<Throwable> throwables) {
					if ("tools/call".equals(input.jsonRpcMethod())) { outcome.set(result); finished.countDown(); }
				}
			};
			McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
					McpImplementationMetadata.withNameAndVersion("physical-terminal", "4.0.0"))
					.tool(McpNormalizedOperation.named("run")).build();
			McpHttpEndpointBinding binding = new McpHttpEndpointBinding(
					McpHttpEndpointPolicy.forDiscovery(CorsAuthorizer.rejectAllInstance(),
							request -> McpRequestAdmissionDecision.ACCEPT), endpoint,
					McpApplicationRequestRouter.fromHandlers(Map.of("tools/call", call -> {
						invocation.set(call); handlerEntered.countDown(); awaitUninterruptibly(releaseHandler);
						return McpWireResult.complete(McpJsonObject.empty());
					})), observation, List.of(), Optional.empty(), Map.of(revision, endpoint));
			McpHttpTransportConfiguration transportConfiguration = McpHttpTransportConfiguration.productionDefaults(0);
			McpHttpServerRuntime runtime = new McpHttpServerRuntime(transportConfiguration, List.of(binding),
					McpJsonLimits.productionDefaults(), new McpApplicationExecutionConfiguration(2, 2,
							Duration.ofDays(1), Duration.ofDays(1)), now::get,
					McpApplicationHandlerExecutorFactory.production(), ignored -> {}, ignored -> {});
			runtime.configureLegacySessions(Optional.of(McpSessionConfig.withOwnerKeyResolver(identity -> "owner")
					.anonymousSessionsAllowed(true).build()), Map.of("/mcp", Set.of(revision)), identity -> "owner");
			try {
				InetSocketAddress address = runtime.start();
				AtomicReference<MicrohttpResponse> initialized = new AtomicReference<>();
				submit(runtime, address, message(address, revision, null,
						"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
								+ "\"protocolVersion\":\"" + revision + "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}"), initialized);
				await(() -> initialized.get() != null);
				assertEquals(200, initialized.get().status());
				String sessionId = initialized.get().headers().stream().filter(header -> header.name().equalsIgnoreCase("Mcp-Session-Id"))
						.findFirst().orElseThrow().value();
				finishBody(initialized.get());
				AtomicReference<MicrohttpResponse> target = new AtomicReference<>();
				submit(runtime, address, message(address, revision, sessionId,
						"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"run\",\"arguments\":{}}}"), target);
				assertTrue(handlerEntered.await(5, TimeUnit.SECONDS));
				AtomicReference<MicrohttpResponse> cancellation = new AtomicReference<>();
				submit(runtime, address, message(address, revision, sessionId,
						"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":2}}"), cancellation);
				await(() -> cancellation.get() != null && target.get() != null);
				assertEquals(202, cancellation.get().status()); finishBody(cancellation.get());
				assertTrue(target.get().streaming());
				assertEquals(200, target.get().status());
				assertEquals(1, runtime.diagnosticsSnapshot().activeRequestStreams());
				assertEquals(Optional.of(StreamTerminationReason.CLIENT_CANCELED), invocation.get().cancellationReason());
				// Deliberately never create/start the body source: the transport offer
				// cannot bypass physical cleanup by waiting for a writer indefinitely.
				now.addAndGet(transportConfiguration.responseWriteIdleTimeout().toNanos());
				runtime.runApplicationTimerCycle();
				assertTrue(finished.await(5, TimeUnit.SECONDS));
				assertEquals(McpRequestOutcome.WRITE_FAILED, outcome.get());
				assertEquals(StreamTerminationReason.RESPONSE_IDLE_TIMEOUT, closed.get());
				assertEquals(1, streamCloses.get());
				assertEquals(0, runtime.diagnosticsSnapshot().activeRequestStreams());
				assertEquals(Optional.of(StreamTerminationReason.CLIENT_CANCELED), invocation.get().cancellationReason());
				releaseHandler.countDown();
				await(() -> runtime.requestExecutionSnapshot().retainedRequestControls() == 0);
			} finally {
				releaseHandler.countDown(); runtime.close();
			}
		}
	}

	@Test
	void unregistered_handoff_is_distinct_from_a_lost_terminal_race() throws Exception {
		McpApplicationExecution execution = execution();
		try {
			execution.start();
			McpApplicationCancellationReservation reservation = execution.tryReserveCancellation(transport(),
					StreamTerminationReason.CLIENT_CANCELED);
			assertFalse(reservation.registered()); assertFalse(reservation.won());
			reservation.complete().run();
			assertEquals(0, execution.snapshot().admittedRequests());
			assertEquals(0, execution.snapshot().abandonedResponses());
		} finally {
			execution.stop(); assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	private static McpApplicationExecution execution() {
		return new McpApplicationExecution(new McpApplicationExecutionConfiguration(1, 1,
				Duration.ofSeconds(20), Duration.ofDays(1)), McpApplicationClock.SYSTEM);
	}
	private static MicrohttpRequest transport() {
		return new MicrohttpRequest("POST", "/mcp", "HTTP/1.1", List.of(), new byte[0], false,
				new InetSocketAddress("127.0.0.1", 12345));
	}
	private static McpEffectiveAdmissionIdentity identity() {
		return McpEffectiveAdmissionIdentity.resolve(McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion("cancellation-reservation", "4.0.0")).build(),
				"/mcp", McpAdmissionIdentity.anonymousInstance());
	}
	private static McpJsonRpcMessage.Request request(McpProtocolProfile profile) {
		McpJsonLimits limits = McpJsonLimits.productionDefaults();
		String json = "{\"jsonrpc\":\"2.0\",\"id\":\"reserved\",\"method\":\"tools/call\","
				+ "\"params\":{\"name\":\"run\",\"arguments\":{}}}";
		McpJsonRpcEnvelope.Request request = (McpJsonRpcEnvelope.Request) new McpJsonRpcEnvelopeCodec(
				new McpJsonCodec(limits)).decode(json.getBytes(StandardCharsets.UTF_8));
		return profile.mapRequest(new McpRequestWireMapper(limits), request);
	}
	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		try {
			for (;;) {
				try {
					if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Missing test release.");
					return;
				} catch (InterruptedException ignored) { interrupted = true; }
			}
		} finally { if (interrupted) Thread.currentThread().interrupt(); }
	}
	private static MicrohttpRequest message(InetSocketAddress address, String revision, String sessionId, String json) {
		java.util.ArrayList<Header> headers = new java.util.ArrayList<>(List.of(
				new Header("Host", "127.0.0.1:" + address.getPort()), new Header("Content-Type", "application/json"),
				new Header("Accept", "application/json, text/event-stream"), new Header("MCP-Protocol-Version", revision)));
		if (sessionId != null) headers.add(new Header("Mcp-Session-Id", sessionId));
		return new MicrohttpRequest("POST", "/mcp", "HTTP/1.1", headers, json.getBytes(StandardCharsets.UTF_8),
				false, new InetSocketAddress("127.0.0.1", 12345));
	}
	private static void submit(McpHttpServerRuntime runtime, InetSocketAddress address,
			MicrohttpRequest request, AtomicReference<MicrohttpResponse> response) throws Exception {
		Field processor = McpHttpServerRuntime.class.getDeclaredField("requestProcessor"); processor.setAccessible(true);
		Field application = McpHttpServerRuntime.class.getDeclaredField("applicationExecution"); application.setAccessible(true);
		Method method = McpHttpServerRuntime.class.getDeclaredMethod("submitRequest", ThreadPoolExecutor.class,
				McpApplicationExecution.class, InetSocketAddress.class, MicrohttpRequest.class, com.soklet.Request.class,
				McpSimulationRuntime.class, Runnable.class, java.util.function.Consumer.class);
		method.setAccessible(true);
		method.invoke(runtime, processor.get(runtime), application.get(runtime), address, request, null, null, null,
				(java.util.function.Consumer<MicrohttpResponse>) response::set);
	}
	private static void finishBody(MicrohttpResponse response) throws Exception {
		Method reserve = MicrohttpResponse.class.getDeclaredMethod("reserveBodyTermination", StreamTerminationReason.class, Throwable.class);
		reserve.setAccessible(true); reserve.invoke(response, StreamTerminationReason.COMPLETED, null);
		Method deliver = MicrohttpResponse.class.getDeclaredMethod("deliverBodyTermination"); deliver.setAccessible(true); deliver.invoke(response);
	}
	private static void await(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean() && System.nanoTime() - deadline < 0) Thread.sleep(5);
		assertTrue(condition.getAsBoolean(), "The bounded execution did not physically retire.");
	}
}
