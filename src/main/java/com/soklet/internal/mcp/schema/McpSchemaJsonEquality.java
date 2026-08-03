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

import com.soklet.internal.mcp.protocol.McpJsonArray;
import com.soklet.internal.mcp.protocol.McpJsonNumber;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonValue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Iterative, budget-aware JSON Schema semantic equality.
 */
final class McpSchemaJsonEquality {
	enum Result {
		EQUAL,
		NOT_EQUAL,
		LIMIT_EXCEEDED
	}

	Result compare(McpJsonValue left, McpJsonValue right,
			McpSchemaEvaluationContext context) {
		requireNonNull(left);
		requireNonNull(right);
		requireNonNull(context);
		Deque<ValuePair> pairs = new ArrayDeque<>();
		if (!context.chargeEvaluationOperation())
			return Result.LIMIT_EXCEEDED;
		pairs.push(new ValuePair(left, right));

		while (!pairs.isEmpty()) {
			ValuePair pair = pairs.pop();
			McpJsonValue leftValue = pair.left();
			McpJsonValue rightValue = pair.right();
			if (leftValue == rightValue
					&& !(leftValue instanceof McpJsonArray)
					&& !(leftValue instanceof McpJsonObject))
				continue;

			if (leftValue instanceof McpJsonNumber leftNumber
					&& rightValue instanceof McpJsonNumber rightNumber) {
				if (leftNumber.value().compareTo(rightNumber.value()) != 0)
					return Result.NOT_EQUAL;
				continue;
			}
			if (leftValue.getClass() != rightValue.getClass())
				return Result.NOT_EQUAL;

			if (leftValue instanceof McpJsonArray leftArray) {
				McpJsonArray rightArray = (McpJsonArray) rightValue;
				if (leftArray.values().size() != rightArray.values().size())
					return Result.NOT_EQUAL;
				if (!context.chargeEvaluationOperations(leftArray.values().size()))
					return Result.LIMIT_EXCEEDED;
				for (int index = leftArray.values().size() - 1; index >= 0; --index) {
					pairs.push(new ValuePair(leftArray.values().get(index),
							rightArray.values().get(index)));
				}
				continue;
			}

			if (leftValue instanceof McpJsonObject leftObject) {
				McpJsonObject rightObject = (McpJsonObject) rightValue;
				if (leftObject.members().size() != rightObject.members().size())
					return Result.NOT_EQUAL;
				if (!context.chargeEvaluationOperations(leftObject.members().size()))
					return Result.LIMIT_EXCEEDED;
				for (String key : leftObject.members().keySet()) {
					if (!rightObject.members().containsKey(key))
						return Result.NOT_EQUAL;
				}
				for (Map.Entry<String, McpJsonValue> entry
						: leftObject.members().entrySet())
					pairs.push(new ValuePair(entry.getValue(),
							rightObject.members().get(entry.getKey())));
				continue;
			}

			if (!leftValue.equals(rightValue))
				return Result.NOT_EQUAL;
		}

		return Result.EQUAL;
	}

	private record ValuePair(McpJsonValue left, McpJsonValue right) {
		private ValuePair {
			requireNonNull(left);
			requireNonNull(right);
		}
	}
}
