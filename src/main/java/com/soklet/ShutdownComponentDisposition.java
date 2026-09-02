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

/** Describes the terminal evidence for one shutdown component. */
public enum ShutdownComponentDisposition {
	/** The component did not start and no tracked setup call remains. */
	NOT_STARTED,
	/** Graceful termination was proven. */
	GRACEFUL_TERMINATION,
	/** Termination was proven after the forced boundary. */
	FORCED_TERMINATION,
	/** Premature termination was proven. */
	UNEXPECTED_TERMINATION,
	/** Positive evidence of component activity remains. */
	RESIDUAL_ACTIVITY,
	/** Termination could not be proven. */
	TERMINATION_UNKNOWN
}
