/*
 * Copyright (c) 2015 Transmogrify LLC.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.soklet.deploy;

import static com.soklet.util.IoUtils.copyStreamCloseAfterwards;
import static com.soklet.util.StringUtils.trimToNull;
import static java.lang.String.format;
import static java.util.Collections.emptySet;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;
import static java.util.UUID.randomUUID;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

import com.soklet.util.PathUtils;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class Archiver {
  private static final Set<Path> DEFAULT_PATHS_TO_EXCLUDE = unmodifiableSet(new HashSet<Path>() {
    {
      add(Paths.get(".git"));
    }
  });

  private final Path archiveFile;
  private final Set<DeploymentPath> deploymentPathsToInclude;
  private final Set<Path> pathsToExclude;
  private final Optional<Path> staticFileRootDirectory;
  private final Optional<MavenSupport> mavenSupport;
  private final Optional<ArchiveSupportOperation> preProcessOperation;
  private final Optional<ArchiveSupportOperation> postProcessOperation;
  private final Optional<FileAlterationOperation> fileAlterationOperation;

  private final Logger logger = Logger.getLogger(Archiver.class.getName());

  protected Archiver(Builder builder) {
    requireNonNull(builder);

    this.archiveFile = builder.archiveFile;
    this.deploymentPathsToInclude = builder.deploymentPathsToInclude;
    this.pathsToExclude = builder.pathsToExclude;
    this.staticFileRootDirectory = builder.staticFileRootDirectory;
    this.mavenSupport = builder.mavenSupport;
    this.preProcessOperation = builder.preProcessOperation;
    this.postProcessOperation = builder.postProcessOperation;
    this.fileAlterationOperation = builder.fileAlterationOperation;
  }

  public void run() throws Exception {
    logger.info(format("Creating deployment archive %s...", archiveFile()));

    Path temporaryDirectory =
        Files.createTempDirectory(format("com.soklet.%s-%s-", getClass().getSimpleName(), randomUUID()));

    try {
      PathUtils.copyDirectory(Paths.get("."), temporaryDirectory, pathsToExclude);

      if (preProcessOperation().isPresent())
        preProcessOperation().get().perform(this, temporaryDirectory);

      if (mavenSupport().isPresent())
        performMavenSupport(mavenSupport.get(), temporaryDirectory);

      if (fileAlterationOperation().isPresent())
        PathUtils.walkDirectory(
          temporaryDirectory,
          (file) -> {
            try {
              Optional<InputStream> alteredFile =
                  fileAlterationOperation().get().alterFile(this, temporaryDirectory, file);

              // TODO: need to document that we will close the InputStream - the caller is not responsible for that
              if (alteredFile.isPresent())
                copyStreamCloseAfterwards(alteredFile.get(), Files.newOutputStream(file));
            } catch (IOException e) {
              throw e;
            } catch (Exception e) {
              throw new IOException(format("Unable to alter file %s", file.toAbsolutePath()), e);
            }
          });

      if (postProcessOperation().isPresent())
        postProcessOperation().get().perform(this, temporaryDirectory);
    } finally {
      PathUtils.deleteDirectory(temporaryDirectory);
    }

    logger.info(format("Deployment archive %s was created successfully.", archiveFile()));
  }

  protected void performMavenSupport(MavenSupport mavenSupport, Path workingDirectory) {
    requireNonNull(mavenSupport);
    requireNonNull(workingDirectory);

    ArchiverProcess mavenArchiverProcess = new ArchiverProcess(mavenSupport.mavenExecutableFile(), workingDirectory);

    mavenArchiverProcess.execute(mavenSupport().get().cleanArguments());
    mavenArchiverProcess.execute(mavenSupport().get().compileArguments());
    mavenArchiverProcess.execute(mavenSupport().get().dependenciesArguments());
  }

  public static Builder forArchiveFile(Path archiveFile) {
    return new Builder(archiveFile);
  }

  public static class Builder {
    private final Path archiveFile;
    private Set<DeploymentPath> deploymentPathsToInclude = emptySet();
    private Set<Path> pathsToExclude = defaultPathsToExclude();
    private Optional<Path> staticFileRootDirectory = Optional.empty();
    private Optional<MavenSupport> mavenSupport = Optional.empty();
    private Optional<ArchiveSupportOperation> preProcessOperation = Optional.empty();
    private Optional<ArchiveSupportOperation> postProcessOperation = Optional.empty();
    private Optional<FileAlterationOperation> fileAlterationOperation = Optional.empty();

    protected Builder(Path archiveFile) {
      this.archiveFile = requireNonNull(archiveFile);
    }

    public Builder deploymentPathsToInclude(Set<DeploymentPath> deploymentPaths) {
      this.deploymentPathsToInclude = unmodifiableSet(new HashSet<>(requireNonNull(deploymentPathsToInclude)));
      return this;
    }

    public Builder pathsToExclude(Set<Path> pathsToExclude) {
      this.pathsToExclude = unmodifiableSet(new HashSet<>(requireNonNull(pathsToExclude)));
      return this;
    }

    public Builder staticFileRootDirectory(Path staticFileRootDirectory) {
      this.staticFileRootDirectory = Optional.ofNullable(staticFileRootDirectory);
      return this;
    }

    public Builder mavenSupport(MavenSupport mavenSupport) {
      this.mavenSupport = Optional.ofNullable(mavenSupport);
      return this;
    }

    public Builder preProcessOperation(ArchiveSupportOperation preProcessOperation) {
      this.preProcessOperation = Optional.ofNullable(preProcessOperation);
      return this;
    }

    public Builder postProcessOperation(ArchiveSupportOperation postProcessOperation) {
      this.postProcessOperation = Optional.ofNullable(postProcessOperation);
      return this;
    }

    public Builder fileAlterationOperation(FileAlterationOperation fileAlterationOperation) {
      this.fileAlterationOperation = Optional.ofNullable(fileAlterationOperation);
      return this;
    }

    public Archiver build() {
      return new Archiver(this);
    }
  }

  public static class MavenSupport {
    private final Path mavenExecutableFile;
    private final List<String> cleanArguments;
    private final List<String> compileArguments;
    private final List<String> dependenciesArguments;

    protected MavenSupport(Builder builder) {
      requireNonNull(builder);
      this.mavenExecutableFile = builder.mavenExecutableFile;
      this.cleanArguments = builder.cleanArguments;
      this.compileArguments = builder.compileArguments;
      this.dependenciesArguments = builder.dependenciesArguments;
    }

    public static Builder create() {
      return new Builder();
    }

    public static MavenSupport standard() {
      return new MavenSupport(create());
    }

    public static class Builder {
      private Path mavenExecutableFile;
      private List<String> cleanArguments;
      private List<String> compileArguments;
      private List<String> dependenciesArguments;

      protected Builder() {
        this.cleanArguments = unmodifiableList(new ArrayList<String>() {
          {
            add("clean");
          }
        });

        this.compileArguments = unmodifiableList(new ArrayList<String>() {
          {
            add("compile");
          }
        });

        this.dependenciesArguments = unmodifiableList(new ArrayList<String>() {
          {
            add("-DincludeScope=runtime");
            add("dependency:copy-dependencies");
          }
        });

        // TODO: support configuration of this through code as well
        String mavenHome = trimToNull(System.getProperty("soklet.MAVEN_HOME"));

        if (mavenHome == null)
          mavenHome = trimToNull(System.getenv("MAVEN_HOME"));

        if (mavenHome == null)
          throw new DeploymentProcessExecutionException(
            "In order to determine the absolute path to your mvn executable, the soklet.MAVEN_HOME system property "
                + "or the MAVEN_HOME environment variable must be defined");

        this.mavenExecutableFile = Paths.get(format("%s/bin/mvn", mavenHome));
      }

      public Builder mavenExecutableFile(Path mavenExecutableFile) {
        this.mavenExecutableFile = requireNonNull(mavenExecutableFile);
        return this;
      }

      public Builder cleanArguments(List<String> cleanArguments) {
        this.cleanArguments = unmodifiableList(new ArrayList<>(requireNonNull(cleanArguments)));
        return this;
      }

      public Builder compileArguments(List<String> compileArguments) {
        this.compileArguments = unmodifiableList(new ArrayList<>(requireNonNull(compileArguments)));
        return this;
      }

      public Builder dependenciesArguments(List<String> dependenciesArguments) {
        this.dependenciesArguments = unmodifiableList(new ArrayList<>(requireNonNull(dependenciesArguments)));
        return this;
      }
    }

    public List<String> cleanArguments() {
      return this.cleanArguments;
    }

    public List<String> compileArguments() {
      return this.compileArguments;
    }

    public List<String> dependenciesArguments() {
      return this.dependenciesArguments;
    }

    public Path mavenExecutableFile() {
      return this.mavenExecutableFile;
    }
  }

  public static Set<Path> defaultPathsToExclude() {
    return DEFAULT_PATHS_TO_EXCLUDE;
  }

  public Path archiveFile() {
    return this.archiveFile;
  }

  public Set<DeploymentPath> deploymentPathsToInclude() {
    return this.deploymentPathsToInclude;
  }

  public Set<Path> pathsToExclude() {
    return this.pathsToExclude;
  }

  public Optional<Path> staticFileRootDirectory() {
    return this.staticFileRootDirectory;
  }

  public Optional<MavenSupport> mavenSupport() {
    return this.mavenSupport;
  }

  public Optional<ArchiveSupportOperation> preProcessOperation() {
    return this.preProcessOperation;
  }

  public Optional<ArchiveSupportOperation> postProcessOperation() {
    return this.postProcessOperation;
  }

  public Optional<FileAlterationOperation> fileAlterationOperation() {
    return this.fileAlterationOperation;
  }

  @FunctionalInterface
  public interface ArchiveSupportOperation {
    /**
     * Executes an operation which supports archive creation.
     * 
     * @param archiver
     *          the {@link Archiver} currently running
     * @param workingDirectory
     *          the temporary directory context in which the {@link Archiver} is working
     * @throws Exception
     *           if an error occurs while executing the archive support operation
     */
    void perform(Archiver archiver, Path workingDirectory) throws Exception;
  }

  @FunctionalInterface
  public interface FileAlterationOperation {
    /**
     * Executes an operation which (possibly) alters a file.
     * 
     * @param archiver
     *          the {@link Archiver} currently running
     * @param workingDirectory
     *          the temporary directory context in which the {@link Archiver} is working
     * @param file
     *          the file to (possibly) alter
     * @return bytes for the altered file, or empty if the file does not need to be altered
     * @throws Exception
     *           if an error occurs while executing the archive support operation
     */
    Optional<InputStream> alterFile(Archiver archiver, Path workingDirectory, Path file) throws Exception;
  }
}