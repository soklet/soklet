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

package com.soklet.internal.mcp.schema;

import com.soklet.internal.mcp.protocol.McpJsonCodec;
import com.soklet.internal.mcp.protocol.McpJsonLimits;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpTypedSchemaCompilerTests {
	@Test
	void retainsAProfileCompiledProductionSerializableDocument() {
		FakeTypeModel model = new FakeTypeModel();
		model.add("string", new McpTypedTypeDescriptor.Scalar<>(
				McpTypedSchemaScalar.STRING));
		model.add("arguments", new McpTypedTypeDescriptor.RecordValue<>(
				"example.Arguments", List.of(component("query", "string"))));
		McpJsonCodec codec = new McpJsonCodec(productionJsonLimits());
		McpCompiledTypedSchema compiled = compiler(model, codec)
				.compileToolInput("arguments");

		assertEquals(compiled.document(), compiled.program().document());
		assertEquals(compiled.document(), codec.parse(
				compiled.serializedDocument()));
		assertEquals(compiled.serializedDocument().length,
				compiled.serializedDocumentLength());

		byte[] first = compiled.serializedDocument();
		byte[] second = compiled.serializedDocument();
		assertNotSame(first, second);
		assertArrayEquals(first, second);
		first[0] ^= 1;
		assertArrayEquals(second, compiled.serializedDocument());
	}

	@Test
	void generatedOutputByteLimitIsEnforcedDuringCompilation() {
		FakeTypeModel model = new FakeTypeModel();
		model.add("string", new McpTypedTypeDescriptor.Scalar<>(
				McpTypedSchemaScalar.STRING));
		model.add("arguments", new McpTypedTypeDescriptor.RecordValue<>(
				"example.Arguments", List.of(new McpTypedTypeDescriptor
						.RecordComponent<>("query", "string", Optional.empty(),
						Optional.of("x".repeat(200)), Optional.empty()))));
		McpJsonLimits limits = new McpJsonLimits(1_024, 64, 1_024, 1_024,
				128, 1_000, 1_000, 100);

		McpTypedSchemaException exception = assertThrows(
				McpTypedSchemaException.class,
				() -> compiler(model, new McpJsonCodec(limits))
						.compileToolInput("arguments"));
		assertEquals(McpTypedSchemaException.Reason.INVALID_DESCRIPTOR,
				exception.reason());
		assertEquals("$", exception.path().toString());
	}

	@Test
	void malformedUnicodeMetadataIsRejectedDuringCompilation() {
		FakeTypeModel model = new FakeTypeModel();
		model.add("string", new McpTypedTypeDescriptor.Scalar<>(
				McpTypedSchemaScalar.STRING));
		model.add("arguments", new McpTypedTypeDescriptor.RecordValue<>(
				"example.Arguments", List.of(new McpTypedTypeDescriptor
						.RecordComponent<>("query", "string", Optional.empty(),
						Optional.of("bad\uD800text"), Optional.empty()))));

		McpTypedSchemaException exception = assertThrows(
				McpTypedSchemaException.class,
				() -> compiler(model, new McpJsonCodec(productionJsonLimits()))
						.compileToolInput("arguments"));
		assertEquals(McpTypedSchemaException.Reason.INVALID_DESCRIPTOR,
				exception.reason());
	}

	@Test
	void useValidationRunsBeforeACompiledSchemaCanEscape() {
		FakeTypeModel model = new FakeTypeModel();
		model.add("string", new McpTypedTypeDescriptor.Scalar<>(
				McpTypedSchemaScalar.STRING));
		model.add("arguments", new McpTypedTypeDescriptor.RecordValue<>(
				"example.Arguments", List.of(new McpTypedTypeDescriptor
						.RecordComponent<>("query", "string", Optional.empty(),
						Optional.empty(), Optional.of("not a header")))));

		McpSchemaCompilationException exception = assertThrows(
				McpSchemaCompilationException.class,
				() -> compiler(model, new McpJsonCodec(productionJsonLimits()))
						.compileToolInput("arguments"));
		assertEquals(McpSchemaCompilationException.Kind.INVALID_KEYWORD_VALUE,
				exception.kind());
		assertEquals(Optional.of("x-mcp-header"), exception.keyword());
	}

	private McpTypedSchemaCompiler<String> compiler(FakeTypeModel model,
			McpJsonCodec codec) {
		return new McpTypedSchemaCompiler<>(model,
				McpSchemaCompilationLimits.productionDefaults(), codec);
	}

	private McpJsonLimits productionJsonLimits() {
		return new McpJsonLimits(4 * 1_024 * 1_024, 128,
				1_024 * 1_024, 1_024 * 1_024, 1_024, 10_000,
				100_000, 4 * 1_024 * 1_024);
	}

	private McpTypedTypeDescriptor.RecordComponent<String> component(
			String name, String type) {
		return McpTypedTypeDescriptor.RecordComponent.fromNameAndType(name,
				type);
	}

	private static final class FakeTypeModel
			implements McpTypedTypeModel<String> {
		private final Map<String, McpTypedTypeDescriptor<String>> descriptors =
				new LinkedHashMap<>();

		private void add(String type,
				McpTypedTypeDescriptor<String> descriptor) {
			descriptors.put(type, descriptor);
		}

		@Override
		public McpTypedTypeDescriptor<String> describe(String type) {
			return descriptors.get(type);
		}
	}
}
