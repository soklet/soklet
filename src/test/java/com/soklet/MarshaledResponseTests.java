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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static java.nio.file.StandardOpenOption.READ;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class MarshaledResponseTests {
	@Test
	public void byte_array_body_is_exposed_as_body_descriptor() {
		byte[] bytes = new byte[]{1, 2, 3};

		MarshaledResponse response = MarshaledResponse.withStatusCode(200)
				.body(bytes)
				.build();

		Assertions.assertEquals(Long.valueOf(3), response.getBodyLength());
		Assertions.assertTrue(response.getBody().orElseThrow() instanceof MarshaledResponseBody.Bytes);
		Assertions.assertSame(bytes, ((MarshaledResponseBody.Bytes) response.getBody().orElseThrow()).getBytes());
	}

	@Test
	public void byte_array_body_null_removes_body() {
		MarshaledResponse response = MarshaledResponse.withStatusCode(200)
				.body(new byte[]{1, 2, 3})
				.body((byte[]) null)
				.build();

		Assertions.assertTrue(response.getBody().isEmpty());
		Assertions.assertEquals(Long.valueOf(0), response.getBodyLength());
	}

	@Test
	public void body_descriptor_can_be_set_directly() {
		byte[] bytes = new byte[]{4, 5};
		MarshaledResponseBody body = new MarshaledResponseBody.Bytes(bytes);

		MarshaledResponse response = MarshaledResponse.withStatusCode(200)
				.body(body)
				.build();

		Assertions.assertEquals(Long.valueOf(2), response.getBodyLength());
		Assertions.assertSame(body, response.getBody().orElseThrow());
	}

	@Test
	public void body_descriptor_null_removes_body() {
		MarshaledResponse response = MarshaledResponse.withStatusCode(200)
				.body(new MarshaledResponseBody.Bytes(new byte[]{1, 2, 3}))
				.body((MarshaledResponseBody) null)
				.build();

		Assertions.assertTrue(response.getBody().isEmpty());
		Assertions.assertEquals(Long.valueOf(0), response.getBodyLength());
	}

	@Test
	public void nullable_body_sources_remove_body(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("example.txt");
		Files.writeString(file, "abcdef", StandardCharsets.UTF_8);

		MarshaledResponse pathResponse = MarshaledResponse.withStatusCode(200)
				.body(file)
				.body((Path) null)
				.build();

		Assertions.assertTrue(pathResponse.getBody().isEmpty());
		Assertions.assertEquals(Long.valueOf(0), pathResponse.getBodyLength());

		MarshaledResponse byteBufferResponse = MarshaledResponse.withStatusCode(200)
				.body(ByteBuffer.wrap(new byte[]{1, 2, 3}))
				.body((ByteBuffer) null)
				.build();

		Assertions.assertTrue(byteBufferResponse.getBody().isEmpty());
		Assertions.assertEquals(Long.valueOf(0), byteBufferResponse.getBodyLength());
	}

	@Test
	public void multi_argument_body_sources_reject_null_source() {
		Assertions.assertThrows(NullPointerException.class,
				() -> MarshaledResponse.withStatusCode(200).body((Path) null, 0L, 3L));

		Assertions.assertThrows(NullPointerException.class,
				() -> MarshaledResponse.withStatusCode(200).body((FileChannel) null, 0L, 3L, true));
	}

	@Test
	public void nullable_byte_array_body_can_flow_through_with_response_builder() {
		byte[] body = null;

		MarshaledResponse response = MarshaledResponse.withResponse(Response.fromStatusCode(204))
				.headers(Map.of("Content-Type", Set.of("application/json;charset=UTF-8")))
				.body(body)
				.build();

		Assertions.assertEquals(Integer.valueOf(204), response.getStatusCode());
		Assertions.assertEquals(Set.of("application/json;charset=UTF-8"), response.getHeaders().get("Content-Type"));
		Assertions.assertTrue(response.getBody().isEmpty());
		Assertions.assertEquals(Long.valueOf(0), response.getBodyLength());
	}

	@Test
	public void missing_body_has_zero_length() {
		MarshaledResponse response = MarshaledResponse.fromStatusCode(204);

		Assertions.assertTrue(response.getBody().isEmpty());
		Assertions.assertEquals(Long.valueOf(0), response.getBodyLength());
	}

	@Test
	public void copier_preserves_and_can_clear_body_descriptor() {
		MarshaledResponse response = MarshaledResponse.withStatusCode(200)
				.body(new byte[]{1})
				.build();

		MarshaledResponse copied = response.copy().finish();
		Assertions.assertTrue(copied.getBody().orElseThrow() instanceof MarshaledResponseBody.Bytes);
		Assertions.assertEquals(Long.valueOf(1), copied.getBodyLength());

		MarshaledResponse cleared = response.copy()
				.withoutBody()
				.finish();

		Assertions.assertTrue(cleared.getBody().isEmpty());
		Assertions.assertEquals(Long.valueOf(0), cleared.getBodyLength());
	}

	@Test
	public void file_body_is_exposed_as_body_descriptor(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("example.txt");
		Files.writeString(file, "abcdef", StandardCharsets.UTF_8);

		MarshaledResponse response = MarshaledResponse.withStatusCode(200)
				.body(file)
				.build();

		Assertions.assertEquals(Long.valueOf(6), response.getBodyLength());
		Assertions.assertTrue(response.getBody().orElseThrow() instanceof MarshaledResponseBody.File);
		MarshaledResponseBody.File body = (MarshaledResponseBody.File) response.getBody().orElseThrow();
		Assertions.assertEquals(file, body.getPath());
		Assertions.assertEquals(Long.valueOf(0), body.getOffset());
		Assertions.assertEquals(Long.valueOf(6), body.getCount());
		Assertions.assertEquals("abcdef", new String(response.bodyBytesOrEmpty(), StandardCharsets.UTF_8));
	}

	@Test
	public void file_slice_body_uses_requested_offset_and_count(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("example.txt");
		Files.writeString(file, "abcdef", StandardCharsets.UTF_8);

		MarshaledResponse response = MarshaledResponse.withStatusCode(206)
				.body(file, 2L, 3L)
				.build();

		Assertions.assertEquals(Long.valueOf(3), response.getBodyLength());
		MarshaledResponseBody.File body = (MarshaledResponseBody.File) response.getBody().orElseThrow();
		Assertions.assertEquals(Long.valueOf(2), body.getOffset());
		Assertions.assertEquals(Long.valueOf(3), body.getCount());
		Assertions.assertEquals("cde", new String(response.bodyBytesOrEmpty(), StandardCharsets.UTF_8));
	}

	@Test
	public void file_channel_body_respects_close_on_complete_when_materialized(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("example.txt");
		Files.writeString(file, "abcdef", StandardCharsets.UTF_8);
		FileChannel fileChannel = FileChannel.open(file, READ);

		MarshaledResponse response = MarshaledResponse.withStatusCode(200)
				.body(fileChannel, 1L, 4L, true)
				.build();

		Assertions.assertEquals(Long.valueOf(4), response.getBodyLength());
		Assertions.assertEquals("bcde", new String(response.bodyBytesOrEmpty(), StandardCharsets.UTF_8));
		Assertions.assertFalse(fileChannel.isOpen());
	}

	@Test
	public void without_body_closes_owned_file_channel_body(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("example.txt");
		Files.writeString(file, "abcdef", StandardCharsets.UTF_8);
		FileChannel fileChannel = FileChannel.open(file, READ);

		try {
			MarshaledResponse response = MarshaledResponse.withStatusCode(200)
					.body(fileChannel, 1L, 4L, true)
					.withoutBody()
					.build();

			Assertions.assertTrue(response.getBody().isEmpty());
			Assertions.assertEquals(Long.valueOf(0), response.getBodyLength());
			Assertions.assertFalse(fileChannel.isOpen());
		} finally {
			if (fileChannel.isOpen())
				fileChannel.close();
		}
	}

	@Test
	public void without_body_does_not_close_borrowed_file_channel_body(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("example.txt");
		Files.writeString(file, "abcdef", StandardCharsets.UTF_8);
		FileChannel fileChannel = FileChannel.open(file, READ);

		try {
			MarshaledResponse response = MarshaledResponse.withStatusCode(200)
					.body(fileChannel, 1L, 4L, false)
					.withoutBody()
					.build();

			Assertions.assertTrue(response.getBody().isEmpty());
			Assertions.assertEquals(Long.valueOf(0), response.getBodyLength());
			Assertions.assertTrue(fileChannel.isOpen());
		} finally {
			fileChannel.close();
		}
	}

	@Test
	public void default_head_response_closes_owned_file_channel_body(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("example.txt");
		Files.writeString(file, "abcdef", StandardCharsets.UTF_8);
		FileChannel fileChannel = FileChannel.open(file, READ);

		try {
			MarshaledResponse getResponse = MarshaledResponse.withStatusCode(200)
					.body(fileChannel, 0L, 6L, true)
					.build();
			MarshaledResponse headResponse = DefaultResponseMarshaler.defaultInstance().forHead(
					Request.withPath(HttpMethod.HEAD, "/example.txt").build(), getResponse);

			Assertions.assertTrue(headResponse.getBody().isEmpty());
			Assertions.assertEquals(Set.of("6"), headResponse.getHeaders().get("Content-Length"));
			Assertions.assertFalse(fileChannel.isOpen());
		} finally {
			if (fileChannel.isOpen())
				fileChannel.close();
		}
	}

	@Test
	public void file_body_rejects_non_regular_path(@TempDir Path tempDir) {
		Assertions.assertThrows(IllegalArgumentException.class, () -> MarshaledResponse.withStatusCode(200).body(tempDir));
	}

	@Test
	public void byte_buffer_body_uses_remaining_slice_without_mutating_caller() {
		ByteBuffer buffer = ByteBuffer.wrap("abcdef".getBytes(StandardCharsets.UTF_8));
		buffer.position(2);
		buffer.limit(5);

		MarshaledResponse response = MarshaledResponse.withStatusCode(200)
				.body(buffer)
				.build();

		Assertions.assertEquals(2, buffer.position());
		Assertions.assertEquals(5, buffer.limit());
		Assertions.assertEquals(Long.valueOf(3), response.getBodyLength());
		Assertions.assertTrue(response.getBody().orElseThrow() instanceof MarshaledResponseBody.ByteBuffer);
		MarshaledResponseBody.ByteBuffer body = (MarshaledResponseBody.ByteBuffer) response.getBody().orElseThrow();
		Assertions.assertTrue(body.getBuffer().isReadOnly());
		Assertions.assertEquals("cde", new String(response.bodyBytesOrEmpty(), StandardCharsets.UTF_8));
	}
}
