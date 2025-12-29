/*
 * Copyright 2022-2026 Revetware LLC.
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

package com.soklet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.net.InetSocketAddress;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class RequestTests {
	@Test
	public void queryDecodingUsesUtf8RegardlessOfContentTypeCharset() {
		Map<String, Set<String>> headers = Map.of("Content-Type", Set.of("text/plain; charset=ISO-8859-1"));

		Request request = Request.withRawUrl(HttpMethod.GET, "/q?q=%C3%A9")
				.headers(headers)
				.build();

		Assertions.assertEquals(Set.of("\u00E9"), request.getQueryParameters().get("q"));
	}

	@Test
	public void remoteAddressIsPreservedOnBuild() {
		InetSocketAddress address = new InetSocketAddress("127.0.0.1", 1234);

		Request request = Request.withPath(HttpMethod.GET, "/")
				.remoteAddress(address)
				.build();

		Assertions.assertEquals(address, request.getRemoteAddress().orElse(null));
	}

	@Test
	public void rawPathPreservesDotSegmentsAndTrailingSlash() {
		Request request = Request.withRawUrl(HttpMethod.GET, "/a/../b/").build();

		Assertions.assertEquals("/a/../b/", request.getRawPath());
		Assertions.assertEquals("/b", request.getPath());
	}

	@Test
	public void copyUpdatesRawQueryWhenQueryParametersChange() {
		Request request = Request.withRawUrl(HttpMethod.GET, "/search?q=one%20two&lang=en").build();

		Assertions.assertEquals("q=one%20two&lang=en", request.getRawQuery().orElse(null));

		Request updated = request.copy()
				.queryParameters(parameters -> {
					parameters.clear();
					parameters.put("q", new LinkedHashSet<>(List.of("new value")));
					parameters.put("lang", new LinkedHashSet<>(List.of("en")));
					parameters.put("page", new LinkedHashSet<>(List.of("2")));
				})
				.finish();

		Assertions.assertEquals("q=new%20value&lang=en&page=2", updated.getRawQuery().orElse(null));
		Assertions.assertEquals("/search?q=new%20value&lang=en&page=2", updated.getRawPathAndQuery());
	}
}
