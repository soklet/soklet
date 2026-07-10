## How To Contribute
 
#### Basics

Pull requests and bug reports are welcomed.  For enhancement pull requests, please ask first to save time!  It's possible the proposed enhancement is outside the scope or design goals of the project.

#### Local Installation

```shell
$ mvn install
```

This will build artifacts and install to your local system's Maven repository.

#### Benchmarks

Soklet's formal microbenchmarks live in `benchmarks/` and use JMH. If a pull request may affect request parsing, response writing, routing, allocation behavior, or other hot paths, run the relevant benchmarks and include enough before/after results to show that it does not introduce a performance regression. See `BENCHMARKS.md` for build, run, and reporting guidance.

#### Publishing to Maven Central

Contact Mark Allen at mark@revetware.com to request publishing access for the `com.soklet` namespace. Generate a [Central Portal user token](https://central.sonatype.org/publish/generate-portal-token/) and configure its generated username and password in `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>central-portal</id>
      <username>YOUR_TOKEN_USERNAME</username>
      <password>YOUR_TOKEN_PASSWORD</password>
    </server>
  </servers>
</settings>
```

The server ID must match the `central-publishing-maven-plugin` configuration in `pom.xml`. Before uploading a release, either prime `gpg-agent` in an interactive session or securely export the GPG passphrase through the Maven GPG plugin's default `MAVEN_GPG_PASSPHRASE` environment variable. Do not put the passphrase directly in shell history or project files.

Build and sign the complete artifact set locally:

```shell
mvn clean verify
```

Confirm that the versioned main JAR, sources JAR, Javadocs JAR, and their signatures were produced under `target/`. The GPG plugin also signs the project POM for deployment. Then upload the release bundle:

```shell
mvn clean deploy
```

The current Central plugin configuration waits for the uploaded deployment to validate but does not publish it automatically. Review the validated deployment in the [Central Publisher Portal](https://central.sonatype.com/publishing/deployments), then select **Publish**. Published coordinates are immutable, so verify the version and artifacts before completing that step.
