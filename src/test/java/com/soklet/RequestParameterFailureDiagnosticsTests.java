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

import com.soklet.annotation.GET;
import com.soklet.annotation.POST;
import com.soklet.annotation.PathParameter;
import com.soklet.annotation.RequestBody;
import com.soklet.converter.ValueConversionException;
import com.soklet.converter.ValueConverter;
import com.soklet.converter.ValueConverterRegistry;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

final class RequestParameterFailureDiagnosticsTests {
	@Test
	void uncheckedBindingCollaboratorFailuresRemainServerErrorsAndObservable() {
		String pathSecret = "path-secret-7a912fe4";
		String bodySecret = "body-secret-7a912fe4";
		IllegalStateException pathFailure = new IllegalStateException(
				"path-converter-internal-failure");
		IllegalStateException bodyFailure = new IllegalStateException(
				"body-marshaler-internal-failure");
		List<LogEvent> logEvents = new CopyOnWriteArrayList<>();
		ValueConverter<String, Integer> converter =
				new FailingPathConverter(pathFailure);

		SimulatorConfig config = SimulatorConfig.builder()
				.httpServer()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(FailureResource.class)))
				.valueConverterRegistry(ValueConverterRegistry
						.fromDefaultsSupplementedBy(Set.of(converter)))
				.requestBodyMarshaler((request, resourceMethod, parameter,
						requestBodyType) -> { throw bodyFailure; })
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
						logEvents.add(logEvent);
					}
				})
				.build();

		SokletSimulator.run(config, simulator -> {
			HttpRequestResult pathResult = simulator.performHttpRequest(
					Request.withPath(HttpMethod.GET,
							"/binding-failure/path/" + pathSecret).build());
			Assertions.assertEquals(500,
					pathResult.getMarshaledResponse().getStatusCode());
			assertGenericServerError(pathResult, pathSecret);

			HttpRequestResult bodyResult = simulator.performHttpRequest(
					Request.withPath(HttpMethod.POST, "/binding-failure/body")
							.body(bodySecret.getBytes(StandardCharsets.UTF_8))
							.build());
			Assertions.assertEquals(500,
					bodyResult.getMarshaledResponse().getStatusCode());
			assertGenericServerError(bodyResult, bodySecret);
		});

		List<LogEvent> processingFailures = logEvents.stream()
				.filter(event -> event.getLogEventType()
						== LogEventType.REQUEST_PROCESSING_FAILED)
				.toList();
		Assertions.assertEquals(2, processingFailures.size(),
				logEvents::toString);
		Assertions.assertSame(pathFailure,
				processingFailures.get(0).getThrowable().orElseThrow().getCause());
		Assertions.assertSame(bodyFailure,
				processingFailures.get(1).getThrowable().orElseThrow().getCause());
		for (LogEvent event : processingFailures) {
			Assertions.assertFalse(event.getMessage().contains(pathSecret));
			Assertions.assertFalse(event.getMessage().contains(bodySecret));
		}
	}

	private static void assertGenericServerError(@NonNull HttpRequestResult result,
			@NonNull String secret) {
		String body = new String(result.getMarshaledResponse().bodyBytesOrEmpty(),
				StandardCharsets.UTF_8);
		Assertions.assertEquals("HTTP 500: Internal HttpServer Error", body);
		Assertions.assertFalse(body.contains(secret));
	}

	public static final class FailureResource {
		@GET("/binding-failure/path/{value}")
		public void path(@PathParameter Integer value) {
		}

		@POST("/binding-failure/body")
		public void body(@RequestBody Integer value) {
		}
	}

	private static final class FailingPathConverter
			implements ValueConverter<String, Integer> {
		@NonNull
		private final RuntimeException failure;

		private FailingPathConverter(@NonNull RuntimeException failure) {
			this.failure = failure;
		}

		@Override
		@NonNull
		public Optional<@NonNull Integer> convert(@Nullable String from)
				throws ValueConversionException {
			throw this.failure;
		}

		@Override
		@NonNull
		public Type getFromType() {
			return String.class;
		}

		@Override
		@NonNull
		public Type getToType() {
			return Integer.class;
		}
	}
}
