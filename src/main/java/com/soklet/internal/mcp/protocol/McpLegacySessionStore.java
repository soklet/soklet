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
package com.soklet.internal.mcp.protocol;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

import static java.util.Objects.requireNonNull;

/**
 * Bounded, node-local correlation for explicitly enabled 2025 endpoints.
 * Admission and current identity belong to the caller. This store never runs
 * application code under its lock, and it never owns transport terminal races.
 * Logical retirement and physical metadata ownership are separate transitions.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpLegacySessionStore {
	static final int MAXIMUM_ACTIVE_CALLS_PER_SESSION = 32;
	static final int MAXIMUM_CORRELATION_BYTES = 256;
	static final int MAXIMUM_METADATA_NODES = 4096;
	static final int MAXIMUM_ANONYMOUS_SESSIONS = 64;
	static final long ACKNOWLEDGEMENT_WAIT_NANOS = 30_000_000_000L;
	static final long MINIMUM_EVICTION_IDLE_NANOS = 30_000_000_000L;
	static final int MAXIMUM_SESSION_ID_HEADER_BYTES = 256;
	static final int MAXIMUM_LOGICAL_GETS_PER_SESSION = 2;
	static final int MAXIMUM_URI_GRANTS_PER_SESSION = 64;
	static final int MAXIMUM_URI_GRANTS_PER_OWNER = 64;
	static final int MAXIMUM_URI_GRANTS_GLOBAL = 512;
	static final int MAXIMUM_RETAINED_URI_BYTES_PER_SESSION = 65_536;
	static final long MAXIMUM_NOTIFICATION_BYTES_PER_OWNER = 2_097_152L;
	static final long MAXIMUM_NOTIFICATION_BYTES_GLOBAL = 16_777_216L;
	static final long MAINTENANCE_DEMAND_SCALE = 1_000_000L;
	static final long MAXIMUM_MAINTENANCE_DEMAND_UNITS = 64L * MAINTENANCE_DEMAND_SCALE;
	private static final BooleanSupplier ALWAYS_ACTIVE_SOURCE = () -> true;

	enum Status {
		ACCEPTED, NOT_FOUND, REVISION_MISMATCH, INVALID_ID,
		ACTIVE_ID_COLLISION, CALL_CAPACITY, OWNER_CAPACITY, GLOBAL_CAPACITY,
		METADATA_TOO_LARGE, ANONYMOUS_DENIED, PARTITION_MISMATCH,
		AUTHORIZATION_EXPIRED, STOPPED, INTERNAL_FAILURE
	}

	enum Cause { CLIENT_CANCEL, SESSION_EXPIRED, SESSION_CLOSED, SERVER_STOPPING }

	/** Target callbacks must perform the request's own atomic terminal reservation. */
	interface Target {
		boolean cancel(@NonNull Cause cause);
		void retire(@NonNull Cause cause);
	}

	/** Internal common modern/legacy quota bookkeeping; never application code. */
	interface GetQuota {
		boolean reserve(@NonNull McpEffectivePartition partition);
		void release(@NonNull McpEffectivePartition partition);
	}

	enum GetCause {
		SESSION_EXPIRED, SESSION_CLOSED, SERVER_STOPPING, REPLACED,
		LEASE_EXPIRED, TOTAL_LIFETIME_EXPIRED, AUTHORIZATION_DENIED, AUTHORIZATION_FAILED
	}

	/** A fence must purge unwritten keyed bytes or close before those bytes drain. */
	interface GetTarget {
		void retire(@NonNull GetCause cause);
		void fence(long authorizationGeneration);
	}

	enum GrantCause {
		AUTHORIZATION_DENIED, AUTHORIZATION_FAILED, LEASE_EXPIRED, TOTAL_LIFETIME_EXPIRED,
		SESSION_EXPIRED, SESSION_CLOSED, SERVER_STOPPING, UNSUBSCRIBED, REPLACED
	}

	/** These framework callbacks fence writer keys before returning; they run outside our lock. */
	interface GrantTarget {
		void fence(long authorizationGeneration);
		void retire(@NonNull GrantCause cause);
	}

	record GrantAllocation(@NonNull Status status, @NonNull Optional<@NonNull Grant> grant) {
		GrantAllocation { requireNonNull(status); requireNonNull(grant); }
		@Override public String toString() { return "GrantAllocation[status=" + status + "]"; }
	}

	record GrantCounts(int logicalGrants, int physicalGrantHolds, long retainedUriBytes,
			long queuedNotificationBytes, long maintenanceDemandUnits) {}

	record GetAllocation(@NonNull Status status, @NonNull Optional<@NonNull Get> get) {
		GetAllocation { requireNonNull(status); requireNonNull(get); }
		@Override public String toString() { return "GetAllocation[status=" + status + "]"; }
	}

	record GetCounts(int logicalGets, int physicalGets, int quotaRegistrations) {}

	record Config(int maximumSessions, int maximumSessionsPerOwner,
			long idleNanos, long lifetimeNanos, int maximumClientMetadataBytes,
			boolean anonymousAllowed, long maximumSessionEvidenceBytes,
			long maximumOwnerEvidenceBytes, long maximumGlobalEvidenceBytes) {
		Config {
			if (maximumSessions <= 0 || maximumSessionsPerOwner <= 0
					|| maximumSessionsPerOwner > maximumSessions
					|| idleNanos <= 0 || lifetimeNanos < idleNanos
					|| maximumClientMetadataBytes <= 0
					|| maximumSessionEvidenceBytes <= 0
					|| maximumOwnerEvidenceBytes < maximumSessionEvidenceBytes
					|| maximumGlobalEvidenceBytes < maximumOwnerEvidenceBytes)
				throw new IllegalArgumentException("Inconsistent legacy session resource bounds.");
		}
	}

	/** The anonymous namespace cannot collide with an authenticated owner's key. */
	record Owner(@NonNull String key, boolean anonymous) {
		Owner {
			requireNonNull(key);
			if (key.isBlank() || !fitsUtf8(key, MAXIMUM_CORRELATION_BYTES))
				throw new IllegalArgumentException("A session owner must be nonblank, valid UTF-8, and at most 256 bytes.");
		}

		@Override public String toString() { return "Owner[redacted]"; }
	}

	record Snapshot(@NonNull McpClientCapabilities clientCapabilities,
			@NonNull Optional<@NonNull McpImplementationMetadata> clientInformation) {
		Snapshot {
			requireNonNull(clientCapabilities);
			requireNonNull(clientInformation);
		}

		@Override public String toString() { return "Snapshot[redacted]"; }
	}

	record Allocation(@NonNull Status status,
			@NonNull Optional<@NonNull Initialization> initialization) {
		Allocation { requireNonNull(status); requireNonNull(initialization); }
		@Override public String toString() { return "Allocation[status=" + status + "]"; }
	}

	record Acquisition(@NonNull Status status, @NonNull Optional<@NonNull Call> call) {
		Acquisition { requireNonNull(status); requireNonNull(call); }
		@Override public String toString() { return "Acquisition[status=" + status + "]"; }
	}

	/** Counts are diagnostic internals; no identifiers or owners escape here. */
	record Counts(int liveSessions, int anonymousSessions, int owners,
			long retainedBytes, int physicalReferences) {}

	private final Object lock = new Object();
	private final Config config;
	private final McpJsonCodec snapshotCodec;
	private final McpApplicationClock clock;
	private final Supplier<byte[]> tokenSource;
	private final Map<String, Session> sessions = new LinkedHashMap<>();
	private final Map<Owner, OwnerUsage> owners = new HashMap<>();
	private int anonymousSessions;
	private long retainedBytes;
	private int physicalReferences;
	private long sequence;
	private @Nullable GetQuota getQuota;
	private int logicalGets;
	private int physicalGets;
	private int quotaRegistrations;
	private int logicalGrants;
	private int physicalGrantHolds;
	private long retainedUriBytes;
	private long queuedNotificationBytes;
	private long maintenanceDemandUnits;

	McpLegacySessionStore(@NonNull Config config, @NonNull McpJsonLimits limits,
			@NonNull McpApplicationClock clock) {
		this(config, limits, clock, secureTokens());
	}

	/** Lock order is this store, then the quota's nonblocking bookkeeping lock. */
	void configureGetQuota(@NonNull GetQuota quota) {
		synchronized (lock) {
			if (getQuota != null) throw new IllegalStateException("Legacy GET quota bookkeeping is already configured.");
			getQuota = requireNonNull(quota);
		}
	}

	/** The caller reserves its new channel before atomically replacing an old GET. */
	@NonNull GetAllocation reserveGet(@NonNull String sessionId, @NonNull Owner owner,
			@NonNull String path, @NonNull String revision, @NonNull Object lifecycleGeneration,
			@NonNull McpEffectivePartition partition, @NonNull GetTarget target,
			long retainedRequestEvidenceBytes, long leaseDeadlineNanos, long totalDeadlineNanos) {
		return reserveGet(sessionId, owner, path, revision, lifecycleGeneration, partition, target,
				retainedRequestEvidenceBytes, leaseDeadlineNanos, totalDeadlineNanos, Set.of(), false);
	}

	@NonNull GetAllocation reserveGet(@NonNull String sessionId, @NonNull Owner owner,
			@NonNull String path, @NonNull String revision, @NonNull Object lifecycleGeneration,
			@NonNull McpEffectivePartition partition, @NonNull GetTarget target,
			long retainedRequestEvidenceBytes, long leaseDeadlineNanos, long totalDeadlineNanos,
			@NonNull Set<@NonNull McpResourceNotificationType> notificationTypes) {
		return reserveGet(sessionId, owner, path, revision, lifecycleGeneration, partition, target,
				retainedRequestEvidenceBytes, leaseDeadlineNanos, totalDeadlineNanos, notificationTypes, true);
	}

	private @NonNull GetAllocation reserveGet(@NonNull String sessionId, @NonNull Owner owner,
			@NonNull String path, @NonNull String revision, @NonNull Object lifecycleGeneration,
			@NonNull McpEffectivePartition partition, @NonNull GetTarget target,
			long retainedRequestEvidenceBytes, long leaseDeadlineNanos, long totalDeadlineNanos,
			@NonNull Set<@NonNull McpResourceNotificationType> notificationTypes, boolean accountDemand) {
		requireNonNull(owner); requireNonNull(path); requireNonNull(lifecycleGeneration);
		requireNonNull(partition); requireNonNull(target); requireEvidence(retainedRequestEvidenceBytes);
		Set<McpResourceNotificationType> families = Set.copyOf(requireNonNull(notificationTypes));
		requireLegacy(revision);
		if (!validSessionId(sessionId)) return getAllocation(Status.INVALID_ID);
		List<Runnable> actions = new ArrayList<>();
		GetAllocation result;
		synchronized (lock) {
			long now = clock.nanoTime();
			maintainWhileLocked(now, actions);
			Session session = sessions.get(sessionId);
			if (session == null || !session.owner.equals(owner) || !session.path.equals(path)
					|| session.lifecycleGeneration != lifecycleGeneration) result = getAllocation(Status.NOT_FOUND);
			else if (!session.revision.equals(revision)) result = getAllocation(Status.REVISION_MISMATCH);
			else if (partition.purpose() != McpPartitionPurpose.AUTHORIZATION
					|| !partition.endpointIdentity().endpointPath().equals(path)
					|| partition.applicationKey().map(value -> value.isBlank() || !fitsUtf8(value, MAXIMUM_CORRELATION_BYTES)).orElse(false)
					|| session.deliveryPartition != null && !session.deliveryPartition.equals(partition))
				result = getAllocation(Status.PARTITION_MISMATCH);
			else if (now - leaseDeadlineNanos >= 0L || now - totalDeadlineNanos >= 0L)
				result = getAllocation(Status.AUTHORIZATION_EXPIRED);
			else {
				Get replacement = session.gets.size() >= MAXIMUM_LOGICAL_GETS_PER_SESSION
						? session.gets.iterator().next() : null;
				long clippedTotal = earlierDeadline(now, totalDeadlineNanos, session.createdNanos + config.lifetimeNanos());
				long demand = accountDemand ? demandUnits(earlierDeadline(now, leaseDeadlineNanos, clippedTotal) - now) : 0L;
				long oldDemand = replacement == null ? 0L : replacement.maintenanceDemandUnits;
				long partitionBytes = session.deliveryPartition == null
						? (long) path.length() + partition.applicationKey().map(value -> value.getBytes(StandardCharsets.UTF_8).length).orElse(0) : 0L;
				long allocationBytes = partitionBytes > Long.MAX_VALUE - retainedRequestEvidenceBytes
						? Long.MAX_VALUE : partitionBytes + retainedRequestEvidenceBytes;
				if (exceeds(session.retainedBytes, allocationBytes, config.maximumSessionEvidenceBytes())
						|| exceeds(session.usage.retainedBytes, allocationBytes, config.maximumOwnerEvidenceBytes()))
					result = getAllocation(Status.OWNER_CAPACITY);
				else if (exceeds(retainedBytes, allocationBytes, config.maximumGlobalEvidenceBytes()))
					result = getAllocation(Status.GLOBAL_CAPACITY);
				else if (exceeds(maintenanceDemandUnits - oldDemand, demand, MAXIMUM_MAINTENANCE_DEMAND_UNITS))
					result = getAllocation(Status.GLOBAL_CAPACITY);
				else {
					boolean quotaAccepted = session.deliveryQuotaReserved;
					boolean quotaFailed = false;
					if (!quotaAccepted && getQuota != null) {
						try { quotaAccepted = getQuota.reserve(partition); }
						catch (Throwable ignored) { quotaFailed = true; }
					}
					if (!quotaAccepted) result = getAllocation(quotaFailed ? Status.INTERNAL_FAILURE : Status.GLOBAL_CAPACITY);
					else {
						if (!session.deliveryQuotaReserved) { session.deliveryQuotaReserved = true; quotaRegistrations++; }
						if (session.deliveryPartition == null) session.deliveryPartition = partition;
						Get get = new Get(session, target, retainedRequestEvidenceBytes,
								leaseDeadlineNanos, totalDeadlineNanos, now, families, accountDemand, demand);
						maintenanceDemandUnits += demand;
						session.retainedBytes += allocationBytes;
						session.usage.retainedBytes += allocationBytes;
						retainedBytes += allocationBytes;
						session.gets.add(get); logicalGets++; physicalGets++;
						session.physicalReferences++; physicalReferences++;
						session.deliveryEvidence = true; session.lastActivityNanos = now;
						if (replacement != null) retireGetWhileLocked(replacement, GetCause.REPLACED, actions);
						result = new GetAllocation(Status.ACCEPTED, Optional.of(get));
					}
				}
			}
		}
		run(actions);
		return result;
	}

	/** Fresh DELETE authority is checked again at the same lock as retirement. */
	boolean retireIfCurrent(@NonNull Call verifiedUse, @NonNull Cause cause, long authorizationDeadlineNanos) {
		requireNonNull(verifiedUse); requireNonNull(cause);
		List<Runnable> actions = new ArrayList<>();
		boolean retired;
		synchronized (lock) {
			long now = clock.nanoTime();
			maintainWhileLocked(now, actions);
			retired = now - authorizationDeadlineNanos < 0L && owned(verifiedUse)
					&& verifiedUse.logical && verifiedUse.session.live && verifiedUse.accepted;
			if (retired) retireWhileLocked(verifiedUse.session, cause, actions);
		}
		run(actions);
		return retired;
	}

	/** Synchronously invalidates establishing and active authorization before return. */
	void fenceGets() {
		List<Runnable> actions = new ArrayList<>();
		synchronized (lock) {
			for (Session session : sessions.values())
				for (Get get : session.gets) fenceGetWhileLocked(get, actions);
		}
		run(actions);
	}

	@NonNull GetCounts getCounts() {
		synchronized (lock) { return new GetCounts(logicalGets, physicalGets, quotaRegistrations); }
	}

	/** Pins the real admitted subscribe request; no duplicate raw evidence allocation is made. */
	@NonNull GrantAllocation beginGrant(@NonNull Call verifiedUse, @NonNull String uri,
			@NonNull McpEffectivePartition partition, @NonNull GrantTarget target, long totalDeadlineNanos) {
		requireNonNull(verifiedUse); requireNonNull(partition); requireNonNull(target);
		if (!validResourceUri(uri)) return grantAllocation(Status.INVALID_ID);
		List<Runnable> actions = new ArrayList<>();
		GrantAllocation result;
		synchronized (lock) {
			long now = clock.nanoTime(); maintainWhileLocked(now, actions);
			Session session = verifiedUse.session;
			if (!owned(verifiedUse) || !verifiedUse.logical || !verifiedUse.physical
					|| !verifiedUse.accepted || !session.live) result = grantAllocation(Status.NOT_FOUND);
			else if (!validPartition(session, partition)) result = grantAllocation(Status.PARTITION_MISMATCH);
			else if (totalDeadlineNanos - now <= 0L) result = grantAllocation(Status.AUTHORIZATION_EXPIRED);
			else {
				GrantEntry entry = session.grants.get(URI.create(uri));
				long uriBytes = entry == null ? uri.getBytes(StandardCharsets.UTF_8).length : 0L;
				long partitionBytes = session.deliveryPartition == null ? partitionBytes(partition) : 0L;
				long allocationBytes = uriBytes + partitionBytes;
				if (entry == null && (session.grants.size() >= MAXIMUM_URI_GRANTS_PER_SESSION
						|| session.usage.liveGrants >= MAXIMUM_URI_GRANTS_PER_OWNER
						|| exceeds(session.retainedUriBytes, uriBytes, MAXIMUM_RETAINED_URI_BYTES_PER_SESSION)))
					result = grantAllocation(Status.OWNER_CAPACITY);
				else if (entry == null && logicalGrants >= MAXIMUM_URI_GRANTS_GLOBAL)
					result = grantAllocation(Status.GLOBAL_CAPACITY);
				else if (exceeds(session.retainedBytes, allocationBytes, config.maximumSessionEvidenceBytes())
						|| exceeds(session.usage.retainedBytes, allocationBytes, config.maximumOwnerEvidenceBytes()))
					result = grantAllocation(Status.OWNER_CAPACITY);
				else if (exceeds(retainedBytes, allocationBytes, config.maximumGlobalEvidenceBytes()))
					result = grantAllocation(Status.GLOBAL_CAPACITY);
				else {
					Status quota = ensureDeliveryQuotaWhileLocked(session, partition);
					if (quota != Status.ACCEPTED) result = grantAllocation(quota);
					else {
						if (session.deliveryPartition == null) session.deliveryPartition = partition;
						addEvidenceWhileLocked(session, allocationBytes);
						if (entry == null) {
							entry = new GrantEntry(session, uri, uriBytes,
									earlierDeadline(now, totalDeadlineNanos, session.createdNanos + config.lifetimeNanos()));
							session.grants.put(URI.create(uri), entry); logicalGrants++; session.usage.liveGrants++;
							session.retainedUriBytes += uriBytes; retainedUriBytes += uriBytes;
						}
						invalidatePendingGrantHintsWhileLocked(entry);
						entry.generation++; entry.authorized = false;
						if (entry.pending != null) retireGrantHandleWhileLocked(entry.pending, GrantCause.REPLACED, actions);
						fenceGrantTargetWhileLocked(entry.activeGrant, entry.generation, actions);
						Grant grant = new Grant(entry, target, verifiedUse.evidence);
						entry.pending = grant;
						verifiedUse.evidence.references += 2;
						entry.physicalHolds++; physicalGrantHolds++;
						session.physicalReferences++; physicalReferences++;
						result = new GrantAllocation(Status.ACCEPTED, Optional.of(grant));
					}
				}
			}
		}
		run(actions); return result;
	}

	/** Successful unsubscribe has no grant-existence oracle and fences pending establishment too. */
	boolean unsubscribe(@NonNull Call verifiedUse, @NonNull String uri) {
		requireNonNull(verifiedUse); requireNonNull(uri);
		List<Runnable> actions = new ArrayList<>(); boolean accepted;
		synchronized (lock) {
			maintainWhileLocked(clock.nanoTime(), actions);
			accepted = owned(verifiedUse) && verifiedUse.logical && verifiedUse.accepted && verifiedUse.session.live;
			if (accepted) {
				GrantEntry entry = validResourceUri(uri) ? verifiedUse.session.grants.get(URI.create(uri)) : null;
				if (entry != null) retireGrantEntryWhileLocked(entry, GrantCause.UNSUBSCRIBED, actions);
			}
		}
		run(actions); return accepted;
	}

	/** Includes detached grants and in-progress establishment, before any asynchronous retry is scheduled. */
	void fenceGrants() {
		List<Runnable> actions = new ArrayList<>();
		synchronized (lock) {
			for (Session session : sessions.values())
				for (GrantEntry entry : session.grants.values()) fenceGrantEntryWhileLocked(entry, actions);
		}
		run(actions);
	}

	/** A bounded maintenance view; pending first authorization is owned by its POST, not renewed here. */
	@NonNull List<@NonNull Grant> pendingGrants(@NonNull String path, @NonNull String revision) {
		requireNonNull(path); requireLegacy(revision);
		List<Grant> result = new ArrayList<>();
		synchronized (lock) {
			for (Session session : sessions.values()) if (session.path.equals(path) && session.revision.equals(revision))
				for (GrantEntry entry : session.grants.values())
					if (entry.activeGrant != null && entry.pending == null) result.add(entry.activeGrant);
		}
		return List.copyOf(result);
	}

	@NonNull GrantCounts grantCounts() {
		synchronized (lock) { return new GrantCounts(logicalGrants, physicalGrantHolds, retainedUriBytes,
				queuedNotificationBytes, maintenanceDemandUnits); }
	}

	void markCatalogDirty(@NonNull String path, @NonNull String revision,
			@NonNull McpResourceNotificationType notificationType) {
		markCatalogDirty(path, revision, notificationType, ALWAYS_ACTIVE_SOURCE);
	}
	/** Source predicates are framework-owned, nonblocking atomic generation reads. */
	void markCatalogDirty(@NonNull String path, @NonNull String revision,
			@NonNull McpResourceNotificationType notificationType, @NonNull BooleanSupplier sourceActive) {
		requireNonNull(path); requireLegacy(revision); requireCatalogType(notificationType);
		requireNonNull(sourceActive);
		synchronized (lock) {
			if (!sourceActive.getAsBoolean()) return;
			for (Session session : sessions.values()) if (session.path.equals(path) && session.revision.equals(revision)) {
				CatalogDirty dirty = session.catalogDirty.computeIfAbsent(notificationType, ignored -> new CatalogDirty());
				if (!dirty.dirty || !dirty.sourceActive.getAsBoolean()) {
					dirty.dirty = true; dirty.sequence++; dirty.lastOfferedGet = null; dirty.lastOfferedAttempt = null;
				}
				dirty.sourceActive = sourceActive;
			}
		}
	}

	void markResourceDirty(@NonNull String path, @NonNull String revision, @NonNull String uri) {
		markResourceDirty(path, revision, uri, ALWAYS_ACTIVE_SOURCE);
	}
	void markResourceDirty(@NonNull String path, @NonNull String revision, @NonNull String uri,
			@NonNull BooleanSupplier sourceActive) {
		requireNonNull(path); requireLegacy(revision); requireNonNull(uri);
		requireNonNull(sourceActive);
		synchronized (lock) {
			if (!sourceActive.getAsBoolean()) return;
			for (Session session : sessions.values()) if (session.path.equals(path) && session.revision.equals(revision)) {
				GrantEntry entry = validResourceUri(uri) ? session.grants.get(URI.create(uri)) : null;
				if (entry != null && entry.live) {
					entry.dirty = true; entry.dirtySequence++; entry.sourceActive = sourceActive;
				}
			}
		}
	}

	/** Only a freshly admitted corresponding list operation rearms the catalog hint. */
	boolean rearmCatalog(@NonNull Call verifiedUse, @NonNull McpResourceNotificationType notificationType) {
		requireNonNull(verifiedUse); requireCatalogType(notificationType);
		synchronized (lock) {
			if (!owned(verifiedUse) || !verifiedUse.logical || !verifiedUse.accepted || !verifiedUse.session.live)
				return false;
			CatalogDirty dirty = verifiedUse.session.catalogDirty.get(notificationType);
			if (dirty != null) { dirty.dirty = false; dirty.sequence++; dirty.lastOfferedGet = null; }
			return true;
		}
	}

	/** The bounded dirty state contains no event history and produces at most one attempt per session/key. */
	@NonNull List<@NonNull Delivery> pendingDeliveries(@NonNull String path, @NonNull String revision) {
		requireNonNull(path); requireLegacy(revision);
		List<Delivery> result = new ArrayList<>();
		synchronized (lock) {
			long now = clock.nanoTime();
			for (Session session : sessions.values()) if (session.path.equals(path) && session.revision.equals(revision)) {
				for (Map.Entry<McpResourceNotificationType, CatalogDirty> item : session.catalogDirty.entrySet()) {
					CatalogDirty dirty = item.getValue();
					List<Get> gets = eligibleGetsWhileLocked(session, item.getKey(), now);
					if (dirty.dirty && !gets.isEmpty() && gets.get(0) != dirty.lastOfferedGet)
						result.add(new Delivery(session, item.getKey(), null, dirty.sequence, gets));
				}
				for (GrantEntry entry : session.grants.values()) {
					List<Get> gets = eligibleGetsWhileLocked(session, McpResourceNotificationType.RESOURCE_UPDATED, now);
					if (entry.dirty && grantDeliverable(entry, now) && !gets.isEmpty()
							&& (gets.get(0) != entry.lastOfferedGet || entry.lastOfferedSequence != entry.dirtySequence))
						result.add(new Delivery(session, McpResourceNotificationType.RESOURCE_UPDATED, entry,
								entry.dirtySequence, gets));
				}
			}
		}
		return List.copyOf(result);
	}

	boolean acknowledgeOffered(@NonNull DeliveryAttempt attempt) {
		requireNonNull(attempt);
		synchronized (lock) {
			if (!attempt.valid() || !attempt.currentDirtyWhileLocked()) return false;
			if (attempt.entry == null) {
				CatalogDirty dirty = attempt.get.session.catalogDirty.get(attempt.notificationType);
				requireNonNull(dirty).lastOfferedGet = attempt.get; dirty.lastOfferedAttempt = attempt;
			} else {
				attempt.entry.lastOfferedGet = attempt.get; attempt.entry.lastOfferedSequence = attempt.dirtySequence;
				attempt.entry.lastOfferedAttempt = attempt;
			}
			return true;
		}
	}

	@NonNull Optional<@NonNull NotificationReservation> reserveNotificationBytes(
			@NonNull DeliveryAttempt attempt, long encodedBytes) {
		requireNonNull(attempt); requireEvidence(encodedBytes);
		synchronized (lock) {
			OwnerUsage usage = attempt.get.session.usage;
			if (encodedBytes == 0L || !attempt.valid()
					|| exceeds(usage.queuedNotificationBytes, encodedBytes, MAXIMUM_NOTIFICATION_BYTES_PER_OWNER)
					|| exceeds(queuedNotificationBytes, encodedBytes, MAXIMUM_NOTIFICATION_BYTES_GLOBAL))
				return Optional.empty();
			usage.queuedNotificationBytes += encodedBytes; queuedNotificationBytes += encodedBytes; attempt.reservations++;
			attempt.get.queuedNotificationBytes += encodedBytes;
			return Optional.of(new NotificationReservation(attempt, encodedBytes));
		}
	}

	boolean notificationOwnerCapacityExceeded(@NonNull Get get, long incomingBytes) {
		requireNonNull(get); requireEvidence(incomingBytes);
		synchronized (lock) {
			return exceeds(get.session.usage.queuedNotificationBytes, incomingBytes, MAXIMUM_NOTIFICATION_BYTES_PER_OWNER);
		}
	}
	long queuedNotificationBytes(@NonNull Get get) {
		synchronized (lock) { return requireNonNull(get).queuedNotificationBytes; }
	}

	@ThreadSafe
	final class Delivery {
		private final Session session;
		private final McpResourceNotificationType notificationType;
		private final @Nullable GrantEntry entry;
		private final long dirtySequence;
		private final List<Get> eligibleGets;
		private Delivery(Session session, McpResourceNotificationType type, @Nullable GrantEntry entry,
				long sequence, List<Get> eligibleGets) {
			this.session = session; this.notificationType = type; this.entry = entry;
			this.dirtySequence = sequence; this.eligibleGets = List.copyOf(eligibleGets);
		}
		@NonNull McpResourceNotificationType notificationType() { return notificationType; }
		@NonNull Optional<@NonNull String> uri() { return entry == null ? Optional.empty() : Optional.of(entry.uri); }
		@NonNull List<@NonNull Get> eligibleGets() { return eligibleGets; }
		@NonNull Optional<@NonNull DeliveryAttempt> forGet(@NonNull Get get) {
			requireNonNull(get);
			synchronized (lock) {
				if (get.session != session || !eligibleGets.contains(get)) return Optional.empty();
				DeliveryAttempt attempt = new DeliveryAttempt(get, notificationType, entry, dirtySequence);
				return attempt.valid() && attempt.currentDirtyWhileLocked() && !attempt.alreadyOfferedWhileLocked()
						? Optional.of(attempt) : Optional.empty();
			}
		}
		@Override public String toString() { return "Delivery[redacted]"; }
	}

	/** Lock-free predicate suitable for the transport's actual socket-write boundary. */
	@ThreadSafe
	final class DeliveryAttempt {
		private final Get get;
		private final McpResourceNotificationType notificationType;
		private final @Nullable GrantEntry entry;
		private final long dirtySequence;
		private final long getGeneration;
		private final long grantGeneration;
		private final BooleanSupplier sourceActive;
		private int reservations;
		private DeliveryAttempt(Get get, McpResourceNotificationType type, @Nullable GrantEntry entry, long sequence) {
			this.get = get; this.notificationType = type; this.entry = entry; this.dirtySequence = sequence;
			this.getGeneration = get.authorizationGeneration; this.grantGeneration = entry == null ? 0L : entry.generation;
			this.sourceActive = entry == null
					? requireNonNull(get.session.catalogDirty.get(type)).sourceActive : entry.sourceActive;
		}
		@NonNull Get get() { return get; }
		@NonNull McpResourceNotificationType notificationType() { return notificationType; }
		@NonNull Optional<@NonNull String> uri() { return entry == null ? Optional.empty() : Optional.of(entry.uri); }
		boolean valid() {
			long now = clock.nanoTime();
			return sourceActive.getAsBoolean() && get.validGeneration(getGeneration, notificationType, now)
					&& (entry == null || entry.generation == grantGeneration && grantDeliverable(entry, now));
		}
		private boolean currentDirtyWhileLocked() {
			if (entry != null) return entry.dirty && entry.dirtySequence == dirtySequence;
			CatalogDirty dirty = get.session.catalogDirty.get(notificationType);
			return dirty != null && dirty.dirty && dirty.sequence == dirtySequence;
		}
		private boolean alreadyOfferedWhileLocked() {
			if (entry != null) return entry.lastOfferedGet == get && entry.lastOfferedSequence == dirtySequence;
			CatalogDirty dirty = get.session.catalogDirty.get(notificationType);
			return dirty != null && dirty.lastOfferedGet == get;
		}
		@Override public String toString() { return "DeliveryAttempt[redacted]"; }
	}

	@ThreadSafe
	final class NotificationReservation {
		private final DeliveryAttempt attempt;
		private final long encodedBytes;
		private final AtomicBoolean released = new AtomicBoolean();
		private NotificationReservation(DeliveryAttempt attempt, long encodedBytes) {
			this.attempt = attempt; this.encodedBytes = encodedBytes;
		}
		boolean valid() { return !released.get() && attempt.valid(); }
		void release() {
			if (!released.compareAndSet(false, true)) return;
			synchronized (lock) {
				OwnerUsage usage = attempt.get.session.usage;
				usage.queuedNotificationBytes -= encodedBytes; queuedNotificationBytes -= encodedBytes; attempt.reservations--;
				attempt.get.queuedNotificationBytes -= encodedBytes;
				removeOwnerIfCompleteWhileLocked(attempt.get.session);
			}
		}
		@Override public String toString() { return "NotificationReservation[redacted]"; }
	}

	/** The deterministic token seam is package-private and absent from public configuration. */
	McpLegacySessionStore(@NonNull Config config, @NonNull McpJsonLimits limits,
			@NonNull McpApplicationClock clock, @NonNull Supplier<byte[]> tokenSource) {
		this.config = requireNonNull(config);
		requireNonNull(limits);
		this.clock = requireNonNull(clock);
		this.tokenSource = requireNonNull(tokenSource);
		this.snapshotCodec = new McpJsonCodec(new McpJsonLimits(
				limits.maximumInputBytes(), limits.maximumNestingDepth(),
				limits.maximumTokenLengthInCharacters(), limits.maximumStringLengthInCharacters(),
				limits.maximumNumberLengthInCharacters(), limits.maximumExponentMagnitude(),
				Math.min(MAXIMUM_METADATA_NODES, limits.maximumNodeCount()),
				Math.min(config.maximumClientMetadataBytes(), limits.maximumOutputBytes())));
	}

	/** Atomically publishes before its caller offers the ID in HTTP bytes. */
	@NonNull Allocation publish(@NonNull Owner owner, @NonNull String path,
			@NonNull String revision, @NonNull Object lifecycleGeneration,
			@NonNull Snapshot snapshot, @NonNull Target target) {
		return publish(owner, path, revision, lifecycleGeneration, snapshot, target, 0L);
	}

	@NonNull Allocation publish(@NonNull Owner owner, @NonNull String path,
			@NonNull String revision, @NonNull Object lifecycleGeneration,
			@NonNull Snapshot snapshot, @NonNull Target target, long retainedRequestEvidenceBytes) {
		requireNonNull(owner); requireNonNull(lifecycleGeneration);
		requireNonNull(snapshot); requireNonNull(target);
		requireEvidence(retainedRequestEvidenceBytes);
		McpEndpointPathLimit.requireValidWirePath(path);
		requireLegacy(revision);
		if (owner.anonymous() && !config.anonymousAllowed()) return allocation(Status.ANONYMOUS_DENIED);
		long bytes;
		try {
			Map<String, McpJsonValue> projection = new LinkedHashMap<>();
			projection.put("capabilities", snapshot.clientCapabilities().toJsonObject());
			snapshot.clientInformation().ifPresent(value -> projection.put("clientInfo", value.toJsonObject()));
			bytes = Math.addExact(snapshotCodec.toUtf8Bytes(new McpJsonObject(projection)).length,
					(long) path.length() + owner.key().getBytes(StandardCharsets.UTF_8).length + 43L);
		} catch (IllegalArgumentException | ArithmeticException exception) {
			return allocation(Status.METADATA_TOO_LARGE);
		}
		if (exceeds(bytes, retainedRequestEvidenceBytes, config.maximumSessionEvidenceBytes()))
			return allocation(Status.OWNER_CAPACITY);
		List<Runnable> actions = new ArrayList<>();
		Allocation result;
		long allocationBytes = Math.addExact(bytes, retainedRequestEvidenceBytes);
		synchronized (lock) {
			long now = clock.nanoTime();
			maintainWhileLocked(now, actions);
			if (owner.anonymous() && !config.anonymousAllowed()) result = allocation(Status.ANONYMOUS_DENIED);
			else {
				OwnerUsage usage = owners.get(owner);
				Status capacity = publicationCapacityWhileLocked(owner, usage, allocationBytes);
				while (capacity != Status.ACCEPTED) {
					Session evictable = oldestEvictableWhileLocked(owner, now);
					if (evictable == null) break;
					retireWhileLocked(evictable, Cause.SESSION_CLOSED, actions);
					usage = owners.get(owner);
					capacity = publicationCapacityWhileLocked(owner, usage, allocationBytes);
				}
				if (capacity != Status.ACCEPTED) result = allocation(capacity);
				else {
					String id = mintWhileLocked();
					if (id == null) result = allocation(Status.INTERNAL_FAILURE);
					else {
						if (usage == null) { usage = new OwnerUsage(); owners.put(owner, usage); }
						Session session = new Session(id, owner, path, revision, lifecycleGeneration,
								snapshot, bytes, now, ++sequence, usage);
						Initialization initialization = new Initialization(session, target, retainedRequestEvidenceBytes);
						session.retainedBytes = allocationBytes;
						session.initialization = initialization;
						session.physicalReferences = 1;
						sessions.put(id, session);
						usage.liveSessions++;
						usage.retainedBytes += allocationBytes;
						retainedBytes += allocationBytes;
						physicalReferences++;
						if (owner.anonymous()) anonymousSessions++;
						result = new Allocation(Status.ACCEPTED, Optional.of(initialization));
					}
				}
			}
		}
		run(actions);
		return result;
	}

	/**
	 * Owner/path/generation failures are intentionally indistinguishable. Only
	 * after those match may the caller observe a stored-revision mismatch.
	 * A returned handle is not activity until acceptedUse or acknowledge.
	 */
	@NonNull Acquisition acquire(@NonNull String sessionId, @NonNull Owner owner,
			@NonNull String path, @NonNull String revision, @NonNull Object lifecycleGeneration,
			@Nullable McpJsonRpcId requestId, @Nullable McpProgressToken progressToken,
			@NonNull Target target) {
		return acquire(sessionId, owner, path, revision, lifecycleGeneration, requestId,
				progressToken, target, 0L);
	}

	@NonNull Acquisition acquire(@NonNull String sessionId, @NonNull Owner owner,
			@NonNull String path, @NonNull String revision, @NonNull Object lifecycleGeneration,
			@Nullable McpJsonRpcId requestId, @Nullable McpProgressToken progressToken,
			@NonNull Target target, long retainedRequestEvidenceBytes) {
		requireNonNull(owner); requireNonNull(lifecycleGeneration); requireNonNull(target);
		requireEvidence(retainedRequestEvidenceBytes);
		requireNonNull(path);
		requireLegacy(revision);
		if (!validSessionId(sessionId) || !validRequestId(requestId)) return acquisition(Status.INVALID_ID);
		List<Runnable> actions = new ArrayList<>();
		Acquisition result;
		synchronized (lock) {
			long now = clock.nanoTime();
			maintainWhileLocked(now, actions);
			Session session = sessions.get(sessionId);
			if (session == null || !session.owner.equals(owner) || !session.path.equals(path)
					|| session.lifecycleGeneration != lifecycleGeneration)
				result = acquisition(Status.NOT_FOUND);
			else if (!session.revision.equals(revision)) result = acquisition(Status.REVISION_MISMATCH);
			else if (requestId != null && session.requests.containsKey(requestId)) result = acquisition(Status.ACTIVE_ID_COLLISION);
			else if (requestId != null && session.requests.size() >= MAXIMUM_ACTIVE_CALLS_PER_SESSION)
				result = acquisition(Status.CALL_CAPACITY);
			else if (exceeds(session.retainedBytes, retainedRequestEvidenceBytes, config.maximumSessionEvidenceBytes())
					|| exceeds(session.usage.retainedBytes, retainedRequestEvidenceBytes, config.maximumOwnerEvidenceBytes()))
				result = acquisition(Status.OWNER_CAPACITY);
			else if (exceeds(retainedBytes, retainedRequestEvidenceBytes, config.maximumGlobalEvidenceBytes()))
				result = acquisition(Status.GLOBAL_CAPACITY);
			else {
				McpProgressToken retainedToken = requestId != null && validProgressToken(progressToken)
						&& !session.progressTokens.contains(progressToken) ? progressToken : null;
				Call call = new Call(session, requestId, retainedToken, target, retainedRequestEvidenceBytes);
				session.retainedBytes += retainedRequestEvidenceBytes;
				session.usage.retainedBytes += retainedRequestEvidenceBytes;
				retainedBytes += retainedRequestEvidenceBytes;
				session.uses.add(call);
				if (requestId != null) session.requests.put(requestId, call);
				if (retainedToken != null) session.progressTokens.add(retainedToken);
				session.physicalReferences++;
				physicalReferences++;
				result = new Acquisition(Status.ACCEPTED, Optional.of(call));
			}
		}
		run(actions);
		return result;
	}

	/** Freshly verified use, not raw lookup or bytes, is delivery evidence. */
	boolean acknowledge(@NonNull Call call) {
		return acceptUse(requireNonNull(call), true);
	}

	/** The target performs final-response-versus-cancellation reservation outside our lock. */
	boolean cancel(@NonNull Call verifiedUse, @NonNull McpJsonRpcId requestId) {
		requireNonNull(verifiedUse); requireNonNull(requestId);
		if (!validRequestId(requestId)) return false;
		Target target;
		List<Runnable> actions = new ArrayList<>();
		synchronized (lock) {
			maintainWhileLocked(clock.nanoTime(), actions);
			if (!owned(verifiedUse) || !verifiedUse.logical || !verifiedUse.session.live
					|| !verifiedUse.accepted) target = null;
			else {
				Call matching = verifiedUse.session.requests.get(requestId);
				target = matching == null ? null : matching.target;
			}
		}
		run(actions);
		try { return target != null && target.cancel(Cause.CLIENT_CANCEL); }
		catch (Throwable ignored) { return false; }
	}

	void retire(@NonNull Call verifiedUse, @NonNull Cause cause) {
		requireNonNull(verifiedUse); requireNonNull(cause);
		List<Runnable> actions = new ArrayList<>();
		synchronized (lock) {
			if (owned(verifiedUse) && verifiedUse.logical && verifiedUse.session.live
					&& verifiedUse.accepted)
				retireWhileLocked(verifiedUse.session, cause, actions);
		}
		run(actions);
	}

	void maintain() {
		List<Runnable> actions = new ArrayList<>();
		synchronized (lock) { maintainWhileLocked(clock.nanoTime(), actions); }
		run(actions);
	}

	void close() {
		List<Runnable> actions = new ArrayList<>();
		synchronized (lock) {
			for (Session session : List.copyOf(sessions.values()))
				retireWhileLocked(session, Cause.SERVER_STOPPING, actions);
		}
		run(actions);
	}

	@NonNull Counts counts() {
		synchronized (lock) { return new Counts(sessions.size(), anonymousSessions, owners.size(), retainedBytes, physicalReferences); }
	}

	@ThreadSafe
	final class Initialization {
		private final Session session;
		private @Nullable Target target;
		private final long evidenceBytes;
		private boolean physical = true;

		private Initialization(Session session, Target target, long evidenceBytes) {
			this.session = session; this.target = target; this.evidenceBytes = evidenceBytes;
		}
		@NonNull String sessionId() { return session.id; }
		@NonNull Snapshot snapshot() { return session.snapshot; }

		/** A failed initial writer cannot revoke delivery already proved by accepted use. */
		void deliveryFailed() {
			List<Runnable> actions = new ArrayList<>();
			synchronized (lock) {
				if (!session.deliveryEvidence) retireWhileLocked(session, Cause.SESSION_CLOSED, actions);
			}
			run(actions);
		}

		void physicalComplete() {
			synchronized (lock) {
				if (!physical) return;
				physical = false;
				target = null;
				session.initialization = null;
				releaseRequestEvidenceWhileLocked(session, evidenceBytes);
				releasePhysicalWhileLocked(session);
			}
		}
		@Override public String toString() { return "Initialization[redacted]"; }
	}

	@ThreadSafe
	final class Call {
		private final Session session;
		private final @Nullable McpJsonRpcId requestId;
		private @Nullable McpProgressToken progressToken;
		private @Nullable Target target;
		private final long evidenceBytes;
		private final EvidenceReservation evidence;
		private boolean logical = true;
		private boolean physical = true;
		private boolean accepted;

		private Call(Session session, @Nullable McpJsonRpcId requestId,
				@Nullable McpProgressToken progressToken, Target target, long evidenceBytes) {
			this.session = session; this.requestId = requestId;
			this.progressToken = progressToken; this.target = target; this.evidenceBytes = evidenceBytes;
			this.evidence = new EvidenceReservation(session, evidenceBytes);
		}

		@NonNull Snapshot snapshot() { return session.snapshot; }
		boolean progressAllowed() { synchronized (lock) { return logical && session.live && progressToken != null; } }
		boolean acceptedUse() { return acceptUse(this, false); }
		void detachProgress() { synchronized (lock) { releaseProgressWhileLocked(this); } }
		void logicalComplete() { synchronized (lock) { logicalCompleteWhileLocked(this); } }
		void physicalComplete() {
			synchronized (lock) {
				if (!physical) return;
				physical = false;
				logicalCompleteWhileLocked(this);
				releaseSharedEvidenceWhileLocked(evidence);
				releasePhysicalWhileLocked(session);
			}
		}
		@Override public String toString() { return "Call[redacted]"; }
		private McpLegacySessionStore store() { return McpLegacySessionStore.this; }
	}

	/** One historical GET evidence reservation, retained until physical exit. */
	@ThreadSafe
	final class Get {
		private final Session session;
		private @Nullable GetTarget target;
		private final long evidenceBytes;
		private final long totalDeadlineNanos;
		private volatile long leaseDeadlineNanos;
		private volatile long authorizationGeneration = 1L;
		private volatile boolean authorized = true;
		private volatile boolean logical = true;
		private boolean physical = true;
		private volatile Set<McpResourceNotificationType> notificationTypes;
		private final boolean accountDemand;
		private long maintenanceDemandUnits;
		private long queuedNotificationBytes;

		private Get(Session session, GetTarget target, long evidenceBytes,
				long leaseDeadlineNanos, long totalDeadlineNanos, long now,
				Set<McpResourceNotificationType> notificationTypes, boolean accountDemand, long demand) {
			this.session = session; this.target = target; this.evidenceBytes = evidenceBytes;
			this.totalDeadlineNanos = earlierDeadline(now, totalDeadlineNanos, session.createdNanos + config.lifetimeNanos());
			this.leaseDeadlineNanos = earlierDeadline(now, leaseDeadlineNanos, this.totalDeadlineNanos);
			this.notificationTypes = notificationTypes; this.accountDemand = accountDemand;
			this.maintenanceDemandUnits = demand;
		}

		@NonNull Snapshot snapshot() { return session.snapshot; }
		@NonNull Owner owner() { return session.owner; }
		@NonNull String path() { return session.path; }
		@NonNull String revision() { return session.revision; }
		@NonNull Object lifecycleGeneration() { return session.lifecycleGeneration; }
		@NonNull Optional<@NonNull GetTarget> target() { synchronized (lock) { return Optional.ofNullable(target); } }
		long generation() { synchronized (lock) { return authorizationGeneration; } }
		long deadlineNanos() { synchronized (lock) { return leaseDeadlineNanos; } }
		long totalDeadlineNanos() { return totalDeadlineNanos; }
		boolean active() {
			synchronized (lock) {
				return logical && session.live && authorized && clock.nanoTime() - leaseDeadlineNanos < 0L;
			}
		}
		long fence() {
			List<Runnable> actions = new ArrayList<>();
			long generation;
			synchronized (lock) {
				maintainWhileLocked(clock.nanoTime(), actions);
				if (logical && session.live) fenceGetWhileLocked(this, actions);
				generation = authorizationGeneration;
			}
			run(actions);
			return generation;
		}
		boolean fenceIfCurrent(long expectedGeneration) {
			List<Runnable> actions = new ArrayList<>(); boolean fenced;
			synchronized (lock) {
				maintainWhileLocked(clock.nanoTime(), actions);
				fenced = logical && session.live && authorizationGeneration == expectedGeneration;
				if (fenced) fenceGetWhileLocked(this, actions);
			}
			run(actions); return fenced;
		}
		/** A stale result cannot clear a later reconciliation or revive an expired GET. */
		boolean renew(long expectedGeneration, @NonNull Owner freshOwner,
				@NonNull McpEffectivePartition freshPartition, long freshLeaseDeadlineNanos) {
			return renewStatus(expectedGeneration, freshOwner, freshPartition, freshLeaseDeadlineNanos,
					notificationTypes) == Status.ACCEPTED;
		}
		boolean renew(long expectedGeneration, @NonNull Owner freshOwner,
				@NonNull McpEffectivePartition freshPartition, long freshLeaseDeadlineNanos,
				@NonNull Set<@NonNull McpResourceNotificationType> freshNotificationTypes) {
			return renewStatus(expectedGeneration, freshOwner, freshPartition, freshLeaseDeadlineNanos,
					freshNotificationTypes) == Status.ACCEPTED;
		}
		@NonNull Status renewStatus(long expectedGeneration, @NonNull Owner freshOwner,
				@NonNull McpEffectivePartition freshPartition, long freshLeaseDeadlineNanos,
				@NonNull Set<@NonNull McpResourceNotificationType> freshNotificationTypes) {
			requireNonNull(freshOwner); requireNonNull(freshPartition);
			Set<McpResourceNotificationType> families = Set.copyOf(requireNonNull(freshNotificationTypes));
			List<Runnable> actions = new ArrayList<>();
			Status result = Status.NOT_FOUND;
			synchronized (lock) {
				long now = clock.nanoTime();
				maintainWhileLocked(now, actions);
				if (logical && session.live && authorizationGeneration == expectedGeneration) {
					if (!session.owner.equals(freshOwner) || !requireNonNull(session.deliveryPartition).equals(freshPartition)) {
						retireGetWhileLocked(this, GetCause.AUTHORIZATION_DENIED, actions);
						result = Status.PARTITION_MISMATCH;
					} else if (now - freshLeaseDeadlineNanos >= 0L) {
						retireGetWhileLocked(this, GetCause.LEASE_EXPIRED, actions);
						result = Status.AUTHORIZATION_EXPIRED;
					} else {
						long deadline = earlierDeadline(now, freshLeaseDeadlineNanos, totalDeadlineNanos);
						long demand = accountDemand ? demandUnits(deadline - now) : 0L;
						if (exceeds(McpLegacySessionStore.this.maintenanceDemandUnits - maintenanceDemandUnits,
								demand, MAXIMUM_MAINTENANCE_DEMAND_UNITS)) result = Status.GLOBAL_CAPACITY;
						else {
							McpLegacySessionStore.this.maintenanceDemandUnits += demand - maintenanceDemandUnits;
							invalidatePendingGetHintsWhileLocked(this);
							maintenanceDemandUnits = demand; leaseDeadlineNanos = deadline;
							notificationTypes = families; authorizationGeneration++; authorized = true;
							result = Status.ACCEPTED;
						}
					}
				}
			}
			run(actions);
			return result;
		}
		private boolean validGeneration(long expected, McpResourceNotificationType type, long now) {
			return logical && session.live && authorized && authorizationGeneration == expected
					&& notificationTypes.contains(type) && now - leaseDeadlineNanos < 0L;
		}
		void retire(@NonNull GetCause cause) {
			List<Runnable> actions = new ArrayList<>();
			synchronized (lock) { retireGetWhileLocked(this, requireNonNull(cause), actions); }
			run(actions);
		}
		boolean retireIfCurrent(long expectedGeneration, @NonNull GetCause cause) {
			List<Runnable> actions = new ArrayList<>(); boolean retired;
			synchronized (lock) {
				retired = logical && session.live && authorizationGeneration == expectedGeneration;
				if (retired) retireGetWhileLocked(this, requireNonNull(cause), actions);
			}
			run(actions); return retired;
		}
		void logicalComplete() {
			List<Runnable> actions = new ArrayList<>();
			synchronized (lock) { completeGetWhileLocked(this, actions); }
			run(actions);
		}
		void physicalComplete() {
			List<Runnable> actions = new ArrayList<>();
			synchronized (lock) {
				if (!physical) return;
				physical = false;
				completeGetWhileLocked(this, actions);
				physicalGets--;
				releaseRequestEvidenceWhileLocked(session, evidenceBytes);
				releasePhysicalWhileLocked(session);
			}
			run(actions);
		}
		@Override public String toString() { return "Get[redacted]"; }
	}

	/** Historical subscribe evidence persists for the logical grant and any unfinished callback. */
	@ThreadSafe
	final class Grant {
		private final GrantEntry entry;
		private final EvidenceReservation evidence;
		private @Nullable GrantTarget target;
		private volatile boolean logical = true;
		private boolean initialPhysical = true;

		private Grant(GrantEntry entry, GrantTarget target, EvidenceReservation evidence) {
			this.entry = entry; this.target = target; this.evidence = evidence;
		}
		@NonNull String uri() { return entry.uri; }
		@NonNull Owner owner() { return entry.session.owner; }
		@NonNull String path() { return entry.session.path; }
		@NonNull String revision() { return entry.session.revision; }
		@NonNull Object lifecycleGeneration() { return entry.session.lifecycleGeneration; }
		@NonNull Snapshot snapshot() { return entry.session.snapshot; }
		@NonNull Optional<@NonNull GrantTarget> target() { synchronized (lock) { return Optional.ofNullable(target); } }
		long generation() { return entry.generation; }
		long deadlineNanos() { synchronized (lock) { return entry.leaseDeadlineNanos; } }
		long totalDeadlineNanos() { return entry.totalDeadlineNanos; }
		long retainedEvidenceBytes() { return evidence.bytes; }
		boolean active() {
			return logical && entry.live && entry.session.live && entry.activeGrant == this
					&& clock.nanoTime() - entry.leaseDeadlineNanos < 0L;
		}
		boolean current() {
			long now = clock.nanoTime();
			return logical && entry.live && entry.session.live && now - entry.totalDeadlineNanos < 0L
					&& (entry.pending == this || entry.activeGrant == this && now - entry.leaseDeadlineNanos < 0L);
		}
		/** The body slot survives replacement and retirement while an ignored callback still runs. */
		boolean tryBeginAuthorization() {
			synchronized (lock) {
				if (!current() || !(entry.pending == this || entry.pending == null && entry.activeGrant == this)
						|| entry.authorizationSlot.busy != null) return false;
				entry.authorizationSlot.busy = this; return true;
			}
		}
		void finishAuthorization() {
			synchronized (lock) {
				if (entry.authorizationSlot.busy == this) entry.authorizationSlot.busy = null;
			}
		}
		boolean isFenced() { return !entry.authorized || entry.pending != null; }
		long fence() {
			List<Runnable> actions = new ArrayList<>();
			synchronized (lock) {
				maintainWhileLocked(clock.nanoTime(), actions);
				if (logical && entry.live && (entry.activeGrant == this || entry.pending == this))
					fenceGrantEntryWhileLocked(entry, actions);
			}
			run(actions); return entry.generation;
		}
		boolean fenceIfCurrent(long expectedGeneration) {
			List<Runnable> actions = new ArrayList<>(); boolean fenced;
			synchronized (lock) {
				maintainWhileLocked(clock.nanoTime(), actions);
				fenced = logical && entry.live && entry.session.live && entry.generation == expectedGeneration
						&& (entry.activeGrant == this || entry.pending == this);
				if (fenced) fenceGrantEntryWhileLocked(entry, actions);
			}
			run(actions); return fenced;
		}
		boolean commit(long expectedGeneration, long leaseDeadlineNanos) {
			return commitStatus(expectedGeneration, leaseDeadlineNanos) == Status.ACCEPTED;
		}
		@NonNull Status commitStatus(long expectedGeneration, long leaseDeadlineNanos) {
			return updateGrant(this, expectedGeneration, leaseDeadlineNanos, true);
		}
		boolean renew(long expectedGeneration, long leaseDeadlineNanos) {
			return renewStatus(expectedGeneration, leaseDeadlineNanos) == Status.ACCEPTED;
		}
		@NonNull Status renewStatus(long expectedGeneration, long leaseDeadlineNanos) {
			return updateGrant(this, expectedGeneration, leaseDeadlineNanos, false);
		}
		/** Reconciliation can renew a fenced current grant without lifting its writer fence early. */
		@NonNull Optional<@NonNull GrantWork> acquireWork(long expectedGeneration) {
			List<Runnable> actions = new ArrayList<>(); GrantWork result = null;
			synchronized (lock) {
				long now = clock.nanoTime(); maintainWhileLocked(now, actions);
				if (logical && entry.live && entry.session.live && entry.activeGrant == this
						&& entry.pending == null && entry.generation == expectedGeneration
						&& now - entry.leaseDeadlineNanos < 0L) {
					evidence.references++; entry.physicalHolds++; physicalGrantHolds++;
					entry.session.physicalReferences++; physicalReferences++;
					result = new GrantWork(this);
				}
			}
			run(actions); return Optional.ofNullable(result);
		}
		/** A transient first-authorization failure must never leave a callback able to establish later. */
		void abort() {
			List<Runnable> actions = new ArrayList<>();
			synchronized (lock) {
				if (logical && entry.live && entry.pending == this) {
					entry.pending = null;
					retireGrantHandleWhileLocked(this, GrantCause.AUTHORIZATION_FAILED, actions);
					if (entry.activeGrant == null) retireGrantEntryWhileLocked(entry, GrantCause.AUTHORIZATION_FAILED, actions);
					else fenceGrantEntryWhileLocked(entry, actions);
				}
			}
			run(actions);
		}
		void retire(@NonNull GrantCause cause) {
			List<Runnable> actions = new ArrayList<>();
			synchronized (lock) {
				if (logical && entry.live && (entry.activeGrant == this || entry.pending == this))
					retireGrantEntryWhileLocked(entry, requireNonNull(cause), actions);
			}
			run(actions);
		}
		/** Stale denial/failure cannot remove permission reinstated by a later renewal. */
		boolean retireIfCurrent(long expectedGeneration, @NonNull GrantCause cause) {
			List<Runnable> actions = new ArrayList<>(); boolean retired;
			synchronized (lock) {
				retired = logical && entry.live && entry.generation == expectedGeneration
						&& (entry.activeGrant == this || entry.pending == this);
				if (retired) retireGrantEntryWhileLocked(entry, requireNonNull(cause), actions);
			}
			run(actions); return retired;
		}
		/** Releases the establishing callback hold, never the still-active historical request. */
		void physicalComplete() {
			synchronized (lock) {
				if (!initialPhysical) return;
				initialPhysical = false;
				releaseGrantPhysicalWhileLocked(this);
			}
		}
		@Override public String toString() { return "Grant[redacted]"; }
	}

	/** One renewal callback's physical evidence pin, including after logical permission is gone. */
	@ThreadSafe
	final class GrantWork implements AutoCloseable {
		private final Grant grant;
		private boolean physical = true;
		private GrantWork(Grant grant) { this.grant = grant; }
		long retainedEvidenceBytes() { return grant.evidence.bytes; }
		void physicalComplete() { close(); }
		@Override public void close() {
			synchronized (lock) {
				if (!physical) return;
				physical = false; releaseGrantPhysicalWhileLocked(grant);
			}
		}
		@Override public String toString() { return "GrantWork[redacted]"; }
	}

	private Status updateGrant(Grant grant, long expectedGeneration, long leaseDeadlineNanos, boolean establishing) {
		List<Runnable> actions = new ArrayList<>(); Status result = Status.NOT_FOUND;
		synchronized (lock) {
			long now = clock.nanoTime(); maintainWhileLocked(now, actions);
			GrantEntry entry = grant.entry;
			boolean current = establishing ? entry.pending == grant
					: entry.activeGrant == grant && entry.pending == null;
			if (grant.logical && entry.live && entry.session.live && current && entry.generation == expectedGeneration) {
				long deadline = earlierDeadline(now, leaseDeadlineNanos, entry.totalDeadlineNanos);
				if (deadline - now <= 0L) result = Status.AUTHORIZATION_EXPIRED;
				else {
					long demand = demandUnits(deadline - now);
					if (exceeds(maintenanceDemandUnits - entry.maintenanceDemandUnits, demand,
							MAXIMUM_MAINTENANCE_DEMAND_UNITS)) result = Status.GLOBAL_CAPACITY;
					else {
						if (establishing && entry.activeGrant != null)
							retireGrantHandleWhileLocked(entry.activeGrant, GrantCause.REPLACED, actions);
						entry.pending = null; entry.activeGrant = grant;
						maintenanceDemandUnits += demand - entry.maintenanceDemandUnits;
						entry.maintenanceDemandUnits = demand;
						invalidatePendingGrantHintsWhileLocked(entry);
						entry.leaseDeadlineNanos = deadline; entry.generation++; entry.authorized = true;
						result = Status.ACCEPTED;
					}
				}
			}
		}
		run(actions); return result;
	}

	private boolean acceptUse(Call call, boolean acknowledge) {
		List<Runnable> actions = new ArrayList<>();
		boolean accepted;
		synchronized (lock) {
			long now = clock.nanoTime();
			maintainWhileLocked(now, actions);
			accepted = owned(call) && call.logical && call.session.live;
			if (accepted) {
				call.accepted = true;
				call.session.deliveryEvidence = true;
				call.session.lastActivityNanos = now;
				if (acknowledge) call.session.acknowledged = true;
			}
		}
		run(actions);
		return accepted;
	}

	private boolean owned(Call call) { return call.store() == this; }

	private void maintainWhileLocked(long now, List<Runnable> actions) {
		for (Session session : List.copyOf(sessions.values())) {
			boolean hardExpired = now - session.createdNanos >= config.lifetimeNanos();
			boolean ackExpired = !session.acknowledged && !session.deliveryEvidence
					&& now - session.createdNanos >= Math.min(ACKNOWLEDGEMENT_WAIT_NANOS, config.lifetimeNanos());
			boolean idleExpired = session.physicalReferences == 0
					&& now - session.quiescentNanos >= config.idleNanos()
					&& now - session.lastActivityNanos >= config.idleNanos();
			if (hardExpired || ackExpired || idleExpired)
				retireWhileLocked(session, Cause.SESSION_EXPIRED, actions);
			else {
				for (Get get : List.copyOf(session.gets)) {
					if (now - get.totalDeadlineNanos >= 0L) retireGetWhileLocked(get, GetCause.TOTAL_LIFETIME_EXPIRED, actions);
					else if (now - get.leaseDeadlineNanos >= 0L) retireGetWhileLocked(get, GetCause.LEASE_EXPIRED, actions);
				}
				for (GrantEntry entry : List.copyOf(session.grants.values())) {
					if (now - entry.totalDeadlineNanos >= 0L)
						retireGrantEntryWhileLocked(entry, GrantCause.TOTAL_LIFETIME_EXPIRED, actions);
					else if (entry.activeGrant != null && now - entry.leaseDeadlineNanos >= 0L)
						retireGrantEntryWhileLocked(entry, GrantCause.LEASE_EXPIRED, actions);
				}
			}
		}
	}

	private @Nullable Session oldestEvictableWhileLocked(Owner owner, long now) {
		Session oldest = null;
		for (Session candidate : sessions.values()) {
			if (!candidate.owner.equals(owner) || candidate.physicalReferences != 0
						|| now - candidate.quiescentNanos < MINIMUM_EVICTION_IDLE_NANOS
						|| now - candidate.lastActivityNanos < MINIMUM_EVICTION_IDLE_NANOS) continue;
			if (oldest == null || now - candidate.lastActivityNanos > now - oldest.lastActivityNanos
					|| (candidate.lastActivityNanos == oldest.lastActivityNanos && candidate.sequence < oldest.sequence))
				oldest = candidate;
		}
		return oldest;
	}

	private Status publicationCapacityWhileLocked(Owner owner, @Nullable OwnerUsage usage, long bytes) {
		if (usage != null && (usage.liveSessions >= config.maximumSessionsPerOwner()
				|| exceeds(usage.retainedBytes, bytes, config.maximumOwnerEvidenceBytes())))
			return Status.OWNER_CAPACITY;
		if (sessions.size() >= config.maximumSessions()
				|| owner.anonymous() && anonymousSessions >= Math.min(MAXIMUM_ANONYMOUS_SESSIONS, config.maximumSessions())
				|| exceeds(retainedBytes, bytes, config.maximumGlobalEvidenceBytes()))
			return Status.GLOBAL_CAPACITY;
		return Status.ACCEPTED;
	}

	private void retireWhileLocked(Session session, Cause cause, List<Runnable> actions) {
		if (!session.live) return;
		session.live = false;
		sessions.remove(session.id, session);
		session.usage.liveSessions--;
		if (session.owner.anonymous()) anonymousSessions--;
		Initialization initialization = session.initialization;
		if (initialization != null && initialization.target != null) {
			Target target = initialization.target;
			initialization.target = null;
			actions.add(() -> target.retire(cause));
		}
		for (Call call : List.copyOf(session.uses)) {
			Target target = call.target;
			logicalCompleteWhileLocked(call);
			if (target != null) actions.add(() -> target.retire(cause));
		}
		GetCause getCause = switch (cause) {
			case SESSION_EXPIRED -> GetCause.SESSION_EXPIRED;
			case SESSION_CLOSED, CLIENT_CANCEL -> GetCause.SESSION_CLOSED;
			case SERVER_STOPPING -> GetCause.SERVER_STOPPING;
		};
		for (Get get : List.copyOf(session.gets)) retireGetWhileLocked(get, getCause, actions);
		GrantCause grantCause = switch (cause) {
			case SESSION_EXPIRED -> GrantCause.SESSION_EXPIRED;
			case SESSION_CLOSED, CLIENT_CANCEL -> GrantCause.SESSION_CLOSED;
			case SERVER_STOPPING -> GrantCause.SERVER_STOPPING;
		};
		for (GrantEntry entry : List.copyOf(session.grants.values())) retireGrantEntryWhileLocked(entry, grantCause, actions);
		session.catalogDirty.clear();
		if (session.physicalReferences == 0) releaseEvidenceWhileLocked(session);
	}

	private void fenceGetWhileLocked(Get get, List<Runnable> actions) {
		if (!get.logical || !get.session.live) return;
		invalidatePendingGetHintsWhileLocked(get);
		get.authorized = false;
		long generation = ++get.authorizationGeneration;
		GetTarget target = get.target;
		if (target != null) actions.add(() -> target.fence(generation));
	}

	private void retireGetWhileLocked(Get get, GetCause cause, List<Runnable> actions) {
		if (!get.logical) return;
		GetTarget target = get.target;
		completeGetWhileLocked(get, actions);
		if (target != null) actions.add(() -> target.retire(cause));
	}

	private void completeGetWhileLocked(Get get, List<Runnable> actions) {
		if (!get.logical) return;
		get.logical = false; get.authorized = false; get.authorizationGeneration++; get.target = null;
		get.session.gets.remove(get); logicalGets--;
		maintenanceDemandUnits -= get.maintenanceDemandUnits; get.maintenanceDemandUnits = 0L;
		for (CatalogDirty dirty : get.session.catalogDirty.values()) if (dirty.lastOfferedGet == get) {
			dirty.lastOfferedGet = null; dirty.lastOfferedAttempt = null;
		}
		for (GrantEntry entry : get.session.grants.values()) if (entry.lastOfferedGet == get) {
			entry.lastOfferedGet = null; entry.lastOfferedAttempt = null;
		}
		maybeReleaseDeliveryQuotaWhileLocked(get.session, actions);
	}

	private void invalidatePendingGetHintsWhileLocked(Get get) {
		for (CatalogDirty dirty : get.session.catalogDirty.values())
			if (dirty.lastOfferedAttempt != null && dirty.lastOfferedAttempt.get == get
					&& dirty.lastOfferedAttempt.reservations > 0) {
				dirty.lastOfferedGet = null; dirty.lastOfferedAttempt = null;
			}
		for (GrantEntry entry : get.session.grants.values())
			if (entry.lastOfferedAttempt != null && entry.lastOfferedAttempt.get == get
						&& entry.lastOfferedAttempt.reservations > 0) {
				entry.lastOfferedGet = null; entry.lastOfferedAttempt = null;
			}
	}

	private void fenceGrantEntryWhileLocked(GrantEntry entry, List<Runnable> actions) {
		if (!entry.live || !entry.session.live) return;
		invalidatePendingGrantHintsWhileLocked(entry);
		entry.authorized = false; entry.generation++;
		fenceGrantTargetWhileLocked(entry.activeGrant, entry.generation, actions);
		fenceGrantTargetWhileLocked(entry.pending, entry.generation, actions);
	}

	/** A generation change drops queued guards, but does not repeat a hint whose payload was already released. */
	private void invalidatePendingGrantHintsWhileLocked(GrantEntry entry) {
		if (entry.lastOfferedAttempt != null && entry.lastOfferedAttempt.reservations > 0) {
			entry.lastOfferedGet = null; entry.lastOfferedAttempt = null;
		}
	}

	private void fenceGrantTargetWhileLocked(@Nullable Grant grant, long generation, List<Runnable> actions) {
		if (grant == null || grant.target == null) return;
		GrantTarget target = grant.target; actions.add(() -> target.fence(generation));
	}

	private void retireGrantHandleWhileLocked(Grant grant, GrantCause cause, List<Runnable> actions) {
		if (!grant.logical) return;
		grant.logical = false;
		GrantTarget target = grant.target; grant.target = null;
		releaseSharedEvidenceWhileLocked(grant.evidence);
		if (target != null) actions.add(() -> target.retire(cause));
	}

	private void retireGrantEntryWhileLocked(GrantEntry entry, GrantCause cause, List<Runnable> actions) {
		if (!entry.live) return;
		entry.live = false; entry.authorized = false; entry.generation++;
		entry.session.grants.remove(URI.create(entry.uri), entry);
		logicalGrants--; entry.session.usage.liveGrants--;
		maintenanceDemandUnits -= entry.maintenanceDemandUnits; entry.maintenanceDemandUnits = 0L;
		entry.dirty = false; entry.lastOfferedGet = null; entry.lastOfferedAttempt = null;
		if (entry.activeGrant != null) retireGrantHandleWhileLocked(entry.activeGrant, cause, actions);
		if (entry.pending != null) retireGrantHandleWhileLocked(entry.pending, cause, actions);
		entry.activeGrant = null; entry.pending = null;
		releaseUriEvidenceIfCompleteWhileLocked(entry);
		maybeReleaseDeliveryQuotaWhileLocked(entry.session, actions);
	}

	private void releaseGrantPhysicalWhileLocked(Grant grant) {
		releaseSharedEvidenceWhileLocked(grant.evidence);
		grant.entry.physicalHolds--; physicalGrantHolds--;
		releaseUriEvidenceIfCompleteWhileLocked(grant.entry);
		releasePhysicalWhileLocked(grant.entry.session);
	}

	private void releaseUriEvidenceIfCompleteWhileLocked(GrantEntry entry) {
		if (entry.live || entry.physicalHolds != 0 || entry.uriEvidenceReleased) return;
		entry.uriEvidenceReleased = true;
		entry.session.retainedUriBytes -= entry.uriBytes; retainedUriBytes -= entry.uriBytes;
		releaseRequestEvidenceWhileLocked(entry.session, entry.uriBytes);
		if (--entry.authorizationSlot.references == 0)
			entry.session.authorizationSlots.remove(URI.create(entry.uri), entry.authorizationSlot);
	}

	private static boolean validPartition(Session session, McpEffectivePartition partition) {
		return partition.purpose() == McpPartitionPurpose.AUTHORIZATION
				&& partition.endpointIdentity().endpointPath().equals(session.path)
				&& partition.applicationKey().map(value -> !value.isBlank() && fitsUtf8(value, MAXIMUM_CORRELATION_BYTES)).orElse(true)
				&& (session.deliveryPartition == null || session.deliveryPartition.equals(partition));
	}

	private static long partitionBytes(McpEffectivePartition partition) {
		return (long) partition.endpointIdentity().endpointPath().length()
				+ partition.applicationKey().map(value -> value.getBytes(StandardCharsets.UTF_8).length).orElse(0);
	}

	private Status ensureDeliveryQuotaWhileLocked(Session session, McpEffectivePartition partition) {
		if (session.deliveryQuotaReserved) return Status.ACCEPTED;
		if (getQuota == null) return Status.INTERNAL_FAILURE;
		boolean accepted;
		try { accepted = getQuota.reserve(partition); } catch (Throwable ignored) { return Status.INTERNAL_FAILURE; }
		if (!accepted) return Status.GLOBAL_CAPACITY;
		session.deliveryQuotaReserved = true; quotaRegistrations++; return Status.ACCEPTED;
	}

	private void maybeReleaseDeliveryQuotaWhileLocked(Session session, List<Runnable> actions) {
		if (!session.gets.isEmpty() || !session.grants.isEmpty() || !session.deliveryQuotaReserved) return;
		session.deliveryQuotaReserved = false; quotaRegistrations--;
		McpEffectivePartition partition = requireNonNull(session.deliveryPartition);
		GetQuota quota = requireNonNull(getQuota); actions.add(() -> quota.release(partition));
	}

	private void addEvidenceWhileLocked(Session session, long bytes) {
		session.retainedBytes += bytes; session.usage.retainedBytes += bytes; retainedBytes += bytes;
	}

	private void releaseSharedEvidenceWhileLocked(EvidenceReservation evidence) {
		if (--evidence.references == 0) releaseRequestEvidenceWhileLocked(evidence.session, evidence.bytes);
	}

	private List<Get> eligibleGetsWhileLocked(Session session, McpResourceNotificationType type, long now) {
		List<Get> result = new ArrayList<>();
		for (Get get : session.gets) if (get.validGeneration(get.authorizationGeneration, type, now)) result.add(get);
		java.util.Collections.reverse(result); return result;
	}

	private static boolean grantDeliverable(GrantEntry entry, long now) {
		return entry.live && entry.session.live && entry.authorized && entry.pending == null
				&& entry.activeGrant != null && entry.activeGrant.logical && now - entry.leaseDeadlineNanos < 0L;
	}

	private static void requireCatalogType(McpResourceNotificationType type) {
		if (requireNonNull(type) == McpResourceNotificationType.RESOURCE_UPDATED)
			throw new IllegalArgumentException("Resource updates require an authorized URI grant.");
	}

	private static boolean validResourceUri(String uri) {
		try { McpLevelOneUriTemplate.requireValidAbsoluteUri(requireNonNull(uri), "Resource subscription URI"); return true; }
		catch (IllegalArgumentException exception) { return false; }
	}

	private static long demandUnits(long remainingLeaseNanos) {
		if (remainingLeaseNanos <= 0L) return MAXIMUM_MAINTENANCE_DEMAND_UNITS + 1L;
		long numerator = 2_000_000_000_000_000L;
		long units = numerator / remainingLeaseNanos + (numerator % remainingLeaseNanos == 0L ? 0L : 1L);
		return Math.min(units, MAXIMUM_MAINTENANCE_DEMAND_UNITS + 1L);
	}

	private void logicalCompleteWhileLocked(Call call) {
		if (!call.logical) return;
		call.logical = false;
		call.target = null;
		call.session.uses.remove(call);
		if (call.requestId != null) call.session.requests.remove(call.requestId, call);
		releaseProgressWhileLocked(call);
	}

	private void releaseProgressWhileLocked(Call call) {
		if (call.progressToken == null) return;
		call.session.progressTokens.remove(call.progressToken);
		call.progressToken = null;
	}

	private void releasePhysicalWhileLocked(Session session) {
		session.physicalReferences--;
		physicalReferences--;
		if (session.physicalReferences == 0) {
			session.quiescentNanos = clock.nanoTime();
			if (!session.live) releaseEvidenceWhileLocked(session);
		}
	}

	private void releaseRequestEvidenceWhileLocked(Session session, long bytes) {
		session.retainedBytes -= bytes;
		session.usage.retainedBytes -= bytes;
		retainedBytes -= bytes;
	}

	private void releaseEvidenceWhileLocked(Session session) {
		if (session.evidenceReleased) return;
		session.evidenceReleased = true;
		session.usage.retainedBytes -= session.retainedBytes;
		retainedBytes -= session.retainedBytes;
		removeOwnerIfCompleteWhileLocked(session);
	}

	private void removeOwnerIfCompleteWhileLocked(Session session) {
		if (session.usage.liveSessions == 0 && session.usage.retainedBytes == 0 && session.usage.queuedNotificationBytes == 0)
			owners.remove(session.owner, session.usage);
	}

	private @Nullable String mintWhileLocked() {
		for (int attempt = 0; attempt < 16; attempt++) {
			byte[] token;
			try { token = tokenSource.get(); } catch (Throwable ignored) { return null; }
			if (token == null || token.length != 32) return null;
			String value = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
			if (!sessions.containsKey(value)) return value;
		}
		return null;
	}

	static boolean validSessionId(@NonNull String value) {
		requireNonNull(value);
		if (value.isEmpty() || value.length() > MAXIMUM_SESSION_ID_HEADER_BYTES) return false;
		for (int index = 0; index < value.length(); index++)
			if (value.charAt(index) < 0x21 || value.charAt(index) > 0x7E) return false;
		return true;
	}

	private static boolean validRequestId(@Nullable McpJsonRpcId id) {
		if (id == null) return true;
		return fitsUtf8(id instanceof McpJsonRpcId.StringId text ? text.value()
				: ((McpJsonRpcId.IntegerId) id).value().toString(), MAXIMUM_CORRELATION_BYTES);
	}

	private static boolean validProgressToken(@Nullable McpProgressToken token) {
		if (token == null) return false;
		return fitsUtf8(token instanceof McpProgressToken.StringToken text ? text.value()
				: ((McpProgressToken.IntegerToken) token).value().toString(), MAXIMUM_CORRELATION_BYTES);
	}

	private static boolean fitsUtf8(String value, int maximumBytes) {
		if (value.length() > maximumBytes) return false;
		int bytes = 0;
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (Character.isHighSurrogate(character)) {
				if (index + 1 == value.length() || !Character.isLowSurrogate(value.charAt(++index))) return false;
				bytes += 4;
			} else if (Character.isLowSurrogate(character)) return false;
			else bytes += character <= 0x7F ? 1 : character <= 0x7FF ? 2 : 3;
			if (bytes > maximumBytes) return false;
		}
		return true;
	}

	private static boolean exceeds(long used, long addition, long maximum) { return addition > maximum - used; }
	private static void requireEvidence(long bytes) {
		if (bytes < 0) throw new IllegalArgumentException("Retained session evidence bytes must not be negative.");
	}
	private static void requireLegacy(String revision) {
		if (!McpLegacyHttpWire.isLegacyRevision(requireNonNull(revision)))
			throw new IllegalArgumentException("Legacy sessions require an exact supported 2025 revision.");
	}
	private static Allocation allocation(Status status) { return new Allocation(status, Optional.empty()); }
	private static Acquisition acquisition(Status status) { return new Acquisition(status, Optional.empty()); }
	private static GetAllocation getAllocation(Status status) { return new GetAllocation(status, Optional.empty()); }
	private static GrantAllocation grantAllocation(Status status) { return new GrantAllocation(status, Optional.empty()); }
	private static long earlierDeadline(long now, long first, long second) {
		return first - now <= second - now ? first : second;
	}
	private static Supplier<byte[]> secureTokens() {
		SecureRandom random = new SecureRandom();
		return () -> { byte[] value = new byte[32]; random.nextBytes(value); return value; };
	}
	private static void run(List<Runnable> actions) {
		for (Runnable action : actions) {
			try { action.run(); } catch (Throwable ignored) {
				// Framework target failure cannot resurrect state or skip another retirement.
			}
		}
	}

	private static final class OwnerUsage {
		private int liveSessions;
		private int liveGrants;
		private long retainedBytes;
		private long queuedNotificationBytes;
	}
	private final class EvidenceReservation {
		private final Session session;
		private final long bytes;
		private int references = 1;
		private EvidenceReservation(Session session, long bytes) { this.session = session; this.bytes = bytes; }
	}
	private final class CatalogDirty {
		private boolean dirty;
		private long sequence;
		private @Nullable Get lastOfferedGet;
		private @Nullable DeliveryAttempt lastOfferedAttempt;
		private BooleanSupplier sourceActive = ALWAYS_ACTIVE_SOURCE;
	}
	private final class GrantEntry {
		private final Session session;
		private final String uri;
		private final long uriBytes;
		private final long totalDeadlineNanos;
		private final AuthorizationSlot authorizationSlot;
		private volatile long generation;
		private volatile long leaseDeadlineNanos;
		private volatile boolean live = true;
		private volatile boolean authorized;
		private volatile @Nullable Grant activeGrant;
		private volatile @Nullable Grant pending;
		private int physicalHolds;
		private boolean uriEvidenceReleased;
		private long maintenanceDemandUnits;
		private boolean dirty;
		private long dirtySequence;
		private @Nullable Get lastOfferedGet;
		private long lastOfferedSequence;
		private @Nullable DeliveryAttempt lastOfferedAttempt;
		private BooleanSupplier sourceActive = ALWAYS_ACTIVE_SOURCE;
		private GrantEntry(Session session, String uri, long uriBytes, long totalDeadlineNanos) {
			this.session = session; this.uri = uri; this.uriBytes = uriBytes; this.totalDeadlineNanos = totalDeadlineNanos;
			this.authorizationSlot = session.authorizationSlots.computeIfAbsent(URI.create(uri), ignored -> new AuthorizationSlot());
			this.authorizationSlot.references++;
		}
	}
	private final class AuthorizationSlot {
		private int references;
		private @Nullable Grant busy;
	}
	private final class Session {
		private final String id;
		private final Owner owner;
		private final String path;
		private final String revision;
		private final Object lifecycleGeneration;
		private final Snapshot snapshot;
		private long retainedBytes;
		private final long createdNanos;
		private final long sequence;
		private final OwnerUsage usage;
		private final Map<McpJsonRpcId, Call> requests = new HashMap<>();
		private final Set<McpProgressToken> progressTokens = new HashSet<>();
		private final Set<Call> uses = new HashSet<>();
		private final Set<Get> gets = new java.util.LinkedHashSet<>();
		private final Map<URI, GrantEntry> grants = new LinkedHashMap<>();
		private final Map<URI, AuthorizationSlot> authorizationSlots = new HashMap<>();
		private final Map<McpResourceNotificationType, CatalogDirty> catalogDirty = new EnumMap<>(McpResourceNotificationType.class);
		private long retainedUriBytes;
		private @Nullable McpEffectivePartition deliveryPartition;
		private boolean deliveryQuotaReserved;
		private @Nullable Initialization initialization;
		private long lastActivityNanos;
		private long quiescentNanos;
		private int physicalReferences;
		private volatile boolean live = true;
		private boolean deliveryEvidence;
		private boolean acknowledged;
		private boolean evidenceReleased;
		private Session(String id, Owner owner, String path, String revision, Object generation,
				Snapshot snapshot, long bytes, long now, long sequence, OwnerUsage usage) {
			this.id = id; this.owner = owner; this.path = path; this.revision = revision;
			this.lifecycleGeneration = generation; this.snapshot = snapshot;
			this.retainedBytes = bytes; this.createdNanos = now; this.lastActivityNanos = now;
			this.quiescentNanos = now; this.sequence = sequence; this.usage = usage;
		}
	}
}
