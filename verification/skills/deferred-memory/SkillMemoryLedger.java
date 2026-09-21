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
package com.soklet.internal.mcp.skills;

/**
 * Deferred experiment, outside production source roots. Not part of Skills v1.
 * Policy-neutral, private accounting arithmetic for two independent dimensions.
 * This class neither measures allocations nor decides when storage is dead.
 * It is not wired to parsing, bundles, catalogs, inspection, or transport.
 *
 * <p>Callers must reserve before allocating, charge bookkeeping as well as
 * payloads, and release only after ownership really ends. Zero charges are
 * permitted by this primitive; they are not free production allocations.
 * A reservation's close method is an internal rollback/release operation,
 * not a public bundle lease or permission to refund reachable values.</p>
 */
final class SkillMemoryLedger {
	private final long maximumRetainedBytes;
	private final long maximumTransientBytes;
	// All mutable fields, including reservation fields, use this ledger's monitor.
	private long retainedBytes;
	private long transientBytes;

	SkillMemoryLedger(long maximumRetainedBytes, long maximumTransientBytes) {
		if (maximumRetainedBytes <= 0 || maximumTransientBytes <= 0)
			throw new IllegalArgumentException("Skills memory limits must be positive.");
		this.maximumRetainedBytes = maximumRetainedBytes;
		this.maximumTransientBytes = maximumTransientBytes;
	}

	/** Reserves both dimensions atomically; rejection changes neither counter. */
	synchronized Reservation reserve(long retainedBytes, long transientBytes) {
		requireCharges(retainedBytes, transientBytes);
		requireCapacity(this.retainedBytes, this.transientBytes, retainedBytes, transientBytes);
		// Allocate the token before committing, so token-allocation failure cannot
		// leave an unreachable charge. Production callers must budget its overhead.
		Reservation reservation = new Reservation(retainedBytes, transientBytes);
		this.retainedBytes += retainedBytes;
		this.transientBytes += transientBytes;
		return reservation;
	}

	/** One linearizable observation; no sum that could overflow across dimensions. */
	synchronized Snapshot snapshot() {
		return new Snapshot(this.retainedBytes, this.transientBytes);
	}

	private void requireCapacity(long retainedByOthers, long transientByOthers,
			long retainedBytes, long transientBytes) {
		// All operands are nonnegative; subtraction avoids overflowing tentative
		// totals even when a limit or requested charge is Long.MAX_VALUE.
		if (retainedBytes > this.maximumRetainedBytes - retainedByOthers)
			throw new LimitExceededException(Kind.RETAINED);
		if (transientBytes > this.maximumTransientBytes - transientByOthers)
			throw new LimitExceededException(Kind.TRANSIENT);
	}

	private static void requireCharges(long retainedBytes, long transientBytes) {
		if (retainedBytes < 0 || transientBytes < 0)
			throw new IllegalArgumentException("Skills memory charges must be nonnegative.");
	}

	@Override
	public String toString() { return "SkillMemoryLedger[redacted]"; }

	/** Internal ownership token. It does not keep any charged payload alive. */
	final class Reservation implements AutoCloseable {
		private long retainedBytes;
		private long transientBytes;
		private boolean closed;

		private Reservation(long retainedBytes, long transientBytes) {
			this.retainedBytes = retainedBytes;
			this.transientBytes = transientBytes;
		}

		/**
		 * Replaces absolute charges atomically. Increases precede allocation;
		 * decreases follow actual release. This is not a publication transaction
		 * or a move of payloads between ownership categories.
		 */
		void resize(long retainedBytes, long transientBytes) {
			requireCharges(retainedBytes, transientBytes);
			synchronized (SkillMemoryLedger.this) {
				if (this.closed) throw new IllegalStateException("The Skills memory reservation is closed.");
				long retainedByOthers = SkillMemoryLedger.this.retainedBytes - this.retainedBytes;
				long transientByOthers = SkillMemoryLedger.this.transientBytes - this.transientBytes;
				requireCapacity(retainedByOthers, transientByOthers, retainedBytes, transientBytes);
				SkillMemoryLedger.this.retainedBytes = retainedByOthers + retainedBytes;
				SkillMemoryLedger.this.transientBytes = transientByOthers + transientBytes;
				this.retainedBytes = retainedBytes;
				this.transientBytes = transientBytes;
			}
		}

		/** Releases once; a later resize cannot reopen the reservation. */
		@Override
		public void close() {
			synchronized (SkillMemoryLedger.this) {
				if (this.closed) return;
				SkillMemoryLedger.this.retainedBytes -= this.retainedBytes;
				SkillMemoryLedger.this.transientBytes -= this.transientBytes;
				this.retainedBytes = 0;
				this.transientBytes = 0;
				this.closed = true;
			}
		}

		@Override
		public String toString() { return "SkillMemoryLedger.Reservation[redacted]"; }
	}

	record Snapshot(long retainedBytes, long transientBytes) {}

	enum Kind { RETAINED, TRANSIENT }

	/** Fixed diagnostics, containing no authored content or exception cause. */
	static final class LimitExceededException extends IllegalStateException {
		private final Kind kind;

		private LimitExceededException(Kind kind) {
			super(kind == Kind.RETAINED ? "The Skills retained memory limit was exceeded."
					: "The Skills transient memory limit was exceeded.");
			this.kind = kind;
		}

		Kind kind() { return this.kind; }
	}
}
