package com.soklet.web.response;

import static java.util.Objects.requireNonNull;

/**
 * @author Transmogrify LLC.
 */
public class RedirectResponse implements Response {
  private final String url;
  private final Type type;

  public RedirectResponse(String url) {
    this.url = requireNonNull(url);
    this.type = Type.TEMPORARY;
  }

  public RedirectResponse(String url, Type type) {
    this.url = requireNonNull(url);
    this.type = requireNonNull(type);
  }

  @Override
  public int status() {
    return type() == RedirectResponse.Type.PERMANENT ? 301 : 302;
  }

  public String url() {
    return this.url;
  }

  public Type type() {
    return this.type;
  }

  public static enum Type {
    TEMPORARY, PERMANENT
  }
}