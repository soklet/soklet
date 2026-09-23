import com.soklet.StreamingResponseBody;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.function.Supplier;

public final class LegacyInputStreamSupplier {
    static void exercise() {
        Supplier<InputStream> inputStreamSupplier = () -> new ByteArrayInputStream(new byte[0]);
        StreamingResponseBody.fromInputStream(inputStreamSupplier); // EXPECT-ERROR: compiler.err.cant.apply.symbol | fromInputStream | java.util.function.Supplier | com.soklet.StreamResourceFactory | compiler.misc.inconvertible.types
    }
}
