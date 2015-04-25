package com.soklet.web.response;

import java.util.Optional;

/**
 * @author Transmogrify LLC.
 */
public class ApiResponse implements Response {
  private final int status;
  private final Optional<Object> model;

  public ApiResponse() {
    this.status = 200;
    this.model = Optional.empty();
  }

  public ApiResponse(int status) {
    this.status = status;
    this.model = Optional.empty();
  }

  public ApiResponse(Object model) {
    this.status = 200;
    this.model = Optional.ofNullable(model);
  }

  public ApiResponse(int status, Object model) {
    this.status = status;
    this.model = Optional.ofNullable(model);
  }

  @Override
  public int status() {
    return status;
  }

  public Optional<Object> model() {
    return model;
  }
}