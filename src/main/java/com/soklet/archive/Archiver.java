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

package com.soklet.archive;

import static com.soklet.util.IoUtils.copyStreamCloseAfterwards;
import static com.soklet.util.PathUtils.allFilesInDirectory;
import static com.soklet.util.StringUtils.trimToNull;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptySet;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableSet;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;
import static java.util.UUID.randomUUID;
import static java.util.logging.Level.WARNING;
import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static java.util.regex.Pattern.compile;
import static java.util.stream.Collectors.toSet;

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
public class Archiver {
  private static final Set<Path> DEFAULT_PATHS_TO_EXCLUDE = unmodifiableSet(new HashSet<Path>() {
    {
      add(Paths.get(".git"));
    }
  });

  private static final Set<String> DEFAULT_STATIC_FILE_UNZIPPABLE_EXTENSIONS = unmodifiableSet(new HashSet<String>() {
    {
      // Image
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

  private static final Hasher DEFAULT_FILE_HASHER = (bytes) -> {
    requireNonNull(bytes);

    MessageDigest messageDigest = null;

    try {
      messageDigest = MessageDigest.getInstance("MD5");
    } catch (Exception e) {
      throw new RuntimeException("Unable to create hasher", e);
    }

    byte[] hashBytes = messageDigest.digest(bytes);
    StringBuilder stringBuilder = new StringBuilder(2 * hashBytes.length);

    for (byte b : hashBytes) {
      stringBuilder.append("0123456789ABCDEF".charAt((b & 0xF0) >> 4));
      stringBuilder.append("0123456789ABCDEF".charAt((b & 0x0F)));
    }

    return stringBuilder.toString();
  };

  private static final FilenameHasher DEFAULT_FILENAME_HASHER = (file, hash) -> {
    requireNonNull(file);
    requireNonNull(hash);

    String filename = file.toAbsolutePath().toString();

    int lastIndexOfPeriod = filename.lastIndexOf(".");

    if (lastIndexOfPeriod == -1)
      return format("%s.%s", filename, hash);

    if (filename.endsWith("."))
      return format("%s%s", filename, hash);

    return format("%s.%s%s", filename.substring(0, lastIndexOfPeriod), hash, filename.substring(lastIndexOfPeriod));
  };

  private final Path archiveFile;
  private final Set<ArchivePath> archivePathsToInclude;
  private final Set<Path> pathsToExclude;
  private final Optional<Path> staticFileRootDirectory;
  private final Optional<MavenSupport> mavenSupport;
  private final Optional<ArchiveSupportOperation> preProcessOperation;
  private final Optional<ArchiveSupportOperation> postProcessOperation;
  private final Optional<FileAlterationOperation> fileAlterationOperation;
  private final Hasher fileHasher;
  private final FilenameHasher filenameHasher;
  private final Set<String> staticFileUnzippableExtensions;

  private final Logger logger = Logger.getLogger(Archiver.class.getName());

  protected Archiver(Builder builder) {
    requireNonNull(builder);

    this.archiveFile = builder.archiveFile;
    this.archivePathsToInclude = builder.archivePathsToInclude;
    this.pathsToExclude = builder.pathsToExclude;
    this.staticFileRootDirectory = builder.staticFileRootDirectory;
    this.mavenSupport = builder.mavenSupport;
    this.preProcessOperation = builder.preProcessOperation;
    this.postProcessOperation = builder.postProcessOperation;
    this.fileAlterationOperation = builder.fileAlterationOperation;
    this.fileHasher = builder.fileHasher;
    this.filenameHasher = builder.filenameHasher;
    this.staticFileUnzippableExtensions = builder.staticFileUnzippableExtensions;

    // Enforce relative paths
    for (ArchivePath archivePath : archivePathsToInclude) {
      if (archivePath.sourcePath().isAbsolute())
        throw new IllegalArgumentException(format(
          "Deployment paths cannot be absolute, they must be relative. Offending source path was %s",
          archivePath.sourcePath()));
      if (archivePath.destinationDirectory().isAbsolute())
        throw new IllegalArgumentException(format(
          "Deployment paths cannot be absolute, they must be relative. Offending destination directory was %s",
          archivePath.destinationDirectory()));
    }
  }

  public void run() throws Exception {
    logger.info(format("Creating deployment archive %s...", archiveFile()));

    Path temporaryDirectory =
        Files.createTempDirectory(format("com.soklet.%s-%s-", getClass().getSimpleName(), randomUUID()));

    Optional<Path> temporaryStaticFileRootDirectory = Optional.empty();

    if (staticFileRootDirectory().isPresent()) {
      Path relativeStaticFileRootDirectory =
          Paths.get(".").toAbsolutePath().getParent().relativize(staticFileRootDirectory.get().toAbsolutePath());
      temporaryStaticFileRootDirectory = Optional.of(temporaryDirectory.resolve(relativeStaticFileRootDirectory));
    }

    try {
      // Copy everything over to our temporary working directory
      PathUtils.copyDirectory(Paths.get("."), temporaryDirectory, pathsToExclude);

      // Run any client-supplied preprocessing code
      if (preProcessOperation().isPresent())
        preProcessOperation().get().perform(this, temporaryDirectory);

      // Run any Maven tasks
      if (mavenSupport().isPresent())
        performMavenSupport(mavenSupport.get(), temporaryDirectory);

      // Permit client code to modify files in-place (for example, compress JS and CSS)
      if (fileAlterationOperation().isPresent())
        performFileAlterations(temporaryDirectory);

      // Hash static files and store off the manifest
      Optional<Path> hashedUrlManifestFile = Optional.empty();

      if (temporaryStaticFileRootDirectory.isPresent()) {
        // 1. Do the hashing
        HashedUrlManifest hashedUrlManifest = performStaticFileHashing(temporaryStaticFileRootDirectory.get());

        // 2. Write the manifest to disk for inclusion in the archive
        hashedUrlManifestFile = Optional.of(temporaryDirectory.resolve(HashedUrlManifest.defaultManifestFile()));

        try (OutputStream outputStream = Files.newOutputStream(hashedUrlManifestFile.get())) {
          hashedUrlManifest.writeToOutputStream(outputStream, PersistenceFormat.PRETTY_PRINTED);
        }

        // 3. Create gzipped versions of static files as appropriate
        for (Path staticFile : PathUtils.allFilesInDirectory(temporaryStaticFileRootDirectory.get())) {
          if (!shouldZipStaticFile(staticFile))
            continue;

          Path gzippedFile = Paths.get(format("%s.gz", staticFile.toAbsolutePath()));

          try (InputStream inputStream = Files.newInputStream(staticFile);
              GZIPOutputStream outputStream = new GZIPOutputStream(Files.newOutputStream(gzippedFile))) {
            IoUtils.copyStream(inputStream, outputStream);
            outputStream.flush();
          }
        }
      }

      // Run any client-supplied postprocessing code
      if (postProcessOperation().isPresent())
        postProcessOperation().get().perform(this, temporaryDirectory);

      // Re-root the provided deployment paths to point to the temporary directory
      Set<ArchivePath> workingArchivePaths = archivePathsToInclude().stream().map(archivePath -> {
        Path sourcePath = temporaryDirectory.resolve(archivePath.sourcePath());
        return ArchivePaths.get(sourcePath, archivePath.destinationDirectory());
      }).collect(toSet());

      // If we generated a hashed url manifest, add it to the archive
      if (hashedUrlManifestFile.isPresent())
        workingArchivePaths.add(ArchivePaths.get(hashedUrlManifestFile.get(), Paths.get(".")));

      // Finally - create the archive
      createZip(archiveFile(), extractFilesFromarchivePaths(workingArchivePaths));

      logger.info(format("Deployment archive %s was created successfully.", archiveFile));
    } finally {
      PathUtils.deleteDirectory(temporaryDirectory);
    }
  }

  protected void createZip(Path archiveFile, Set<ArchivePath> archivePathsToInclude) {
    requireNonNull(archiveFile);
    requireNonNull(archivePathsToInclude);

    logger.info(format("Assembling %s...", archiveFile));

    FileOutputStream fileOutputStream = null;

    try {
      fileOutputStream = new FileOutputStream(archiveFile.toFile());
    } catch (IOException e) {
      throw new UncheckedIOException(format("Unable to create zip archive %s", archiveFile), e);
    }

    ZipOutputStream zipOutputStream = null;
    Function<ArchivePath, String> zipEntryNameProvider =
        (archivePath) -> format("%s/%s", archivePath.destinationDirectory(), archivePath.sourcePath().getFileName());

    try {
      zipOutputStream = new ZipOutputStream(fileOutputStream);
      zipOutputStream.setLevel(9);

      // Zip root is the name of the archive without the extension, e.g. "app.zip" would be "app".
      String zipRoot = archiveFile.getFileName().toString();
      int indexOfPeriod = zipRoot.indexOf(".");
      if (indexOfPeriod != -1 && zipRoot.length() > 1)
        zipRoot = zipRoot.substring(0, indexOfPeriod);

      SortedSet<ArchivePath> sortedarchivePathsToInclude = new TreeSet<ArchivePath>(new Comparator<ArchivePath>() {
        @Override
        public int compare(ArchivePath archivePath1, ArchivePath archivePath2) {
          return zipEntryNameProvider.apply(archivePath1).compareTo(zipEntryNameProvider.apply(archivePath2));
        }
      });

      sortedarchivePathsToInclude.addAll(archivePathsToInclude);

      for (ArchivePath archivePath : sortedarchivePathsToInclude) {
        String zipEntryName = zipEntryNameProvider.apply(archivePath);
        logger.fine(format("Adding %s...", zipEntryName));
        zipOutputStream.putNextEntry(new ZipEntry(format("%s/%s", zipRoot, zipEntryName)));
        zipOutputStream.write(Files.readAllBytes(archivePath.sourcePath()));
      }

      zipOutputStream.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(format("An error occurred while creating deployment archive %s", archiveFile), e);
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

  protected Set<ArchivePath> extractFilesFromarchivePaths(Set<ArchivePath> pathsToInclude) {
    requireNonNull(pathsToInclude);

    Set<ArchivePath> filesToInclude = new HashSet<>();

    pathsToInclude.forEach(archivePath -> {
      if (Files.exists(archivePath.sourcePath())) {
        if (Files.isDirectory(archivePath.sourcePath())) {
          try {
            Files.walk(archivePath.sourcePath()).forEach(
              childPath -> {
                if (!Files.isDirectory(childPath)) {

                  Path destinationDirectory =
                      Paths.get(format("%s/%s", archivePath.destinationDirectory(), archivePath.sourcePath()
                        .relativize(childPath)));

                  if (destinationDirectory.getParent() != null)
                    destinationDirectory = destinationDirectory.getParent();

                  filesToInclude.add(ArchivePaths.get(childPath, destinationDirectory));
                }
              });
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        } else {
          filesToInclude.add(archivePath);
        }
      }
    });

    return filesToInclude;
  }

  protected void performFileAlterations(Path workingDirectory) throws IOException {
    requireNonNull(workingDirectory);

    if (!Files.exists(workingDirectory))
      throw new IllegalArgumentException(format("Directory %s does not exist!", workingDirectory.toAbsolutePath()));
    if (!Files.isDirectory(workingDirectory))
      throw new IllegalArgumentException(format("%s is not a directory!", workingDirectory.toAbsolutePath()));

    PathUtils.walkDirectory(workingDirectory, (file) -> {
      try {
        Optional<InputStream> alteredFile = fileAlterationOperation().get().alterFile(this, workingDirectory, file);

        // TODO: need to document that we will close the InputStream - the caller is not responsible for that
      if (alteredFile.isPresent())
        copyStreamCloseAfterwards(alteredFile.get(), Files.newOutputStream(file));
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException(format("Unable to alter file %s", file.toAbsolutePath()), e);
    }
  } );
  }

  protected HashedUrlManifest performStaticFileHashing(Path staticFileRootDirectory) throws IOException {
    requireNonNull(staticFileRootDirectory);

    if (!Files.exists(staticFileRootDirectory))
      throw new IllegalArgumentException(format("Directory %s does not exist!",
        staticFileRootDirectory.toAbsolutePath()));
    if (!Files.isDirectory(staticFileRootDirectory))
      throw new IllegalArgumentException(format("%s is not a directory!", staticFileRootDirectory.toAbsolutePath()));

    Map<String, String> hashedUrlsByUrl = new HashMap<>();

    // Step 1: hash everything but CSS files (we rewrite those next)
    for (Path file : allFilesInDirectory(staticFileRootDirectory)) {
      if (isCssFile(file))
        continue;

      HashedFileUrlMapping hashedFileUrlMapping = createHashedFile(file, staticFileRootDirectory);
      hashedUrlsByUrl.put(hashedFileUrlMapping.url(), hashedFileUrlMapping.hashedUrl());
    }

    // Step 2: rewrite CSS files with manifest created in step 1
    for (Path file : allFilesInDirectory(staticFileRootDirectory))
      if (isCssFile(file))
        rewriteCssUrls(file, new HashedUrlManifest(hashedUrlsByUrl));

    // Step 3: add final CSS file hashes to manifest created in step 1
    for (Path file : allFilesInDirectory(staticFileRootDirectory)) {
      if (!isCssFile(file))
        continue;

      HashedFileUrlMapping hashedFileUrlMapping = createHashedFile(file, staticFileRootDirectory);
      hashedUrlsByUrl.put(hashedFileUrlMapping.url(), hashedFileUrlMapping.hashedUrl());
    }

    return new HashedUrlManifest(hashedUrlsByUrl);
  }

  protected HashedFileUrlMapping createHashedFile(Path file, Path staticFileRootDirectory) throws IOException {
    requireNonNull(file);
    requireNonNull(staticFileRootDirectory);

    if (!Files.exists(file))
      throw new IllegalArgumentException(format("File %s does not exist!", file.toAbsolutePath()));
    if (Files.isDirectory(file))
      throw new IllegalArgumentException(format("%s is a directory!", file.toAbsolutePath()));

    if (!Files.exists(staticFileRootDirectory))
      throw new IllegalArgumentException(format("Directory %s does not exist!",
        staticFileRootDirectory.toAbsolutePath()));
    if (!Files.isDirectory(staticFileRootDirectory))
      throw new IllegalArgumentException(format("%s is not a directory!", staticFileRootDirectory.toAbsolutePath()));

    byte[] fileData = Files.readAllBytes(file);
    String hash = fileHasher().hash(fileData);
    String hashedFilename = filenameHasher().hashFilename(file, hash);

    // Keep track of mapping between static URLs and hashed counterparts
    Path relativeStaticFile = staticFileRootDirectory.getParent().relativize(file);
    Path relativeHashedStaticFile = staticFileRootDirectory.getParent().relativize(Paths.get(hashedFilename));

    copyStreamCloseAfterwards(Files.newInputStream(file), Files.newOutputStream(Paths.get(hashedFilename)));

    return new HashedFileUrlMapping(format("/%s", relativeStaticFile), format("/%s", relativeHashedStaticFile));
  }

  protected static class HashedFileUrlMapping {
    private final String url;
    private final String hashedUrl;

    public HashedFileUrlMapping(String url, String hashedUrl) {
      this.url = requireNonNull(url);
      this.hashedUrl = requireNonNull(hashedUrl);
    }

    public String url() {
      return this.url;
    }

    public String hashedUrl() {
      return this.hashedUrl;
    }
  }

  protected boolean isCssFile(Path file) {
    requireNonNull(file);
    return file.getFileName().toString().toLowerCase(ENGLISH).endsWith(".css");
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

  protected void rewriteCssUrls(Path cssFile, HashedUrlManifest hashedUrlManifest) throws IOException {
    requireNonNull(cssFile);
    requireNonNull(hashedUrlManifest);

    if (!Files.exists(cssFile))
      throw new IllegalArgumentException(format("CSS file %s does not exist!", cssFile.toAbsolutePath()));
    if (Files.isDirectory(cssFile))
      throw new IllegalArgumentException(format("%s is a directory!", cssFile.toAbsolutePath()));

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
      } else if (matcher.group(3) != null) {
        originalUrl = matcher.group(3);
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
        logger.fine(format("Rewrote CSS URL reference '%s' to '%s' in %s", originalUrl, rewrittenUrl,
          cssFile.getFileName()));
      } else if (!dataUrl && !processingImportUrl) {
        logger.warning(format("Unable to resolve CSS URL reference '%s' in %s", originalUrl, cssFile.getFileName()));
      }

      String replacement;

      if (processingImportUrl) {
        logger.warning(format(
          "Warning: CSS @import statements are not fully supported yet. Detected @import of %s in %s", originalUrl,
          cssFile.getFileName()));
        replacement = format("@import \"%s\";", rewrittenUrl);
      } else {
        replacement = format("url(\"%s\")", rewrittenUrl);
      }

      matcher.appendReplacement(stringBuffer, replacement);
    }

    matcher.appendTail(stringBuffer);

    String rewrittenFileContents = stringBuffer.toString();

    try {
      Files.write(cssFile, rewrittenFileContents.getBytes(UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(format("Unable to write rewritten CSS file %s", cssFile.getFileName()), e);
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
    String cssImportUrlPattern = "@import\\s+['\"](.+?)['\"].*;";
    String alternateCssImportUrlPattern = "@import\\s+url\\(\\s*['\"](.+?)['\"]\\).*;";
    return compile(format("%s|%s|%s", cssUrlPattern, cssImportUrlPattern, alternateCssImportUrlPattern),
      CASE_INSENSITIVE);
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

  public static class Builder {
    private final Path archiveFile;
    private Set<ArchivePath> archivePathsToInclude = emptySet();
    private Set<Path> pathsToExclude = defaultPathsToExclude();
    private Optional<Path> staticFileRootDirectory = Optional.empty();
    private Optional<MavenSupport> mavenSupport = Optional.empty();
    private Optional<ArchiveSupportOperation> preProcessOperation = Optional.empty();
    private Optional<ArchiveSupportOperation> postProcessOperation = Optional.empty();
    private Optional<FileAlterationOperation> fileAlterationOperation = Optional.empty();
    private Hasher fileHasher = DEFAULT_FILE_HASHER;
    private FilenameHasher filenameHasher = DEFAULT_FILENAME_HASHER;
    private Set<String> staticFileUnzippableExtensions = DEFAULT_STATIC_FILE_UNZIPPABLE_EXTENSIONS;

    protected Builder(Path archiveFile) {
      this.archiveFile = requireNonNull(archiveFile);
    }

    public Builder archivePathsToInclude(Set<ArchivePath> archivePaths) {
      this.archivePathsToInclude = unmodifiableSet(new HashSet<>(requireNonNull(archivePaths)));
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

    public Builder fileHasher(Hasher fileHasher) {
      this.fileHasher = requireNonNull(fileHasher);
      return this;
    }

    public Builder filenameHasher(FilenameHasher filenameHasher) {
      this.filenameHasher = requireNonNull(filenameHasher);
      return this;
    }

    public Builder staticFileUnzippableExtensions(Set<String> staticFileUnzippableExtensions) {
      this.staticFileUnzippableExtensions =
          unmodifiableSet(new HashSet<>(requireNonNull(staticFileUnzippableExtensions)));
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
          throw new ArchiveProcessException(
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

  public Set<ArchivePath> archivePathsToInclude() {
    return this.archivePathsToInclude;
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

  public Hasher fileHasher() {
    return this.fileHasher;
  }

  public FilenameHasher filenameHasher() {
    return this.filenameHasher;
  }

  public Set<String> staticFileUnzippableExtensions() {
    return this.staticFileUnzippableExtensions;
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
     */
    Optional<InputStream> alterFile(Archiver archiver, Path workingDirectory, Path file) throws Exception;
  }

  @FunctionalInterface
  public interface Hasher {
    String hash(byte[] data);
  }

  @FunctionalInterface
  public interface FilenameHasher {
    String hashFilename(Path file, String hash);
  }
}