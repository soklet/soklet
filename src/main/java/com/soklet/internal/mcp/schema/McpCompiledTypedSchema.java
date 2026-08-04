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

import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpMirroredHeaderPlan;

import static java.util.Objects.requireNonNull;

/**
 * One immutable typed schema after every registration-time check has passed.
 */
final class McpCompiledTypedSchema {
	private final McpTypedSchemaShape shape;
	private final McpJsonObject document;
	private final McpToolSchemaProfileProgram program;
	private final McpMirroredHeaderPlan mirroredHeaderPlan;
	private final byte[] serializedDocument;

	McpCompiledTypedSchema(McpTypedSchemaShape shape, McpJsonObject document,
			McpToolSchemaProfileProgram program,
			McpMirroredHeaderPlan mirroredHeaderPlan, byte[] serializedDocument) {
		this.shape = requireNonNull(shape);
		this.document = requireNonNull(document);
		this.program = requireNonNull(program);
		this.mirroredHeaderPlan = requireNonNull(mirroredHeaderPlan);
		this.serializedDocument = requireNonNull(serializedDocument).clone();
	}

	McpTypedSchemaShape shape() {
		return shape;
	}

	McpJsonObject document() {
		return document;
	}

	McpToolSchemaProfileProgram program() {
		return program;
	}

	McpMirroredHeaderPlan mirroredHeaderPlan() {
		return mirroredHeaderPlan;
	}

	byte[] serializedDocument() {
		return serializedDocument.clone();
	}

	int serializedDocumentLength() {
		return serializedDocument.length;
	}
}
