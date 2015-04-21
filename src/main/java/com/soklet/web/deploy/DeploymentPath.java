/*
 * Copyright 2015 Transmogrify LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet.web.deploy;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class DeploymentPath implements Comparable<DeploymentPath> {
  private final Path sourcePath;
  private final Path destinationDirectory;

  DeploymentPath(Path sourcePath, Path destinationDirectory) {
    requireNonNull(sourcePath);
    requireNonNull(destinationDirectory);

    this.sourcePath = sourcePath;

    if (sourcePath.equals(destinationDirectory) && !Files.isDirectory(sourcePath))
      destinationDirectory = sourcePath.getParent();

    this.destinationDirectory = destinationDirectory;
  }

  @Override
  public int compareTo(DeploymentPath deploymentPath) {
    requireNonNull(deploymentPath);
    return sourcePath().compareTo(deploymentPath.sourcePath());
  }

  @Override
  public String toString() {
    return format("%s{sourcePath=%s, destinationDirectory=%s}", getClass().getSimpleName(), sourcePath(),
      destinationDirectory());
  }

  @Override
  public boolean equals(Object object) {
    if (this == object)
      return true;

    if (!(object instanceof DeploymentPath))
      return false;

    DeploymentPath deploymentPath = (DeploymentPath) object;

    return Objects.equals(sourcePath(), deploymentPath.sourcePath())
        && Objects.equals(destinationDirectory(), deploymentPath.destinationDirectory());
  }

  @Override
  public int hashCode() {
    return Objects.hash(sourcePath(), destinationDirectory());
  }

  public Path sourcePath() {
    return sourcePath;
  }

  public Path destinationDirectory() {
    return destinationDirectory;
  }
}