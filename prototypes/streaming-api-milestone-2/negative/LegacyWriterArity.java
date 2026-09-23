import com.soklet.StreamingResponseWriter;

public final class LegacyWriterArity {
    static void exercise() {
        StreamingResponseWriter streamingResponseWriter = (responseStream, streamingResponseContext) -> {}; // EXPECT-ERROR: compiler.err.prob.found.req | compiler.misc.incompatible.arg.types.in.lambda
    }
}
