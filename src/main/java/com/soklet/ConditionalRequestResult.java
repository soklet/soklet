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
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Immutable decision from evaluating HTTP conditional request fields against a selected representation snapshot.
 * <p>
 * Only {@link ConditionalRequests} and the framework's shared evaluator create these values. A decision does not
 * authorize a request, lock storage, or establish that its preconditions will remain true when a mutation commits.
 * <p>
 * This is a closed domain; exhaustive switches are supported, and any new alternative requires an explicit reviewed
 * contract amendment.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public sealed interface ConditionalRequestResult permits ConditionalRequestResult.Proceed,
		ConditionalRequestResult.NotModified, ConditionalRequestResult.PreconditionFailed {
	/**
	 * Preconditions handled by this decision API. Range selection and {@code If-Range} are separate.
	 * <p>
	 * This is a closed domain; exhaustive switches are supported, and any new alternative requires an explicit reviewed
	 * contract amendment.
	 */
	enum Precondition {
		/** The {@code If-Match} strong entity-tag or existence precondition. */
		IF_MATCH("If-Match"),
		/** The {@code If-Unmodified-Since} modification-date precondition. */
		IF_UNMODIFIED_SINCE("If-Unmodified-Since"),
		/** The {@code If-None-Match} weak entity-tag or nonexistence precondition. */
		IF_NONE_MATCH("If-None-Match"),
		/** The {@code If-Modified-Since} GET/HEAD modification-date precondition. */
		IF_MODIFIED_SINCE("If-Modified-Since");

		@NonNull
		private final String headerName;

		Precondition(@NonNull String headerName) {
			this.headerName = requireNonNull(headerName);
		}

		/**
		 * The name of the request header that carries this precondition, such as {@code If-Match}.
		 * <p>
		 * Useful when an application-owned response or log entry names the precondition that decided a request.
		 *
		 * @return the request header name
		 */
		@NonNull
		public String getHeaderName() {
			return this.headerName;
		}
	}

	/**
	 * No conditional short circuit is required. Ignored or invalid fields do not necessarily prevent this result.
	 * <p>
	 * The satisfied set describes only the supplied snapshot. It does not certify complete header validity, adequate
	 * lost-update protection, authorization, or continued truth at commit time. An internal storage compare-and-set
	 * failure requires fresh evaluation unless the application establishes an equivalent, narrowly accepted predicate.
	 */
	@ThreadSafe
	final class Proceed implements ConditionalRequestResult {
		@NonNull
		private final Set<@NonNull Precondition> satisfiedPreconditions;

		Proceed(@NonNull Set<@NonNull Precondition> satisfiedPreconditions) {
			requireNonNull(satisfiedPreconditions);
			EnumSet<Precondition> copied = EnumSet.noneOf(Precondition.class);
			copied.addAll(satisfiedPreconditions);
			if ((copied.contains(Precondition.IF_MATCH) && copied.contains(Precondition.IF_UNMODIFIED_SINCE))
					|| (copied.contains(Precondition.IF_NONE_MATCH) && copied.contains(Precondition.IF_MODIFIED_SINCE)))
				throw new IllegalArgumentException("Satisfied preconditions cannot contain a suppressed date condition.");
			this.satisfiedPreconditions = Collections.unmodifiableSet(copied);
		}

		/**
		 * Returns an immutable set of the present preconditions that were actually evaluated and true.
		 * <table>
		 * <caption>Meaning of satisfied preconditions</caption>
		 * <tr><th>Member</th><th>What held</th><th>What it does not establish</th></tr>
		 * <tr><td>{@code IF_MATCH}</td><td>A wildcard found an existing representation, or a list contained its current
		 * strong tag.</td><td>A wildcard identifies no revision; this set does not disclose which form was used.</td></tr>
		 * <tr><td>{@code IF_UNMODIFIED_SINCE}</td><td>An available modification time was no later than the valid supplied
		 * date, at whole-second precision.</td><td>No intervening write within that second.</td></tr>
		 * <tr><td>{@code IF_NONE_MATCH}, existing representation</td><td>A valid list matched no current tag. An empty
		 * list qualifies, and every valid list is nonmatching when the current tag is unavailable.</td>
		 * <td>Revision-level lost-update protection.</td></tr>
		 * <tr><td>{@code IF_NONE_MATCH}, missing representation</td><td>A wildcard or valid list evaluated true.</td>
		 * <td>That the client specifically required create-if-absent with a wildcard.</td></tr>
		 * <tr><td>{@code IF_MODIFIED_SINCE}</td><td>GET/HEAD's available modification time was later than the valid
		 * supplied date.</td><td>Any write guard.</td></tr>
		 * </table>
		 * <p>
		 * Invalid or unavailable dates and method-inapplicable or suppressed fields are absent. Any {@code If-Match}
		 * presence suppresses {@code If-Unmodified-Since}; any {@code If-None-Match} presence suppresses
		 * {@code If-Modified-Since}, including empty or malformed values. {@code OPTIONS} always has an empty set.
		 * No set contains both {@code IF_MATCH} and {@code IF_UNMODIFIED_SINCE}, or both {@code IF_NONE_MATCH} and
		 * {@code IF_MODIFIED_SINCE}. With no representation, only the empty set and {@code IF_NONE_MATCH} are possible.
		 * For an existing representation, GET/HEAD permits nine sets: empty; each individual member; and each of
		 * {@code IF_MATCH}/{@code IF_UNMODIFIED_SINCE} paired with each of {@code IF_NONE_MATCH}/{@code IF_MODIFIED_SINCE}.
		 * Other applicable methods permit six: empty; {@code IF_MATCH}, {@code IF_UNMODIFIED_SINCE}, or
		 * {@code IF_NONE_MATCH} individually; and either of the first two paired with {@code IF_NONE_MATCH}.
		 *
		 * @return immutable satisfied preconditions; the set may be empty
		 */
		@NonNull
		public Set<@NonNull Precondition> getSatisfiedPreconditions() {
			return this.satisfiedPreconditions;
		}

		@Override
		public boolean equals(@Nullable Object object) {
			return object instanceof Proceed other
					&& this.satisfiedPreconditions.equals(other.satisfiedPreconditions);
		}

		@Override
		public int hashCode() {
			return Objects.hash(Proceed.class, this.satisfiedPreconditions);
		}

		@Override
		@NonNull
		public String toString() {
			return format("%s{satisfiedPreconditions=%s}", Proceed.class.getSimpleName(), getSatisfiedPreconditions());
		}
	}

	/**
	 * GET/HEAD cache validation selected a bodyless {@code 304 Not Modified}. The decisive precondition is
	 * {@code IF_NONE_MATCH} or {@code IF_MODIFIED_SINCE}; a missing representation never produces this variant.
	 */
	@ThreadSafe
	final class NotModified implements ConditionalRequestResult {
		@NonNull
		private final Precondition precondition;

		NotModified(@NonNull Precondition precondition) {
			this.precondition = requireNonNull(precondition);
			if (precondition != Precondition.IF_NONE_MATCH && precondition != Precondition.IF_MODIFIED_SINCE)
				throw new IllegalArgumentException("NotModified requires a cache-validation precondition.");
		}

		/** @return the decisive cache-validation precondition */
		@NonNull
		public Precondition getPrecondition() {
			return this.precondition;
		}

		@Override
		public boolean equals(@Nullable Object object) {
			return object instanceof NotModified other && this.precondition == other.precondition;
		}

		@Override
		public int hashCode() {
			return Objects.hash(NotModified.class, this.precondition);
		}

		@Override
		@NonNull
		public String toString() {
			return format("%s{precondition=%s}", NotModified.class.getSimpleName(), getPrecondition());
		}
	}

	/**
	 * A precondition stopped processing. The convenience wrapper maps every reason to bodyless {@code 412}.
	 * <p>
	 * {@code IF_MATCH} and {@code IF_UNMODIFIED_SINCE} may fail on GET/HEAD as well as applicable mutation methods.
	 * {@code IF_NONE_MATCH} appears here only for non-GET/HEAD methods. {@code OPTIONS} never produces this result.
	 * Malformed or over-limit entity-tag fields fail closed on applicable mutation methods; invalid dates are ignored.
	 */
	@ThreadSafe
	final class PreconditionFailed implements ConditionalRequestResult {
		@NonNull
		private final Precondition precondition;
		@NonNull
		private final Reason reason;

		PreconditionFailed(@NonNull Precondition precondition, @NonNull Reason reason) {
			this.precondition = requireNonNull(precondition);
			this.reason = requireNonNull(reason);
			if (precondition == Precondition.IF_MODIFIED_SINCE
					|| (precondition == Precondition.IF_UNMODIFIED_SINCE && reason != Reason.CONDITION_FALSE))
				throw new IllegalArgumentException("The precondition and failure reason cannot form this result.");
		}

		/** @return the first decisive precondition in evaluation order */
		@NonNull
		public Precondition getPrecondition() {
			return this.precondition;
		}

		/** @return why the decisive precondition stopped processing */
		@NonNull
		public Reason getReason() {
			return this.reason;
		}

		/**
		 * Reason the decisive precondition stopped processing. Invalid dates never produce a failure reason.
		 * <p>
		 * This enum may gain reviewed constants in Soklet 4.x; use a forward-compatible default when switching over
		 * its values.
		 */
		public enum Reason {
			/** Valid input evaluated false; weak If-Match tags and valid empty If-Match lists use this reason. */
			CONDITION_FALSE,
			/** The applicable entity-tag field could not be parsed. */
			MALFORMED,
			/**
			 * Quote-aware list splitting exceeded its budget, currently 256 elements including empty elements.
			 * Commas inside quoted tags do not count. This is not a claim that the field's syntax is invalid.
			 */
			ENTITY_TAG_LIST_LIMIT_EXCEEDED
		}

		@Override
		public boolean equals(@Nullable Object object) {
			return object instanceof PreconditionFailed other && this.precondition == other.precondition
					&& this.reason == other.reason;
		}

		@Override
		public int hashCode() {
			return Objects.hash(PreconditionFailed.class, this.precondition, this.reason);
		}

		@Override
		@NonNull
		public String toString() {
			return format("%s{precondition=%s, reason=%s}", PreconditionFailed.class.getSimpleName(),
					getPrecondition(), getReason());
		}
	}
}
