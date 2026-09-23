package fixtures;

import com.soklet.CallbackRegistration;
import com.soklet.CancelationToken;
import com.soklet.MarshaledResponse;
import com.soklet.Request;
import com.soklet.ResponseStream;
import com.soklet.SseClientInitializer;
import com.soklet.SseHandshakeResult;
import com.soklet.SseUnicaster;
import com.soklet.StreamResourceFactory;
import com.soklet.StreamingResponseBody;
import com.soklet.StreamingResponseWriter;

import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Compile-only examples of the selected milestone-2 surface. Ownership transfers
 * to Soklet: acquired resources are neither manually closed nor placed in TWR.
 * The local provider stubs document the required concurrency contracts.
 */
public final class StreamingHandlers {
	private StreamingHandlers() {}

	public static MarshaledResponse commonUpstream(Providers providers, String prompt) {
		return MarshaledResponse.withStatusCode(200)
				.headers(textHeaders())
				.stream(responseStream -> {
					var upstream = responseStream.open(() -> providers.stream(prompt));
					for (String delta : upstream) {
						responseStream.writeUtf8(delta);
						responseStream.flush();
					}
				})
				.build();
	}

	public static MarshaledResponse forwardCancelationToken(Providers providers, String prompt) {
		return MarshaledResponse.withStatusCode(200)
				.headers(textHeaders())
				.stream(responseStream -> {
					// This provider can observe cancelation while stream acquisition blocks.
					var upstream = responseStream.open(
							() -> providers.stream(prompt, responseStream.getCancelationToken()));
					for (String delta : upstream)
						responseStream.writeUtf8(delta);
				})
				.build();
	}

	public static MarshaledResponse preparableAcquisition(Providers providers, String prompt) {
		return MarshaledResponse.withStatusCode(200)
				.stream(responseStream -> {
					// Register the inert call's separate abort before execute starts acquisition.
					var streamingCall = responseStream.open(
							() -> providers.newStreamingCall(prompt), Providers.StreamingCall::cancel);
					var upstream = responseStream.open(streamingCall::execute);
					for (String delta : upstream)
						responseStream.writeUtf8(delta);
				})
				.build();
	}

	public static MarshaledResponse zipArchive(byte[] reportBytes) {
		return MarshaledResponse.withStatusCode(200)
				.headers(Map.of("Content-Type", Set.of("application/zip")))
				.stream(responseStream -> {
					// ZIP finalization stays on the producer thread, with output still writable.
					var zipOutputStream = responseStream.own(new ZipOutputStream(responseStream.asOutputStream()));
					zipOutputStream.putNextEntry(new ZipEntry("report.txt"));
					zipOutputStream.write(reportBytes);
					zipOutputStream.closeEntry();
				})
				.build();
	}

	public static MarshaledResponse onePageAtATime(Providers providers, List<Integer> pageNumbers) {
		return MarshaledResponse.withStatusCode(200)
				.stream(responseStream -> {
					for (int pageNumber : pageNumbers)
						responseStream.using(() -> providers.openPage(pageNumber), page -> {
							for (String row : page)
								responseStream.writeUtf8(row);
						});
				})
				.build();
	}

	public static MarshaledResponse pageAndParser(Providers providers, List<Integer> pageNumbers) {
		return MarshaledResponse.withStatusCode(200)
				.stream(responseStream -> {
					for (int pageNumber : pageNumbers)
						responseStream.using(() -> providers.openPage(pageNumber), page -> {
							// The parser borrows page. It does not independently close its input.
							var rowParser = responseStream.own(providers.openParser(page));
							for (String row : rowParser)
								responseStream.writeUtf8(row);
							// This lexical lifetime closes rowParser first, then page.
						});
				})
				.build();
	}

	public static MarshaledResponse lexicalSeparateAbort(Providers providers, List<Integer> pageNumbers) {
		return MarshaledResponse.withStatusCode(200)
				.stream(responseStream -> {
					for (int pageNumber : pageNumbers)
						responseStream.using(() -> providers.openAbortablePage(pageNumber),
								Providers.AbortablePage::cancel, page -> {
									for (String row : page)
										responseStream.writeUtf8(row);
								});
				})
				.build();
	}

	public static SseHandshakeResult.Accepted subscription(Providers providers, String topic) {
		SseClientInitializer sseClientInitializer = sseUnicaster -> {
			// Initializer return does not end this subscription's connection lifetime.
			sseUnicaster.open(() -> providers.subscribe(topic, sseUnicaster::unicastEvent));
			sseUnicaster.onTermination(providers::recordTermination);
		};
		return SseHandshakeResult.Accepted.builder()
				.clientInitializer(sseClientInitializer)
				.build();
	}

