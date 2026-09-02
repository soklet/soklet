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

/** Describes the aggregate shutdown boundary reached by a Soklet. */
public enum ShutdownDisposition {
	/** No configured lifecycle component was started. */
	NOT_STARTED,
	/** Every configured lifecycle component terminated without a forced boundary. */
	GRACEFUL,
	/** Every configured lifecycle component terminated, with at least one forced boundary. */
	FORCED,
	/** Residual activity or missing termination proof remains. */
	INCOMPLETE
}
