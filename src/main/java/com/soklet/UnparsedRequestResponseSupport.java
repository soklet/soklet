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

package com.soklet;

import com.soklet.internal.microhttp.Header;
import com.soklet.internal.microhttp.MicrohttpResponse;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.soklet.internal.ObjectIdentity.sameInstance;
import static com.soklet.Utilities.emptyByteArray;
import static com.soklet.Utilities.trimAggressivelyToEmpty;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

/** Shared bounded application pipeline and finite framing for pre-Request rejections. */
final class UnparsedRequestResponseSupport {
	static final int CAPTURE_LIMIT_IN_BYTES = 64 * 1024;
	static final int RESPONSE_SIZE_LIMIT_IN_BYTES = 64 * 1024;

	private UnparsedRequestResponseSupport() {}

	record PreparedResponse(byte[] serializedBytes, MicrohttpResponse response) {}

	/** The caller's response consumer must only enqueue framework work, never invoke application code. */
	static boolean submit(@Nullable ExecutorService executor, @Nullable TimeoutScheduler scheduler,
						  @NonNull Duration timeout, @NonNull Supplier<UnparsedRequest> requestSupplier,
						  @Nullable ResponseMarshaler responseMarshaler, @NonNull LifecycleObserver observer,
						  @NonNull Consumer<LogEvent> logger, @NonNull Consumer<byte[]> responseConsumer,
						  @NonNull Runnable completion) {
		if (executor == null || executor.isShutdown() || scheduler == null || scheduler.isShutdown())
			return false;
		AtomicBoolean responseClaimed = new AtomicBoolean();
		AtomicBoolean executedInline = new AtomicBoolean();
		AtomicReference<FutureTask<Void>> taskReference = new AtomicReference<>();
		AtomicReference<TimeoutScheduler.ScheduledTask> timeoutReference = new AtomicReference<>();
		Thread submittingThread = Thread.currentThread();
		try {
			timeoutReference.set(scheduler.schedule(() -> {
				if (!responseClaimed.compareAndSet(false, true)) return;
				try { responseConsumer.accept(null); }
				finally {
					completion.run();
					FutureTask<Void> task = taskReference.get();
					if (task != null) task.cancel(true);
				}
			}, timeout));
			FutureTask<Void> task = new FutureTask<>(() -> {
				if (responseClaimed.get()) return;
				PreparedResponse prepared = null;
				try {
					prepared = marshal(requestSupplier.get(), responseMarshaler, observer, logger,
							() -> !responseClaimed.get());
				} catch (Throwable throwable) {
					if (!responseClaimed.get()) logMarshalingFailure(logger, throwable);
				}
				if (responseClaimed.compareAndSet(false, true)) {
					cancel(timeoutReference.getAndSet(null));
					try { responseConsumer.accept(prepared == null ? null : prepared.serializedBytes()); }
					finally { completion.run(); }
				}
			}, null);
			taskReference.set(task);
			executor.execute(() -> {
				if (sameInstance(Thread.currentThread(), submittingThread)) {
					executedInline.set(true);
					return;
				}
				task.run();
			});
			if (executedInline.get()) {
				cancel(timeoutReference.getAndSet(null));
				return false;
			}
			return true;
		} catch (RejectedExecutionException exception) {
			cancel(timeoutReference.getAndSet(null));
			return false;
		} catch (RuntimeException | Error throwable) {
			try { cancel(timeoutReference.getAndSet(null)); }
			catch (RuntimeException | Error failure) { throwable.addSuppressed(failure); }
			throw throwable;
		}
	}

