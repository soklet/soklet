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

package com.soklet.web.server;

import static com.soklet.util.StringUtils.isBlank;
import static java.lang.Runtime.getRuntime;
import static java.lang.String.format;
import static java.lang.System.getProperty;
import static java.lang.management.ManagementFactory.getRuntimeMXBean;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;
import static java.util.logging.Level.FINER;
import static java.util.logging.Level.WARNING;
import static java.util.stream.Collectors.joining;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.1.0
 */
public class ServerLauncher {
  private static final Path PROCESS_ID_FILE = Paths.get("server.pid");
  private static final OperatingSystem CURRENT_OPERATING_SYSTEM;

  private final Server server;
  private final Logger logger = Logger.getLogger(ServerLauncher.class.getName());

  static {
    String osName = getProperty("os.name");
    osName = osName == null ? "" : osName.toLowerCase(ENGLISH);

    if (osName.contains("linux"))
      CURRENT_OPERATING_SYSTEM = OperatingSystem.LINUX;
    else if (osName.contains("windows"))
      CURRENT_OPERATING_SYSTEM = OperatingSystem.WINDOWS;
    else if (osName.contains("mac"))
      CURRENT_OPERATING_SYSTEM = OperatingSystem.MAC;
    else
      CURRENT_OPERATING_SYSTEM = OperatingSystem.OTHER;
  }

  public ServerLauncher(Server server) {
    this.server = requireNonNull(server);
  }

  public void launch(StoppingStrategy stoppingStrategy) throws ServerException {
    requireNonNull(stoppingStrategy);
    launch(stoppingStrategy, () -> {}, () -> {});
  }

  public void launchWithStartupOperation(StoppingStrategy stoppingStrategy, ServerLifecycleOperation onStartupOperation)
      throws ServerException {
    requireNonNull(stoppingStrategy);
    requireNonNull(onStartupOperation);
    launch(stoppingStrategy, onStartupOperation, () -> {});
  }

  public void launchWithShutdownOperation(StoppingStrategy stoppingStrategy,
      ServerLifecycleOperation onShutdownOperation) throws ServerException {
    requireNonNull(stoppingStrategy);
    requireNonNull(onShutdownOperation);
    launch(stoppingStrategy, () -> {}, onShutdownOperation);
  }

  public void launch(StoppingStrategy stoppingStrategy, ServerLifecycleOperation onStartupOperation,
      ServerLifecycleOperation onShutdownOperation) throws ServerException {
    requireNonNull(stoppingStrategy);
    requireNonNull(onStartupOperation);
    requireNonNull(onShutdownOperation);

    stopExistingServerProcess();
    server.start();
    createProcessIdFile();

    try {
      onStartupOperation.perform();
    } catch (Throwable e) {
      throw new ServerException("An error occurred while performing server startup callback", e);
    }

    getRuntime().addShutdownHook(new Thread() {
      public void run() {
        deleteProcessIdFile();

        try {
          server.stop();
        } catch (ServerException e) {
          logger.log(WARNING, "Unable to cleanly shut down server", e);
        } finally {
          try {
            onShutdownOperation.perform();
          } catch (Throwable e) {
            logger.log(WARNING, "Unable to successfully perform server shutdown callback", e);
          }
        }
      }
    });

    if (stoppingStrategy == StoppingStrategy.ON_KEYPRESS) {
      try {
        System.in.read();
      } catch (IOException e) {
        logger.log(WARNING, "Unable to read from standard input, exiting...", e);
      }

      System.exit(0);
    }
  }

  protected void stopExistingServerProcess() {
    if (!Files.isRegularFile(processIdFile())) return;

    String previousProcessId;
    try {
      previousProcessId = new String(Files.readAllBytes(processIdFile()), UTF_8);
    } catch (IOException e) {
      logger.log(Level.WARNING,
        format("Unable to read previous server process ID from %s", processIdFile().toAbsolutePath()), e);
      return;
    }

    if (logger.isLoggable(FINER))
      logger.finer(format("Previous server process ID was %s, killing it...", previousProcessId));

    try {
      killProcess(previousProcessId);

      if (logger.isLoggable(FINER))
        logger.finer(format("Successfully killed previous server process ID %s", previousProcessId));
    } catch (Exception e) {
      if (logger.isLoggable(FINER))
        logger.finer(format("Unable to kill previous server process ID %s", previousProcessId));
    }
  }

  protected void createProcessIdFile() {
    String currentProcessId = currentProcessId();

    if (logger.isLoggable(FINER))
      logger.finer(format("Storing off current process ID %s in %s...", currentProcessId, processIdFile()));

    try {
      Files.write(processIdFile(), currentProcessId.getBytes(UTF_8));
    } catch (IOException e) {
      logger.log(Level.WARNING, format("Unable to write server process ID to %s", processIdFile().toAbsolutePath()), e);
    }
  }

  protected void deleteProcessIdFile() {
    if (!Files.isRegularFile(processIdFile())) return;

    try {
      Files.delete(processIdFile());
    } catch (IOException e) {
      logger.log(Level.WARNING, format("Unable to delete process ID file at %s", processIdFile().toAbsolutePath()), e);
    }
  }

  protected void killProcess(String processId) throws IOException {
    requireNonNull(processId);

    List<String> arguments = new ArrayList<>(4);

    if (currentOperatingSystem() == OperatingSystem.WINDOWS) {
      arguments.add("taskill");
      arguments.add("/f");
      arguments.add("/pid");
      arguments.add(processId);
    } else {
      arguments.add("kill");
      arguments.add(processId);
    }

    try {
      ProcessBuilder processBuilder = new ProcessBuilder(arguments.toArray(new String[] {})).inheritIO();
      Process process = processBuilder.start();
      if (process.waitFor(3, TimeUnit.SECONDS)) {
        int exitValue = process.exitValue();

        if (exitValue != 0)
          throw new IOException(format("Process exit value of '%s' was %d", arguments.stream().collect(joining(" ")),
            exitValue));
      } else {
        process.destroyForcibly();
      }
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException(processId, e);
    }
  }

  protected String currentProcessId() {
    String currentProcessId = getRuntimeMXBean().getName();

    if (isBlank(currentProcessId)) throw new IllegalStateException("Unable to extract current process ID.");

    int indexOfAtSymbol = currentProcessId.indexOf("@");

    if (indexOfAtSymbol > 0) currentProcessId = currentProcessId.substring(0, indexOfAtSymbol);

    return currentProcessId;
  }

  protected Path processIdFile() {
    return PROCESS_ID_FILE;
  }

  protected OperatingSystem currentOperatingSystem() {
    return CURRENT_OPERATING_SYSTEM;
  }

  @FunctionalInterface
  public static interface ServerLifecycleOperation {
    /**
     * Executes a server lifecycle operation.
     * 
     * @throws Throwable
     *           if an error occurs while executing the server lifecycle operation
     */
    void perform() throws Throwable;
  }

  public static enum StoppingStrategy {
    ON_KEYPRESS, ON_JVM_SHUTDOWN
  }

  protected static enum OperatingSystem {
    MAC, LINUX, WINDOWS, OTHER
  }
}