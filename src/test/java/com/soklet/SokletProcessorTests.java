/*
 * Copyright 2022-2025 Revetware LLC.
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

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import javax.tools.JavaFileObject;

import static com.google.testing.compile.CompilationSubject.assertThat;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class SokletProcessorTests {
	@Test
	void validEndpointCompiles() {
		JavaFileObject src = JavaFileObjects.forSourceString("example.Ok",
				"""
						import javax.annotation.Nonnull;
						import com.soklet.annotation.ServerSentEventSource;
						import com.soklet.annotation.PathParameter;
						import com.soklet.HandshakeResult;
						
						public class Ok {
							@ServerSentEventSource("/widgets/{id}")
							public HandshakeResult sse(@Nonnull @PathParameter(name="id") Integer id) {
								return HandshakeResult.acceptWithDefaults().build();
							}
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(src);

		assertThat(compilation).succeeded();
	}

	@Test
	void rejectsWrongReturnType() {
		JavaFileObject src = JavaFileObjects.forSourceString("example.BadReturn",
				"""
						import com.soklet.annotation.ServerSentEventSource;
						
						public class BadReturn {
							@ServerSentEventSource("/a/{id}")
							public void sse(int id) {}
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(src);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining("must specify a return type of HandshakeResult")
				.inFile(src)
				.onLine(5);

		JavaFileObject responseSrc = JavaFileObjects.forSourceString("example.ResponseBadReturn",
				"""
						import com.soklet.annotation.ServerSentEventSource;
						import com.soklet.Response;
						
						public class ResponseBadReturn {
							@ServerSentEventSource("/a/{id}")
							public Response sse(int id) {
							  return Response.withStatusCode(200).build();
							}
						}
						""");

		Compilation responseCompilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(responseSrc);

		assertThat(responseCompilation).failed();
		assertThat(responseCompilation).hadErrorContaining("must specify a return type of HandshakeResult")
				.inFile(responseSrc)
				.onLine(6);
	}

	@Test
	void rejectsMissingPathParameter() {
		JavaFileObject src = JavaFileObjects.forSourceString("example.MissingParam",
				"""
						import com.soklet.annotation.ServerSentEventSource;
						import com.soklet.HandshakeResult;
						
						public class MissingParam {
							@ServerSentEventSource("/widgets/{id}")
							public HandshakeResult sse() { return HandshakeResult.acceptWithDefaults().build(); }
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(src);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining("Resource Method path parameter {id} not bound to a @PathParameter argument");

		JavaFileObject getSrc = JavaFileObjects.forSourceString("example.MissingParamGet",
				"""
						import com.soklet.annotation.GET;
						
						public class MissingParamGet {
							@GET("/widgets/{id}")
							public void widget() { /* nothing to do */ }
						}
						""");

		Compilation getCompilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(getSrc);

		assertThat(getCompilation).failed();
		assertThat(getCompilation).hadErrorContaining("Resource Method path parameter {id} not bound to a @PathParameter argument");
	}

	@Test
	void rejectsUnmatchedAnnotatedParam() {
		JavaFileObject src = JavaFileObjects.forSourceString("example.Unmatched",
				"""
						import com.soklet.annotation.GET;
						import com.soklet.annotation.PathParameter;
						
						public class Unmatched {
							@GET("/widgets")
							public void widgets(@PathParameter(name="id") int id) {
								/* nothing to do */
							}
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(src);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining("No placeholder {id} present in resource path declaration");
	}

	@Test
	void rejectsDuplicatePlaceholders() {
		JavaFileObject src = JavaFileObjects.forSourceString("example.DupPlaceholders",
				"""
						import com.soklet.annotation.GET;
						
						public class DupPlaceholders {
							@GET("/x/{id}/y/{id}")
							public void widget(int id) {
								/* nothing to do */
							}
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(src);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining("Duplicate @PathParameter name: id");
	}

	@Test
	void rejectsStaticMethod() {
		JavaFileObject src = JavaFileObjects.forSourceString("example.StaticBad",
				"""
						import com.soklet.annotation.GET;
						
						public class StaticBad {
							@GET("/x/{id}")
							public static void widget(int id) {
								/* nothing to do */
							}
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(src);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining("Method must not be static");
	}

	@Test
	void rejectsBadPathTemplate() {
		JavaFileObject src = JavaFileObjects.forSourceString("example.BadPath",
				"""
						import com.soklet.annotation.GET;
						
						public class BadPath {
							@GET("/x/{id")
							public void widget(int id) { /* nothing to do */ }
						}
						""");

		Compilation compilation = Compiler.javac()
				.withProcessors(new SokletProcessor())
				.compile(src);

		assertThat(compilation).failed();
		assertThat(compilation).hadErrorContaining("Malformed resource path declaration (unbalanced braces)");
	}
}