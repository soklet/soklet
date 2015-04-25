package com.soklet.web.response;

import static java.util.Objects.requireNonNull;

import java.io.InputStream;

/**
 * @author Transmogrify LLC.
 */
public class BinaryResponse implements Response {
  private final int status;
  private final String contentType;
  private final InputStream inputStream;

  public BinaryResponse(String contentType, InputStream inputStream) {
    this.status = 200;
    this.contentType = requireNonNull(contentType);
    this.inputStream = requireNonNull(inputStream);
  }

  public BinaryResponse(int status, String contentType, InputStream inputStream) {
    this.status = status;
    this.contentType = requireNonNull(contentType);
    this.inputStream = requireNonNull(inputStream);
  }

  @Override
  public int status() {
    return this.status;
  }

  public String contentType() {
    return this.contentType;
  }

  public InputStream inputStream() {
    return this.inputStream;
  }
}