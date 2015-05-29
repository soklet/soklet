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

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class StaticFilesConfiguration {
  private final String urlPattern;
  private final Path rootDirectory;
  private final CacheStrategy cacheStrategy;

  public StaticFilesConfiguration(String urlPattern, Path rootDirectory) {
    this(urlPattern, rootDirectory, CacheStrategy.NEVER);
  }

  public StaticFilesConfiguration(String urlPattern, Path rootDirectory, CacheStrategy cacheStrategy) {
    this.urlPattern = requireNonNull(urlPattern);
    this.rootDirectory = requireNonNull(rootDirectory);
    this.cacheStrategy = requireNonNull(cacheStrategy);

    if (!Files.isDirectory(rootDirectory))
      throw new IllegalArgumentException(format("The specified static file root '%s' is not a directory",
        rootDirectory.toAbsolutePath()));
  }

  public String urlPattern() {
    return urlPattern;
  }

  public Path rootDirectory() {
    return rootDirectory;
  }

  public CacheStrategy cacheStrategy() {
    return cacheStrategy;
  }

  public static enum CacheStrategy {
    DEFAULT, NEVER, FOREVER
  }
}