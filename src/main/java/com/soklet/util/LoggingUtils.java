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

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.util.StatusPrinter;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.logging.LogManager.getLogManager;
import static org.slf4j.LoggerFactory.getILoggerFactory;

/**
 * Utility methods for common logging functions.
 *
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.1.4
 */
public final class LoggingUtils {
  private static final Object LOCK;

  static {
    LOCK = new Object();
  }

  public enum LogbackOption {
    DEBUGGING_ENABLED
  }

  private LoggingUtils() {}

  public static void initializeLogback(Path logbackConfigurationFile, LogbackOption... logbackOptions) {
    synchronized (LOCK) {
      requireNonNull(logbackConfigurationFile);

      List<LogbackOption> logbackOptionsAsList = logbackOptions == null ? Collections.emptyList() : Arrays.asList(logbackOptions);

      if (!Files.exists(logbackConfigurationFile))
        throw new IllegalArgumentException(format(
            "Unable to initialize Logback logging. Could not find a configuration file at %s",
            logbackConfigurationFile.toAbsolutePath()));

      if (!Files.isRegularFile(logbackConfigurationFile))
        throw new IllegalArgumentException(format(
            "Unable to initialize Logback logging. The configuration path %s does not appear to be a regular file",
            logbackConfigurationFile.toAbsolutePath()));

      uninstallLogback();

      LoggerContext loggerContext = (LoggerContext) getILoggerFactory();

      try {
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(loggerContext);
        loggerContext.reset();
        configurator.doConfigure(logbackConfigurationFile.toFile().getAbsolutePath());
      } catch (JoranException e) {
        throw new IllegalStateException("Unable to configure Logback logging", e);
      }

      if (logbackOptionsAsList.contains(LogbackOption.DEBUGGING_ENABLED))
        StatusPrinter.printInCaseOfErrorsOrWarnings(loggerContext);

      // Bridge all java.util.logging to SLF4J
      java.util.logging.Logger rootLogger = getLogManager().getLogger("");
      for (Handler handler : rootLogger.getHandlers())
        rootLogger.removeHandler(handler);

      SLF4JBridgeHandler.install();
    }
  }

  public static void uninstallLogback() {
    synchronized (LOCK) {
      if (SLF4JBridgeHandler.isInstalled()) SLF4JBridgeHandler.uninstall();
    }
  }
}