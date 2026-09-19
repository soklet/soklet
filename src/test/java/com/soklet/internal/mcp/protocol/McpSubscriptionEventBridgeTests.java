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

import com.soklet.McpSubscriptionEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URI;

public class McpSubscriptionEventBridgeTests {
	@Test
	public void every_public_subscription_event_has_an_exact_internal_mirror()
			throws Exception {
		Method bridge = McpServerRuntimeBridge.class.getDeclaredMethod(
				"toInternal", McpSubscriptionEvent.class);
		bridge.setAccessible(true);

		Assertions.assertInstanceOf(
				McpSubscriptionEventSource.Event.ResourcesListChanged.class,
				bridge.invoke(null, McpSubscriptionEvent.resourcesListChanged()));
		Assertions.assertInstanceOf(
				McpSubscriptionEventSource.Event.ToolsListChanged.class,
				bridge.invoke(null, McpSubscriptionEvent.toolsListChanged()));
		Assertions.assertInstanceOf(
				McpSubscriptionEventSource.Event.PromptsListChanged.class,
				bridge.invoke(null, McpSubscriptionEvent.promptsListChanged()));

		URI resourceUri = URI.create("test://subscription-event-bridge/item");
		McpSubscriptionEventSource.Event.ResourceUpdated updated =
				Assertions.assertInstanceOf(
						McpSubscriptionEventSource.Event.ResourceUpdated.class,
						bridge.invoke(null,
								McpSubscriptionEvent.resourceUpdated(resourceUri)));
		Assertions.assertEquals(resourceUri, updated.resourceUri());
		Assertions.assertEquals(resourceUri.toASCIIString(), updated.wireResourceUri());
	}
}
