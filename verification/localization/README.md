# Candidate-artifact localization gate

This tracked gate proves that Soklet's packaged jar is sufficient for a
library-neutral localization integration. It fails unless all of the
following hold:

- the candidate jar contains exactly one embedded Soklet POM, that POM is
  byte-identical to the checkout POM supplying the examples, and it declares no
  compile- or runtime-scoped dependency;
- the generic provider compiles and runs on Java 17 with only the candidate
  jar.

Translation-library adapters are intentionally documentation examples, not
checked-in Soklet verification code. Applications can implement the same
public seam with Lokalized or another library without making that library part
of Soklet's release gate.

Build the jar first, then run:

```sh
verification/localization/verify.sh \
  target/soklet-4.0.0.jar
```

`SOKLET_CANDIDATE_ARTIFACT` may replace the argument. The verifier downloads
nothing and writes only beneath a temporary directory.
