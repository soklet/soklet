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

package com.soklet.web;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.readAllBytes;
import static java.util.Collections.unmodifiableMap;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.soklet.json.JSONException;
import com.soklet.json.JSONObject;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class CachedUrlMapper {
  private final Map<String, String> cachedUrlsByUrl;

  public CachedUrlMapper(Map<String, String> cachedUrlsByUrl) {
    requireNonNull(cachedUrlsByUrl);
    this.cachedUrlsByUrl = unmodifiableMap(new HashMap<>(cachedUrlsByUrl));
  }

  public CachedUrlMapper(Path cachedUrlManifestFile, ErrorStrategy errorStrategy) {
    requireNonNull(cachedUrlManifestFile);
    requireNonNull(errorStrategy);
    this.cachedUrlsByUrl = unmodifiableMap(cachedUrlsByUrlFromUrlManifestFile(cachedUrlManifestFile, errorStrategy));
  }

  public String cachedUrlWithFallback(String url) {
    requireNonNull(url);
    return cachedUrl(url).orElse(url);
  }

  public Optional<String> cachedUrl(String url) {
    requireNonNull(url);
    return Optional.ofNullable(cachedUrlsByUrl.get(url));
  }

  protected Map<String, String> cachedUrlsByUrlFromUrlManifestFile(Path cachedUrlManifestFile,
      ErrorStrategy errorStrategy) {
    requireNonNull(cachedUrlManifestFile);
    requireNonNull(errorStrategy);

    if (!Files.exists(cachedUrlManifestFile) && errorStrategy == ErrorStrategy.FAIL_FAST)
      throw new IllegalArgumentException(format("No file exists at %s", cachedUrlManifestFile));
    if (Files.isDirectory(cachedUrlManifestFile))
      throw new IllegalArgumentException(format("%s is a directory but a regular file is required instead",
        cachedUrlManifestFile));

    String cachedUrlManifestFileContents;

    try {
      cachedUrlManifestFileContents = new String(readAllBytes(cachedUrlManifestFile), UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(format("Unable to load cached URL manifest at %s", cachedUrlManifestFile), e);
    }

    Map<String, String> cachedUrlsByUrl = null;

    try {
      JSONObject jsonObject = new JSONObject(cachedUrlManifestFileContents);
      cachedUrlsByUrl = jsonObject.keySet().stream().collect(toMap(key -> key, key -> jsonObject.getString(key)));
    } catch (JSONException e) {
      throw new RuntimeException(format(
        "Unable to parse cached URL manifest at %s. Please ensure the file content is a valid JSON object!",
        cachedUrlManifestFile), e);
    }

    return cachedUrlsByUrl;
  }

  public static enum ErrorStrategy {
    FAIL_FAST, LENIENT
  }
}