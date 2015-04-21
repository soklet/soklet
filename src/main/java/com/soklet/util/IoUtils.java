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

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public final class IoUtils {
  private static final int DEFAULT_BUFFER_CAPACITY = 4096;

  private IoUtils() {}

  public static void copyStream(InputStream inputStream, OutputStream outputStream) throws IOException {
    requireNonNull(inputStream);
    requireNonNull(outputStream);

    int n;
    byte[] buffer = new byte[DEFAULT_BUFFER_CAPACITY];

    while ((n = inputStream.read(buffer)) > 0)
      outputStream.write(buffer, 0, n);
  }

  public static void copyStreamCloseAfterwards(InputStream inputStream, OutputStream outputStream) throws IOException {
    requireNonNull(inputStream);
    requireNonNull(outputStream);

    try (InputStream closableInputStream = inputStream; OutputStream closableOutputStream = outputStream) {
      copyStream(closableInputStream, closableOutputStream);
    }
  }

  public static byte[] copyStreamToBytes(InputStream inputStream) throws IOException {
    requireNonNull(inputStream);

    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    copyStream(inputStream, byteArrayOutputStream);
    return byteArrayOutputStream.toByteArray();
  }

  public static byte[] copyStreamToBytesCloseAfterwards(InputStream inputStream) throws IOException {
    requireNonNull(inputStream);

    try (InputStream closableInputStream = inputStream) {
      return copyStreamToBytes(closableInputStream);
    }
  }

  public static String stringFromStream(InputStream inputStream) throws IOException {
    requireNonNull(inputStream);

    StringBuilder stringBuilder = new StringBuilder();
    InputStreamReader inputStreamReader = new InputStreamReader(inputStream, UTF_8);
    char[] buffer = new char[DEFAULT_BUFFER_CAPACITY];
    int length;

    while ((length = inputStreamReader.read(buffer)) != -1)
      stringBuilder.append(buffer, 0, length);

    return stringBuilder.toString();
  }

  public static String stringFromStreamCloseAfterwards(InputStream inputStream) throws IOException {
    requireNonNull(inputStream);

    try (InputStream closableInputStream = inputStream) {
      return stringFromStream(closableInputStream);
    }
  }
}