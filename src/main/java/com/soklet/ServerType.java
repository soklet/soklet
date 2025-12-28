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
 * Types of servers supported by Soklet - currently {@link #STANDARD_HTTP} and {@link #SERVER_SENT_EVENT}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum ServerType {
	/**
	 * A server which speaks HTTP over TCP (that is, services <em>Resource Methods</em> annotated with {@link com.soklet.annotation.GET}, {@link com.soklet.annotation.POST}, etc.)
	 */
	STANDARD_HTTP,
	/**
	 * A Server-Sent Event server which handles SSE connections (that is, services <em>Resource Methods</em> annotated with {@link com.soklet.annotation.ServerSentEventSource}).
	 */
	SERVER_SENT_EVENT
}
