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
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;

import static java.util.Objects.requireNonNull;

/**
 * One immutable typed schema after every registration-time check has passed.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpCompiledTypedSchema {
	@NonNull
	private final McpTypedSchemaShape shape;
	@NonNull
	private final McpJsonObject document;
	@NonNull
	private final McpToolSchemaProfileProgram program;
	@NonNull
	private final McpMirroredHeaderPlan mirroredHeaderPlan;
	private final byte @NonNull [] serializedDocument;

	McpCompiledTypedSchema(@NonNull McpTypedSchemaShape shape,
			@NonNull McpJsonObject document,
			@NonNull McpToolSchemaProfileProgram program,
			@NonNull McpMirroredHeaderPlan mirroredHeaderPlan,
			byte @NonNull [] serializedDocument) {
		this.shape = requireNonNull(shape);
		this.document = requireNonNull(document);
		this.program = requireNonNull(program);
		this.mirroredHeaderPlan = requireNonNull(mirroredHeaderPlan);
		this.serializedDocument = requireNonNull(serializedDocument).clone();
	}

	@NonNull
	McpTypedSchemaShape shape() {
		return shape;
	}

	@NonNull
	McpJsonObject document() {
		return document;
	}

	@NonNull
	McpToolSchemaProfileProgram program() {
		return program;
	}

	@NonNull
	McpMirroredHeaderPlan mirroredHeaderPlan() {
		return mirroredHeaderPlan;
	}

	byte @NonNull [] serializedDocument() {
		return serializedDocument.clone();
	}

	int serializedDocumentLength() {
		return serializedDocument.length;
	}
}
