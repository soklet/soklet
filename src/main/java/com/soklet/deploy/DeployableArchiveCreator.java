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
import static java.util.UUID.randomUUID;
import static java.util.logging.Level.WARNING;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.soklet.util.PathUtils;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public abstract class DeployableArchiveCreator {
  private final String archiveName;
  private final Logger logger = Logger.getLogger(getClass().getName());

  {
    archiveName =
        format("%s-deployment.zip", DateTimeFormatter.ofPattern("yyyyMMdd-hhmmss").format(LocalDateTime.now()));
  }

  public abstract Set<DeploymentPath> pathsToInclude();

  public abstract Set<Path> staticFileDirectories();

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

      logger.info(format("Creating deployment archive %s...", archiveName));

      preProcess();

      staticFileDirectories.stream().forEach(this::verifyValidDirectory);

      Set<DeploymentPath> filesToInclude = extractAllFilesFromPaths(pathsToInclude, pathsToExclude);
      Set<DeploymentPath> staticFilesToInclude = extractStaticFilesFromPaths(staticFileDirectories, filesToInclude);

      // Remove static files for now - we handle them specially below and add them back as we go
      filesToInclude.removeAll(staticFilesToInclude);

      Path temporaryDirectory =
          Files.createTempDirectory(format("com.soklet.%s-%s-", getClass().getSimpleName(), randomUUID()));

      try {
        for (DeploymentPath staticFileToInclude : staticFilesToInclude) {
          // Make sure we have a place to put it by creating any intermediate directories.
          // File.mkdirs() actually creates the file so we delete it right after (the directories will stay)
          File file = temporaryDirectory.resolve(staticFileToInclude.sourcePath()).toFile();
          file.mkdirs();
          file.delete();

          Optional<InputStream> transformedStaticFile = transformStaticFile(staticFileToInclude.sourcePath());
          Path copiedFile = null;

          if (transformedStaticFile.isPresent()) {
            // TODO: write transformed bytes out as copiedFile
          } else {
            copiedFile =
                Files.copy(staticFileToInclude.sourcePath(),
                  temporaryDirectory.resolve(staticFileToInclude.sourcePath()));
          }

          // 1. The static file by itself, e.g. test.css
          filesToInclude.add(new DeploymentPath(copiedFile, staticFileToInclude.destinationDirectory()));

          // 2. The static file with its hash embedded, e.g. test.BE9748F56259CA49B7E5F249BE1030BE.css
          String hash = hash(Files.readAllBytes(staticFileToInclude.sourcePath()));
          String hashedFilename = hashedFilename(staticFileToInclude.sourcePath().toString(), hash);
          Path copiedHashedFile =
              Files.copy(staticFileToInclude.sourcePath(), temporaryDirectory.resolve(hashedFilename));

          if (createHashedStaticFiles())
            filesToInclude.add(new DeploymentPath(copiedHashedFile, staticFileToInclude.destinationDirectory()));

          // 3. The pre-gzipped static file, e.g. test.css.gz
          // TODO

          // 4. The pre-gzipped hashed static file, e.g. test.BE9748F56259CA49B7E5F249BE1030BE.css.gz
          // TODO
        }

        // TODO: permit manual filtering

        postProcess();
        createZip(archiveName, filesToInclude);
      } finally {
        PathUtils.deleteDirectory(temporaryDirectory);
      }

    } finally {
      try {
        performCleanup();
      } catch (Exception e) {
        logger.log(WARNING, "Unable to perform cleanup", e);
      }
    }
  }

  protected boolean createHashedStaticFiles() {
    return true;
  }

  protected boolean createZippedStaticFiles() {
    return true;
  }

  protected Optional<InputStream> transformStaticFile(Path staticFile) {
    return Optional.empty();
  }

  protected Set<DeploymentPath> extractStaticFilesFromPaths(Set<Path> staticFileDirectories,
      Set<DeploymentPath> filesToInclude) {
    requireNonNull(staticFileDirectories);
    requireNonNull(filesToInclude);

    Set<DeploymentPath> staticPathsToInclude = new HashSet<>();

    for (Path staticFileDirectory : staticFileDirectories) {
      Path parentDirectory = staticFileDirectory.toAbsolutePath();

      for (DeploymentPath fileToInclude : filesToInclude) {
        Path possibleChildFile = fileToInclude.sourcePath().toAbsolutePath();

        if (possibleChildFile.startsWith(parentDirectory))
          staticPathsToInclude.add(fileToInclude);
      }
    }

    return staticPathsToInclude;
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
    Function<DeploymentPath, String> zipEntryNameProvider =
        (deploymentPath) -> format("%s/%s", deploymentPath.destinationDirectory(), deploymentPath.sourcePath()
          .getFileName());

    try {
      zipOutputStream = new ZipOutputStream(fileOutputStream);
      zipOutputStream.setLevel(9);

      SortedSet<DeploymentPath> sortedFilesToInclude = new TreeSet<DeploymentPath>(new Comparator<DeploymentPath>() {
        @Override
        public int compare(DeploymentPath deploymentPath1, DeploymentPath deploymentPath2) {
          return zipEntryNameProvider.apply(deploymentPath1).compareTo(zipEntryNameProvider.apply(deploymentPath2));
        }
      });

      sortedFilesToInclude.addAll(filesToInclude);

      for (DeploymentPath deploymentPath : sortedFilesToInclude) {
        String zipEntryName = zipEntryNameProvider.apply(deploymentPath);
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
            Files.walk(deploymentPath.sourcePath()).forEach(
              childPath -> {
                if (!Files.isDirectory(childPath) && shouldIncludePath(childPath, pathsToExclude)) {

                  Path destinationDirectory =
                      Paths.get(format("%s/%s", deploymentPath.destinationDirectory(), deploymentPath.sourcePath()
                        .relativize(childPath)));

                  if (destinationDirectory.getParent() != null)
                    destinationDirectory = destinationDirectory.getParent();

                  filesToInclude.add(DeploymentPaths.get(childPath, destinationDirectory));
                }
              });
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

  protected String hashedFilename(String filename, String hash) {
    requireNonNull(filename);
    requireNonNull(hash);

    int lastIndexOfPeriod = filename.lastIndexOf(".");

    if (lastIndexOfPeriod == -1)
      return format("%s.%s", filename, hash);

    return format("%s.%s%s", filename.substring(0, lastIndexOfPeriod), hash, filename.substring(lastIndexOfPeriod));
  }

  protected String hash(byte[] bytes) throws Exception {
    requireNonNull(bytes);

    MessageDigest messageDigest = MessageDigest.getInstance("MD5");
    byte[] hashBytes = messageDigest.digest(bytes);
    StringBuilder stringBuilder = new StringBuilder(2 * hashBytes.length);

    for (byte b : hashBytes) {
      stringBuilder.append("0123456789ABCDEF".charAt((b & 0xF0) >> 4));
      stringBuilder.append("0123456789ABCDEF".charAt((b & 0x0F)));
    }

    return stringBuilder.toString();
  }
}