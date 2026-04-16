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

import javax.annotation.concurrent.ThreadSafe;

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
}
