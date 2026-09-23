package example;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Small application-owned resources for the packaged consumer smoke. */
final class ConsumerOwnership {
  static final AtomicInteger sourceCloses = new AtomicInteger();
  static final AtomicInteger inputCloses = new AtomicInteger();
  static final CountDownLatch initialized = new CountDownLatch(1);

  static final class Source implements AutoCloseable {
    Source() throws IOException {}
    String text() { return "packaged-stream"; }
    @Override public void close() { sourceCloses.incrementAndGet(); }
  }

  static ByteArrayInputStream openInputStream() throws IOException {
    return new ByteArrayInputStream("packaged-source".getBytes(StandardCharsets.UTF_8)) {
      @Override public void close() throws IOException {
        inputCloses.incrementAndGet();
        super.close();
      }
    };
  }
}
