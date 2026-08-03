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

package com.soklet.internal.mcp.schema;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

public class McpSchemaUriResolverTests {
	private static final URI RFC_3986_BASE = URI.create("http://a/b/c/d;p?q");

	@Test
	public void resolvesRfc3986NormalExamples() {
		assertResolutionCases(List.of(
				new ResolutionCase("g:h", "g:h"),
				new ResolutionCase("g", "http://a/b/c/g"),
				new ResolutionCase("./g", "http://a/b/c/g"),
				new ResolutionCase("g/", "http://a/b/c/g/"),
				new ResolutionCase("/g", "http://a/g"),
				new ResolutionCase("//g", "http://g"),
				new ResolutionCase("?y", "http://a/b/c/d;p?y"),
				new ResolutionCase("g?y", "http://a/b/c/g?y"),
				new ResolutionCase("#s", "http://a/b/c/d;p?q#s"),
				new ResolutionCase("g#s", "http://a/b/c/g#s"),
				new ResolutionCase("g?y#s", "http://a/b/c/g?y#s"),
				new ResolutionCase(";p", "http://a/b/c/;p"),
				new ResolutionCase("g;x", "http://a/b/c/g;x"),
				new ResolutionCase("g;x?y#s", "http://a/b/c/g;x?y#s"),
				new ResolutionCase("", "http://a/b/c/d;p?q"),
				new ResolutionCase(".", "http://a/b/c/"),
				new ResolutionCase("./", "http://a/b/c/"),
				new ResolutionCase("..", "http://a/b/"),
				new ResolutionCase("../", "http://a/b/"),
				new ResolutionCase("../g", "http://a/b/g"),
				new ResolutionCase("../..", "http://a/"),
				new ResolutionCase("../../", "http://a/"),
				new ResolutionCase("../../g", "http://a/g")));
	}

	@Test
	public void resolvesRfc3986AbnormalExamples() {
		assertResolutionCases(List.of(
				new ResolutionCase("../../../g", "http://a/g"),
				new ResolutionCase("../../../../g", "http://a/g"),
				new ResolutionCase("/./g", "http://a/g"),
				new ResolutionCase("/../g", "http://a/g"),
				new ResolutionCase("g.", "http://a/b/c/g."),
				new ResolutionCase(".g", "http://a/b/c/.g"),
				new ResolutionCase("g..", "http://a/b/c/g.."),
				new ResolutionCase("..g", "http://a/b/c/..g"),
				new ResolutionCase("./../g", "http://a/b/g"),
				new ResolutionCase("./g/.", "http://a/b/c/g/"),
				new ResolutionCase("g/./h", "http://a/b/c/g/h"),
				new ResolutionCase("g/../h", "http://a/b/c/h"),
				new ResolutionCase("g;x=1/./y", "http://a/b/c/g;x=1/y"),
				new ResolutionCase("g;x=1/../y", "http://a/b/c/y"),
				new ResolutionCase("g?y/./x", "http://a/b/c/g?y/./x"),
				new ResolutionCase("g?y/../x", "http://a/b/c/g?y/../x"),
				new ResolutionCase("g#s/./x", "http://a/b/c/g#s/./x"),
				new ResolutionCase("g#s/../x", "http://a/b/c/g#s/../x"),
				new ResolutionCase("http:g", "http:g")));
	}

	@Test
	public void resolvesReferencesAgainstJavaOpaqueUrns() {
		McpSchemaUriResolver resolver = new McpSchemaUriResolver();
		URI base = URI.create("urn:example:animal:ferret:nose?old#old-fragment");

		Assertions.assertAll(
				() -> Assertions.assertEquals(
						"urn:example:animal:ferret:nose?old",
						resolver.resolve(base, URI.create("")).toASCIIString()),
				() -> Assertions.assertEquals(
						"urn:example:animal:ferret:nose?new",
						resolver.resolve(base, URI.create("?new")).toASCIIString()),
				() -> Assertions.assertEquals(
						"urn:example:animal:ferret:nose?old#new",
						resolver.resolve(base, URI.create("#new")).toASCIIString()),
				() -> Assertions.assertEquals(
						"urn:sibling",
						resolver.resolve(base, URI.create("sibling")).toASCIIString()),
				() -> Assertions.assertEquals(
						"urn:/child",
						resolver.resolve(base, URI.create("path/../child")).toASCIIString()),
				() -> Assertions.assertEquals(
						"other:resource#anchor",
						resolver.resolve(base, URI.create("other:resource#anchor"))
								.toASCIIString()));
	}

	@Test
	public void canonicalizesSchemeHostPercentEncodingAndDotSegments() {
		McpSchemaUriResolver resolver = new McpSchemaUriResolver();
		URI input = URI.create(
				"HTTP://UsEr%3aName@EXAMPLE.COM:8080/a/%7e/%2f/./b/../c"
						+ "?x=%7e%2f#%41%2f");

		Assertions.assertEquals(
				"http://UsEr%3AName@example.com:8080/a/~/%2F/c?x=~%2F#A%2F",
				resolver.canonicalAbsolute(input).toASCIIString());
	}

	@Test
	public void canonicalizesIpv6HostWithoutChangingUserInfoOrPort() {
		McpSchemaUriResolver resolver = new McpSchemaUriResolver();

		Assertions.assertEquals(
				"https://User@[2001:db8::a]:8443/schema",
				resolver.canonicalAbsolute(
						URI.create("HTTPS://User@[2001:DB8::A]:8443/schema"))
						.toASCIIString());
	}

	@Test
	public void distinguishesAnEmptyFragmentFromNoFragment() {
		McpSchemaUriResolver resolver = new McpSchemaUriResolver();

		Assertions.assertAll(
				() -> Assertions.assertEquals(
						"https://example.com/schema#",
						resolver.canonicalAbsolute(
								URI.create("HTTPS://EXAMPLE.COM/schema#"))
								.toASCIIString()),
				() -> Assertions.assertEquals(
						"https://example.com/schema#",
						resolver.resolve(
								URI.create("https://example.com/schema#old"),
								URI.create("#")).toASCIIString()),
				() -> Assertions.assertEquals("",
						resolver.rawFragment(URI.create("https://example.com/schema#"))),
				() -> Assertions.assertNull(
						resolver.rawFragment(URI.create("https://example.com/schema"))));
	}

	@Test
	public void withoutFragmentPreservesEveryOtherRawComponent() {
		McpSchemaUriResolver resolver = new McpSchemaUriResolver();
		URI input = URI.create(
				"HTTPS://User%3aName@EXAMPLE.COM:8443/a/../b/%7e?x=%2f%7e#%41%2f");

		Assertions.assertEquals(
				"HTTPS://User%3aName@EXAMPLE.COM:8443/a/../b/%7e?x=%2f%7e",
				resolver.withoutFragment(input).toASCIIString());
	}

	@Test
	public void rawFragmentPreservesItsEncodedRepresentation() {
		McpSchemaUriResolver resolver = new McpSchemaUriResolver();

		Assertions.assertEquals("%7e%2fA",
				resolver.rawFragment(URI.create("urn:example:value#%7e%2fA")));
	}

	private static void assertResolutionCases(List<ResolutionCase> cases) {
		McpSchemaUriResolver resolver = new McpSchemaUriResolver();

		for (ResolutionCase resolutionCase : cases) {
			Assertions.assertEquals(
					resolutionCase.expected(),
					resolver.resolve(RFC_3986_BASE,
							URI.create(resolutionCase.reference())).toASCIIString(),
					() -> "RFC 3986 reference: " + resolutionCase.reference());
		}
	}

	private record ResolutionCase(String reference, String expected) {
	}
}
