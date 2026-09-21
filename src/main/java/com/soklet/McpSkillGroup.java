/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Immutable, explicitly grouped locale alternatives for one Skills name.
 *
 * <p>The key is an opaque application-defined, endpoint-local label, not a wire
 * skill identifier. Grouping grants no access and does not select a language or
 * translate file bytes. Group different content versions separately instead of
 * relying on registration order to choose between same-locale versions.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpSkillGroup {
	@NonNull
	private final String key;
	@NonNull
	private final List<@NonNull McpSkillRegistration> skillRegistrations;

	private McpSkillGroup(@NonNull String key,
			@NonNull List<@NonNull McpSkillRegistration> skillRegistrations) {
		this.key = key;
		this.skillRegistrations = skillRegistrations;
	}

	/**
	 * Snapshots ordered alternatives sharing one validated skill name.
	 *
	 * <p>An empty group is permitted. Among its members, registration URIs must be
	 * distinct under {@link URI#equals(Object)} and locales must be unique,
	 * including at most one member with an undeclared locale. The key is preserved
	 * literally; it is neither trimmed nor normalized.
	 *
	 * @param key nonblank application-local group label
	 * @param skillRegistrations alternatives in application order, possibly empty
	 * @return immutable group
	 * @throws NullPointerException if the key, list, or a member is null
	 * @throws IllegalArgumentException if the key is blank, member skill names
	 * differ, or member URIs or locales are duplicated
	 */
	@NonNull
	public static McpSkillGroup fromKeyAndSkillRegistrations(@NonNull String key,
			@NonNull List<@NonNull McpSkillRegistration> skillRegistrations) {
		requireNonNull(key, "A Skills group key is required.");
		requireNonNull(skillRegistrations, "Skills group registrations are required.");
		if (key.isBlank()) throw invalid();
		List<McpSkillRegistration> snapshot = new ArrayList<>();
		Set<URI> uris = new HashSet<>();
		Set<Optional<Locale>> locales = new HashSet<>();
		String name = null;
		for (McpSkillRegistration registration : skillRegistrations) {
			requireNonNull(registration, "A Skills group registration is required.");
			String memberName = registration.getSkillBundle().getName();
			if (name == null) name = memberName;
			else if (!name.equals(memberName)) throw invalid();
			if (!uris.add(registration.getUri()) || !locales.add(registration.getLocale())) throw invalid();
			snapshot.add(registration);
		}
		return new McpSkillGroup(key, List.copyOf(snapshot));
	}

	/** @return exact application-defined group label */
	@NonNull
	public String getKey() { return this.key; }

	/** @return immutable alternatives in the supplied order */
	@NonNull
	public List<@NonNull McpSkillRegistration> getSkillRegistrations() { return this.skillRegistrations; }

	/** @return whether the exact key and ordered registration values match */
	@Override
	public boolean equals(@Nullable Object other) {
		return this == other || other instanceof McpSkillGroup group
				&& this.key.equals(group.key) && this.skillRegistrations.equals(group.skillRegistrations);
	}

	/** @return structural group hash code */
	@Override
	public int hashCode() { return Objects.hash(this.key, this.skillRegistrations); }

	/** @return diagnostic rendering without group labels or registrations */
	@Override
	@NonNull
	public String toString() { return "McpSkillGroup[redacted]"; }

	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException("Invalid Skills group.");
	}
}