	public static List<MarshaledResponse> checkedIoAdapters(Path path, Providers providers) {
		// IOException propagates through the checked factory without a wrapping lambda.
		StreamingResponseBody fromFile = StreamingResponseBody.fromInputStream(() -> Files.newInputStream(path));
		StreamingResponseBody fromReader = StreamingResponseBody.fromReader(
				() -> Files.newBufferedReader(path, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
		StreamingResponseBody inputStreamBody = StreamingResponseBody.withInputStream(providers::openInputStream)
				.bufferSizeInBytes(16 * 1024)
				.build();
		StreamingResponseBody readerBody = StreamingResponseBody.withReader(providers::openReader, StandardCharsets.UTF_8)
				.bufferSizeInCharacters(8192)
				.build();
		return List.of(
				MarshaledResponse.withStatusCode(200).streamingResponseBody(fromFile).build(),
				MarshaledResponse.withStatusCode(200).streamingResponseBody(fromReader).build(),
				MarshaledResponse.withStatusCode(200).streamingResponseBody(inputStreamBody).build(),
				MarshaledResponse.withStatusCode(200).streamingResponseBody(readerBody).build());
	}

	public static List<StreamingResponseBody> checkedFactoryGetters(
			StreamingResponseBody.InputStreamBody inputStreamBody, StreamingResponseBody.ReaderBody readerBody) {
		StreamResourceFactory<? extends InputStream> inputStreamFactory = inputStreamBody.getInputStreamFactory();
		StreamResourceFactory<? extends Reader> readerFactory = readerBody.getReaderFactory();
		return List.of(StreamingResponseBody.fromInputStream(inputStreamFactory),
				StreamingResponseBody.fromReader(readerFactory, StandardCharsets.UTF_8));
	}

	public static MarshaledResponse factoryVarianceAndInference(Providers providers, String prompt) {
		return MarshaledResponse.withStatusCode(200).stream(responseStream -> {
			StreamResourceFactory<Providers.StreamingCall> streamingCallFactory = () -> providers.newStreamingCall(prompt);
			ResponseStream.ResourceAborter<Providers.AbortableResource> resourceAborter = Providers.AbortableResource::cancel;
			var streamingCall = responseStream.open(streamingCallFactory, resourceAborter);
			var upstream = responseStream.open(streamingCall::execute);
			for (String delta : upstream)
				responseStream.writeUtf8(delta);

			// var must preserve DetailedPage, rather than widen to AutoCloseable.
			var detailedPage = responseStream.open(providers::openDetailedPage);
			responseStream.writeUtf8(detailedPage.getDetail());
			StreamResourceFactory<? extends Providers.Page> pageFactory = providers::openDetailedPage;
			Providers.Page page = responseStream.open(pageFactory);
			responseStream.writeUtf8(Integer.toString(page.getPageNumber()));
			ResponseStream.ResourceConsumer<AutoCloseable> resourceConsumer = resource -> responseStream.writeUtf8(resource.toString());
			responseStream.using(pageFactory, resourceConsumer);
			responseStream.using(streamingCallFactory, resourceAborter, resourceConsumer);
		}).build();
	}

	public static MarshaledResponse writerAndCopier() {
		StreamingResponseWriter streamingResponseWriter = responseStream -> responseStream.writeUtf8("copied response\n");
		MarshaledResponse template = MarshaledResponse.withStatusCode(200).headers(textHeaders()).build();
		return template.copy().stream(streamingResponseWriter).finish();
	}

	public static MarshaledResponse writerDescriptor() {
		StreamingResponseWriter streamingResponseWriter = responseStream -> responseStream.writeUtf8("descriptor\n");
		return MarshaledResponse.withStatusCode(200)
				.streamingResponseBody(StreamingResponseBody.fromWriter(streamingResponseWriter))
				.build();
	}

	public static void nativeOutputAndMetadata(ResponseStream responseStream, byte[] bytes) throws Exception {
		Request request = responseStream.getRequest();
		CancelationToken cancelationToken = responseStream.getCancelationToken();
		Optional<Instant> deadline = responseStream.getDeadline();
		Optional<Duration> idleTimeout = responseStream.getIdleTimeout();
		cancelationToken.throwIfCanceled();
		responseStream.write(bytes);
		responseStream.write(bytes, 0, bytes.length);
		responseStream.write(ByteBuffer.wrap(bytes));
		responseStream.writeUtf8("output\n");
		responseStream.flush();
		responseStream.isOpen();
	}

	/** No throws declaration: registration removal is unchecked. */
	public static void registrationTypes(CancelationToken cancelationToken, SseUnicaster sseUnicaster, Providers providers) {
		CallbackRegistration cancelationRegistration = cancelationToken.onCancel(() -> {});
		cancelationRegistration.close();
		CallbackRegistration terminationRegistration = sseUnicaster.onTermination(providers::recordTermination);
		terminationRegistration.close();
		Request request = sseUnicaster.getRequest();
		sseUnicaster.isOpen();
	}

	private static Map<String, Set<String>> textHeaders() {
		return Map.of("Content-Type", Set.of("text/plain; charset=UTF-8"));
	}
}
