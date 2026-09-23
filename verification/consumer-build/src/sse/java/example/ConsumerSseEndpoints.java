package example;

import com.soklet.SseHandshakeResult;
import com.soklet.SseEvent;
import com.soklet.annotation.SseEventSource;

/** Packaged by the driver only for supported live SSE runtimes (Java 21+). */
public final class ConsumerSseEndpoints {
  @SseEventSource("/events")
  public SseHandshakeResult events() {
    return SseHandshakeResult.Accepted.builder().clientInitializer(sseUnicaster -> {
      sseUnicaster.unicastEvent(SseEvent.withData("packaged-sse").build());
      ConsumerOwnership.initialized.countDown();
    }).build();
  }
}
