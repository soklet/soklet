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

package com.soklet.archive;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class ArchivePath implements Comparable<ArchivePath> {
  private final Path sourcePath;
  private final Path destinationDirectory;

  ArchivePath(Path sourcePath, Path destinationDirectory) {
    requireNonNull(sourcePath);
    requireNonNull(destinationDirectory);

    this.sourcePath = sourcePath;

    if (sourcePath.equals(destinationDirectory) && !Files.isDirectory(sourcePath))
      destinationDirectory = sourcePath.getParent();

    this.destinationDirectory = destinationDirectory;
  }

  @Override
  public int compareTo(ArchivePath archivePath) {
    requireNonNull(archivePath);
    return sourcePath().compareTo(archivePath.sourcePath());
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

    if (!(object instanceof ArchivePath))
      return false;

    ArchivePath archivePath = (ArchivePath) object;

    return Objects.equals(sourcePath(), archivePath.sourcePath())
        && Objects.equals(destinationDirectory(), archivePath.destinationDirectory());
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