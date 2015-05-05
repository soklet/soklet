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

import static com.soklet.util.IoUtils.copyStream;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.stream;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static java.util.Collections.unmodifiableSet;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;
import static java.util.UUID.randomUUID;
import static java.util.logging.Level.WARNING;
import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static java.util.regex.Pattern.compile;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.soklet.util.IoUtils;
import com.soklet.util.PathUtils;
import com.soklet.web.HashedUrlManifest;
import com.soklet.web.HashedUrlManifest.PersistenceFormat;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public abstract class DeployableArchiveCreator {
  private final Set<String> staticFileUnzippableExtensions;
  private final Logger logger = Logger.getLogger(DeployableArchiveCreator.class.getName());

  {
    /**
     * We don't want to pre-gzip file formats that are already compressed by default.
     */
    staticFileUnzippableExtensions = unmodifiableSet(new HashSet<String>() {
      {
        // Images
        add("png");
        add("jpeg");
        add("jpg");
        add("gif");

        // Audio
        add("aif");
        add("m4a");
        add("m4v");
        add("mp3");
        add("mp4");
        add("mpa");
        add("wma");
      }
    });
  }

  public abstract Set<DeploymentPath> pathsToInclude();

  public abstract Optional<Path> staticFileRootDirectory();

  public void preProcess() throws Exception {}

  public void postProcess() throws Exception {}

  public void performCleanup() throws Exception {}

  public Set<Path> pathsToExclude() {
    return emptySet();
  }

  public Path archiveOutputDirectory() {
    return Paths.get(".");
  }

  protected Set<String> staticFileUnzippableExtensions() {
    return this.staticFileUnzippableExtensions;
  }

  public Path archiveFile() {
    return Paths.get("archive.zip");
  }

  public Path hashedUrlManifestFile() {
    return HashedUrlManifest.defaultManifestFile();
  }

  public void run() throws Exception {
    try {
      Set<DeploymentPath> pathsToInclude = pathsToInclude();
      Set<Path> pathsToExclude = pathsToExclude();
      Path archiveFile = archiveFile();
      Optional<Path> staticFileRootDirectory = staticFileRootDirectory();

      logger.info("Creating deployment archive...");

      preProcess();

      if (staticFileRootDirectory.isPresent())
        verifyValidDirectory(staticFileRootDirectory.get());

      Set<DeploymentPath> filesToInclude = extractAllFilesFromPaths(pathsToInclude, pathsToExclude);
      Set<DeploymentPath> staticFilesToInclude =
          staticFileRootDirectory.isPresent() ? extractStaticFilesFromPaths(singleton(staticFileRootDirectory.get()),
            filesToInclude) : emptySet();

      // Remove static files for now - we handle them specially below and add them back as we go
      filesToInclude.removeAll(staticFilesToInclude);

      // Figure out mappings of static file URLs to their hashed variants
      HashedUrlManifest hashedUrlManifest = createHashedUrlManifest(staticFilesToInclude);

      Path temporaryDirectory =
          Files.createTempDirectory(format("com.soklet.%s-%s-", getClass().getSimpleName(), randomUUID()));

      try {
        Map<DeploymentPath, Path> deploymentPathsToCopiedFiles = new HashMap<>(staticFilesToInclude.size());

        // First, copy all static files to a temporary directory and perform any transforms required on them
        for (DeploymentPath staticFileToInclude : staticFilesToInclude) {
          // Make sure we have a place to put it by creating any intermediate directories.
          // File.mkdirs() actually creates the file so we delete it right after (the directories will stay)
          File file = temporaryDirectory.resolve(staticFileToInclude.sourcePath()).toFile();
          file.mkdirs();
          file.delete();

          Optional<InputStream> transformedStaticFile = transformStaticFile(staticFileToInclude.sourcePath());
          Path copiedFile = null;

          // Allow for transforms to be applied to static files.
          // For example, you might want to compress Javascript
          if (transformedStaticFile.isPresent()) {
            copiedFile = temporaryDirectory.resolve(staticFileToInclude.sourcePath());

            try (InputStream inputStream = transformedStaticFile.get();
                OutputStream outputStream = Files.newOutputStream(copiedFile)) {
              copyStream(inputStream, outputStream);
            }
          } else {
            copiedFile =
                Files.copy(staticFileToInclude.sourcePath(),
                  temporaryDirectory.resolve(staticFileToInclude.sourcePath()));
          }

          deploymentPathsToCopiedFiles.put(staticFileToInclude, copiedFile);
        }

        // Rewrite CSS URL references to use their hashed versions
        Set<DeploymentPath> copiedCssDeploymentPaths = new HashSet<>();

        if (shouldRewriteCssUrls()) {
          for (DeploymentPath staticFileToInclude : staticFilesToInclude) {
            Path copiedFile = deploymentPathsToCopiedFiles.get(staticFileToInclude);

            if (copiedFile == null)
              throw new IllegalStateException(format("Missing copy of %s", staticFileToInclude));

            if (copiedFile.getFileName().toString().toLowerCase(ENGLISH).endsWith(".css")) {
              rewriteCssUrls(copiedFile, hashedUrlManifest);
              copiedCssDeploymentPaths.add(new DeploymentPath(copiedFile, staticFileToInclude.destinationDirectory()));
            }
          }
        }

        // Rewrite the manifest now that we've rewritten CSS URLs (CSS files will hash differently now!)
        // TODO: need to think about how to handle CSS imports
        Map<String, String> hashedUrlsByUrl = new HashMap<>(hashedUrlManifest.hashedUrlsByUrl());
        hashedUrlsByUrl.putAll(createHashedUrlManifest(copiedCssDeploymentPaths).hashedUrlsByUrl());

        hashedUrlManifest = new HashedUrlManifest(hashedUrlsByUrl);

        for (DeploymentPath staticFileToInclude : staticFilesToInclude) {
          Path copiedFile = deploymentPathsToCopiedFiles.get(staticFileToInclude);

          if (copiedFile == null)
            throw new IllegalStateException(format("Missing copy of %s", staticFileToInclude));

          // 1. The static file by itself, e.g. test.css
          filesToInclude.add(new DeploymentPath(copiedFile, staticFileToInclude.destinationDirectory()));

          // 2. The static file with its hash embedded, e.g. test.BE9748F56259CA49B7E5F249BE1030BE.css
          String hash = hash(Files.readAllBytes(copiedFile));
          String hashedFilename = hashedFilename(copiedFile.toString(), hash);
          Path copiedHashedFile = Files.copy(copiedFile, temporaryDirectory.resolve(hashedFilename));

          if (shouldCreateHashedStaticFiles())
            filesToInclude.add(new DeploymentPath(copiedHashedFile, staticFileToInclude.destinationDirectory()));

          if (shouldZipStaticFile(staticFileToInclude.sourcePath())) {
            // 3. The pre-gzipped static file, e.g. test.css.gz
            Path copiedGzippedFile = Paths.get(format("%s.gz", copiedFile.toAbsolutePath()));

            try (InputStream inputStream = Files.newInputStream(copiedFile);
                GZIPOutputStream outputStream = new GZIPOutputStream(Files.newOutputStream(copiedGzippedFile))) {
              IoUtils.copyStream(inputStream, outputStream);
              outputStream.flush();
            }

            filesToInclude.add(new DeploymentPath(copiedGzippedFile, staticFileToInclude.destinationDirectory()));

            // 4. The pre-gzipped hashed static file, e.g. test.BE9748F56259CA49B7E5F249BE1030BE.css.gz
            Path copiedHashedGzippedFile = Paths.get(format("%s.gz", copiedHashedFile.toAbsolutePath()));

            try (InputStream inputStream = Files.newInputStream(copiedFile);
                GZIPOutputStream outputStream = new GZIPOutputStream(Files.newOutputStream(copiedHashedGzippedFile))) {
              IoUtils.copyStream(inputStream, outputStream);
              outputStream.flush();
            }

            filesToInclude.add(new DeploymentPath(copiedHashedGzippedFile, staticFileToInclude.destinationDirectory()));
          }
        }

        if (shouldCreateHashedStaticFiles()) {
          Path hashedUrlManifestFile = Paths.get(temporaryDirectory.toString(), hashedUrlManifestFile().toString());

          try (OutputStream outputStream = Files.newOutputStream(hashedUrlManifestFile)) {
            hashedUrlManifest.writeToOutputStream(outputStream, PersistenceFormat.PRETTY_PRINTED);
            filesToInclude.add(new DeploymentPath(hashedUrlManifestFile, Paths.get(".")));
          }
        }

        postProcess();

        createZip(archiveFile, filesToInclude);
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

  protected HashedUrlManifest createHashedUrlManifest(Set<DeploymentPath> staticFilesToInclude) throws Exception {
    Optional<Path> staticFileRootDirectory = staticFileRootDirectory();

    // Keep track of our hashed URLs for manifest creation
    Map<String, String> hashedUrlsByUrl = new HashMap<>(staticFilesToInclude.size());

    // Figure out the static file manifest
    for (DeploymentPath staticFileToInclude : staticFilesToInclude) {
      Path staticFile = staticFileToInclude.sourcePath();

      String hash = hash(Files.readAllBytes(staticFile));

      // Update manifest
      Path relativizedStaticFileDirectory =
          staticFileRootDirectory.get().relativize(staticFileToInclude.destinationDirectory());

      hashedUrlsByUrl.put(
        format("/%s/%s/%s", staticFileRootDirectory.get().getFileName(), relativizedStaticFileDirectory,
          staticFileToInclude.sourcePath().getFileName()),
        format("/%s/%s/%s", staticFileRootDirectory.get().getFileName(), relativizedStaticFileDirectory,
          hashedFilename(staticFileToInclude.sourcePath().getFileName().toString(), hash)));
    }

    return new HashedUrlManifest(hashedUrlsByUrl);
  }

  protected boolean shouldZipStaticFile(Path staticFile) {
    requireNonNull(staticFile);

    String filename = staticFile.getFileName().toString().toLowerCase(ENGLISH);
    int lastIndexOfPeriod = filename.lastIndexOf(".");

    if (lastIndexOfPeriod == -1 || filename.endsWith("."))
      return true;

    String extension = filename.substring(lastIndexOfPeriod + 1);

    return !staticFileUnzippableExtensions().contains(extension);
  }

  protected boolean shouldCreateHashedStaticFiles() {
    return staticFileRootDirectory().isPresent();
  }

  protected Optional<InputStream> transformStaticFile(Path staticFile) {
    requireNonNull(staticFile);
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

  protected void createZip(Path archiveFile, Set<DeploymentPath> filesToInclude) {
    requireNonNull(archiveFile);
    requireNonNull(filesToInclude);

    logger.info(format("Assembling %s...", archiveFile));

    FileOutputStream fileOutputStream = null;

    try {
      fileOutputStream = new FileOutputStream(archiveFile.toFile());
    } catch (IOException e) {
      throw new UncheckedIOException(format("Unable to create zip archive %s", archiveFile), e);
    }

    ZipOutputStream zipOutputStream = null;
    Function<DeploymentPath, String> zipEntryNameProvider =
        (deploymentPath) -> format("%s/%s", deploymentPath.destinationDirectory(), deploymentPath.sourcePath()
          .getFileName());

    try {
      zipOutputStream = new ZipOutputStream(fileOutputStream);
      zipOutputStream.setLevel(9);

      // Zip root is the name of the archive without the extension, e.g. "app.zip" would be "app".
      String zipRoot = archiveFile.getFileName().toString();
      int indexOfPeriod = zipRoot.indexOf(".");
      if (indexOfPeriod != -1 && zipRoot.length() > 1)
        zipRoot = zipRoot.substring(0, indexOfPeriod);

      SortedSet<DeploymentPath> sortedFilesToInclude = new TreeSet<DeploymentPath>(new Comparator<DeploymentPath>() {
        @Override
        public int compare(DeploymentPath deploymentPath1, DeploymentPath deploymentPath2) {
          return zipEntryNameProvider.apply(deploymentPath1).compareTo(zipEntryNameProvider.apply(deploymentPath2));
        }
      });

      sortedFilesToInclude.addAll(filesToInclude);

      for (DeploymentPath deploymentPath : sortedFilesToInclude) {
        String zipEntryName = zipEntryNameProvider.apply(deploymentPath);
        logger.fine(format("Adding %s...", zipEntryName));
        zipOutputStream.putNextEntry(new ZipEntry(format("%s/%s", zipRoot, zipEntryName)));
        zipOutputStream.write(Files.readAllBytes(deploymentPath.sourcePath()));
      }

      zipOutputStream.flush();

      logger.info(format("Deployment archive %s was created successfully.", archiveFile));
    } catch (IOException e) {
      throw new UncheckedIOException(format("An error occurred while creating zip archive %s", archiveFile), e);
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

  protected boolean shouldRewriteCssUrls() {
    return shouldCreateHashedStaticFiles();
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

  protected void rewriteCssUrls(Path cssFile, HashedUrlManifest hashedUrlManifest) throws IOException {
    verifyValidFile(cssFile);
    requireNonNull(hashedUrlManifest);

    String css = new String(Files.readAllBytes(cssFile), UTF_8);
    String urlPrefix = format("/%s", staticFileRootDirectory().get().getFileName());

    // Don't use StringBuilder as regex methods like appendTail require a StringBuffer
    StringBuffer stringBuffer = new StringBuffer();

    Pattern cssUrlPattern = createCssUrlPattern();
    Matcher matcher = cssUrlPattern.matcher(css);

    while (matcher.find()) {
      // Is true if this a match on something like @import "/static/test.css";
      boolean processingImportUrl = false;

      String originalUrl = matcher.group(1);

      if (matcher.group(2) != null) {
        originalUrl = matcher.group(2);
        processingImportUrl = true;
      }

      String url = originalUrl;
      boolean dataUrl = false;

      // Try to expand relative URL components.
      // Since full expansion of relative components is a lot of work and tricky, we have to enforce some basic rules
      // like any relative URL must start with "../" (URLs like "/static/../whatever.png" are not
      // permitted) and you can't nest relative paths (URLs like "../../test/../whatever.png" are not permitted).
      if (url.startsWith("../")) {
        String temporaryUrl = url;
        int relativePathCount = 0;

        while (temporaryUrl.startsWith("../")) {
          ++relativePathCount;
          temporaryUrl = temporaryUrl.substring(3);
        }

        // Rejects URLs like "../../test/../whatever.png"
        if (temporaryUrl.contains("../")) {
          logger.warning(format("URL '%s' has nested relative path component[s] so we can't process it.", url));
        } else {
          File parent = cssFile.toFile().getParentFile();
          String parentPath = "";

          for (int i = 0; i < relativePathCount - 1; i++) {
            parentPath += parent.getName() + "/";
            parent = parent.getParentFile();
          }

          url = urlPrefix + parentPath + temporaryUrl;
          logger.fine(format("Relative URL %s was rewritten to %s", originalUrl, url));
        }
      } else if (url.contains("../")) {
        // Rejects URLs like "/static/../whatever.png"
        logger.warning(format(
          "URL '%s' has relative path component[s] which do not start with ../ so we can't process it.", url));
      } else if (url.toLowerCase(ENGLISH).startsWith("data:")) {
        dataUrl = true;
      }

      String cleanedUrl = url;

      // Clean up - see comments on createCssUrlPattern() to see why.
      // TODO: fix the pattern so we don't have to do this.
      int indexOfQuestionMark = cleanedUrl.indexOf("?");
      if (indexOfQuestionMark > 0)
        cleanedUrl = cleanedUrl.substring(0, indexOfQuestionMark);

      int indexOfHash = cleanedUrl.indexOf("#");
      if (indexOfHash > 0)
        cleanedUrl = cleanedUrl.substring(0, indexOfHash);

      Optional<String> hashedUrl = hashedUrlManifest.hashedUrl(cleanedUrl);
      String rewrittenUrl = url;

      if (hashedUrl.isPresent()) {
        rewrittenUrl = url.replace(cleanedUrl, hashedUrl.get());
        logger.fine(format("Rewrote CSS URL reference '%s' to be '%s' in %s", originalUrl, rewrittenUrl,
          cssFile.getFileName()));
      } else if (!dataUrl) {
        logger.warning(format("Unable to resolve CSS URL reference '%s' in %s", originalUrl, cssFile));
      }

      String replacement;

      if (processingImportUrl)
        replacement = format("@import \"%s\";", rewrittenUrl);
      else
        replacement = format("url(\"%s\")", rewrittenUrl);

      matcher.appendReplacement(stringBuffer, replacement);

      if (processingImportUrl)
        logger.warning(format(
          "Caution: CSS import URL hash rewriting is not fully supported yet. Detected CSS import URL in %s",
          cssFile.getFileName()));
    }

    matcher.appendTail(stringBuffer);

    String rewrittenFileContents = stringBuffer.toString();

    try {
      Files.write(cssFile, rewrittenFileContents.getBytes(UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(format("Unable to write rewritten CSS file %s", cssFile), e);
    }
  }

  protected Pattern createCssUrlPattern() {
    // TODO: use a better regex, currently it fails in these cases.
    // This is OK since we work around it manually, but would be good to fix once and for all in the pattern.
    // src: url("/static/fonts/example/myfont.eot?#iefix") format('embedded-opentype')
    // will match as
    // /static/fonts/example/myfont.eot?#iefix
    // instead of
    // /static/fonts/example/myfont.eot
    // and
    // url("/static/fonts/example/myfont.svg#ExampleRegular")
    // will match as
    // /static/fonts/example/myfont.svg#ExampleRegular
    // instead of
    // /static/fonts/example/myfont.svg
    String cssUrlPattern = "url\\s*\\(\\s*['\"]?(.+?)\\s*['\"]?\\s*\\)";
    String cssImportUrlPattern = "@import\\s+['\"](.+?)['\"];";
    return compile(format("%s|%s", cssUrlPattern, cssImportUrlPattern), CASE_INSENSITIVE);
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

    if (filename.endsWith("."))
      return format("%s%s", filename, hash);

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