	@Nullable
	static PreparedResponse marshal(@NonNull UnparsedRequest request,
									@Nullable ResponseMarshaler responseMarshaler,
									@NonNull LifecycleObserver observer,
									@NonNull Consumer<LogEvent> logger,
									@NonNull BooleanSupplier active) {
		if (!active.getAsBoolean()) return null;
		try { observer.didRejectUnparsedRequest(request); }
		catch (Throwable throwable) {
			logger.accept(LogEvent.with(LogEventType.LIFECYCLE_OBSERVER_DID_REJECT_UNPARSED_REQUEST_FAILED,
					"An exception occurred while invoking LifecycleObserver::didRejectUnparsedRequest")
					.throwable(throwable).build());
		}
		if (!active.getAsBoolean()) return null;
		MarshaledResponse marshaledResponse = null;
		try {
			marshaledResponse = requireNonNull(requireNonNull(responseMarshaler,
					"Response marshaler is unavailable.").forUnparsedRequest(request),
					"Response marshaler returned null for an unparsed request.");
			if (!active.getAsBoolean()) {
				releaseRejectedUnparsedResponseResources(marshaledResponse);
				return null;
			}
			return prepare(marshaledResponse);
		} catch (Throwable throwable) {
			releaseRejectedUnparsedResponseResources(marshaledResponse);
			if (active.getAsBoolean()) logMarshalingFailure(logger, throwable);
			return null;
		}
	}

	private static void logMarshalingFailure(Consumer<LogEvent> logger, Throwable throwable) {
		logger.accept(LogEvent.with(LogEventType.RESPONSE_MARSHALER_FOR_UNPARSED_REQUEST_FAILED,
				"Unable to marshal a response for an unparsed request; using the built-in response")
				.throwable(throwable).build());
	}

	private static void cancel(TimeoutScheduler.@Nullable ScheduledTask task) {
		if (task != null) task.cancel();
	}

	static PreparedResponse prepare(
			@NonNull MarshaledResponse marshaledResponse) {
		requireNonNull(marshaledResponse);

		if (marshaledResponse.getStreamingResponseBody().isPresent())
			throw new IllegalArgumentException(
					"Unparsed-request responses may not stream a body.");

		int statusCode = marshaledResponse.getStatusCode();
		if (statusCode < 200 || statusCode > 599)
			throw new IllegalArgumentException(
					"Unparsed-request response status must be a final HTTP "
							+ "status from 200 through 599.");
		if (statusMustNotIncludeBody(statusCode)
				&& marshaledResponse.getBody().isPresent())
			throw new IllegalArgumentException(format(
					"HTTP status %d must not include an unparsed-request response body.",
					statusCode));

		byte[] body = unparsedRequestResponseBody(marshaledResponse);
		String reasonPhrase = StatusCode.fromStatusCode(statusCode)
				.map(StatusCode::getReasonPhrase).orElse("Unknown");
		long serializedSize = body.length
				+ "HTTP/1.1".length() + 1L
				+ Integer.toString(statusCode).length() + 1L
				+ reasonPhrase.length() + 2L
				+ serializedHeaderSize("Connection", "close") + 2L;
		if (!statusMustNotIncludeBody(statusCode))
			serializedSize += serializedHeaderSize("Content-Length",
					Integer.toString(body.length));
		ensureUnparsedResponseSize(serializedSize);

		Set<String> connectionNamedHeaders = new TreeSet<>(
				String.CASE_INSENSITIVE_ORDER);
		List<String> connectionValues = marshaledResponse.getHeaders()
				.get("Connection");

		if (connectionValues != null) {
			for (String value : connectionValues) {
				for (String token : value.split(",", -1)) {
					String normalized = trimAggressivelyToEmpty(token);
					if (!normalized.isEmpty())
						connectionNamedHeaders.add(normalized);
				}
			}
		}

		List<Header> headers = new ArrayList<>();
		for (Map.Entry<String, List<String>> entry :
				marshaledResponse.getHeaders().entrySet()) {
			String name = entry.getKey();
			if (unparsedResponseHeaderIsTransportOwned(name)
					|| connectionNamedHeaders.contains(name))
				continue;

			for (String value : entry.getValue()) {
				serializedSize += serializedHeaderSize(name, value);
				ensureUnparsedResponseSize(serializedSize);
				headers.add(new Header(name, value));
			}
		}

		List<ResponseCookie> cookies = marshaledResponse.getCookies();
		List<ResponseCookie> sortedCookies = new ArrayList<>(cookies);
		if (!connectionNamedHeaders.contains("Set-Cookie")) {
			for (ResponseCookie cookie : sortedCookies) {
				String value = cookie.toSetCookieHeaderRepresentation();
				serializedSize += serializedHeaderSize("Set-Cookie", value);
				ensureUnparsedResponseSize(serializedSize);
				headers.add(new Header("Set-Cookie", value));
			}
		}

		headers.sort(Comparator.comparing(Header::name, String.CASE_INSENSITIVE_ORDER));

		MicrohttpResponse response = new MicrohttpResponse(statusCode,
				reasonPhrase, headers, body);
		List<Header> transportHeaders = new ArrayList<>();
		transportHeaders.add(new Header("Connection", "close"));
		if (!statusMustNotIncludeBody(statusCode))
			transportHeaders.add(new Header("Content-Length",
					Integer.toString(body.length)));
		if (!response.hasHeader("Date"))
			transportHeaders.add(new Header("Date", HttpDate.currentSecondHeaderValue()));
		byte[] serialized = response.serialize("HTTP/1.1", transportHeaders, RESPONSE_SIZE_LIMIT_IN_BYTES);
		List<Header> completeHeaders = new ArrayList<>(transportHeaders);
		completeHeaders.addAll(headers);
		return new PreparedResponse(serialized, new MicrohttpResponse(statusCode, reasonPhrase, completeHeaders, body));
	}

