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

import com.soklet.CorsAuthorizer;
import com.soklet.MetricsCollector;
import com.soklet.internal.microhttp.ConnectionListener;
import com.soklet.internal.microhttp.EventLoop;
import com.soklet.internal.microhttp.NoopLogger;
import com.soklet.internal.microhttp.Options;
import com.soklet.internal.microhttp.TransportFailureObserver;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

@Timeout(60)
public class McpTransportMetricsEventRuntimeTests {
	private static final String LOOPBACK = "127.0.0.1";

	@Test
	public void sameConnectionRecordsAcceptedBeforeRequestAccepted() throws Exception {
		RecordingExecutionObserver observer = new RecordingExecutionObserver();
		McpHttpServerRuntime runtime = runtime(
				McpHttpTransportConfiguration.productionDefaults(0), observer);

		try {
			int port = runtime.start().getPort();
			try (McpChunkedHttpClient client = McpChunkedHttpClient.postMcp(
					port, "\"same-connection\"", "server/discover")) {
				McpChunkedHttpClient.HttpResponseHead head = client.readHead();
				Assertions.assertEquals(200, head.status(), head.raw());
				Assertions.assertTrue(client.readFixedBody(head).contains(
						"\"id\":\"same-connection\""));
			}

			observer.awaitValues(List.of(Marker.CONNECTION_ACCEPTED,
					Marker.REQUEST_ACCEPTED));
		} finally {
			runtime.close();
		}
	}

	@Test
	public void maximumConnectionCapacityRejectsOnlyAndRecoversAfterRelease()
			throws Exception {
		RecordingExecutionObserver observer = new RecordingExecutionObserver();
		McpHttpTransportConfiguration defaults =
				McpHttpTransportConfiguration.productionDefaults(0);
		McpHttpServerRuntime runtime = runtime(configuration(defaults,
				defaults.requestHeaderTimeout(), 1), observer);
		Socket first = null;
		Socket second = null;
		Socket recovered = null;

		try {
			int port = runtime.start().getPort();
			first = new Socket(LOOPBACK, port);
			observer.awaitValues(List.of(Marker.CONNECTION_ACCEPTED));
			second = new Socket(LOOPBACK, port);
			second.setSoTimeout(5_000);
			observer.awaitValues(List.of(Marker.CONNECTION_ACCEPTED,
					Marker.CONNECTION_REJECTED));
			Assertions.assertEquals(-1, second.getInputStream().read(),
					"The over-capacity connection was not refused.");

			first.close();
			first = null;
			Assertions.assertTrue(eventLoop(runtime).awaitConnectionsDrained(
					Duration.ofSeconds(5)),
					"The accepted connection did not release its capacity slot.");
			observer.awaitValues(List.of(Marker.CONNECTION_ACCEPTED,
					Marker.CONNECTION_REJECTED));
			Assertions.assertEquals(List.of(Marker.CONNECTION_ACCEPTED,
					Marker.CONNECTION_REJECTED), observer.values());
			recovered = new Socket(LOOPBACK, port);
			observer.awaitAtLeastSize(3);
			recovered.close();
			recovered = null;
			Assertions.assertTrue(eventLoop(runtime).awaitConnectionsDrained(
					Duration.ofSeconds(5)),
					"The recovered connection did not complete registration cleanup.");
			observer.awaitValues(List.of(Marker.CONNECTION_ACCEPTED,
					Marker.CONNECTION_REJECTED, Marker.CONNECTION_ACCEPTED));
			Assertions.assertEquals(List.of(Marker.CONNECTION_ACCEPTED,
					Marker.CONNECTION_REJECTED, Marker.CONNECTION_ACCEPTED),
					observer.values());
		} finally {
			if (first != null)
				first.close();
			if (second != null)
				second.close();
			if (recovered != null)
				recovered.close();
			runtime.close();
		}
	}

