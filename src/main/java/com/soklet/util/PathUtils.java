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

package com.soklet.util;

import static java.lang.String.format;
import static java.util.Collections.emptySet;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public final class PathUtils {
  private PathUtils() {}

  public static void deleteDirectory(Path directory) throws IOException {
    requireNonNull(directory);

    if (!Files.isDirectory(directory))
      throw new IOException(format("%s is not a directory - cannot delete it", directory.toAbsolutePath()));

    Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path directory, IOException e) throws IOException {
        Files.delete(directory);
        return FileVisitResult.CONTINUE;
      }
    });

    Files.deleteIfExists(directory);
  }

  public static void walkDirectory(Path directory, FileOperation fileOperation) throws IOException {
    requireNonNull(directory);
    requireNonNull(fileOperation);

    if (!Files.isDirectory(directory))
      throw new IOException(format("%s is not a directory - cannot walk it", directory.toAbsolutePath()));

    Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        if (!Files.isDirectory(file))
          fileOperation.perform(file);

        return FileVisitResult.CONTINUE;
      }
    });
  }

  public static List<Path> allFilesInDirectory(Path directory) throws IOException {
    requireNonNull(directory);

    if (!Files.isDirectory(directory))
      throw new IOException(format("%s is not a directory - cannot extract files from it", directory.toAbsolutePath()));

    List<Path> files = new ArrayList<>();
    PathUtils.walkDirectory(directory, file -> files.add(file));

    return files;
  }

  /**
   * Copies a directory.
   * <p>
   * NOTE: This method is not thread-safe.
   * <p>
   * Most of the implementation is thanks to
   * http://stackoverflow.com/questions/17641706/how-to-copy-a-directory-with-its-attributes-permissions-from-one
   * -location-to-ano/18691793#18691793
   * 
   * @param sourceDirectory
   *          the directory to copy from
   * @param targetDirectory
   *          the directory to copy into
   * @throws IOException
   *           if an I/O error occurs
   */
  public static void copyDirectory(Path sourceDirectory, Path targetDirectory) throws IOException {
    copyDirectory(sourceDirectory, targetDirectory, emptySet());
  }

  /**
   * Copies a directory.
   * <p>
   * NOTE: This method is not thread-safe.
   * <p>
   * Most of the implementation is thanks to
   * http://stackoverflow.com/questions/17641706/how-to-copy-a-directory-with-its-attributes-permissions-from-one
   * -location-to-ano/18691793#18691793
   * 
   * @param sourceDirectory
   *          the directory to copy from
   * @param targetDirectory
   *          the directory to copy into
   * @param pathsToSkip
   *          paths that should not be included in the copy, if any
   * @throws IOException
   *           if an I/O error occurs
   */
  public static void copyDirectory(Path sourceDirectory, Path targetDirectory, Set<Path> pathsToSkip)
      throws IOException {
    requireNonNull(sourceDirectory);
    requireNonNull(targetDirectory);
    requireNonNull(pathsToSkip);

    Set<Path> absolutePathsToSkip =
        pathsToSkip.stream().map(path -> path.toAbsolutePath().normalize()).collect(toSet());

    Files.walkFileTree(sourceDirectory, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
      new FileVisitor<Path>() {
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes sourceBasic) throws IOException {
          if (absolutePathsToSkip.contains(dir.toAbsolutePath().normalize()))
            return FileVisitResult.SKIP_SUBTREE;

          Path targetDir = Files.createDirectories(targetDirectory.resolve(sourceDirectory.relativize(dir)));
          AclFileAttributeView acl = Files.getFileAttributeView(dir, AclFileAttributeView.class);

          if (acl != null)
            Files.getFileAttributeView(targetDir, AclFileAttributeView.class).setAcl(acl.getAcl());

          DosFileAttributeView dosAttrs = Files.getFileAttributeView(dir, DosFileAttributeView.class);

          if (dosAttrs != null) {
            DosFileAttributes sourceDosAttrs = dosAttrs.readAttributes();
            DosFileAttributeView targetDosAttrs = Files.getFileAttributeView(targetDir, DosFileAttributeView.class);
            targetDosAttrs.setArchive(sourceDosAttrs.isArchive());
            targetDosAttrs.setHidden(sourceDosAttrs.isHidden());
            targetDosAttrs.setReadOnly(sourceDosAttrs.isReadOnly());
            targetDosAttrs.setSystem(sourceDosAttrs.isSystem());
          }

          FileOwnerAttributeView ownerAttrs = Files.getFileAttributeView(dir, FileOwnerAttributeView.class);

          if (ownerAttrs != null) {
            FileOwnerAttributeView targetOwner = Files.getFileAttributeView(targetDir, FileOwnerAttributeView.class);
            targetOwner.setOwner(ownerAttrs.getOwner());
          }

          PosixFileAttributeView posixAttrs = Files.getFileAttributeView(dir, PosixFileAttributeView.class);

          if (posixAttrs != null) {
            PosixFileAttributes sourcePosix = posixAttrs.readAttributes();
            PosixFileAttributeView targetPosix = Files.getFileAttributeView(targetDir, PosixFileAttributeView.class);
            targetPosix.setPermissions(sourcePosix.permissions());
            targetPosix.setGroup(sourcePosix.group());
          }

          UserDefinedFileAttributeView userAttrs = Files.getFileAttributeView(dir, UserDefinedFileAttributeView.class);

          if (userAttrs != null) {
            UserDefinedFileAttributeView targetUser =
                Files.getFileAttributeView(targetDir, UserDefinedFileAttributeView.class);
            for (String key : userAttrs.list()) {
              ByteBuffer buffer = ByteBuffer.allocate(userAttrs.size(key));
              userAttrs.read(key, buffer);
              buffer.flip();
              targetUser.write(key, buffer);
            }
          }

          // Must be done last, otherwise last-modified time may be wrong
          BasicFileAttributeView targetBasic = Files.getFileAttributeView(targetDir, BasicFileAttributeView.class);
          targetBasic.setTimes(sourceBasic.lastModifiedTime(), sourceBasic.lastAccessTime(), sourceBasic.creationTime());

          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          if (absolutePathsToSkip.contains(file.toAbsolutePath().normalize()))
            return FileVisitResult.SKIP_SUBTREE;

          Files.copy(file, targetDirectory.resolve(sourceDirectory.relativize(file)),
            StandardCopyOption.COPY_ATTRIBUTES);

          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
          throw e;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
          if (e != null)
            throw e;

          return FileVisitResult.CONTINUE;
        }
      });
  }

  @FunctionalInterface
  public static interface FileOperation {
    void perform(Path file) throws IOException;
  }
}