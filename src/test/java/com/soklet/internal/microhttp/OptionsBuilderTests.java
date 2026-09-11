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

package com.soklet.internal.microhttp;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class OptionsBuilderTests {
	@Test
	public void explicitRequestBodyLimitIsIndependentOfSetterOrder() {
		Options aggregateThenBody = OptionsBuilder.newBuilder()
				.withMaxRequestSize(4_096)
				.withMaxRequestBodySize(1_024)
				.build();
		Options bodyThenAggregate = OptionsBuilder.newBuilder()
				.withMaxRequestBodySize(1_024)
				.withMaxRequestSize(4_096)
				.build();

		Assertions.assertEquals(4_096, aggregateThenBody.maxRequestSize());
		Assertions.assertEquals(1_024, aggregateThenBody.maxRequestBodySize());
		Assertions.assertEquals(aggregateThenBody, bodyThenAggregate);
	}

	@Test
	public void omittedRequestBodyLimitTracksAggregateRequestLimit() {
		Options options = OptionsBuilder.newBuilder()
				.withMaxRequestSize(2_048)
				.build();

		Assertions.assertEquals(2_048, options.maxRequestSize());
		Assertions.assertEquals(2_048, options.maxRequestBodySize());
	}

	@Test
	public void explicitBodyLimitAboveAggregateIsRejectedInEitherSetterOrder() {
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> OptionsBuilder.newBuilder()
						.withMaxRequestSize(1_024)
						.withMaxRequestBodySize(2_048)
						.build());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> OptionsBuilder.newBuilder()
						.withMaxRequestBodySize(2_048)
						.withMaxRequestSize(1_024)
						.build());
	}
}
