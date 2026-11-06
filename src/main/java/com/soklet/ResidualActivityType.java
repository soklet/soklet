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
 * Categorizes conservative framework-observed activity retained in shutdown evidence.
 * A category may come from an earlier shutdown observation when complete termination
 * could not be proven; it does not assert that the work is still active when the
 * immutable result is published. See {@link ResidualActivityEvidence}.
 * <p>
 * This type may gain reviewed constants in Soklet 4.x; use a forward-compatible default when switching over its values.
 */
public enum ResidualActivityType {
	/** Observed admitted callback activity. */
	CALLBACK,
	/** Observed response or event stream activity. */
	STREAM,
	/** Observed transport connection activity. */
	CONNECTION,
	/** Observed event-loop activity. */
	EVENT_LOOP,
	/** Observed executor task activity. */
	EXECUTOR_TASK,
	/** Observed tracked lifecycle call activity. */
	LIFECYCLE_CALL
}
