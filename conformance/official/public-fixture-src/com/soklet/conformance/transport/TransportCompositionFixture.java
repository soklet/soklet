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

package com.soklet.conformance.transport;

import com.soklet.HttpMethod;
import com.soklet.HttpServer;
import com.soklet.HttpTransportAttachmentContext;
import com.soklet.Request;
import com.soklet.ResourcePath;
import com.soklet.ShutdownContext;
import com.soklet.SseBroadcaster;
import com.soklet.SseHandshakeResult;
import com.soklet.SseServer;
import com.soklet.SseTransportAttachmentContext;
import com.soklet.StartupContext;
import com.soklet.TransportDelegateAttachment;
import com.soklet.TransportIdentity;
import com.soklet.TransportRuntime;
import com.soklet.TransportTerminationSignal;
import com.soklet.annotation.GET;
import com.soklet.annotation.SseEventSource;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/**
 * Published external-package reference implementations for Soklet's HTTP/SSE
 * transport-composition SPI. The fixture deliberately imports public API only.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public final class TransportCompositionFixture {
	/** Supported reference graph shapes. */
	public enum Composition {
		/** One independently implemented transport engine. */
		ALTERNATIVE,
		/** One activity-free transparent decorator around the engine. */
		TRANSPARENT,
		/** One independently terminating decorator around the engine. */
		LIFECYCLE_OWNING,
		/** Two nested independently terminating decorators. */
		NESTED_LIFECYCLE_OWNING
	}

	/** Thread-safe event recorder shared by the reference graph. */
	public static final class Probe {
		private final ConcurrentLinkedQueue<String> events =
				new ConcurrentLinkedQueue<>();

		/** @return an immutable event snapshot */
		public List<String> events() {
			return List.copyOf(this.events);
		}

		/**
		 * Counts exact events.
		 *
		 * @param event event to count
		 * @return number of matching observations
		 */
		public long count(String event) {
			requireNonNull(event);
			return this.events.stream().filter(event::equals).count();
		}

		private void record(String event) {
			this.events.add(requireNonNull(event));
		}
	}

	/** One configured HTTP graph plus its inspectable leaf and probe. */
	public record HttpGraph(HttpServer outer, AlternativeHttpServer leaf,
			Probe probe) {
		/** Validates required graph members. */
		public HttpGraph {
			requireNonNull(outer);
			requireNonNull(leaf);
			requireNonNull(probe);
		}
	}

	/** One configured SSE graph plus its inspectable leaf and probe. */
	public record SseGraph(SseServer outer, AlternativeSseServer leaf,
			Probe probe) {
		/** Validates required graph members. */
		public SseGraph {
			requireNonNull(outer);
			requireNonNull(leaf);
			requireNonNull(probe);
		}
	}

	private TransportCompositionFixture() {
	}

	/** Minimal resources used to exercise the wrapped HTTP and SSE handlers. */
	public static final class FixtureResources {
		/** @return a deterministic ordinary HTTP response */
		@GET("/external-http-fixture")
		public String http() {
			return "ok";
		}

		/** @return an accepted deterministic SSE handshake */
		@SseEventSource("/external-sse-fixture")
		public SseHandshakeResult sse() {
			return SseHandshakeResult.accept();
		}
	}

	/**
	 * Creates a fresh HTTP reference graph.
	 *
	 * @param composition graph shape
	 * @return a fresh graph
	 */
	public static HttpGraph httpGraph(Composition composition) {
		requireNonNull(composition);
		Probe probe = new Probe();
		AlternativeHttpServer leaf = new AlternativeHttpServer(probe);
		HttpServer outer = switch (composition) {
			case ALTERNATIVE -> leaf;
			case TRANSPARENT -> new TransparentHttpDecorator(
					"http-transparent", leaf, probe);
			case LIFECYCLE_OWNING -> new OwningHttpDecorator(
					"http-owner", leaf, probe);
			case NESTED_LIFECYCLE_OWNING -> new OwningHttpDecorator(
					"http-outer", new OwningHttpDecorator(
					"http-inner", leaf, probe), probe);
		};
		return new HttpGraph(outer, leaf, probe);
	}

	/**
	 * Creates a fresh SSE reference graph.
	 *
	 * @param composition graph shape
	 * @return a fresh graph
	 */
	public static SseGraph sseGraph(Composition composition) {
		requireNonNull(composition);
		Probe probe = new Probe();
		AlternativeSseServer leaf = new AlternativeSseServer(probe);
		SseServer outer = switch (composition) {
			case ALTERNATIVE -> leaf;
			case TRANSPARENT -> new TransparentSseDecorator(
					"sse-transparent", leaf, probe);
			case LIFECYCLE_OWNING -> new OwningSseDecorator(
					"sse-owner", leaf, probe);
			case NESTED_LIFECYCLE_OWNING -> new OwningSseDecorator(
					"sse-outer", new OwningSseDecorator(
					"sse-inner", leaf, probe), probe);
		};
		return new SseGraph(outer, leaf, probe);
	}

	/** Public-API-only alternative HTTP transport engine. */
	public static final class AlternativeHttpServer implements HttpServer {
		private final TransportIdentity identity = TransportIdentity.create();
		private final Probe probe;
		private final AtomicReference<RequestHandler> requestHandler =
				new AtomicReference<>();

		/** @param probe graph event recorder */
		public AlternativeHttpServer(Probe probe) {
			this.probe = requireNonNull(probe);
		}

		@Override
		public TransportIdentity getTransportIdentity() {
			return this.identity;
		}

		@Override
		public TransportRuntime attach(HttpTransportAttachmentContext context,
				StartupContext startupContext) {
			requireNonNull(startupContext);
			this.probe.record("http-leaf:attach");
			this.requestHandler.set(requireNonNull(context)
					.getAdmissionFencedRequestHandler());
			return new LeafRuntime("http-leaf", this.probe,
					context.getTerminationSignal());
		}

		/** Dispatches one request through every installed handler wrapper. */
		public void dispatchTestRequest() throws InterruptedException {
			CountDownLatch response = new CountDownLatch(1);
			requireNonNull(this.requestHandler.get(),
					"HTTP leaf was not attached").handleRequest(
					Request.fromPath(HttpMethod.GET, "/external-http-fixture"),
					ignored -> response.countDown());
			if (!response.await(5, TimeUnit.SECONDS))
				throw new IllegalStateException(
						"HTTP fixture request did not complete");
		}
	}

	/** Public-API-only alternative SSE transport engine. */
	public static final class AlternativeSseServer implements SseServer {
		private final TransportIdentity identity = TransportIdentity.create();
		private final Probe probe;
		private final AtomicReference<RequestHandler> requestHandler =
				new AtomicReference<>();

		/** @param probe graph event recorder */
		public AlternativeSseServer(Probe probe) {
			this.probe = requireNonNull(probe);
		}

		@Override
		public TransportIdentity getTransportIdentity() {
			return this.identity;
		}

		@Override
		public TransportRuntime attach(SseTransportAttachmentContext context,
				StartupContext startupContext) {
			requireNonNull(startupContext);
			this.probe.record("sse-leaf:attach");
			this.requestHandler.set(requireNonNull(context)
					.getAdmissionFencedRequestHandler());
			return new LeafRuntime("sse-leaf", this.probe,
					context.getTerminationSignal());
		}

		@Override
		public Optional<? extends SseBroadcaster> acquireBroadcaster(
				ResourcePath resourcePath) {
			this.probe.record("sse-leaf:broadcaster");
			return Optional.empty();
		}

		/** Dispatches one handshake through every installed handler wrapper. */
		public void dispatchTestRequest() throws InterruptedException {
			CountDownLatch response = new CountDownLatch(1);
			requireNonNull(this.requestHandler.get(),
					"SSE leaf was not attached").handleRequest(
					Request.fromPath(HttpMethod.GET, "/external-sse-fixture"),
					ignored -> response.countDown());
			if (!response.await(5, TimeUnit.SECONDS))
				throw new IllegalStateException(
						"SSE fixture request did not complete");
		}
	}

	private static final class TransparentHttpDecorator implements HttpServer {
		private final String name;
		private final HttpServer delegate;
		private final Probe probe;

		private TransparentHttpDecorator(String name, HttpServer delegate,
				Probe probe) {
			this.name = requireNonNull(name);
			this.delegate = requireNonNull(delegate);
			this.probe = requireNonNull(probe);
		}

		@Override
		public TransportIdentity getTransportIdentity() {
			return this.delegate.getTransportIdentity();
		}

		@Override
		public TransportRuntime attach(HttpTransportAttachmentContext context,
				StartupContext startupContext) {
			requireNonNull(startupContext);
			this.probe.record(this.name + ":attach");
			RequestHandler parent = context.getAdmissionFencedRequestHandler();
			RequestHandler wrapped = (request, consumer) -> {
				this.probe.record(this.name + ":handler");
				parent.handleRequest(request, consumer);
			};
			return context.attachTransparentDelegate(this.delegate, wrapped);
		}
	}

	private static final class TransparentSseDecorator implements SseServer {
		private final String name;
		private final SseServer delegate;
		private final Probe probe;

		private TransparentSseDecorator(String name, SseServer delegate,
				Probe probe) {
			this.name = requireNonNull(name);
			this.delegate = requireNonNull(delegate);
			this.probe = requireNonNull(probe);
		}

		@Override
		public TransportIdentity getTransportIdentity() {
			return this.delegate.getTransportIdentity();
		}

		@Override
		public TransportRuntime attach(SseTransportAttachmentContext context,
				StartupContext startupContext) {
			requireNonNull(startupContext);
			this.probe.record(this.name + ":attach");
			RequestHandler parent = context.getAdmissionFencedRequestHandler();
			RequestHandler wrapped = (request, consumer) -> {
				this.probe.record(this.name + ":handler");
				parent.handleRequest(request, consumer);
			};
			return context.attachTransparentDelegate(this.delegate, wrapped);
		}

		@Override
		public Optional<? extends SseBroadcaster> acquireBroadcaster(
				ResourcePath resourcePath) {
			this.probe.record(this.name + ":broadcaster");
			return this.delegate.acquireBroadcaster(resourcePath);
		}
	}

	private static final class OwningHttpDecorator implements HttpServer {
		private final String name;
		private final HttpServer delegate;
		private final Probe probe;

		private OwningHttpDecorator(String name, HttpServer delegate, Probe probe) {
			this.name = requireNonNull(name);
			this.delegate = requireNonNull(delegate);
			this.probe = requireNonNull(probe);
		}

		@Override
		public TransportIdentity getTransportIdentity() {
			return this.delegate.getTransportIdentity();
		}

		@Override
		public TransportRuntime attach(HttpTransportAttachmentContext context,
				StartupContext startupContext) {
			requireNonNull(startupContext);
			this.probe.record(this.name + ":attach");
			RequestHandler parent = context.getAdmissionFencedRequestHandler();
			RequestHandler wrapped = (request, consumer) -> {
				this.probe.record(this.name + ":handler");
				parent.handleRequest(request, consumer);
			};
			TransportDelegateAttachment attachment =
					context.attachLifecycleOwningDelegate(this.delegate, wrapped);
			OwningRuntime runtime = new OwningRuntime(this.name,
					attachment.getRuntime(), context.getTerminationSignal(), this.probe);
			attachment.whenTerminated().thenRun(runtime::submitCleanup);
			return runtime;
		}
	}

	private static final class OwningSseDecorator implements SseServer {
		private final String name;
		private final SseServer delegate;
		private final Probe probe;

		private OwningSseDecorator(String name, SseServer delegate, Probe probe) {
			this.name = requireNonNull(name);
			this.delegate = requireNonNull(delegate);
			this.probe = requireNonNull(probe);
		}

		@Override
		public TransportIdentity getTransportIdentity() {
			return this.delegate.getTransportIdentity();
		}

		@Override
		public TransportRuntime attach(SseTransportAttachmentContext context,
				StartupContext startupContext) {
			requireNonNull(startupContext);
			this.probe.record(this.name + ":attach");
			RequestHandler parent = context.getAdmissionFencedRequestHandler();
			RequestHandler wrapped = (request, consumer) -> {
				this.probe.record(this.name + ":handler");
				parent.handleRequest(request, consumer);
			};
			TransportDelegateAttachment attachment =
					context.attachLifecycleOwningDelegate(this.delegate, wrapped);
			OwningRuntime runtime = new OwningRuntime(this.name,
					attachment.getRuntime(), context.getTerminationSignal(), this.probe);
			attachment.whenTerminated().thenRun(runtime::submitCleanup);
			return runtime;
		}

		@Override
		public Optional<? extends SseBroadcaster> acquireBroadcaster(
				ResourcePath resourcePath) {
			this.probe.record(this.name + ":broadcaster");
			return this.delegate.acquireBroadcaster(resourcePath);
		}
	}

	private static final class LeafRuntime implements TransportRuntime {
		private final String name;
		private final Probe probe;
		private final TransportTerminationSignal signal;
		private final AtomicBoolean signaled = new AtomicBoolean();

		private LeafRuntime(String name, Probe probe,
				TransportTerminationSignal signal) {
			this.name = requireNonNull(name);
			this.probe = requireNonNull(probe);
			this.signal = requireNonNull(signal);
		}

		@Override
		public void start(StartupContext context) {
			requireNonNull(context);
			this.probe.record(this.name + ":start");
		}

		@Override
		public void quiesce(ShutdownContext context) {
			requireNonNull(context);
			this.probe.record(this.name + ":quiesce");
			publishProof();
		}

		@Override
		public void force(ShutdownContext context) {
			requireNonNull(context);
			this.probe.record(this.name + ":force");
			publishProof();
		}

		private void publishProof() {
			if (this.signaled.compareAndSet(false, true))
				this.signal.signalTerminated();
		}
	}

	private static final class OwningRuntime implements TransportRuntime {
		private final String name;
		private final TransportRuntime child;
		private final Probe probe;
		private final OwnedExecutor executor;
		private final AtomicBoolean cleanupSubmitted = new AtomicBoolean();

		private OwningRuntime(String name, TransportRuntime child,
				TransportTerminationSignal signal, Probe probe) {
			this.name = requireNonNull(name);
			this.child = requireNonNull(child);
			this.probe = requireNonNull(probe);
			this.executor = new OwnedExecutor(this.name, requireNonNull(signal),
					this.probe);
		}

		@Override
		public void start(StartupContext context) {
			requireNonNull(context);
			this.probe.record(this.name + ":start");
			if (!this.executor.prestartCoreThread())
				throw new IllegalStateException(
						"Decorator executor did not prestart");
			try {
				this.child.start(context);
			} catch (RuntimeException | Error failure) {
				this.executor.shutdownNow();
				throw failure;
			}
		}

		@Override
		public void quiesce(ShutdownContext context) {
			requireNonNull(context);
			this.probe.record(this.name + ":quiesce");
			this.child.quiesce(context);
		}

		@Override
		public void force(ShutdownContext context) {
			requireNonNull(context);
			this.probe.record(this.name + ":force");
			this.executor.shutdownNow();
			this.child.force(context);
		}

		private void submitCleanup() {
			if (!this.cleanupSubmitted.compareAndSet(false, true))
				return;
			this.probe.record(this.name + ":delegate-proof");
			try {
				this.executor.execute(() -> {
					this.probe.record(this.name + ":cleanup");
					this.executor.shutdown();
				});
			} catch (RuntimeException | Error failure) {
				this.executor.shutdownNow();
				throw failure;
			}
		}
	}

	private static final class OwnedExecutor extends ThreadPoolExecutor {
		private final String name;
		private final TransportTerminationSignal signal;
		private final Probe probe;

		private OwnedExecutor(String name, TransportTerminationSignal signal,
				Probe probe) {
			super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
					runnable -> {
						Thread thread = new Thread(runnable,
								"soklet-external-transport-" + name);
						thread.setDaemon(true);
						return thread;
					});
			this.name = requireNonNull(name);
			this.signal = requireNonNull(signal);
			this.probe = requireNonNull(probe);
		}

		@Override
		protected void terminated() {
			super.terminated();
			this.probe.record(this.name + ":terminated");
			this.signal.signalTerminated();
		}
	}
}
