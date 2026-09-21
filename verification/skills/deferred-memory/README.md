# Deferred memory-accounting experiment

The owner deferred lifetime-wide shared Skills memory accounting on 2026-09-20.
These two source files preserve the unused ledger experiment and its 20 tests
for possible future investigation. They are outside production and test source
roots, are not packaged, and do not gate current Skills implementation.

Historically, the experiment passed its 20 tests on Java 17 and 26; that result
does not establish an allocation formula, payload lifetime, or memory guarantee.
No public budget owner, forced inspection copies, or GC-reclamation policy was
adopted. Current development retains the single-argument bundle factory and
ordinary immutable getters, with the existing focused resource safeguards.
