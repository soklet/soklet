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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.concurrent.ThreadSafe;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class StreamingResponseBodyFactoryTests {
	@Test
	public void checked_input_stream_factory_is_lazy_and_reusable(@TempDir Path directory) throws Exception {
		Path sourcePath = Files.writeString(directory.resolve("source.txt"), "response", StandardCharsets.UTF_8);
		CheckedFileSources checkedFileSources = new CheckedFileSources(sourcePath);
		StreamResourceFactory<FileInputStream> inputStreamFactory = checkedFileSources::openInputStream;

		StreamingResponseBody.InputStreamBody inputStreamBody = (StreamingResponseBody.InputStreamBody)
				StreamingResponseBody.fromInputStream(inputStreamFactory);
		StreamingResponseBody.InputStreamBody configuredBody = (StreamingResponseBody.InputStreamBody)
				StreamingResponseBody.withInputStream(inputStreamFactory).bufferSizeInBytes(3).build();
		StreamResourceFactory<? extends InputStream> retainedFactory = inputStreamBody.getInputStreamFactory();

		Assertions.assertEquals(0, checkedFileSources.inputStreamOpenCount.get());
		Assertions.assertSame(inputStreamFactory, retainedFactory);
		Assertions.assertSame(inputStreamFactory, configuredBody.getInputStreamFactory());
		Assertions.assertEquals(Integer.valueOf(3), configuredBody.getBufferSizeInBytes());

		try (InputStream firstInputStream = retainedFactory.open();
				 InputStream secondInputStream = retainedFactory.open()) {
			Assertions.assertNotSame(firstInputStream, secondInputStream);
			Assertions.assertEquals(2, checkedFileSources.inputStreamOpenCount.get());
			Assertions.assertEquals('r', firstInputStream.read());
			Assertions.assertEquals("response", new String(secondInputStream.readAllBytes(), StandardCharsets.UTF_8));
			Assertions.assertEquals("esponse", new String(firstInputStream.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	@Test
	public void checked_reader_factory_is_lazy_and_reusable(@TempDir Path directory) throws Exception {
		Path sourcePath = Files.writeString(directory.resolve("source.txt"), "response", StandardCharsets.UTF_8);
		CheckedFileSources checkedFileSources = new CheckedFileSources(sourcePath);
		StreamResourceFactory<BufferedReader> readerFactory = checkedFileSources::openReader;

		StreamingResponseBody.ReaderBody readerBody = (StreamingResponseBody.ReaderBody)
				StreamingResponseBody.fromReader(readerFactory, StandardCharsets.UTF_8);
		StreamingResponseBody.ReaderBody configuredBody = (StreamingResponseBody.ReaderBody)
				StreamingResponseBody.withReader(readerFactory, StandardCharsets.UTF_8)
						.bufferSizeInCharacters(3).build();
		StreamResourceFactory<? extends Reader> retainedFactory = readerBody.getReaderFactory();

		Assertions.assertEquals(0, checkedFileSources.readerOpenCount.get());
		Assertions.assertSame(readerFactory, retainedFactory);
		Assertions.assertSame(readerFactory, configuredBody.getReaderFactory());
		Assertions.assertEquals(Integer.valueOf(3), configuredBody.getBufferSizeInCharacters());

		try (Reader firstReader = retainedFactory.open();
				 Reader secondReader = retainedFactory.open()) {
			Assertions.assertNotSame(firstReader, secondReader);
			Assertions.assertEquals(2, checkedFileSources.readerOpenCount.get());
			Assertions.assertEquals('r', firstReader.read());
			Assertions.assertEquals("response", readRemaining(secondReader));
			Assertions.assertEquals("esponse", readRemaining(firstReader));
		}
	}

	@Test
	public void checked_factory_failures_are_not_wrapped() {
		IOException acquisitionFailure = new IOException("Source acquisition failed");
		FailingSources failingSources = new FailingSources(acquisitionFailure);
		StreamingResponseBody.InputStreamBody inputStreamBody = (StreamingResponseBody.InputStreamBody)
				StreamingResponseBody.fromInputStream(failingSources::openInputStream);
		StreamingResponseBody.ReaderBody readerBody = (StreamingResponseBody.ReaderBody)
				StreamingResponseBody.fromReader(failingSources::openReader, StandardCharsets.UTF_8);

		Assertions.assertSame(acquisitionFailure,
				Assertions.assertThrows(IOException.class, () -> inputStreamBody.getInputStreamFactory().open()));
		Assertions.assertSame(acquisitionFailure,
				Assertions.assertThrows(IOException.class, () -> readerBody.getReaderFactory().open()));
	}

	@Test
	public void input_stream_buffer_settings_remain_snapshots_with_default_reset() {
		StreamingResponseBody.InputStreamBuilder builder = StreamingResponseBody.withInputStream(InputStream::nullInputStream)
				.bufferSizeInBytes(7);
		StreamingResponseBody.InputStreamBody configuredBody = (StreamingResponseBody.InputStreamBody) builder.build();
		StreamingResponseBody.InputStreamBody defaultBody = (StreamingResponseBody.InputStreamBody)
				builder.bufferSizeInBytes(null).build();

		Assertions.assertEquals(Integer.valueOf(7), configuredBody.getBufferSizeInBytes());
		Assertions.assertEquals(StreamingResponseBody.DEFAULT_INPUT_STREAM_BUFFER_SIZE_IN_BYTES, defaultBody.getBufferSizeInBytes());
		Assertions.assertThrows(IllegalArgumentException.class, () -> builder.bufferSizeInBytes(0).build());
	}

	@Test
	public void reader_encoding_and_buffer_settings_remain_snapshots_with_default_reset() {
		StreamingResponseBody.ReaderBuilder builder = StreamingResponseBody.withReader(Reader::nullReader, StandardCharsets.US_ASCII)
				.bufferSizeInCharacters(7)
				.malformedInputAction(CodingErrorAction.IGNORE)
				.unmappableCharacterAction(CodingErrorAction.REPLACE);
		StreamingResponseBody.ReaderBody configuredBody = (StreamingResponseBody.ReaderBody) builder.build();
		StreamingResponseBody.ReaderBody defaultBody = (StreamingResponseBody.ReaderBody) builder
				.bufferSizeInCharacters(null)
				.malformedInputAction(null)
				.unmappableCharacterAction(null)
				.build();

		Assertions.assertEquals(Integer.valueOf(7), configuredBody.getBufferSizeInCharacters());
		Assertions.assertEquals(StandardCharsets.US_ASCII, configuredBody.getCharset());
		Assertions.assertSame(CodingErrorAction.IGNORE, configuredBody.getMalformedInputAction());
		Assertions.assertSame(CodingErrorAction.REPLACE, configuredBody.getUnmappableCharacterAction());
		CharsetEncoder firstEncoder = configuredBody.newEncoder();
		CharsetEncoder secondEncoder = configuredBody.newEncoder();
		Assertions.assertNotSame(firstEncoder, secondEncoder);
		Assertions.assertEquals(StandardCharsets.US_ASCII, firstEncoder.charset());
		Assertions.assertSame(CodingErrorAction.IGNORE, firstEncoder.malformedInputAction());
		Assertions.assertSame(CodingErrorAction.REPLACE, firstEncoder.unmappableCharacterAction());
		firstEncoder.onMalformedInput(CodingErrorAction.REPORT);
		Assertions.assertSame(CodingErrorAction.IGNORE, secondEncoder.malformedInputAction());

		Assertions.assertEquals(StreamingResponseBody.DEFAULT_READER_BUFFER_SIZE_IN_CHARACTERS, defaultBody.getBufferSizeInCharacters());
		Assertions.assertSame(StreamingResponseBody.DEFAULT_MALFORMED_INPUT_ACTION, defaultBody.getMalformedInputAction());
		Assertions.assertSame(StreamingResponseBody.DEFAULT_UNMAPPABLE_CHARACTER_ACTION, defaultBody.getUnmappableCharacterAction());
		Assertions.assertThrows(IllegalArgumentException.class, () -> builder.bufferSizeInCharacters(0).build());
	}

	private static String readRemaining(Reader reader) throws IOException {
		StringBuilder stringBuilder = new StringBuilder();
		char[] characters = new char[16];
		int count;

		while ((count = reader.read(characters)) != -1)
			stringBuilder.append(characters, 0, count);

		return stringBuilder.toString();
	}

	private static final class CheckedFileSources {
		private final Path sourcePath;
		private final AtomicInteger inputStreamOpenCount = new AtomicInteger();
		private final AtomicInteger readerOpenCount = new AtomicInteger();

		private CheckedFileSources(Path sourcePath) {
			this.sourcePath = sourcePath;
		}

		private FileInputStream openInputStream() throws IOException {
			this.inputStreamOpenCount.incrementAndGet();
			return new FileInputStream(this.sourcePath.toFile());
		}

		private BufferedReader openReader() throws IOException {
			this.readerOpenCount.incrementAndGet();
			return Files.newBufferedReader(this.sourcePath, StandardCharsets.UTF_8);
		}
	}

	private static final class FailingSources {
		private final IOException acquisitionFailure;

		private FailingSources(IOException acquisitionFailure) {
			this.acquisitionFailure = acquisitionFailure;
		}

		private InputStream openInputStream() throws IOException {
			throw this.acquisitionFailure;
		}

		private Reader openReader() throws IOException {
			throw this.acquisitionFailure;
		}
	}
}
