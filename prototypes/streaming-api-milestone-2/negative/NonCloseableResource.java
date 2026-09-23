import com.soklet.ResponseStream;

public final class NonCloseableResource {
    static void exercise(ResponseStream responseStream) throws Exception {
        responseStream.open(Object::new); // EXPECT-ERROR: compiler.err.cant.apply.symbols | kindname.method, open, | com.soklet.StreamResourceFactory | compiler.misc.incompatible.bounds | java.lang.AutoCloseable
    }
}
