import com.soklet.CancelationToken;
import java.io.IOException;

public final class CheckedCancelationCallback {
    static void exercise(CancelationToken cancelationToken) {
        cancelationToken.onCancel(() -> { throw new IOException("Checked cleanup belongs to resource ownership."); }); // EXPECT-ERROR: compiler.err.unreported.exception.need.to.catch.or.throw | java.io.IOException
    }
}
