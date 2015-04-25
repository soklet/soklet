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

package com.soklet.deploy;

import static java.lang.String.format;
import static java.util.Arrays.stream;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.Objects.requireNonNull;
import static java.util.logging.Level.WARNING;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public abstract class DeployableArchiveCreator {
  private final String archiveName;
  private final Logger logger = Logger.getLogger(getClass().getName());

  {
    archiveName = format("%s.zip", DateTimeFormatter.ofPattern("yyyyMMdd-hhmmss").format(LocalDateTime.now()));
  }

  public abstract Set<DeploymentPath> pathsToInclude();

  public abstract Set<Path> staticFileDirectories();

  public abstract Set<Path> stringsDirectories();

  public void preProcess() throws Exception {}

  public void postProcess() throws Exception {}

  public void performCleanup() throws Exception {}

  public Set<Path> pathsToExclude() {
    return emptySet();
  }

  public Path archiveOutputDirectory() {
    return Paths.get(".");
  }

  public String archiveName() {
    return archiveName;
  }

  public void run() throws Exception {
    try {
      String archiveName = archiveName();
      Set<DeploymentPath> pathsToInclude = pathsToInclude();
      Set<Path> pathsToExclude = pathsToExclude();
      Set<Path> staticFileDirectories = staticFileDirectories();
      Set<Path> stringsDirectories = stringsDirectories();

      logger.info(format("Creating deployment archive %s...", archiveName));

      preProcess();

      staticFileDirectories.stream().forEach(this::verifyValidDirectory);
      stringsDirectories.stream().forEach(this::verifyValidDirectory);

      Set<DeploymentPath> filesToInclude = extractAllFilesFromPaths(pathsToInclude, pathsToExclude);

      // TODO: permit manual filtering

      postProcess();

      createZip(archiveName, filesToInclude);
    } finally {
      try {
        performCleanup();
      } catch (Exception e) {
        logger.log(WARNING, "Unable to perform cleanup", e);
      }
    }
  }

  protected void createZip(String archiveName, Set<DeploymentPath> filesToInclude) {
    requireNonNull(archiveName);
    requireNonNull(filesToInclude);

    FileOutputStream fileOutputStream = null;

    try {
      fileOutputStream = new FileOutputStream(archiveName);
    } catch (IOException e) {
      throw new UncheckedIOException(format("Unable to create zip archive %s", archiveName), e);
    }

    ZipOutputStream zipOutputStream = null;

    try {
      zipOutputStream = new ZipOutputStream(fileOutputStream);
      zipOutputStream.setLevel(9);

      for (DeploymentPath deploymentPath : filesToInclude) {
        String zipEntryName =
            format("%s/%s", deploymentPath.destinationDirectory(), deploymentPath.sourcePath().getFileName());
        logger.info(format("Adding %s...", zipEntryName));
        zipOutputStream.putNextEntry(new ZipEntry(zipEntryName));
        zipOutputStream.write(Files.readAllBytes(deploymentPath.sourcePath()));
      }

      zipOutputStream.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(format("An error occurred while creating zip archive %s", archiveName), e);
    } finally {
      if (zipOutputStream != null) {
        try {
          zipOutputStream.close();
        } catch (IOException e) {
          logger.log(WARNING, format("Unable to close %s. Continuing on...", ZipOutputStream.class.getSimpleName()), e);
        }
      }
    }
  }

  protected Set<DeploymentPath> extractAllFilesFromPaths(Set<DeploymentPath> pathsToInclude, Set<Path> pathsToExclude) {
    requireNonNull(pathsToInclude);
    requireNonNull(pathsToExclude);

    Set<DeploymentPath> filesToInclude = new HashSet<>();

    pathsToInclude.forEach(deploymentPath -> {
      if (Files.exists(deploymentPath.sourcePath())) {
        if (Files.isDirectory(deploymentPath.sourcePath())) {
          try {
            Files.walk(deploymentPath.sourcePath()).forEach(childPath -> {
              if (!Files.isDirectory(childPath) && shouldIncludePath(childPath, pathsToExclude)) {
                // System.out.println("Adding leaf " + childPath + ": "
                // + deploymentPath.sourcePath().relativize(childPath));

              Path destinationDirectory =
                  Paths.get(deploymentPath.destinationDirectory() + "/"
                      + deploymentPath.sourcePath().relativize(childPath));

              if (destinationDirectory.getParent() != null)
                destinationDirectory = destinationDirectory.getParent();

              filesToInclude.add(DeploymentPaths.get(childPath, destinationDirectory));
            }
          } );
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        } else {
          filesToInclude.add(deploymentPath);
        }
      }
    });

    return filesToInclude;
  }

  protected boolean shouldIncludePath(Path path, Set<Path> pathsToExclude) {
    requireNonNull(path);
    requireNonNull(pathsToExclude);

    for (Path pathToExclude : pathsToExclude)
      if (path.startsWith(pathToExclude) || path.equals(pathToExclude))
        return false;

    return true;
  }

  protected void verifyValidDirectory(Path directory) {
    requireNonNull(directory);

    if (!Files.exists(directory))
      throw new IllegalArgumentException(format("Directory %s does not exist!", directory));
    if (!Files.isDirectory(directory))
      throw new IllegalArgumentException(format("%s is not a directory!", directory));
  }

  protected void verifyValidFile(Path file) {
    requireNonNull(file);

    if (!Files.exists(file))
      throw new IllegalArgumentException(format("File %s does not exist!", file));
    if (Files.isDirectory(file))
      throw new IllegalArgumentException(format("%s is a directory!", file));
  }

  protected ProcessBuilder createProcessBuilder(Path executableFile, String... arguments) {
    requireNonNull(executableFile);
    return createProcessBuilder(executableFile, arguments == null ? emptyList() : stream(arguments).collect(toList()));
  }

  protected ProcessBuilder createProcessBuilder(Path executableFile, List<String> arguments) {
    requireNonNull(executableFile);
    requireNonNull(arguments);
    verifyValidFile(executableFile);

    List<String> finalArguments = new ArrayList<>(arguments.size() + 1);
    finalArguments.add(executableFile.toAbsolutePath().toString());
    finalArguments.addAll(arguments);

    ProcessBuilder processBuilder = new ProcessBuilder(finalArguments.toArray(new String[] {})).inheritIO();

    Map<String, String> environment = processBuilder.environment();
    environment.putIfAbsent("JAVA_HOME", System.getProperty("java.home"));

    return processBuilder;
  }

  protected int executeProcess(Path executableFile, String... arguments) {
    requireNonNull(executableFile);
    requireNonNull(arguments);
    return executeProcess(executableFile, arguments == null ? emptyList() : stream(arguments).collect(toList()));
  }

  protected int executeProcess(Path executableFile, List<String> arguments) {
    requireNonNull(executableFile);
    requireNonNull(arguments);
    return executeProcess(createProcessBuilder(executableFile, arguments));
  }

  protected int executeProcess(ProcessBuilder processBuilder) {
    requireNonNull(processBuilder);

    String processDescription = processBuilder.command().stream().collect(joining(" "));
    logger.info(format("Starting process: %s", processDescription));

    try {
      Process process = processBuilder.start();

      if (process.waitFor(5, TimeUnit.MINUTES)) {
        int exitValue = process.exitValue();

        logger.info(format("Completed process (exit value %d): %s", exitValue, processDescription));

        if (invalidProcessExitValue(process))
          throw new DeploymentProcessExecutionException(format("Invalid process exit value: %d", exitValue), process);

        return exitValue;
      } else {
        process.destroyForcibly();
        throw new DeploymentProcessExecutionException("Process timed out, forcibly terminating.", process);
      }
    } catch (IOException e) {
      throw new DeploymentProcessExecutionException("Unable to execute process.", e);
    } catch (InterruptedException e) {
      throw new DeploymentProcessExecutionException("Process was interrupted.", e);
    }
  }

  protected boolean invalidProcessExitValue(Process process) {
    requireNonNull(process);
    return process.exitValue() != 0;
  }

  // TODO: keep these 3 methods or remove them?

  protected Set<Path> allFilesInDirectory(Path directory, DirectoryWalkingDepth directoryWalkingDepth) {
    return allFilesInDirectory(directory, directoryWalkingDepth, emptySet());
  }

  protected Set<Path> allFilesInDirectory(Path directory, DirectoryWalkingDepth directoryWalkingDepth,
      Set<Path> pathsToExclude) {
    requireNonNull(directory);
    requireNonNull(directoryWalkingDepth);
    requireNonNull(pathsToExclude);
    verifyValidDirectory(directory);

    throw new UnsupportedOperationException();
  }

  protected static enum DirectoryWalkingDepth {
    CURRENT_DIRECTORY, ALL_SUBDIRECTORIES
  }
}