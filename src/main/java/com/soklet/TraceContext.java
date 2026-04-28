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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Parsed W3C trace context from {@code traceparent} and {@code tracestate} HTTP header values.
 * <p>
 * This type models the normalized trace context understood by Soklet. Future-version extension fields are ignored and
 * not preserved.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class TraceContext {
	private static final int VERSION_00_TRACEPARENT_LENGTH = 55;
	private static final String ZERO_TRACE_ID = "00000000000000000000000000000000";
	private static final String ZERO_PARENT_ID = "0000000000000000";
	private static final Integer SAMPLED_FLAG = 1;
	private static final Integer MAX_TRACESTATE_ENTRIES = 32;
	private static final Integer MAX_TRACESTATE_LENGTH = 512;
	private static final Integer MAX_TRACESTATE_ENTRY_TRUNCATION_LENGTH = 128;
	@NonNull
	private final String traceId;
	@NonNull
	private final String parentId;
	@NonNull
	private final Integer traceFlags;
	@NonNull
	private final List<@NonNull TraceStateEntry> traceStateEntries;

	/**
	 * Parses W3C trace context from physical HTTP header values.
	 *
	 * @param traceparentHeaderValues the physical {@code traceparent} header values
	 * @param tracestateHeaderValues  the physical {@code tracestate} header values, in arrival order
	 * @return the parsed trace context, or {@link Optional#empty()} if no valid {@code traceparent} is available
	 */
	@NonNull
	public static Optional<TraceContext> fromHeaderValues(@Nullable Collection<@NonNull String> traceparentHeaderValues,
																											 @Nullable List<@NonNull String> tracestateHeaderValues) {
		if (traceparentHeaderValues == null || traceparentHeaderValues.size() != 1)
			return Optional.empty();

		ParsedTraceparent parsedTraceparent = parseTraceparent(traceparentHeaderValues.iterator().next()).orElse(null);

		if (parsedTraceparent == null)
			return Optional.empty();

		return Optional.of(new TraceContext(
				parsedTraceparent.traceId(),
				parsedTraceparent.parentId(),
				parsedTraceparent.traceFlags(),
				parseTraceStateEntries(tracestateHeaderValues)));
	}

	private TraceContext(@NonNull String traceId,
											 @NonNull String parentId,
											 @NonNull Integer traceFlags,
											 @NonNull List<@NonNull TraceStateEntry> traceStateEntries) {
		this.traceId = requireNonNull(traceId);
		this.parentId = requireNonNull(parentId);
		this.traceFlags = requireNonNull(traceFlags);
		this.traceStateEntries = List.copyOf(traceStateEntries);
	}

	/**
	 * Returns the 32-character lowercase hexadecimal trace identifier.
	 *
	 * @return the trace identifier
	 */
	@NonNull
	public String getTraceId() {
		return this.traceId;
	}

	/**
	 * Returns the 16-character lowercase hexadecimal parent identifier.
	 *
	 * @return the parent identifier
	 */
	@NonNull
	public String getParentId() {
		return this.parentId;
	}

	/**
	 * Returns the trace flags as an unsigned 8-bit value represented by an {@link Integer}.
	 *
	 * @return the trace flags, in the range {@code 0..255}
	 */
	@NonNull
	public Integer getTraceFlags() {
		return this.traceFlags;
	}

	/**
	 * Is the W3C sampled flag set?
	 *
	 * @return {@code true} if the sampled flag is set
	 */
	@NonNull
	public Boolean isSampled() {
		return (getTraceFlags() & SAMPLED_FLAG) == SAMPLED_FLAG;
	}

	/**
	 * Returns the normalized W3C {@code tracestate} entries.
	 *
	 * @return the trace-state entries, or an empty list if none are present
	 */
	@NonNull
	public List<@NonNull TraceStateEntry> getTraceStateEntries() {
		return this.traceStateEntries;
	}

	/**
	 * Returns this context in W3C {@code traceparent} header value form.
	 *
	 * @return the {@code traceparent} header value
	 */
	@NonNull
	public String toTraceparentHeaderValue() {
		return format("00-%s-%s-%02x", getTraceId(), getParentId(), getTraceFlags());
	}

	/**
	 * Returns this context's normalized W3C {@code tracestate} header value.
	 *
	 * @return the {@code tracestate} header value, or {@link Optional#empty()} if none is present
	 */
	@NonNull
	public Optional<String> toTracestateHeaderValue() {
		if (getTraceStateEntries().isEmpty())
			return Optional.empty();

		StringBuilder value = new StringBuilder();

		for (TraceStateEntry entry : getTraceStateEntries()) {
			if (!value.isEmpty())
				value.append(',');

			value.append(entry.toHeaderMemberValue());
		}

		return Optional.of(value.toString());
	}

	@Override
	@NonNull
	public String toString() {
		return format("%s{traceId=%s, parentId=%s, traceFlags=%s, traceStateEntryCount=%s}",
				getClass().getSimpleName(), getTraceId(), getParentId(), getTraceFlags(), getTraceStateEntries().size());
	}

	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object)
			return true;

		if (!(object instanceof TraceContext traceContext))
			return false;

		return Objects.equals(getTraceId(), traceContext.getTraceId())
				&& Objects.equals(getParentId(), traceContext.getParentId())
				&& Objects.equals(getTraceFlags(), traceContext.getTraceFlags())
				&& Objects.equals(getTraceStateEntries(), traceContext.getTraceStateEntries());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getTraceId(), getParentId(), getTraceFlags(), getTraceStateEntries());
	}

	@NonNull
	private static Optional<ParsedTraceparent> parseTraceparent(@Nullable String traceparentHeaderValue) {
		if (traceparentHeaderValue == null || traceparentHeaderValue.length() < VERSION_00_TRACEPARENT_LENGTH)
			return Optional.empty();

		if (traceparentHeaderValue.charAt(2) != '-'
				|| !isLowercaseHex(traceparentHeaderValue.charAt(0))
				|| !isLowercaseHex(traceparentHeaderValue.charAt(1)))
			return Optional.empty();

		String version = traceparentHeaderValue.substring(0, 2);

		if ("ff".equals(version))
			return Optional.empty();

		boolean version00 = "00".equals(version);

		if (version00 && traceparentHeaderValue.length() != VERSION_00_TRACEPARENT_LENGTH)
			return Optional.empty();

		if (!version00 && traceparentHeaderValue.length() > VERSION_00_TRACEPARENT_LENGTH && traceparentHeaderValue.charAt(VERSION_00_TRACEPARENT_LENGTH) != '-')
			return Optional.empty();

		if (traceparentHeaderValue.charAt(35) != '-' || traceparentHeaderValue.charAt(52) != '-')
			return Optional.empty();

		String traceId = traceparentHeaderValue.substring(3, 35);
		String parentId = traceparentHeaderValue.substring(36, 52);
		String traceFlagsText = traceparentHeaderValue.substring(53, 55);

		if (!isLowercaseHex(traceId)
				|| !isLowercaseHex(parentId)
				|| !isLowercaseHex(traceFlagsText)
				|| ZERO_TRACE_ID.equals(traceId)
				|| ZERO_PARENT_ID.equals(parentId))
			return Optional.empty();

		int traceFlags = Integer.parseInt(traceFlagsText, 16);

		if (!version00)
			traceFlags = traceFlags & SAMPLED_FLAG;

		return Optional.of(new ParsedTraceparent(traceId, parentId, traceFlags));
	}

	@NonNull
	private static List<@NonNull TraceStateEntry> parseTraceStateEntries(@Nullable List<@NonNull String> tracestateHeaderValues) {
		if (tracestateHeaderValues == null || tracestateHeaderValues.isEmpty())
			return List.of();

		List<TraceStateEntry> entries = new ArrayList<>();
		LinkedHashSet<String> keys = new LinkedHashSet<>();

		for (String tracestateHeaderValue : tracestateHeaderValues) {
			if (tracestateHeaderValue == null)
				continue;

			for (String member : tracestateHeaderValue.split(",", -1)) {
				TraceStateEntry entry = TraceStateEntry.fromMember(member).orElse(null);

				if (entry == null || keys.contains(entry.getKey()))
					continue;

				keys.add(entry.getKey());
				entries.add(entry);
			}
		}

		return truncateTraceStateEntries(entries);
	}

	@NonNull
	private static List<@NonNull TraceStateEntry> truncateTraceStateEntries(@NonNull List<@NonNull TraceStateEntry> entries) {
		requireNonNull(entries);

		if (entries.isEmpty())
			return List.of();

		List<TraceStateEntry> normalizedEntries = new ArrayList<>(entries);

		int largeEntryIndex;
		while (isOverTraceStateLimits(normalizedEntries)
				&& (largeEntryIndex = lastLargeTraceStateEntryIndex(normalizedEntries)) >= 0)
			normalizedEntries.remove(largeEntryIndex);

		while (normalizedEntries.size() > MAX_TRACESTATE_ENTRIES)
			normalizedEntries.remove(normalizedEntries.size() - 1);

		while (traceStateLength(normalizedEntries) > MAX_TRACESTATE_LENGTH)
			normalizedEntries.remove(normalizedEntries.size() - 1);

		return List.copyOf(normalizedEntries);
	}

	private static boolean isOverTraceStateLimits(@NonNull List<@NonNull TraceStateEntry> entries) {
		requireNonNull(entries);
		return entries.size() > MAX_TRACESTATE_ENTRIES || traceStateLength(entries) > MAX_TRACESTATE_LENGTH;
	}

	private static int lastLargeTraceStateEntryIndex(@NonNull List<@NonNull TraceStateEntry> entries) {
		requireNonNull(entries);

		for (int i = entries.size() - 1; i >= 0; i--)
			if (entries.get(i).toHeaderMemberValue().length() > MAX_TRACESTATE_ENTRY_TRUNCATION_LENGTH)
				return i;

		return -1;
	}

	private static int traceStateLength(@NonNull List<@NonNull TraceStateEntry> entries) {
		requireNonNull(entries);

		int length = 0;

		for (TraceStateEntry entry : entries) {
			if (length > 0)
				length++;

			length += entry.toHeaderMemberValue().length();
		}

		return length;
	}

	private static boolean isLowercaseHex(@NonNull String value) {
		requireNonNull(value);

		for (int i = 0; i < value.length(); i++)
			if (!isLowercaseHex(value.charAt(i)))
				return false;

		return true;
	}

	private static boolean isLowercaseHex(char c) {
		return (c >= '0' && c <= '9')
				|| (c >= 'a' && c <= 'f');
	}

	private record ParsedTraceparent(@NonNull String traceId,
																	 @NonNull String parentId,
																	 @NonNull Integer traceFlags) {
		// Value record
	}
}
