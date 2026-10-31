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

/**
 * Core Soklet contracts and functionality.
 * <p>
 * Includes ordinary HTTP routing, dedicated Server-Sent Events support, and a
 * dedicated {@link com.soklet.McpServer} for Model Context Protocol
 * 2026-07-28 endpoints. Each configured server owns an independent listener;
 * {@link com.soklet.SokletConfig} composes their lifecycle without mounting
 * MCP inside the ordinary HTTP or SSE server.
 * <p>
 * Unless a type explicitly documents a closed domain, public enums and sealed
 * roots may gain reviewed constants or permitted subtypes in Soklet 4.x.
 * Applications switching over evolutionary types should retain a default that
 * safely handles new alternatives. Closed-domain decision/value families state
 * their exhaustive-switch contract on the type itself.
 * <p>
 * Full documentation is available at <a href="https://www.soklet.com">https://www.soklet.com</a>.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
package com.soklet;
