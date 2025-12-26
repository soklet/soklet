/*
 * Copyright 2022-2025 Revetware LLC.
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

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultLifecycleObserver implements LifecycleObserver {
	@Nonnull
	private static final DefaultLifecycleObserver DEFAULT_INSTANCE;

	static {
		DEFAULT_INSTANCE = new DefaultLifecycleObserver();
	}

	@Nonnull
	public static DefaultLifecycleObserver defaultInstance() {
		return DEFAULT_INSTANCE;
	}

	// No method overrides
}
