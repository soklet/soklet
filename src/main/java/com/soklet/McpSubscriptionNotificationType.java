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

package com.soklet;

/**
 * Resource-notification families an MCP endpoint may support through
 * {@code subscriptions/listen}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum McpSubscriptionNotificationType {
	/** The endpoint's discoverable resource list changed. */
	RESOURCES_LIST_CHANGED,
	/** The representation at a subscribed resource URI changed. */
	RESOURCE_UPDATED
}