	private static long serializedHeaderSize(@NonNull String name,
			@NonNull String value) {
		return (long) requireNonNull(name).length() + 2L
				+ requireNonNull(value).length() + 2L;
	}

	private static void ensureUnparsedResponseSize(long serializedSize) {
		if (serializedSize > RESPONSE_SIZE_LIMIT_IN_BYTES)
			throw new IllegalArgumentException(
					"Serialized unparsed-request response exceeds its size limit.");
	}

	private static byte @NonNull [] unparsedRequestResponseBody(
			@NonNull MarshaledResponse marshaledResponse) {
		MarshaledResponseBody body = requireNonNull(marshaledResponse)
				.getBody().orElse(null);

		if (body == null)
			return emptyByteArray();
		if (body.getLength() > RESPONSE_SIZE_LIMIT_IN_BYTES)
			throw new IllegalArgumentException(
					"Unparsed-request response body exceeds its size limit.");
		if (body instanceof MarshaledResponseBody.Bytes bytes)
			return bytes.getBytes().clone();
		if (body instanceof MarshaledResponseBody.ByteBuffer byteBuffer) {
			ByteBuffer source = byteBuffer.getBuffer();
			byte[] bytes = new byte[source.remaining()];
			source.get(bytes);
			return bytes;
		}

		throw new IllegalArgumentException(format(
				"Unsupported unparsed-request response body type: %s",
				body.getClass().getName()));
	}

	private static boolean unparsedResponseHeaderIsTransportOwned(
			@NonNull String name) {
		return switch (requireNonNull(name).toLowerCase(ENGLISH)) {
			case "connection", "content-length", "keep-alive",
					"proxy-connection", "te", "trailer", "transfer-encoding",
					"upgrade" -> true;
			default -> false;
		};
	}

	private static boolean statusMustNotIncludeBody(int statusCode) {
		return statusCode == 204 || statusCode == 205 || statusCode == 304;
	}

	private static void releaseRejectedUnparsedResponseResources(
			@Nullable MarshaledResponse marshaledResponse) {
		if (marshaledResponse == null)
			return;

		MarshaledResponseBody body = marshaledResponse.getBody().orElse(null);
		if (!(body instanceof MarshaledResponseBody.FileChannel fileChannel)
				|| !fileChannel.getCloseOnComplete())
			return;

		try {
			fileChannel.getChannel().close();
		} catch (IOException ignored) {
			// Best effort: this is already a response-validation fallback path.
		}
	}

}
