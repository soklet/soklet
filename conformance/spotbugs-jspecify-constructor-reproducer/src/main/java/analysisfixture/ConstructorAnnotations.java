package analysisfixture;

import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Standalone scanner-defect reproduction; deliberately not part of Soklet's artifact. */
public final class ConstructorAnnotations {
  public enum EnumCase {
    VALUE("required", null, null);

    private final String required;

    EnumCase(@NonNull String required, @Nullable String optionalOne,
        @Nullable String optionalTwo) {
      this.required = Objects.requireNonNull(required);
    }

    public String required() { return this.required; }
  }

  public final class InnerCase {
    private final String required;

    public InnerCase(@NonNull String required, @Nullable String optional,
        @NonNull String last) {
      this.required = Objects.requireNonNull(required);
    }

    public String required() { return this.required; }
  }

  public InnerCase createInner(@Nullable String optional) {
    return new InnerCase("required", optional, "last");
  }

  /** Negative control: identical authored parameter annotations, no enclosing-instance parameter. */
  public static final class StaticControl {
    private final String required;

    public StaticControl(@NonNull String required, @Nullable String optional,
        @NonNull String last) {
      this.required = Objects.requireNonNull(required);
    }

    public String required() { return this.required; }
  }

  public static StaticControl createStatic(@Nullable String optional) {
    return new StaticControl("required", optional, "last");
  }

  public static void main(String[] args) {
    if (!"required".equals(EnumCase.VALUE.required())
        || !"required".equals(new ConstructorAnnotations().createInner(null).required())
        || !"required".equals(createStatic(null).required()))
      throw new AssertionError("Nullable arguments must be accepted in every case");
  }
}
