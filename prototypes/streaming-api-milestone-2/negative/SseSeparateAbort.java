import com.soklet.SseUnicaster;
import java.io.ByteArrayInputStream;

public final class SseSeparateAbort {
    static void exercise(SseUnicaster sseUnicaster) throws Exception {
        sseUnicaster.open(() -> new ByteArrayInputStream(new byte[0]), inputStream -> {}); // EXPECT-ERROR: compiler.err.cant.apply.symbol | kindname.method, open, | com.soklet.SseUnicaster | compiler.misc.infer.arg.length.mismatch
    }
}
