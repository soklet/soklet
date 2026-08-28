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

package com.soklet.annotation;

import org.jspecify.annotations.NonNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Publishes and enforces an MCP custom mirrored header for one typed tool
 * input property.
 * <p>
 * The annotation value is the suffix of the corresponding
 * {@code Mcp-Param-*} request header. For example, {@code @McpHeader("Region")}
 * publishes {@code x-mcp-header: "Region"} on the derived property schema and
 * requires {@code Mcp-Param-Region} to agree with that property's request-body
 * value whenever the value is present and non-null. If the body property is
 * absent or {@code null}, the corresponding request header must also be absent;
 * a mirrored header never supplies or injects a tool argument.
 * <p>
 * A method-parameter declaration must also carry {@link McpToolArgument} and
 * belong to an {@link McpTool} method. A record-component declaration applies
 * when that component is reached from a typed tool input solely through record
 * properties; it may also carry {@link McpToolProperty} to customize its
 * published property metadata. Mirrored properties must derive to the JSON Schema type
 * {@code string}, {@code boolean}, or a JavaScript-safe {@code integer}. Soklet
 * rejects unsupported placements, other property types, invalid HTTP
 * field-name suffixes, and case-insensitive name collisions while deriving the
 * tool schema.
 * <p>
 * Mirrored headers can expose argument values to HTTP infrastructure. Do not
 * mirror credentials, secrets, or other sensitive values unless that exposure
 * is explicitly intended and protected by the deployment.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Target({ ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface McpHeader {
	/**
	 * The non-empty HTTP field-name token appended to {@code Mcp-Param-}.
	 *
	 * @return mirrored-header suffix
	 */
	@NonNull
	String value();
}
