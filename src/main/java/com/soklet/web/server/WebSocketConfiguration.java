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

import javax.servlet.http.HttpServlet;
import java.util.Objects;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="http://revetkn.com">Mark Allen</a>
 * @since 1.1.15
 */
public class WebSocketConfiguration {
	private final Class<? extends HttpServlet> webSocketClass;
	private final String url;

	public WebSocketConfiguration(Class<? extends HttpServlet> webSocketClass, String url) {
		this.webSocketClass = requireNonNull(webSocketClass);
		this.url = requireNonNull(url);
	}

	@Override
	public String toString() {
		return format("%s{webSocketClass=%s, url=%s}", getClass().getSimpleName(), webSocketClass()
				.getName(), url());
	}

	@Override
	public boolean equals(Object object) {
		if (this == object)
			return true;

		if (!(object instanceof FilterConfiguration))
			return false;

		WebSocketConfiguration websocketConfiguration = (WebSocketConfiguration) object;

		return Objects.equals(webSocketClass(), websocketConfiguration.webSocketClass())
				&& Objects.equals(url(), websocketConfiguration.url());
	}

	@Override
	public int hashCode() {
		return Objects.hash(webSocketClass(), url());
	}

	public Class<? extends HttpServlet> webSocketClass() {
		return webSocketClass;
	}

	public String url() {
		return url;
	}
}
