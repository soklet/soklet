# Toy Store Docker smoke — September 26, 2026

**Development PASS; exact-commit release gate still pending.** The documented
opted-in `ToyStoreMcpDockerSmokeTests` executed against a disposable local image
with the current Java 17 Soklet main JAR. The fresh Surefire report says one
test, zero failures, zero errors, zero skips; `VerifyDockerSmokeReport.java`
independently returned PASS. The test covered authenticated discovery and
`list_toys` through both allowed published authorities, rejected Host authorities,
and a missing credential.

| Input or result | Identity |
| --- | --- |
| Core JAR in Maven copy, build context, and image | SHA-256 `f91ab625a5a7fb506d0066dfc73cc942e96afa20f71fd6f368d945b9d701ce51` |
| Toy Store source | Clean commit `ea6bf75e74e682e5adbfe383ba40709c879c9c81`, tree `ae398ea1e5ca656e55b82407708be397ce069fd9` |
| Toy Store POM | SHA-256 `ad6ffc8c88d34797c6e7c36afc6eb8cd2bb8ed4f8efcc6e5c557aaec4d39b534` |
| Disposable image | `sha256:f574348e9b21addecf5c26f754e35c520d58efe3df44e5bdb326883aa562b486` |
| Temporary runtime Dockerfile | SHA-256 `0db65162b87e52a11b57b1dda9d5201aa63c816d13625ab19d4cc7a6daa5f719` |
| Fresh Surefire XML | SHA-256 `90b42f6c2f2ff7356aa24fc523827f9e25ffece437ac5fffa9a45bdab3e8a55e` |

Toy Store compiled 44 main and eight test source files with JDK 25 and Maven
3.9.16, offline, using a private copy of the dependency cache with only the
Soklet 4.0.0 JAR/POM replaced. Its dependency-copy step selected that exact JAR.
The local image used the repository Dockerfile's runtime stage with its two
builder-output `COPY` commands changed to copy these locally compiled classes
and dependencies. The base image was the locally cached `amazoncorretto:25` at
digest `sha256:ec395950366b60da545925171be5e8f457f7b33d034ee12b3a0110657da285fc`;
the image build used no network or registry push. Docker Engine was 29.6.1.

The container published only `127.0.0.1:18080`, `:8081`, and `:8082` on the
host. The smoke used the exact lowercase opt-in and HTTP-port override with a
new report directory. Its container was removed automatically; the original
Toy Store checkout and other containers were unchanged. The local run log and
raw XML are retained under `/private/tmp/soklet-toystore-smoke-20260926/`.

This validates the host-to-container behavior of those local artifact bytes. It
does not validate the original Maven-in-container Docker build against Central,
prove reproducible Linux candidate artifacts, or replace the required rerun
from the owner's final committed 4.0.0 candidate.
