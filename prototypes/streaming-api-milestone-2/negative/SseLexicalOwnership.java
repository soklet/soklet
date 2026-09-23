import com.soklet.SseUnicaster;
import java.io.ByteArrayInputStream;

public final class SseLexicalOwnership {
    static void exercise(SseUnicaster sseUnicaster) throws Exception {
        sseUnicaster.using(() -> new ByteArrayInputStream(new byte[0]), inputStream -> {}); // EXPECT-ERROR: compiler.err.cant.resolve.location.args | kindname.method, using, | com.soklet.SseUnicaster
    }
}
