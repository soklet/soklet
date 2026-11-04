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

import com.soklet.McpJsonValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class McpTypedJsonArrayCovarianceTests {

	@Test
	@SuppressWarnings("unchecked")
	void typedListArraysAcceptAssignableImplementationArrays() {
		McpRuntimeTypedSchemaBridge<ListOutput> bridge =
				McpRuntimeTypedSchemaBridge.compileToolOutput(ListOutput.class);
		List<String>[] declared = (List<String>[]) new List<?>[]{List.of("value")};
		List<String>[] implementation = (List<String>[]) new ArrayList<?>[]{
				new ArrayList<>(List.of("value"))};
		McpJsonValue expected = bridge.encode(new ListOutput(declared));
		assertEquals(expected, bridge.encode(new ListOutput(implementation)));
		assertTrue(bridge.isValid(expected));
		ListOutput decoded = bridge.decode(expected);
		assertEquals(List[].class, decoded.values().getClass());
		assertEquals(List.of("value"), decoded.values()[0]);
		assertEquals(expected, bridge.encode(decoded));
	}

	@Test
	@SuppressWarnings("unchecked")
	void typedMapArraysAcceptAssignableImplementationArrays() {
		McpRuntimeTypedSchemaBridge<MapOutput> bridge =
				McpRuntimeTypedSchemaBridge.compileToolOutput(MapOutput.class);
		Map<String, Integer>[] declared = (Map<String, Integer>[]) new Map<?, ?>[]{Map.of("value", 1)};
		Map<String, Integer>[] implementation = (Map<String, Integer>[]) new HashMap<?, ?>[]{
				new HashMap<>(Map.of("value", 1))};
		McpJsonValue expected = bridge.encode(new MapOutput(declared));
		assertEquals(expected, bridge.encode(new MapOutput(implementation)));
		MapOutput decoded = bridge.decode(expected);
		assertEquals(Map[].class, decoded.values().getClass());
		assertEquals(Map.of("value", 1), decoded.values()[0]);
		assertEquals(expected, bridge.encode(decoded));
	}

	@Test
	@SuppressWarnings("unchecked")
	void assignabilityAppliesAtEveryArrayDimension() {
		McpRuntimeTypedSchemaBridge<NestedListOutput> bridge =
				McpRuntimeTypedSchemaBridge.compileToolOutput(NestedListOutput.class);
		List<String>[][] declared = (List<String>[][]) new List<?>[][]{{List.of("value")}};
		List<String>[][] implementation = (List<String>[][]) new ArrayList<?>[][]{{
				new ArrayList<>(List.of("value"))}};
		McpJsonValue expected = bridge.encode(new NestedListOutput(declared));
		assertEquals(expected, bridge.encode(new NestedListOutput(implementation)));
		assertEquals(expected, bridge.encode(bridge.decode(expected)));
	}

	@Test
	void recordArraysRetainDeclaredRuntimeAndElementConstraints() {
		McpRuntimeTypedSchemaBridge<Object> bridge =
				McpRuntimeTypedSchemaBridge.compileJsonValue(Item[].class);
		Item[] items = {new Item("value")};
		McpJsonValue encoded = bridge.encode(items);
		assertArrayEquals(items, (Item[]) bridge.decode(encoded));
		assertThrows(McpTypedJsonBindingException.class,
				() -> bridge.encode(new Object[]{new Item("value")}));
		assertThrows(McpTypedJsonBindingException.class,
				() -> bridge.encode(new Item[]{null}));
	}

	@Test
	void primitiveArraysRetainTheirExactPrimitiveType() {
		McpRuntimeTypedSchemaBridge<Object> bridge =
				McpRuntimeTypedSchemaBridge.compileJsonValue(int[].class);
		McpJsonValue encoded = bridge.encode(new int[]{1, 2});
		assertArrayEquals(new int[]{1, 2}, (int[]) bridge.decode(encoded));
		assertThrows(McpTypedJsonBindingException.class,
				() -> bridge.encode(new long[]{1, 2}));
		assertThrows(McpTypedJsonBindingException.class,
				() -> bridge.encode(new Integer[]{1, 2}));
		assertThrows(McpTypedJsonBindingException.class,
				() -> bridge.encode(List.of(1, 2)));
	}

	@Test
	@SuppressWarnings("unchecked")
	void assignableContainerArraysStillValidateEveryElement() {
		McpRuntimeTypedSchemaBridge<ListOutput> lists =
				McpRuntimeTypedSchemaBridge.compileToolOutput(ListOutput.class);
		List<String>[] wrongListElement = (List<String>[]) new ArrayList<?>[]{
				new ArrayList<>(List.of(1))};
		List<String>[] nullListElement = (List<String>[]) new ArrayList<?>[]{null};
		McpTypedJsonBindingException wrongListValue = assertThrows(McpTypedJsonBindingException.class,
				() -> lists.encode(new ListOutput(wrongListElement)));
		assertEquals(McpTypedJsonBindingException.Reason.JAVA_TYPE_MISMATCH, wrongListValue.reason());
		assertEquals("$/properties/values/items/items", wrongListValue.path().toString());
		McpTypedJsonBindingException nullListValue = assertThrows(McpTypedJsonBindingException.class,
				() -> lists.encode(new ListOutput(nullListElement)));
		assertEquals(McpTypedJsonBindingException.Reason.NULL_VALUE, nullListValue.reason());
		assertEquals("$/properties/values/items", nullListValue.path().toString());

		McpRuntimeTypedSchemaBridge<MapOutput> maps =
				McpRuntimeTypedSchemaBridge.compileToolOutput(MapOutput.class);
		Map<String, Integer>[] wrongMapElement = (Map<String, Integer>[]) new HashMap<?, ?>[]{
				new HashMap<>(Map.of("value", "wrong"))};
		McpTypedJsonBindingException wrongMapValue = assertThrows(McpTypedJsonBindingException.class,
				() -> maps.encode(new MapOutput(wrongMapElement)));
		assertEquals(McpTypedJsonBindingException.Reason.JAVA_TYPE_MISMATCH, wrongMapValue.reason());
		assertEquals("$/properties/values/items/additionalProperties", wrongMapValue.path().toString());
	}

	private record ListOutput(List<String>[] values) {}
	private record MapOutput(Map<String, Integer>[] values) {}
	private record NestedListOutput(List<String>[][] values) {}
	private record Item(String value) {}
}
