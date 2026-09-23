import com.soklet.SseUnicaster;
import java.io.ByteArrayInputStream;

public final class SseProducerThreadOwnership {
    static void exercise(SseUnicaster sseUnicaster) throws Exception {
        sseUnicaster.own(new ByteArrayInputStream(new byte[0])); // EXPECT-ERROR: compiler.err.cant.resolve.location.args | kindname.method, own, | com.soklet.SseUnicaster
    }
}
