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

import org.jspecify.annotations.NonNull;

/** Creates a configuration against one fresh set of simulator transports. */
@FunctionalInterface
public interface SimulatorConfigFactory {
	/**
	 * Creates the configuration for one isolated simulation scope.
	 *
	 * @param transports fresh off-network transports owned by that scope
	 * @return simulation configuration
	 */
	@NonNull
	SokletConfig create(@NonNull SimulatorTransports transports);
}
