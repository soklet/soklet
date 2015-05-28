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

package com.soklet.util;

import static com.soklet.util.StringUtils.isBlank;
import static java.lang.String.format;
import static java.util.Collections.unmodifiableMap;
import static java.util.Objects.requireNonNull;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import com.soklet.converter.ValueConversionException;
import com.soklet.converter.ValueConverter;
import com.soklet.converter.ValueConverterRegistry;

/**
 * Reads a properties file from disk and handles value conversion to arbitrary Java types.
 * 
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.1.0
 */
public class PropertiesFileReader {
  private final Map<String, String> properties;
  private final ValueConverterRegistry valueConverterRegistry;

  public PropertiesFileReader(Path propertiesFile) {
    this(requireNonNull(propertiesFile), new ValueConverterRegistry());
  }

  public PropertiesFileReader(Path propertiesFile, ValueConverterRegistry valueConverterRegistry) {
    requireNonNull(propertiesFile);
    requireNonNull(valueConverterRegistry);
    this.properties = unmodifiableMap(new HashMap<>(loadPropertiesForPath(propertiesFile)));
    this.valueConverterRegistry = valueConverterRegistry;
  }

  public <T> T valueFor(String key, Class<T> type) {
    requireNonNull(key);
    requireNonNull(type);

    String value = properties().get(key);

    if (isBlank(value))
      throw new IllegalStateException(format("No properties file value was found for key '%s'", key));

    Optional<T> optionalValue = optionalValueFor(key, type);

    if (optionalValue.isPresent())
      return optionalValue.get();

    throw new IllegalStateException(format("Properties file value '%s' for key '%s' was converted to a null value. "
        + "Use optionalValueFor() if this value is truly optional", value, key));
  }

  @SuppressWarnings("unchecked")
  public <T> Optional<T> optionalValueFor(String key, Class<T> type) {
    requireNonNull(key);
    requireNonNull(type);

    String value = properties().get(key);

    if (isBlank(value))
      return Optional.empty();

    Optional<ValueConverter<Object, Object>> valueConverter = this.valueConverterRegistry.get(String.class, type);

    if (!valueConverter.isPresent())
      throw new IllegalArgumentException(format(
        "Not sure how to convert properties file value '%s' for key '%s' to requested type %s", value, key, type));

    try {
      T convertedValue = (T) valueConverter.get().convert(value);
      return Optional.ofNullable(convertedValue);
    } catch (ValueConversionException e) {
      throw new IllegalArgumentException(e);
    }
  }

  protected Map<String, String> loadPropertiesForPath(Path propertiesFile) {
    requireNonNull(propertiesFile);

    Properties properties = new Properties();

    if (!Files.exists(propertiesFile))
      throw new IllegalArgumentException(
        format("Unable to find properties file at %s", propertiesFile.toAbsolutePath()));

    if (!Files.isRegularFile(propertiesFile))
      throw new IllegalArgumentException(format("Properties file at %s is not a regular file",
        propertiesFile.toAbsolutePath()));

    try (FileInputStream propertiesInputStream = new FileInputStream(propertiesFile.toFile())) {
      properties.load(propertiesInputStream);
    } catch (Exception e) {
      throw new IllegalArgumentException(format("Invalid format for properties file at %s",
        propertiesFile.toAbsolutePath()), e);
    }

    Map<String, String> propertiesMap = new HashMap<>();

    @SuppressWarnings("unchecked")
    Enumeration<String> e = (Enumeration<String>) properties.propertyNames();

    while (e.hasMoreElements()) {
      String key = (String) e.nextElement();
      propertiesMap.put(key, properties.getProperty(key));
    }

    return propertiesMap;
  }

  public Map<String, String> properties() {
    return this.properties;
  }
}