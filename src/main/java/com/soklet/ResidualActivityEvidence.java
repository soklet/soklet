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
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Immutable, bounded, conservative activity evidence retained at the final shutdown boundary.
 * Observations may be combined across shutdown phases when complete termination cannot be
 * proven. A category can therefore describe work that ended after an earlier observation;
 * the evidence is not a live census of work active at result publication. An empty category
 * set alone does not prove termination. Published evidence remains unchanged when work exits later.
 */
@ThreadSafe
public final class ResidualActivityEvidence {
	@NonNull
	private final Set<@NonNull ResidualActivityType> residualActivityTypes;
	@NonNull
	private final String summary;

	ResidualActivityEvidence(
			@NonNull Set<@NonNull ResidualActivityType> residualActivityTypes,
			@NonNull String summary) {
		Set<ResidualActivityType> exactTypes = requireNonNull(
				residualActivityTypes);
		this.residualActivityTypes = Collections.unmodifiableSet(
				exactTypes.isEmpty()
						? EnumSet.noneOf(ResidualActivityType.class)
						: EnumSet.copyOf(exactTypes));
		this.summary = requireNonNull(summary);
	}

	/** @return immutable conservative activity categories in enum order */
	@NonNull
	public Set<@NonNull ResidualActivityType> getResidualActivityTypes() {
		return this.residualActivityTypes;
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

	/**
	 * Returns a bounded, single-line diagnostic containing the enum-ordered
	 * residual-activity categories. The free-text summary is available only
	 * through {@link #getSummary()} and is not rendered here.
	 *
	 * @return compact residual-activity diagnostic; not a serialization format
	 */
	@Override
	@NonNull
	public String toString() {
		return "ResidualActivityEvidence{residualActivityTypes=" + this.residualActivityTypes + '}';
	}
}
