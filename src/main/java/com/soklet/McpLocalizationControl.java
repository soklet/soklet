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

import javax.annotation.concurrent.ThreadSafe;

/**
 * Thread-safe server-owned control plane for localized MCP catalog
 * invalidation.
 * <p>
 * The control is local to one server. Applications distribute and install
 * immutable translation snapshots themselves, then call this control on every
 * applicable instance.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpLocalizationControl {
	/** @return whether localization was enabled when the server was built */
	@NonNull
	Boolean isEnabled();

	/**
	 * Invalidates future derived localized render state and publishes coarse
	 * framework-owned list-change notifications to eligible local streams. The
	 * call returns after the current generation accepts or coalesces the
	 * invalidation, not after client delivery. It is safe before start and after
	 * stop, when there is no active stream to notify.
	 *
	 * @throws IllegalStateException if localization is disabled
	 */
	void catalogsChanged();
}
