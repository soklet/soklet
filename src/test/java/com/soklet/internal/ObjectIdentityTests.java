package com.soklet.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ObjectIdentityTests {
	@Test
	public void equalButDistinctValuesAreNotTheSameInstance() {
		Value first = new Value(42);
		Value second = new Value(42);
		assertTrue(first.equals(second));
		assertFalse(ObjectIdentity.sameInstance(first, second));
		assertFalse(ObjectIdentity.sameInstance(second, first));
		assertTrue(ObjectIdentity.sameInstance(first, first));
	}

	@Test
	public void comparisonNeverInvokesUserEqualityOrHashCode() {
		Object first = new HostileEquality();
		Object second = new HostileEquality();
		assertTrue(ObjectIdentity.sameInstance(first, first));
		assertFalse(ObjectIdentity.sameInstance(first, second));
		assertFalse(ObjectIdentity.sameInstance(second, first));
		assertFalse(ObjectIdentity.sameInstance(first, null));
		assertFalse(ObjectIdentity.sameInstance(null, first));
	}

	@Test
	public void nullMatchesOnlyNull() {
		assertTrue(ObjectIdentity.sameInstance(null, null));
		assertFalse(ObjectIdentity.sameInstance(new Object(), null));
		assertFalse(ObjectIdentity.sameInstance(null, new Object()));
	}

	private record Value(int value) {}

	private static final class HostileEquality {
		@Override
		public boolean equals(Object other) {
			throw new AssertionError("Identity comparison must not invoke equals");
		}

		@Override
		public int hashCode() {
			throw new AssertionError("Identity comparison must not invoke hashCode");
		}
	}
}
