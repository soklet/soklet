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

/** Categorizes framework-observed work that remains after shutdown. */
public enum ResidualActivityType {
	/** An admitted callback remains active. */
	CALLBACK,
	/** A response or event stream remains active. */
	STREAM,
	/** A transport connection remains active. */
	CONNECTION,
	/** An event loop remains active. */
	EVENT_LOOP,
	/** An executor task remains active. */
	EXECUTOR_TASK,
	/** A tracked lifecycle call remains active. */
	LIFECYCLE_CALL
}
