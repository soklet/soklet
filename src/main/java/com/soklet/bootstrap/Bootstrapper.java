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

package com.soklet.bootstrap;

import static java.lang.String.format;
import static java.lang.System.in;
import static java.lang.System.out;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.0.0
 */
public class Bootstrapper {
  public static void main(String[] args) throws IOException {
    new Bootstrapper().run();
  }

  public void run() throws IOException {
    out.println("*** Welcome to Soklet! ***");

    try (BufferedReader inputReader = new BufferedReader(new InputStreamReader(in))) {
      String appName = valueFromInput(inputReader, "What's the name of your app? (e.g. ExampleApp)");
      String authorName = valueFromInput(inputReader, "Who's the author/owner of this app? (e.g. MyCompany LLC.)");
      String basePackageName =
          valueFromInput(inputReader, "What's your app's base package name? (e.g. com.mycompany)",
            new PackageNameInputValidator());

      out.println(format("Application name is %s", appName));
      out.println(format("Author name is %s", authorName));
      out.println(format("Back package is %s", basePackageName));

      throw new UnsupportedOperationException();
    }
  }

  protected String valueFromInput(BufferedReader reader, String prompt) throws IOException {
    return valueFromInput(reader, prompt, new InputValidator() {
      public boolean isValid(String input) {
        return input.trim().length() > 0;
      }
    });
  }

  protected String valueFromInput(BufferedReader reader, String prompt, InputValidator inputValidator)
      throws IOException {
    String value = "";

    do {
      out.print(format("%s\n-> ", prompt));
      value = reader.readLine().trim();
    } while (!inputValidator.isValid(value));

    return value;
  }

  protected static interface InputValidator {
    boolean isValid(String input);
  }

  protected static class PackageNameInputValidator implements InputValidator {
    public boolean isValid(String input) {
      input = input.trim();
      return input.length() > 0 && input.matches("^([a-zA-Z_]{1}[a-zA-Z0-9_]*(\\.[a-zA-Z_]{1}[a-zA-Z0-9_]*)*)?$");
    }
  }
}