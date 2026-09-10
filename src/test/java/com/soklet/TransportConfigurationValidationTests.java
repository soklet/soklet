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

import com.soklet.internal.microhttp.OptionsBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;

final class TransportConfigurationValidationTests {
	@Test
	void publicHttpBuilderRejectsUnserviceableValuesAtConfigurationTime() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> HttpServer.withPort(-1));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> HttpServer.withPort(65_536));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> HttpServer.withPort(0).port(65_536));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> HttpServer.withPort(0).concurrency(0).build());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> HttpServer.withPort(0).requestReadBufferSizeInBytes(0).build());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> HttpServer.withPort(0).socketSelectTimeout(Duration.ZERO).build());
	}

	@Test
	void publicSseBuilderRejectsOutOfRangePortsAtConfigurationTime() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> SseServer.withPort(-1));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> SseServer.withPort(65_536));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> SseServer.withPort(0).port(65_536));
	}

	@Test
	void microhttpOptionsRejectInvalidLoopInputsBeforeStartup() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> OptionsBuilder.newBuilder().withConcurrency(0).build());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> OptionsBuilder.newBuilder().withReadBufferSize(0).build());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> OptionsBuilder.newBuilder().withResolution(Duration.ZERO).build());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> OptionsBuilder.newBuilder().withResolution(null).build());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> OptionsBuilder.newBuilder().withPort(65_536).build());
	}
}
