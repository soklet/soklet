/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet.internal.streaming;

import com.soklet.CallbackRegistration;
import com.soklet.CancelationToken;
import com.soklet.StreamTerminationReason;
import com.soklet.StreamingResponseBody;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

@Timeout(10)
public class PublisherResponseStreamDemandTests {
	@Test
	public void synchronous_single_item_demand_is_iterative_and_preserves_order() throws Exception {
		int itemCount = 20_000;
		AtomicInteger received = new AtomicInteger();
		AtomicInteger requested = new AtomicInteger();
		AtomicInteger canceled = new AtomicInteger();
		Flow.Publisher<ByteBuffer> publisher = subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
			private int emitted;
			private int depth;
			@Override public void request(long count) {
				Assertions.assertEquals(1L, count, "Demand must stay bounded to one item");
				Assertions.assertEquals(1, ++this.depth, "Demand recursed from onNext into request");
				try {
					requested.incrementAndGet();
					if (this.emitted == itemCount)
						subscriber.onComplete();
					else
						subscriber.onNext(ByteBuffer.wrap(new byte[]{(byte) this.emitted++}));
				} finally { this.depth--; }
			}
			@Override public void cancel() { canceled.incrementAndGet(); }
		});
		ManagedResponseStream.Output output = new ManagedResponseStream.Output() {
			@Override public void write(ByteBuffer buffer) {
				Assertions.assertEquals(1, buffer.remaining());
				Assertions.assertEquals((byte) received.getAndIncrement(), buffer.get());
			}
			@Override public void flush() {}
			@Override public boolean isOpen() { return true; }
		};
		PublisherResponseStream.copy((StreamingResponseBody.PublisherBody) StreamingResponseBody.fromPublisher(publisher), new CancelationToken() {
			@Override public Boolean isCanceled() { return false; }
			@Override public Optional<StreamTerminationReason> getCancelationReason() { return Optional.empty(); }
			@Override public Optional<Throwable> getCancelationCause() { return Optional.empty(); }
			@Override public CallbackRegistration onCancel(Runnable callback) { return () -> {}; }
		}, output, null, () -> {}, failure -> {}, failure -> {});
		Assertions.assertEquals(itemCount, received.get());
		Assertions.assertEquals(itemCount + 1, requested.get());
		Assertions.assertEquals(0, canceled.get());
	}
}
