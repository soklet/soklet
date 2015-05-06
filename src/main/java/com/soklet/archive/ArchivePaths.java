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

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public final class ArchivePaths {
  private ArchivePaths() {}

  public static ArchivePath get(String sourcePath) {
    requireNonNull(sourcePath);
    return new ArchivePath(Paths.get(sourcePath), Paths.get(sourcePath));
  }

  public static ArchivePath get(String sourcePath, String destinationDirectory) {
    requireNonNull(sourcePath);
    requireNonNull(destinationDirectory);
    return new ArchivePath(Paths.get(sourcePath), Paths.get(destinationDirectory));
  }

  public static ArchivePath get(Path sourcePath) {
    requireNonNull(sourcePath);
    return new ArchivePath(sourcePath, sourcePath);
  }

  public static ArchivePath get(Path sourcePath, Path destinationDirectory) {
    requireNonNull(sourcePath);
    requireNonNull(destinationDirectory);
    return new ArchivePath(sourcePath, destinationDirectory);
  }
}