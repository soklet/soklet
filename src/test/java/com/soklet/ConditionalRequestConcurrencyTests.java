/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.soklet;

import com.soklet.ConditionalRequestResult.NotModified;
import com.soklet.ConditionalRequestResult.PreconditionFailed;
import com.soklet.ConditionalRequestResult.Proceed;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic application examples; no sleeps or database concurrency are required. */
class ConditionalRequestConcurrencyTests {
	private static final Instant MODIFIED = Instant.parse("2026-05-04T01:02:03.456Z");

	@Test
	void concreteRevisionBecomesFalseAfterALostRace() {
		Store store = new Store(5L);
		store.raceBeforeUpdate = () -> store.replaceWith(6L);
		assertEquals(412, update(request(Map.of("If-Match", List.of("\"v5\""))), store));
		assertEquals(2, store.reads);
		assertEquals(1, store.updateAttempts);
		assertEquals(0, store.effects);
	}

	@Test
	void wildcardListsNegativeConditionsAndUnconditionalWritesCanRemainTrue() {
		for (Map<String, List<String>> headers : List.of(
				Map.<String, List<String>>of(),
				Map.of("If-Match", List.of("*")),
				Map.of("If-Match", List.of("\"v5\", \"v6\"")),
				Map.of("If-None-Match", List.of("\"excluded\"")),
				Map.of("If-Unmodified-Since", List.of(HttpDate.toHeaderValue(MODIFIED))))) {
			Store store = new Store(5L);
			store.raceBeforeUpdate = () -> store.replaceWith(6L);
			assertEquals(204, update(request(headers), store), headers.toString());
			assertEquals(2, store.updateAttempts);
			assertEquals(2, store.reads);
			assertEquals(1, store.effects, "Only the successful CAS emits application effects");
			assertEquals(Long.valueOf(7), store.version);
		}
	}

	@Test
	void deletionAndSustainedContentionAreNotInventedFailedPreconditions() {
		Store deleted = new Store(5L);
		deleted.raceBeforeUpdate = () -> deleted.replaceWith(null);
		assertEquals(404, update(request(Map.of("If-Match", List.of("*"))), deleted));
		assertEquals(0, deleted.effects);

		Store contested = new Store(5L);
		contested.alwaysRace = true;
		assertEquals(409, update(request(Map.of("If-Match", List.of("*"))), contested));
		assertEquals(3, contested.updateAttempts);
		assertEquals(0, contested.effects);
	}

	@Test
	void uncertainCommitsAndUnrelatedFailuresNeverEnterTheCasRetryLoop() {
		Store uncertain = new Store(5L);
		uncertain.loseCommitAcknowledgment = true;
		assertThrows(UnknownCommit.class, () -> update(request(Map.of()), uncertain));
		assertEquals(1, uncertain.reads);
		assertEquals(1, uncertain.updateAttempts);
		assertEquals(1, uncertain.effects, "The caller must resolve this original operation, not blindly repeat it");

		Store invalidInsert = new Store(null);
		invalidInsert.insertFailure = new IllegalArgumentException("unrelated constraint");
		assertSame(invalidInsert.insertFailure,
				assertThrows(IllegalArgumentException.class, () -> upsert(request(Map.of()), invalidInsert)));
		assertEquals(1, invalidInsert.insertAttempts);
		assertEquals(1, invalidInsert.reads);
		assertEquals(0, invalidInsert.effects);
	}

	@Test
	void createOnlyRequestCannotOverwriteARivalInsert() {
		Request createOnly = request(Map.of("If-None-Match", List.of("*")));
		Store empty = new Store(null);
		assertEquals(201, upsert(createOnly, empty));
		assertEquals(1, empty.effects);

		Store raced = new Store(null);
		raced.raceBeforeInsert = () -> raced.replaceWith(6L);
		assertEquals(412, upsert(createOnly, raced));
		assertEquals(1, raced.insertAttempts);
		assertEquals(0, raced.updateAttempts);
		assertEquals(0, raced.effects);
		assertEquals(Long.valueOf(6), raced.version);
	}

	@Test
	void upsertReevaluatesExistenceOnEveryAttempt() {
		Store appeared = new Store(null);
		appeared.raceBeforeInsert = () -> appeared.replaceWith(6L);
		assertEquals(204, upsert(request(Map.of()), appeared));
		assertEquals(1, appeared.insertAttempts);
		assertEquals(1, appeared.updateAttempts);
		assertEquals(1, appeared.effects);

		Store disappeared = new Store(5L);
		disappeared.raceBeforeUpdate = () -> disappeared.replaceWith(null);
		assertEquals(201, upsert(request(Map.of()), disappeared));
		assertEquals(1, disappeared.updateAttempts);
		assertEquals(1, disappeared.insertAttempts);
		assertEquals(1, disappeared.effects);

		Store guardedDeletion = new Store(5L);
		guardedDeletion.raceBeforeUpdate = () -> guardedDeletion.replaceWith(null);
		assertEquals(412, upsert(request(Map.of("If-Match", List.of("\"v5\""))), guardedDeletion));
		assertEquals(0, guardedDeletion.insertAttempts);
		assertEquals(0, guardedDeletion.effects);
	}

