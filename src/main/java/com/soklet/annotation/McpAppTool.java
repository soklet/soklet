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

import com.soklet.McpAppToolMetadata;
import org.jspecify.annotations.NonNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Attaches MCP Apps metadata to an {@link McpTool} handler method.
 *
 * <p>The annotation processor requires {@code @McpTool} on the same method
 * and generates the equivalent {@link McpAppToolMetadata} registration.
 * Visibility and resource association do not grant authorization or prove
 * that an invocation originated from an authorized UI. Applications must
 * authorize every invocation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpAppTool {

	/**
	 * Associates an exact UI resource registered in the same endpoint.
	 * The processor applies the same URI validation as programmatic Apps
	 * metadata; endpoint construction validates the resource's eligibility.
	 *
	 * @return concrete normalized absolute ASCII {@code ui://} URI, or an
	 * empty string for no association
	 */
	@NonNull
	String resourceUri() default "";

	/**
	 * The tool's effective audiences, defaulting to both model and app.
	 * Explicitly supplying an empty array means neither audience; duplicate
	 * values are collapsed into the generated metadata's set.
	 *
	 * @return non-null audiences, stored in enum declaration order
	 */
	McpAppToolMetadata.@NonNull Visibility @NonNull [] visibility() default {
			McpAppToolMetadata.Visibility.MODEL, McpAppToolMetadata.Visibility.APP};
}