	@Test
	public void partialRequestReadTimeoutIsRecordedWhileIdleConnectionClosesQuietly()
			throws Exception {
		RecordingExecutionObserver observer = new RecordingExecutionObserver();
		McpHttpTransportConfiguration defaults =
				McpHttpTransportConfiguration.productionDefaults(0);
		McpHttpServerRuntime runtime = runtime(configuration(defaults,
				Duration.ofSeconds(1), 2), observer);

		try {
			int port = runtime.start().getPort();
			try (Socket idle = new Socket(LOOPBACK, port)) {
				idle.setSoTimeout(5_000);
				observer.awaitValues(List.of(Marker.CONNECTION_ACCEPTED));
				Assertions.assertEquals(-1, idle.getInputStream().read(),
						"The idle preconnection did not close at its header timeout.");
				observer.awaitValues(List.of(Marker.CONNECTION_ACCEPTED));
				Assertions.assertEquals(List.of(Marker.CONNECTION_ACCEPTED),
						observer.values(),
						"A byte-free idle connection must not record a transport failure.");
			}

			try (Socket partial = new Socket(LOOPBACK, port)) {
				partial.setSoTimeout(5_000);
				partial.getOutputStream().write(
						"POST /mcp HTTP/1.1\r\nHost:"
								.getBytes(StandardCharsets.US_ASCII));
				partial.getOutputStream().flush();
				observer.awaitValues(List.of(Marker.CONNECTION_ACCEPTED,
						Marker.CONNECTION_ACCEPTED,
						MetricsCollector.TransportFailureReason.REQUEST_READ_TIMEOUT));
				Assertions.assertEquals(-1, partial.getInputStream().read(),
						"The stalled partial request did not close.");
			}

			Assertions.assertEquals(List.of(Marker.CONNECTION_ACCEPTED,
					Marker.CONNECTION_ACCEPTED,
					MetricsCollector.TransportFailureReason.REQUEST_READ_TIMEOUT),
					observer.values());
		} finally {
			runtime.close();
		}
	}

	@Test
	public void acceptAndSetupFailuresRemainPartitionedFromCapacityRejection()
			throws Exception {
		RecordingExecutionObserver acceptObserver = new RecordingExecutionObserver();
		McpHttpServerRuntime runtime = runtime(
				McpHttpTransportConfiguration.productionDefaults(0), acceptObserver);

		try {
			runtime.start();
			Assertions.assertFalse(invokeAccept(eventLoop(runtime), () -> {
				throw new IOException("expected accept failure");
			}));
			acceptObserver.awaitValues(List.of(
					MetricsCollector.TransportFailureReason.ACCEPT_LOOP_ERROR));
			Assertions.assertEquals(List.of(
					MetricsCollector.TransportFailureReason.ACCEPT_LOOP_ERROR),
					acceptObserver.values(),
					"An accept IOException must not delegate to ConnectionRejected.");
		} finally {
			runtime.close();
		}

		RecordingExecutionObserver setupObserver = new RecordingExecutionObserver();
		RuntimeException setupFailure = new RuntimeException(
				"expected connection setup failure");
		ConnectionListener listener = new ConnectionListener() {
			@Override
			public void willAcceptConnection(
					@Nullable InetSocketAddress remoteAddress) {
			}

			@Override
			public void didAcceptConnection(
					@Nullable InetSocketAddress remoteAddress) {
				setupObserver.recordConnectionAccepted();
				throw setupFailure;
			}

			@Override
			public void didFailToAcceptConnection(
					@Nullable InetSocketAddress remoteAddress) {
				setupObserver.recordConnectionRejected();
			}

			@Override
			public void didFailToAcceptConnection(
					@Nullable InetSocketAddress remoteAddress,
					@Nullable Throwable throwable) {
				Assertions.assertSame(setupFailure, throwable);
			}
		};
		TransportFailureObserver failureObserver = reason -> {
			setupObserver.recordTransportFailure(reason);
			return () -> {
			};
		};
		EventLoop setupLoop = new EventLoop(Options.builder()
				.withHost(LOOPBACK).withPort(0).withConcurrency(1).build(),
				NoopLogger.instance(), (request, callback) -> {
		}, listener, failureObserver);

		try (ServerSocketChannel peerListener = ServerSocketChannel.open()) {
			peerListener.bind(new InetSocketAddress(LOOPBACK, 0));
			InetSocketAddress peerAddress = (InetSocketAddress)
					peerListener.getLocalAddress();
			try (SocketChannel peer = SocketChannel.open(peerAddress);
					SocketChannel accepted = peerListener.accept()) {
				Assertions.assertFalse(invokeAccept(setupLoop, () -> accepted));
			}
			Assertions.assertEquals(List.of(Marker.CONNECTION_ACCEPTED,
					MetricsCollector.TransportFailureReason.CONNECTION_SETUP_ERROR),
					setupObserver.values(),
					"A post-reservation setup fault may follow Accepted but must not emit Rejected.");
		} finally {
			setupLoop.stop();
			setupLoop.join(Duration.ofSeconds(3));
		}
	}

