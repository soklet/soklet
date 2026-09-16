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

import com.soklet.McpClientCapability;
import com.soklet.McpInputRequestType;
import com.soklet.annotation.McpMayRequestInput;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Guards the deliberate removal of deprecated client-input features. */
public class McpRetiredInputFeatureTests {
	@Test
	public void publicInputVocabularyContainsOnlyElicitation() {
		Assertions.assertEquals(List.of("ELICITATION_FORM", "ELICITATION_URL"),
				Arrays.stream(McpClientCapability.values()).map(Enum::name).toList());
		Assertions.assertEquals(List.of("ELICITATION_FORM", "ELICITATION_URL"),
				Arrays.stream(McpInputRequestType.values()).map(Enum::name).toList());
		for (String factory : List.of("fromRoots", "fromSampling"))
			Assertions.assertTrue(Arrays.stream(com.soklet.McpInputRequestDeclaration.class
					.getDeclaredMethods()).noneMatch(method -> method.getName().equals(factory)));
		Assertions.assertThrows(NoSuchMethodException.class,
				() -> McpMayRequestInput.class.getDeclaredMethod("samplingCapabilities"));
	}

	@Test
	public void internalDeclarationsCannotBypassTheRetiredMethodBoundary() {
		for (String method : List.of("roots/list", "sampling/createMessage")) {
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> new McpInputRequestDeclaration(method,
							Set.of(McpCoreClientCapability.ELICITATION_FORM),
							McpInputRequirement.CONDITIONAL));
		}
	}

	@Test
	public void retiredInputResponsesCannotEnterTheSupportedResponseUnion() {
		McpJsonCodec codec = new McpJsonCodec(McpJsonLimits.productionDefaults());
		for (String response : List.of(
				"{\"roots\":[]}",
				"{\"roots\":[{\"uri\":\"file:///project\"}]}",
				"{\"role\":\"assistant\",\"model\":\"test\",\"content\":{\"type\":\"text\",\"text\":\"answer\"}}")) {
			McpJsonValue value = codec.parse(response);
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> McpInputResponseValidator.validate(value));
			Assertions.assertFalse(McpInputResponseValidator.matches(
					McpInputRequestDeclaration.elicitationForm(McpInputRequirement.CONDITIONAL), value));
		}
		// Elicitation's open-object contract still preserves unrelated extension fields.
		Assertions.assertDoesNotThrow(() -> McpInputResponseValidator.validate(
				codec.parse("{\"action\":\"accept\",\"roots\":[],\"model\":\"extension-data\"}")));
	}
}
