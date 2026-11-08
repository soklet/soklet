package com.soklet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class FrameworkAttemptPublicationTests {
	@Test
	void proofNotificationPublishesInactiveStartupState() throws Exception {
		try (Fixture fixture = Fixture.create()) {
			assertTrue(fixture.participant.startupCallActive());
			assertFalse(fixture.group.isBarrierComplete());
			AtomicInteger proofNotifications = new AtomicInteger();
			AtomicBoolean activeAtProof = new AtomicBoolean();
			fixture.observeNotifications(() -> {
				if (fixture.group.isBarrierComplete()) {
					proofNotifications.incrementAndGet();
					activeAtProof.set(fixture.participant.startupCallActive());
				}
			});

			fixture.complete();

			assertEquals(1, proofNotifications.get());
			assertFalse(activeAtProof.get(),
					"A coordinator awakened by proof must not see the completed startup action as active");
			assertFalse(fixture.participant.startupCallActive());
			assertTrue(fixture.group.isBarrierComplete());
		}
	}

	@Test
	void repeatedAndReentrantCompletionPublishOneProof() throws Exception {
		try (Fixture fixture = Fixture.create()) {
			AtomicBoolean observedProof = new AtomicBoolean();
			AtomicInteger reentrantCompletions = new AtomicInteger();
			AtomicBoolean activeAtProof = new AtomicBoolean();
			fixture.observeNotifications(() -> {
				if (fixture.group.isBarrierComplete() && observedProof.compareAndSet(false, true)) {
					activeAtProof.set(fixture.participant.startupCallActive());
					try {
						fixture.complete();
						reentrantCompletions.incrementAndGet();
					} catch (ReflectiveOperationException failure) {
						throw new AssertionError("Reentrant completion failed", failure);
					}
				}
			});

			fixture.complete();
			fixture.complete();

			assertTrue(observedProof.get());
			assertEquals(1, reentrantCompletions.get());
			assertFalse(activeAtProof.get());
			assertEquals(1, fixture.group.primaryEventsInSequence().stream()
					.filter(event -> event.type() == InternalTerminationEvent.Type.PROOF).count());
			assertTrue(fixture.group.isBarrierComplete());
			assertFalse(fixture.participant.startupCallActive());
			assertFalse(fixture.group.hasFailure());
		}
	}

	@Test
	void inactiveStartupStateCannotSubstituteForTerminationProof() throws Exception {
		try (Fixture fixture = Fixture.create()) {
			// Model preemption between the reordered state write and proof publication.
			fixture.active.set(false);

			assertFalse(fixture.participant.startupCallActive());
			assertFalse(fixture.group.isBarrierComplete(),
					"Clearing startup activity must not manufacture the independent root proof");
			assertTrue(fixture.group.primaryEventsInSequence().isEmpty());

			fixture.complete();

			assertTrue(fixture.group.isBarrierComplete());
			assertFalse(fixture.participant.startupCallActive());
		}
	}

	/** No listener, producer, lifecycle thread, or started owner generation is used. */
	private static final class Fixture implements AutoCloseable {
		private final Soklet soklet;
		private final InternalLifecycleCoordinator.Participant participant;
		private final InternalTerminationGroup group;
		private final AtomicBoolean active;
		private final Method completed;
		private final Field stateChanged;
		private final Runnable originalNotification;

		private Fixture(Soklet soklet, InternalLifecycleCoordinator.Participant participant,
				AtomicBoolean active, Method completed, Field stateChanged,
				Runnable originalNotification) {
			this.soklet = soklet;
			this.participant = participant;
			this.group = participant.terminationGroup();
			this.active = active;
			this.completed = completed;
			this.stateChanged = stateChanged;
			this.originalNotification = originalNotification;
		}

		private static Fixture create() throws ReflectiveOperationException {
			SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(0).build())
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of())).build();
			// Closing the never-started owner also runs its result handoff synchronously.
			LifecycleWorkers workers = new LifecycleWorkers((name, task) -> task.run());
			Soklet soklet = Soklet.fromConfig(config,
					new LifecycleRuntimeServices(NanoClock.system(), workers), ignored -> { });
			boolean created = false;
			try {
				Class<?> type = Class.forName("com.soklet.SokletDirectLifecycle$FrameworkAttempt");
				Constructor<?> constructor = type.getDeclaredConstructor(SokletDirectLifecycle.class,
						InternalLifecycleComponentType.class);
				constructor.setAccessible(true);
				InternalLifecycleCoordinator.Participant participant =
						(InternalLifecycleCoordinator.Participant) constructor.newInstance(
								soklet.getDirectLifecycle(), InternalLifecycleComponentType.FRAMEWORK);
				Field active = type.getDeclaredField("active");
				active.setAccessible(true);
				Method completed = type.getDeclaredMethod("completed");
				completed.setAccessible(true);
				Field stateChanged = InternalTerminationGroup.class.getDeclaredField("stateChanged");
				stateChanged.setAccessible(true);
				Fixture fixture = new Fixture(soklet, participant, (AtomicBoolean) active.get(participant),
						completed, stateChanged, (Runnable) stateChanged.get(participant.terminationGroup()));
				created = true;
				return fixture;
			} finally {
				if (!created)
					soklet.close();
			}
		}

		private void observeNotifications(Runnable observation) throws IllegalAccessException {
			this.stateChanged.set(this.group, (Runnable) () -> {
				this.originalNotification.run();
				observation.run();
			});
		}

		private void complete() throws ReflectiveOperationException {
			this.completed.invoke(this.participant);
		}

		@Override
		public void close() throws IllegalAccessException {
			try {
				this.stateChanged.set(this.group, this.originalNotification);
			} finally {
				this.soklet.close();
			}
		}
	}
}