	@Test
	public void boundedTransportReasonsPassThroughWithoutUnboundedContext()
			throws Exception {
		RecordingExecutionObserver observer = new RecordingExecutionObserver();
		McpHttpServerRuntime runtime = runtime(
				McpHttpTransportConfiguration.productionDefaults(0), observer);

		try {
			TransportFailureObserver transportObserver = transportFailureObserver(runtime);
			for (MetricsCollector.TransportFailureReason reason
					: MetricsCollector.TransportFailureReason.values()) {
				try (TransportFailureObserver.Observation ignored =
						transportObserver.beginFailure(reason)) {
				}
			}
			observer.awaitValues(Arrays.asList(
					MetricsCollector.TransportFailureReason.values()));
			Assertions.assertEquals(
					Arrays.asList(MetricsCollector.TransportFailureReason.values()),
					observer.values(),
					"The runtime boundary must pass only the fixed typed reason, with no remote address, throwable, or request payload.");
		} finally {
			runtime.close();
		}
	}

	private static McpHttpServerRuntime runtime(
			McpHttpTransportConfiguration transportConfiguration,
			McpApplicationExecutionObserver observer) {
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint
				.withServerInformation(McpImplementationMetadata.withNameAndVersion(
						"transport-metrics-test", "4.0.0-SNAPSHOT"))
				.build();
		McpHttpEndpointPolicy policy = McpHttpEndpointPolicy.forDiscovery(
				CorsAuthorizer.rejectAllInstance(),
				request -> McpRequestAdmissionDecision.ACCEPT);
		McpHttpEndpointBinding binding = new McpHttpEndpointBinding(policy,
				endpoint, McpApplicationRequestRouter.empty());
		return new McpHttpServerRuntime(transportConfiguration, List.of(binding),
				McpJsonLimits.productionDefaults(),
				McpApplicationExecutionConfiguration.productionDefaults(),
				McpApplicationClock.SYSTEM,
				McpApplicationHandlerExecutorFactory.production(),
				ignored -> {
		}, ignored -> {
		}, Optional.empty(), McpFrameworkRequestStateRuntime.disabledInstance(),
				McpSubscriptionRuntimeConfiguration.productionDefaults(), observer);
	}

	private static McpHttpTransportConfiguration configuration(
			McpHttpTransportConfiguration defaults,
			Duration requestHeaderTimeout, int maximumConnections) {
		return new McpHttpTransportConfiguration(defaults.host(), defaults.port(),
				Duration.ofMillis(10), requestHeaderTimeout,
				defaults.requestBodyTimeout(), defaults.responseWriteIdleTimeout(),
				defaults.keepAliveInterval(), defaults.shutdownTimeout(),
				defaults.readBufferSize(), defaults.acceptBacklog(),
				defaults.maximumAggregateRequestBytes(),
				defaults.maximumRequestBodyBytes(), defaults.maximumHeaderCount(),
				defaults.maximumHeaderBytes(), defaults.maximumRequestTargetBytes(),
				maximumConnections, defaults.connectionWriterConcurrency(),
				defaults.requestProcessorConcurrency(),
				defaults.requestProcessorQueueCapacity(),
				defaults.streamQueueCapacity());
	}

	private static EventLoop eventLoop(McpHttpServerRuntime runtime)
			throws Exception {
		Field field = McpHttpServerRuntime.class.getDeclaredField("eventLoop");
		field.setAccessible(true);
		return (EventLoop) requireNonNull(field.get(runtime));
	}

	private static TransportFailureObserver transportFailureObserver(
			McpHttpServerRuntime runtime) throws Exception {
		Field field = McpHttpServerRuntime.class.getDeclaredField(
				"transportFailureObserver");
		field.setAccessible(true);
		return (TransportFailureObserver) requireNonNull(field.get(runtime));
	}

