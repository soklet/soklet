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

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Immutable, bounded evidence describing activity that remained at the final
 * shutdown boundary.
 */
public final class ResidualActivityEvidence {
	@NonNull
	private final Set<@NonNull ResidualActivityKind> activityKinds;
	@NonNull
	private final String summary;

	ResidualActivityEvidence(
			@NonNull Set<@NonNull ResidualActivityKind> activityKinds,
			@NonNull String summary) {
		Set<ResidualActivityKind> exactKinds = requireNonNull(activityKinds);
		this.activityKinds = Collections.unmodifiableSet(exactKinds.isEmpty()
				? EnumSet.noneOf(ResidualActivityKind.class)
				: EnumSet.copyOf(exactKinds));
		this.summary = requireNonNull(summary);
	}

	/** @return immutable residual-activity categories in enum order */
	@NonNull
	public Set<@NonNull ResidualActivityKind> getActivityKinds() {
		return this.activityKinds;
	}

	/**
	 * Returns a framework-generated, control-character-escaped summary capped
	 * at 1,024 Unicode code points.
	 *
	 * @return bounded diagnostic summary
	 */
	@NonNull
	public String getSummary() {
		return this.summary;
	}
}
