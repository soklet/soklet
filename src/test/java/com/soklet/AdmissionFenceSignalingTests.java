package com.soklet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class AdmissionFenceSignalingTests {
	@Test void runningRequestReleaseDoesNotWakeTheCoordinatorButStillPublishesZeroWork() {
		AtomicInteger signals = new AtomicInteger(), zeroWork = new AtomicInteger();
		AdmissionFence fence = new AdmissionFence(signals::incrementAndGet);
		fence.onAdmittedWorkReleased(zeroWork::incrementAndGet);
		fence.tryAdmit().orElseThrow().close();
		assertEquals(0, signals.get());
		assertEquals(1, zeroWork.get());
		AdmissionFence.Admission admission = fence.tryAdmit().orElseThrow();
		fence.close();
		assertEquals(1, signals.get());
		admission.close();
		assertEquals(2, signals.get());
		assertEquals(2, zeroWork.get());
	}

	@Test void concurrentCloseAndReleaseAlwaysSignalsTheClosedZeroWorkBarrier() throws Exception {
		CountDownLatch start = new CountDownLatch(1), released = new CountDownLatch(1), barrier = new CountDownLatch(1);
		AtomicReference<AdmissionFence> holder = new AtomicReference<>();
		AdmissionFence fence = new AdmissionFence(() -> {
			AdmissionFence current = holder.get();
			if (!current.isOpen() && current.admittedWorkCount() == 0) barrier.countDown();
		});
		holder.set(fence);
		AdmissionFence.Admission admission = fence.tryAdmit().orElseThrow();
		Thread release = new Thread(() -> {
			try { start.await(); admission.close(); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
			finally { released.countDown(); }
		}, "round2-admission-release");
		release.setDaemon(true);
		release.start();
		try {
			start.countDown();
			fence.close();
			assertTrue(released.await(2, TimeUnit.SECONDS));
			assertTrue(barrier.await(2, TimeUnit.SECONDS));
			assertEquals(0, fence.admittedWorkCount());
		} finally { start.countDown(); release.join(2000); }
	}
}
