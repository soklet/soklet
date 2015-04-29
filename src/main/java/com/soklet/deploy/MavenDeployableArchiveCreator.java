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

import static com.soklet.util.StringUtils.trimToNull;
import static java.lang.String.format;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public abstract class MavenDeployableArchiveCreator extends DeployableArchiveCreator {
  private final Logger logger = Logger.getLogger(getClass().getName());

  @Override
  public void preProcess() throws Exception {
    super.preProcess();

    Path mavenExecutableFile = mavenExecutableFile();

    logger.info("Cleaning via Maven...");
    executeProcess(mavenExecutableFile, mavenCleaningProcessArguments());

    logger.info("Compiling via Maven...");
    executeProcess(mavenExecutableFile, mavenCompilationProcessArguments());

    logger.info("Extracting Maven dependencies...");
    executeProcess(mavenExecutableFile, mavenDependenciesProcessArguments());
  }

  protected List<String> mavenCleaningProcessArguments() {
    return new ArrayList<String>() {
      {
        add("clean");
      }
    };
  }

  protected List<String> mavenCompilationProcessArguments() {
    return new ArrayList<String>() {
      {
        add("compile");
      }
    };
  }

  protected List<String> mavenDependenciesProcessArguments() {
    return new ArrayList<String>() {
      {
        add("-DincludeScope=runtime");
        add("dependency:copy-dependencies");
      }
    };
  }

  protected Path mavenExecutableFile() {
    String mavenHome = trimToNull(System.getenv("MAVEN_HOME"));

    if (mavenHome == null)
      mavenHome = trimToNull(System.getProperty("soklet.MAVEN_HOME"));

    if (mavenHome == null)
      throw new DeploymentProcessExecutionException(
        "In order to determine the absolute path to your mvn executable, the soklet.MAVEN_HOME system property "
            + "or the MAVEN_HOME environment variable must be defined");

    return Paths.get(format("%s/bin/mvn", mavenHome));
  }
}