	@Test
	void upsertHandlesMissingExistingStaleAndContendedConditions() {
		Store absent = new Store(null);
		assertEquals(412, upsert(request(Map.of("If-Match", List.of("*"))), absent));
		assertEquals(0, absent.insertAttempts);
		Store existing = new Store(5L);
		assertEquals(412, upsert(request(Map.of("If-None-Match", List.of("*"))), existing));
		assertEquals(412, upsert(request(Map.of("If-Match", List.of("\"v4\""))), existing));
		assertEquals(0, existing.updateAttempts);
		Store contested = new Store(5L);
		contested.alwaysRace = true;
		assertEquals(409, upsert(request(Map.of()), contested));
		assertEquals(3, contested.updateAttempts);
		assertEquals(0, contested.effects);
	}

	@Test
	void strictConcreteTagPolicySeparatesMissingUnsupportedAndStaleInput() {
		assertEquals(428, strictConcreteTagPolicy(request(Map.of())));
		for (String value : List.of("", "bad", "*", "W/\"v5\"", "\"v5\", \"v6\""))
			assertEquals(400, strictConcreteTagPolicy(request(Map.of("If-Match", List.of(value)))), value);
		Request repeated = request(Map.of("if-match", List.of("\"other\"", "\"v5\"")));
		assertEquals(400, strictConcreteTagPolicy(repeated));
		assertInstanceOf(Proceed.class, ConditionalRequests.evaluate(repeated, EntityTag.fromStrongValue("v5"), MODIFIED),
				"The generic evaluator still accepts legal repeated list fields");
		Request stale = request(Map.of("If-Match", List.of("\"v4\"")));
		assertEquals(0, strictConcreteTagPolicy(stale));
		assertEquals(412, update(stale, new Store(5L)));
		assertEquals(0, strictConcreteTagPolicy(request(Map.of("IF-MATCH", List.of(" \"v5\"\t")))));
	}

	private static int update(Request request, Store store) {
		for (int attempt = 0; attempt < 3; attempt++) {
			Snapshot current = store.find();
			if (current == null)
				return 404;
			switch (ConditionalRequests.evaluate(request, tag(current), current.modified())) {
				case PreconditionFailed ignored -> { return 412; }
				case NotModified ignored -> throw new AssertionError("NotModified is unreachable for PUT");
				case Proceed ignored -> {
					if (store.updateIfVersionMatches(current.version()))
						return 204;
				}
			}
		}
		return 409;
	}

	private static int upsert(Request request, Store store) {
		for (int attempt = 0; attempt < 3; attempt++) {
			Snapshot current = store.find();
			if (current == null) {
				if (ConditionalRequests.evaluateWithoutRepresentation(request) instanceof PreconditionFailed)
					return 412;
				if (store.insertIfAbsent())
					return 201;
				continue;
			}
			if (ConditionalRequests.evaluate(request, tag(current), current.modified()) instanceof PreconditionFailed)
				return 412;
			if (store.updateIfVersionMatches(current.version()))
				return 204;
		}
		return 409;
	}

	private static int strictConcreteTagPolicy(Request request) {
		List<String> values = request.getHeaders().get("If-Match");
		if (values == null || values.isEmpty())
			return 428;
		if (values.size() != 1)
			return 400;
		EntityTag expected = EntityTag.fromHeaderValue(values.get(0)).orElse(null);
		return expected == null || expected.isWeak() ? 400 : 0;
	}

	private static EntityTag tag(Snapshot snapshot) {
		return EntityTag.fromStrongValue("v" + snapshot.version());
	}

	private static Request request(Map<String, List<String>> headers) {
		return Request.withPath(HttpMethod.PUT, "/example").headers(headers).build();
	}

	private record Snapshot(long version, Instant modified) {}

	private static final class UnknownCommit extends RuntimeException {}

	private static final class Store {
		private Long version;
		private long nextVersion;
		private Instant modified = MODIFIED;
		private int reads;
		private int updateAttempts;
		private int insertAttempts;
		private int effects;
		private Runnable raceBeforeUpdate;
		private Runnable raceBeforeInsert;
		private boolean alwaysRace;
		private boolean loseCommitAcknowledgment;
		private RuntimeException insertFailure;

		private Store(Long version) {
			this.version = version;
			this.nextVersion = version == null ? 1 : version + 1;
		}

		private Snapshot find() {
			this.reads++;
			return this.version == null ? null : new Snapshot(this.version, this.modified);
		}

		private void replaceWith(Long version) {
			this.version = version;
			this.modified = this.modified.plusMillis(400);
			if (version != null)
				this.nextVersion = Math.max(this.nextVersion, version + 1);
		}

		private boolean updateIfVersionMatches(long expected) {
			this.updateAttempts++;
			if (this.raceBeforeUpdate != null) {
				Runnable race = this.raceBeforeUpdate;
				this.raceBeforeUpdate = null;
				race.run();
			}
			if (this.alwaysRace)
				replaceWith(this.nextVersion++);
			if (this.version == null || this.version != expected)
				return false;
			this.version = this.nextVersion++;
			this.effects++;
			if (this.loseCommitAcknowledgment)
				throw new UnknownCommit();
			return true;
		}

		private boolean insertIfAbsent() {
			this.insertAttempts++;
			if (this.insertFailure != null)
				throw this.insertFailure;
			if (this.raceBeforeInsert != null) {
				Runnable race = this.raceBeforeInsert;
				this.raceBeforeInsert = null;
				race.run();
			}
			if (this.version != null)
				return false;
			this.version = this.nextVersion++;
			this.effects++;
			return true;
		}
	}
}
