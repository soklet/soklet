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

package com.soklet.internal;

import org.jspecify.annotations.Nullable;

/**
 * Explicit object identity for ownership tokens, thread confinement, and exact failure retention.
 * This is not value equality: user-controlled {@code equals} implementations must never run here.
 */
public final class ObjectIdentity {
	private ObjectIdentity() {}

	/**
	 * Reports whether both references identify the same object, including two null references.
	 *
	 * @param first the first reference
	 * @param second the second reference
	 * @return whether the references are identical, without invoking either object's methods
	 */
	@SuppressWarnings("ReferenceEquality") // The entire contract is reference identity, never value equality.
	public static boolean sameInstance(@Nullable Object first, @Nullable Object second) {
		return first == second;
	}
}
