# Soklet Soak Tests

Resource-leak probes for live loopback transports.

Run the short smoke mode:

```sh
mvn -f soak/pom.xml test
```

Run the higher-volume scheduled mode:

```sh
SOKLET_SOAK=1 mvn -f soak/pom.xml test
```

The soak module compiles Soklet's main sources directly and keeps soak-only test dependencies out of the published artifact. Default smoke mode is suitable for local checks and pull-request compile protection.

`SOKLET_SOAK=1` is a fast, high-volume leak probe for scheduled CI and manual pre-release runs, not a multi-hour duration soak. It is designed to expose per-operation file-descriptor, thread, heap, and active-gauge leaks by driving many connection/session cycles and then asserting resources return to the running-idle baseline.
