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

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Bounded conversion from Soklet's public immutable JSON values to their
 * internal protocol representation.
 *
 * <p>This type is public only so production code in other internal packages
 * can share one conversion boundary. Soklet excludes internal MCP packages
 * from its public Javadocs and compatibility surface. Conversion first walks
 * the public tree without allocating an internal tree, enforcing the
 * production depth and node limits. It then converts the proven-bounded tree
 * and runs the exact production JSON writer so string, number, token, Unicode,
 * and aggregate output-byte limits are enforced as well.</p>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpPublicJsonValueConverter {
	@NonNull
	private static final McpJsonLimits PRODUCTION_LIMITS =
			McpJsonLimits.productionDefaults();
	@NonNull
	private static final McpJsonCodec PRODUCTION_CODEC =
			new McpJsonCodec(PRODUCTION_LIMITS);

	private McpPublicJsonValueConverter() {
	}

	/**
	 * Converts one public JSON value under the production JSON limits.
	 *
	 * @param value public immutable JSON value
	 * @return equivalent internal immutable JSON value
	 * @throws NullPointerException if {@code value} is null
	 * @throws IllegalArgumentException if the value exceeds a production JSON
	 *                                  limit
	 */
	@NonNull
	public static McpJsonValue toInternal(
			com.soklet.@NonNull McpJsonValue value) {
		return toInternal(requireNonNull(value), PRODUCTION_LIMITS,
				PRODUCTION_CODEC);
	}

	/**
	 * Converts one public JSON object under the production JSON limits.
	 *
	 * @param value public immutable JSON object
	 * @return equivalent internal immutable JSON object
	 * @throws NullPointerException if {@code value} is null
	 * @throws IllegalArgumentException if the value exceeds a production JSON
	 *                                  limit
	 */
	@NonNull
	public static McpJsonObject toInternalObject(
			com.soklet.@NonNull McpJsonObject value) {
		return (McpJsonObject) toInternal(requireNonNull(value));
	}

	/**
	 * Performs the allocation-free production structural preflight and returns
	 * the exact number of JSON value nodes in the public tree.
	 *
	 * <p>Object member names are not value nodes, matching
	 * {@link McpJsonCodec}'s accounting. This method enforces only the production
	 * depth and node ceilings so callers can account for values before deriving
	 * wrappers or aggregate collections. {@link #toInternal(com.soklet.McpJsonValue)}
	 * remains the final authority for scalar, token, Unicode, and serialized-byte
	 * limits.</p>
	 *
	 * @param value public immutable JSON value
	 * @return exact JSON value-node count
	 * @throws NullPointerException if {@code value} is null
	 * @throws IllegalArgumentException if the value exceeds the production depth
	 *                                  or node limit
	 */
	public static int productionNodeCount(
			com.soklet.@NonNull McpJsonValue value) {
		return nodeCount(requireNonNull(value), PRODUCTION_LIMITS);
	}

	/**
	 * Requires an already-computed aggregate node count to fit the production
	 * JSON budget.
	 *
	 * <p>This is the companion to {@link #productionNodeCount} for callers that
	 * project typed application values into JSON. Those callers can count the
	 * eventual tree before allocating wrapper objects or mapped collections.</p>
	 *
	 * @param nodeCount eventual JSON value-node count
	 * @param description bounded value description for diagnostics
	 * @throws NullPointerException if {@code description} is null
	 * @throws IllegalArgumentException if the count is negative or exceeds the
	 *                                  production node limit
	 */
	public static void requireProductionNodeCount(long nodeCount,
			@NonNull String description) {
		requireNodeCount(nodeCount, requireNonNull(description), PRODUCTION_LIMITS);
	}

	/**
	 * Rejects a collection whose cheapest possible public JSON projection cannot
	 * fit within the production node budget.
	 *
	 * <p>This is a lower-bound check for callers that would otherwise allocate a
	 * complete derived collection before conversion. Exact tree and serialized
	 * output validation still occurs in {@link #toInternal(com.soklet.McpJsonValue)}.
	 * Arithmetic is arranged to avoid overflow even for attacker-controlled
	 * reported sizes.</p>
	 *
	 * @param size collection element count
	 * @param minimumNodesPerElement minimum JSON nodes contributed by each
	 *                               element
	 * @param fixedNodes JSON nodes present independently of collection elements
	 * @param description bounded collection description for diagnostics
	 * @throws NullPointerException if {@code description} is null
	 * @throws IllegalArgumentException if an accounting input is invalid or the
	 *                                  lower bound exceeds the production node
	 *                                  budget
	 */
	public static void requireCollectionCouldFitProductionNodeBudget(long size,
			long minimumNodesPerElement, long fixedNodes,
			@NonNull String description) {
		requireCollectionCouldFitNodeBudget(size, minimumNodesPerElement,
				fixedNodes, requireNonNull(description), PRODUCTION_LIMITS);
	}

	@NonNull
	static McpJsonValue toInternal(com.soklet.@NonNull McpJsonValue value,
			@NonNull McpJsonLimits limits) {
		McpJsonLimits requiredLimits = requireNonNull(limits);
		return toInternal(requireNonNull(value), requiredLimits,
				new McpJsonCodec(requiredLimits));
	}

	@NonNull
	static McpJsonObject toInternalObject(
			com.soklet.@NonNull McpJsonObject value,
			@NonNull McpJsonLimits limits) {
		return (McpJsonObject) toInternal(requireNonNull(value), limits);
	}

	static void requireCollectionCouldFitNodeBudget(long size,
			long minimumNodesPerElement, long fixedNodes,
			@NonNull String description, @NonNull McpJsonLimits limits) {
		requireNonNull(description);
		McpJsonLimits requiredLimits = requireNonNull(limits);
		if (size < 0L)
			throw new IllegalArgumentException("Collection size must not be negative.");
		if (minimumNodesPerElement < 1L)
			throw new IllegalArgumentException(
					"Minimum nodes per element must be positive.");
		if (fixedNodes < 0L)
			throw new IllegalArgumentException("Fixed node count must not be negative.");

		long remainingNodes = requiredLimits.maximumNodeCount() - fixedNodes;
		if (remainingNodes < 0L
				|| size > remainingNodes / minimumNodesPerElement)
			throw new IllegalArgumentException(description
					+ " cannot fit within the configured JSON node limit.");
	}

	private static void requireNodeCount(long nodeCount,
			@NonNull String description, @NonNull McpJsonLimits limits) {
		requireNonNull(description);
		McpJsonLimits requiredLimits = requireNonNull(limits);
		if (nodeCount < 0L)
			throw new IllegalArgumentException("JSON node count must not be negative.");
		if (nodeCount > requiredLimits.maximumNodeCount())
			throw new IllegalArgumentException(description
					+ " cannot fit within the configured JSON node limit.");
	}

	@NonNull
	private static McpJsonValue toInternal(
			com.soklet.@NonNull McpJsonValue value,
			@NonNull McpJsonLimits limits, @NonNull McpJsonCodec codec) {
		nodeCount(value, limits);
		McpJsonValue converted = convert(value);
		codec.toUtf8Bytes(converted);
		return converted;
	}

	private static int nodeCount(com.soklet.@NonNull McpJsonValue value,
			@NonNull McpJsonLimits limits) {
		int[] nodeCount = {0};
		preflight(value, 1, nodeCount, requireNonNull(limits));
		return nodeCount[0];
	}

	private static void preflight(com.soklet.@NonNull McpJsonValue value,
			int depth, int @NonNull [] nodeCount,
			@NonNull McpJsonLimits limits) {
		requireNonNull(value);
		if (depth > limits.maximumNestingDepth())
			throw new IllegalArgumentException(
					"JSON output exceeds the configured depth limit.");
		if (nodeCount[0] == limits.maximumNodeCount())
			throw new IllegalArgumentException(
					"JSON output exceeds the configured node limit.");
		nodeCount[0]++;

		if (value instanceof com.soklet.McpJsonArray array) {
			for (com.soklet.McpJsonValue element : array.getElements())
				preflight(element, depth + 1, nodeCount, limits);
		} else if (value instanceof com.soklet.McpJsonObject object) {
			for (com.soklet.McpJsonValue member : object.getMembers().values())
				preflight(member, depth + 1, nodeCount, limits);
		}
	}

	@NonNull
	private static McpJsonValue convert(
			com.soklet.@NonNull McpJsonValue value) {
		if (value instanceof com.soklet.McpJsonString string)
			return new McpJsonString(string.getValue());
		if (value instanceof com.soklet.McpJsonNumber number)
			return new McpJsonNumber(number.getValue());
		if (value instanceof com.soklet.McpJsonBoolean bool)
			return McpJsonBoolean.fromBoolean(bool.getValue());
		if (value instanceof com.soklet.McpJsonNull)
			return McpJsonNull.INSTANCE;
		if (value instanceof com.soklet.McpJsonArray array) {
			List<McpJsonValue> elements = new ArrayList<>(
					array.getElements().size());
			for (com.soklet.McpJsonValue element : array.getElements())
				elements.add(convert(element));
			return new McpJsonArray(elements);
		}
		if (value instanceof com.soklet.McpJsonObject object) {
			Map<String, McpJsonValue> members = new LinkedHashMap<>(
					object.getMembers().size());
			object.getMembers().forEach((name, member) ->
					members.put(name, convert(member)));
			return new McpJsonObject(members);
		}
		throw new IllegalArgumentException(
				"Unsupported public MCP JSON value implementation.");
	}
}