	private static boolean invokeAccept(EventLoop eventLoop,
			ThrowingSocketAcceptor acceptor) throws Exception {
		Class<?> acceptorType = Class.forName(
				"com.soklet.internal.microhttp.EventLoop$SocketAcceptor");
		Object proxy = Proxy.newProxyInstance(acceptorType.getClassLoader(),
				new Class<?>[]{acceptorType}, (ignored, method, arguments) -> {
					if ("accept".equals(method.getName()))
						return acceptor.accept();
					throw new UnsupportedOperationException(method.toString());
				});
		Method method = EventLoop.class.getDeclaredMethod(
				"acceptReadyConnection", acceptorType);
		method.setAccessible(true);
		try {
			return (Boolean) method.invoke(eventLoop, proxy);
		} catch (InvocationTargetException exception) {
			Throwable target = exception.getTargetException();
			if (target instanceof Exception checked)
				throw checked;
			if (target instanceof Error error)
				throw error;
			throw new AssertionError(target);
		}
	}

	@FunctionalInterface
	private interface ThrowingSocketAcceptor {
		SocketChannel accept() throws IOException;
	}

	private enum Marker {
		CONNECTION_ACCEPTED,
		CONNECTION_REJECTED,
		REQUEST_ACCEPTED
	}

	private static final class RecordingExecutionObserver
			implements McpApplicationExecutionObserver {
		private final Object lock = new Object();
		private final List<Entry> entries = new ArrayList<>();

		@Override
		public PendingMetricRecord recordRequestAccepted() {
			return add(Marker.REQUEST_ACCEPTED);
		}

		@Override
		public void recordConnectionAccepted() {
			add(Marker.CONNECTION_ACCEPTED);
		}

		@Override
		public void recordConnectionRejected() {
			add(Marker.CONNECTION_REJECTED);
		}

		@Override
		public PendingMetricRecord recordTransportFailure(
				MetricsCollector.@NonNull TransportFailureReason reason) {
			return add(requireNonNull(reason));
		}

		@Override
		public void discardPendingMetric(PendingMetricRecord pendingMetricRecord) {
			synchronized (this.lock) {
				if (!this.entries.remove(pendingMetricRecord))
					throw new AssertionError("Pending metric was not present.");
				this.lock.notifyAll();
			}
		}

		private Entry add(Object value) {
			Entry entry = new Entry(value);
			synchronized (this.lock) {
				this.entries.add(entry);
				this.lock.notifyAll();
			}
			return entry;
		}

		private void awaitValues(List<?> expectedValues) throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			synchronized (this.lock) {
				while (!values().equals(expectedValues)) {
					long remaining = deadline - System.nanoTime();
					if (remaining <= 0L)
						throw new AssertionError("Timed out awaiting metric records "
								+ expectedValues + "; found " + values());
					TimeUnit.NANOSECONDS.timedWait(this.lock, remaining);
				}
			}
		}

		private void awaitAtLeastSize(int expectedSize) throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			synchronized (this.lock) {
				while (this.entries.size() < expectedSize) {
					long remaining = deadline - System.nanoTime();
					if (remaining <= 0L)
						throw new AssertionError("Timed out awaiting at least "
								+ expectedSize + " metric records; found " + values());
					TimeUnit.NANOSECONDS.timedWait(this.lock, remaining);
				}
			}
		}

		private List<Object> values() {
			synchronized (this.lock) {
				return this.entries.stream().map(Entry::value).toList();
			}
		}

		@Override
		public void beginDeferral() {
		}

		@Override
		public void recordHandlerExecutionStarted() {
		}

		@Override
		public void recordHandlerExecutionFinished() {
		}

		@Override
		public void recordHandlerQueued() {
		}

		@Override
		public void recordHandlerDequeued() {
		}

		@Override
		public void recordHandlerCapacityRejected() {
		}

		@Override
		public void drain() {
		}

		@Override
		public void endDeferral() {
		}
	}

	private record Entry(Object value)
			implements McpApplicationExecutionObserver.PendingMetricRecord {
	}
}
