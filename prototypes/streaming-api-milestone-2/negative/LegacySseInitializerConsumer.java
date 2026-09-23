import com.soklet.SseHandshakeResult;
import com.soklet.SseUnicaster;
import java.util.function.Consumer;

public final class LegacySseInitializerConsumer {
    static void exercise() {
        Consumer<SseUnicaster> clientInitializer = sseUnicaster -> {};
        SseHandshakeResult.Accepted.builder().clientInitializer(clientInitializer); // EXPECT-ERROR: compiler.err.cant.apply.symbol | clientInitializer | java.util.function.Consumer | com.soklet.SseClientInitializer | compiler.misc.inconvertible.types
    }
}
