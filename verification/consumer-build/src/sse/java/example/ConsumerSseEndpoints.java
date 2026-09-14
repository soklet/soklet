package example;

import com.soklet.SseHandshakeResult;
import com.soklet.annotation.SseEventSource;

/** Packaged by the driver only for supported live SSE runtimes (Java 21+). */
public final class ConsumerSseEndpoints {
  @SseEventSource("/events")
  public SseHandshakeResult events() {
    return SseHandshakeResult.accept();
  }
}
