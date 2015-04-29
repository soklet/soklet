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

import static com.soklet.util.IoUtils.copyStream;
import static com.soklet.util.IoUtils.copyStreamToBytes;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.readAllBytes;
import static java.util.Collections.unmodifiableMap;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
public class HashedUrlManifest {
  private final Map<String, String> hashedUrlsByUrl;

  public HashedUrlManifest(Map<String, String> hashedUrlsByUrl) {
    requireNonNull(hashedUrlsByUrl);
    this.hashedUrlsByUrl = unmodifiableMap(new HashMap<>(hashedUrlsByUrl));
  }

  public HashedUrlManifest(InputStream inputStream) {
    requireNonNull(inputStream);
    this.hashedUrlsByUrl = unmodifiableMap(hashedUrlsByUrlFromInputStream(inputStream));
  }

  public HashedUrlManifest(Path hashedUrlManifestFile) {
    requireNonNull(hashedUrlManifestFile);
    this.hashedUrlsByUrl = unmodifiableMap(hashedUrlsByUrlFromManifestFile(hashedUrlManifestFile));
  }

  public String hashedUrlWithFallback(String url) {
    requireNonNull(url);
    return hashedUrl(url).orElse(url);
  }

  public Optional<String> hashedUrl(String url) {
    requireNonNull(url);
    return Optional.ofNullable(hashedUrlsByUrl.get(url));
  }

  public void writeToOutputStream(OutputStream outputStream, PersistenceFormat persistenceFormat) throws IOException {
    requireNonNull(outputStream);
    requireNonNull(persistenceFormat);

    @SuppressWarnings({ "unchecked", "rawtypes" })
    JSONObject jsonObject = new JSONObject((Map) hashedUrlsByUrl());
    String json = jsonObject.toString(persistenceFormat == PersistenceFormat.COMPACT ? 0 : 2);

    copyStream(new ByteArrayInputStream(json.getBytes(UTF_8)), outputStream);
  }

  public Map<String, String> hashedUrlsByUrl() {
    return hashedUrlsByUrl;
  }

  protected Map<String, String> hashedUrlsByUrlFromManifestFile(Path hashedUrlManifestFile) {
    requireNonNull(hashedUrlManifestFile);

    if (!Files.exists(hashedUrlManifestFile))
      throw new IllegalArgumentException(format("No file exists at %s", hashedUrlManifestFile));
    if (Files.isDirectory(hashedUrlManifestFile))
      throw new IllegalArgumentException(format("%s is a directory but a regular file is required instead",
        hashedUrlManifestFile));

    byte[] hashedUrlManifestFileContents = null;

    try {
      hashedUrlManifestFileContents = readAllBytes(hashedUrlManifestFile);
    } catch (IOException e) {
      throw new UncheckedIOException(format("Unable to load hashed URL manifest at %s", hashedUrlManifestFile), e);
    }

    return hashedUrlsByUrlFromInputStream(new ByteArrayInputStream(hashedUrlManifestFileContents));
  }

  protected Map<String, String> hashedUrlsByUrlFromInputStream(InputStream inputStream) {
    requireNonNull(inputStream);

    String json = null;

    try {
      json = new String(copyStreamToBytes(inputStream), UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to read hashed URL manifest", e);
    }

    Map<String, String> hashedUrlsByUrl = null;

    try {
      JSONObject jsonObject = new JSONObject(json);
      hashedUrlsByUrl = jsonObject.keySet().stream().collect(toMap(key -> key, key -> jsonObject.getString(key)));
    } catch (JSONException e) {
      throw new RuntimeException("Unable to parse hashed URL manifest. Please ensure it is a valid JSON object!", e);
    }

    return hashedUrlsByUrl;
  }

  public static enum PersistenceFormat {
    COMPACT, PRETTY_PRINTED
  }
}