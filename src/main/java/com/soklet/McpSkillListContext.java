/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet;

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable, framework-supplied Skills pagination input.
 *
 * <p>The initial-selection optional is the authoritative phase marker: present,
 * even for an empty list, means first-page selection was performed; absent means
 * the application must restore a continuation snapshot. A present cursor,
 * including an empty string, always identifies continuation. Soklet preserves
 * opaque cursor text without authenticating or retaining application snapshots.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpSkillListContext {
	@NonNull
	private final Optional<@NonNull String> cursor;
	@NonNull
	private final Optional<@NonNull List<@NonNull McpSkillRegistration>> initialSkillRegistrations;

	private McpSkillListContext(@NonNull Optional<@NonNull String> cursor,
			@NonNull Optional<@NonNull List<@NonNull McpSkillRegistration>> initialSkillRegistrations) {
		this.cursor = requireNonNull(cursor);
		this.initialSkillRegistrations = requireNonNull(initialSkillRegistrations).map(List::copyOf);
		if (cursor.isPresent() == initialSkillRegistrations.isPresent())
			throw new IllegalArgumentException("Invalid MCP Skills list context phase.");
	}

	@NonNull
	static McpSkillListContext from(@NonNull Optional<@NonNull String> cursor,
			@NonNull Optional<@NonNull List<@NonNull McpSkillRegistration>> initialSkillRegistrations) {
		return new McpSkillListContext(cursor, initialSkillRegistrations);
	}

	/**
	 * Returns exact opaque client cursor text, including present-empty values.
	 * The application owns integrity, authorization and content binding, expiry,
	 * retained snapshot availability, and cross-node portability.
	 *
	 * @return client cursor, or empty when omitted on the first page
	 */
	@NonNull
	public Optional<@NonNull String> getCursor() { return this.cursor; }

	/**
	 * Returns the first-page access/discovery-filtered and variant-selected
	 * registrations in canonical endpoint order. The first page must be an ordered
	 * subsequence of these exact instances. Present-empty still denotes the first
	 * page; absent requires restoration of an application-owned continuation.
	 *
	 * @return immutable initial selection, or empty for a continuation
	 */
	@NonNull
	public Optional<@NonNull List<@NonNull McpSkillRegistration>> getInitialSkillRegistrations() {
		return this.initialSkillRegistrations;
	}

	/** @return diagnostic rendering without cursor or registration data */
	@Override
	@NonNull
	public String toString() { return "McpSkillListContext[redacted]"; }
}
