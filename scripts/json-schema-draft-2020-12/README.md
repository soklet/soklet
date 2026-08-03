# Draft 2020-12 meta-schema bundle

Soklet packages the official Draft 2020-12 meta-schema closure as the
authenticated foundation for future compiler and evaluator meta-validation
without a network resolver. The source is the official
`json-schema-org/json-schema-spec` `2020-12` branch at commit
`601a66c8b0f25246bf0e1fb488c5b5f030a79b72`. That commit is also the exact
submodule revision selected by the official website snapshot at commit
`77cc0650649558df71b0c5a404486dce3d95c81a`.

The bundle includes the default dialect root, its seven vocabulary
meta-schemas, and the separate `format-assertion` vocabulary meta-schema. The
latter is packaged for closed custom-dialect and optional-suite use; it is not
enabled by the default Draft 2020-12 dialect.

## Verify the checked-in bundle

```sh
node scripts/json-schema-draft-2020-12/verify.mjs
```

After Maven packaging, verify exact JAR membership and byte preservation:

```sh
mvn -DskipTests package
node scripts/json-schema-draft-2020-12/verify-jar.mjs --directory target
```

CI and normal development consume only these authenticated local bytes. They
never compare live URLs or fetch a schema at runtime.

## Reproduce or deliberately re-pin

Fetch the two exact reviewed inputs:

```sh
curl -L --fail --output json-schema-spec.tar.gz \
  https://github.com/json-schema-org/json-schema-spec/archive/601a66c8b0f25246bf0e1fb488c5b5f030a79b72.tar.gz
curl -L --fail --output LICENSE.upstream \
  https://raw.githubusercontent.com/json-schema-org/website/77cc0650649558df71b0c5a404486dce3d95c81a/LICENSE
```

Then import into an absent destination:

```sh
node scripts/json-schema-draft-2020-12/import.mjs \
  --archive json-schema-spec.tar.gz \
  --license LICENSE.upstream
node scripts/json-schema-draft-2020-12/verify.mjs
```

A future re-pin is a reviewed source update, not routine dependency refresh:

1. verify the official website's exact submodule selection and review every
   upstream byte delta;
2. update the immutable source identities, input byte counts, and hashes in
   both scripts and in the Java pin test;
3. regenerate the bytewise manifest and hardcode its new digest in the
   independent verifier and Java test;
4. re-check the exact canonical IDs, root vocabulary map, reference closure,
   default exclusion of format assertion, and offline graph compilation;
5. run all supported-JDK, schema-corpus, static-analysis, and packaging gates.

The imported material is attributed under `BSD-3-Clause OR AFL-3.0`; the exact
official dual-license text and upstream README are retained with the bundle.
