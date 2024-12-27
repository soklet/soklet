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

package com.soklet.core.impl;

import com.soklet.core.LifecycleInterceptor;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class DefaultLifecycleInterceptor implements LifecycleInterceptor {
	@Nonnull
	private static final DefaultLifecycleInterceptor SHARED_INSTANCE;

	static {
		SHARED_INSTANCE = new DefaultLifecycleInterceptor();
	}

	@Nonnull
	public static DefaultLifecycleInterceptor sharedInstance() {
		return SHARED_INSTANCE;
	}
	
	// No method overrides
}
