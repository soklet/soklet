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

import java.util.Optional;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class ArchiveException extends RuntimeException {
  private final Optional<Process> process;

  public ArchiveException(String message) {
    super(message);
    this.process = Optional.empty();
  }

  public ArchiveException(String message, Throwable cause) {
    super(message, cause);
    this.process = Optional.empty();
  }

  public ArchiveException(String message, Process process) {
    super(message);
    this.process = Optional.of(requireNonNull(process));
  }

  public ArchiveException(String message, Throwable cause, Process process) {
    super(message, cause);
    this.process = Optional.of(requireNonNull(process));
  }

  public Optional<Process> process() {
    return process;
  }
}