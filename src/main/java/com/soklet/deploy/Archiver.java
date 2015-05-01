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

import static java.lang.String.format;
import static java.util.Collections.emptySet;
import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;
import static java.util.UUID.randomUUID;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

import com.soklet.util.PathUtils;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class Archiver {
  private static final Set<Path> DEFAULT_PATHS_TO_SKIP = unmodifiableSet(new HashSet<Path>() {
    {
      add(Paths.get(".git"));
    }
  });

  private final Path archiveFile;
  private final Set<DeploymentPath> deploymentPathsToInclude;
  private final Set<Path> pathsToSkip;
  private final Optional<Path> staticFileRootDirectory;

  private final Logger logger = Logger.getLogger(Archiver.class.getName());

  protected Archiver(Builder builder) {
    requireNonNull(builder);
    this.archiveFile = builder.archiveFile;
    this.deploymentPathsToInclude = builder.deploymentPathsToInclude;
    this.pathsToSkip = builder.pathsToSkip;
    this.staticFileRootDirectory = builder.staticFileRootDirectory;
  }

  public void run() throws IOException {
    logger.info(format("Creating deployment archive %s...", archiveFile()));

    Path temporaryDirectory =
        Files.createTempDirectory(format("com.soklet.%s-%s-", getClass().getSimpleName(), randomUUID()));

    System.out.println("Temp directory: " + temporaryDirectory.toAbsolutePath());

    try {
      PathUtils.copyDirectory(Paths.get("."), temporaryDirectory, pathsToSkip);
    } finally {
      PathUtils.deleteDirectory(temporaryDirectory);
    }

    logger.info(format("Deployment archive %s was created successfully.", archiveFile()));
  }

  public static Builder forArchiveFile(Path archiveFile) {
    return new Builder(archiveFile);
  }

  public static class Builder {
    private final Path archiveFile;
    private Set<DeploymentPath> deploymentPathsToInclude = emptySet();
    private Set<Path> pathsToSkip = defaultPathsToSkip();
    private Optional<Path> staticFileRootDirectory = Optional.empty();

    protected Builder(Path archiveFile) {
      this.archiveFile = requireNonNull(archiveFile);
    }

    public Builder deploymentPathsToInclude(Set<DeploymentPath> deploymentPaths) {
      this.deploymentPathsToInclude = unmodifiableSet(new HashSet<>(requireNonNull(deploymentPathsToInclude)));
      return this;
    }

    public Builder pathsToSkip(Set<Path> pathsToSkip) {
      this.pathsToSkip = unmodifiableSet(new HashSet<>(requireNonNull(pathsToSkip)));
      return this;
    }

    public Builder staticFileRootDirectory(Path staticFileRootDirectory) {
      this.staticFileRootDirectory = Optional.ofNullable(staticFileRootDirectory);
      return this;
    }

    public Archiver build() {
      return new Archiver(this);
    }
  }

  public static Set<Path> defaultPathsToSkip() {
    return DEFAULT_PATHS_TO_SKIP;
  }

  public Path archiveFile() {
    return this.archiveFile;
  }

  public Set<DeploymentPath> deploymentPathsToInclude() {
    return this.deploymentPathsToInclude;
  }

  public Set<Path> pathsToSkip() {
    return this.pathsToSkip;
  }

  public Optional<Path> staticFileRootDirectory() {
    return this.staticFileRootDirectory;
  }
}