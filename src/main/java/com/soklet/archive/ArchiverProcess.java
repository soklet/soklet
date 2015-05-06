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

import static com.soklet.util.StringUtils.isBlank;
import static java.lang.String.format;
import static java.util.Arrays.stream;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class ArchiverProcess {
  private final Path executableFile;
  private final Path workingDirectory;
  private final long timeout;
  private final TimeUnit timeUnit;

  private final Logger logger = Logger.getLogger(ArchiverProcess.class.getName());

  public ArchiverProcess(Path executableFile) {
    this(executableFile, Paths.get("."));
  }

  public ArchiverProcess(Path executableFile, Path workingDirectory) {
    this(executableFile, workingDirectory, 5, TimeUnit.MINUTES);
  }

  public ArchiverProcess(Path executableFile, Path workingDirectory, long timeout, TimeUnit timeUnit) {
    this.executableFile = requireNonNull(executableFile);
    this.workingDirectory = requireNonNull(workingDirectory);
    this.timeout = timeout;
    this.timeUnit = requireNonNull(timeUnit);

    if (!Files.exists(executableFile))
      throw new IllegalArgumentException(format("File %s does not exist!", executableFile.toAbsolutePath()));
    if (!Files.isExecutable(executableFile))
      throw new IllegalArgumentException(format("%s is not an executable file", executableFile.toAbsolutePath()));

    if (!Files.exists(workingDirectory))
      throw new IllegalArgumentException(format("Directory %s does not exist!", workingDirectory.toAbsolutePath()));
    if (!Files.isDirectory(workingDirectory))
      throw new IllegalArgumentException(format("%s is not a directory!", workingDirectory.toAbsolutePath()));
  }

  public int execute(String... arguments) {
    return execute(arguments == null ? emptyList() : stream(arguments).collect(toList()));
  }

  public int execute(List<String> arguments) {
    requireNonNull(arguments);
    return execute(createProcessBuilder(executableFile, arguments));
  }

  protected ProcessBuilder createProcessBuilder(Path executableFile, List<String> arguments) {
    requireNonNull(executableFile);
    requireNonNull(arguments);

    List<String> finalArguments = new ArrayList<>(arguments.size() + 1);
    finalArguments.add(executableFile.toAbsolutePath().toString());
    finalArguments.addAll(arguments);

    ProcessBuilder processBuilder = new ProcessBuilder(finalArguments.toArray(new String[] {})).inheritIO();
    processBuilder.directory(workingDirectory().toFile());

    Map<String, String> environment = processBuilder.environment();

    String javaHome = System.getProperty("java.home");

    if (!isBlank(javaHome)) {
      Path javaHomeDirectory = Paths.get(javaHome);

      if (Files.isDirectory(javaHomeDirectory)) {
        // Special case: the java.home value might be the JRE, for example
        // /Library/Java/JavaVirtualMachines/jdk1.8.0_20.jdk/Contents/Home/jre
        // On OS X, a workaround is to use the parent directory of the JRE.
        // TODO: investigate workarounds needed for other platforms
        if ("jre".equals(javaHomeDirectory.getFileName().toString()))
          javaHomeDirectory = javaHomeDirectory.getParent();

        environment.putIfAbsent("JAVA_HOME", javaHomeDirectory.toAbsolutePath().toString());
      }
    }

    return processBuilder;
  }

  protected int execute(ProcessBuilder processBuilder) {
    requireNonNull(processBuilder);

    String processDescription = processBuilder.command().stream().collect(joining(" "));
    logger.info(format("Starting process: %s", processDescription));

    try {
      Process process = processBuilder.start();

      if (process.waitFor(timeout(), timeUnit())) {
        int exitValue = process.exitValue();

        logger.info(format("Completed process (exit value %d): %s", exitValue, processDescription));

        if (invalidProcessExitValue(process))
          throw new ArchiveProcessException(format("Invalid process exit value: %d", exitValue), process);

        return exitValue;
      } else {
        process.destroyForcibly();
        throw new ArchiveProcessException("Process timed out, forcibly terminating.", process);
      }
    } catch (IOException e) {
      throw new ArchiveProcessException("Unable to execute process.", e);
    } catch (InterruptedException e) {
      throw new ArchiveProcessException("Process was interrupted.", e);
    }
  }

  protected boolean invalidProcessExitValue(Process process) {
    requireNonNull(process);
    return process.exitValue() != 0;
  }

  public Path executableFile() {
    return this.executableFile;
  }

  public Path workingDirectory() {
    return this.workingDirectory;
  }

  public long timeout() {
    return this.timeout;
  }

  public TimeUnit timeUnit() {
    return this.timeUnit;
  }
}