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

package com.soklet.web.response;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.Optional;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class PageResponse implements Response {
  private final int status;
  private final String name;
  private final Optional<Map<String, Object>> model;

  public PageResponse(String name) {
    this.status = 200;
    this.name = requireNonNull(name);
    this.model = Optional.empty();
  }

  public PageResponse(int status, String name) {
    this.status = status;
    this.name = requireNonNull(name);
    this.model = Optional.empty();
  }

  public PageResponse(String name, Map<String, Object> model) {
    this.status = 200;
    this.name = requireNonNull(name);
    this.model = Optional.ofNullable(model);
  }

  public PageResponse(int status, String name, Map<String, Object> model) {
    this.status = status;
    this.name = requireNonNull(name);
    this.model = Optional.ofNullable(model);
  }

  @Override
  public int status() {
    return status;
  }

  public String name() {
    return name;
  }

  public Optional<Map<String, Object>> model() {
    return model;
  }
}