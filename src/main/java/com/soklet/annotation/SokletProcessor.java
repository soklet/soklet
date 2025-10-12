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

package com.soklet.annotation;

import com.soklet.HandshakeResult;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.Objects;
import java.util.Set;

import static java.lang.String.format;

/**
 * Soklet's standard Annotation Processor which is used to generate lookup tables of <em>Resource Method</em> definitions at compile time as well as prevent usage errors that are detectable by static analysis.
 * <p>
 * This Annotation Processor ensures <em>Resource Methods</em> annotated with {@link ServerSentEventSource} are declared as returning an instance of {@link HandshakeResult}.
 * <p>
 * Your build system should ensure this Annotation Processor is available at compile time. Follow the instructions below to make your application conformant:
 * <p>
 * Using {@code javac} directly:
 * <pre>javac -parameters -processor com.soklet.annotation.SokletProcessor ...[rest of javac command elided]</pre>
 * Using <a href="https://maven.apache.org" target="_blank">Maven</a>:
 * <pre>{@code <plugin>
 *     <groupId>org.apache.maven.plugins</groupId>
 *     <artifactId>maven-compiler-plugin</artifactId>
 *     <version>...</version>
 *     <configuration>
 *         <release>...</release>
 *         <compilerArgs>
 *             <!-- Rest of args elided -->
 *             <arg>-parameters</arg>
 *             <arg>-processor</arg>
 *             <arg>com.soklet.annotation.SokletProcessor</arg>
 *         </compilerArgs>
 *     </configuration>
 * </plugin>}</pre>
 * Using <a href="https://gradle.org" target="_blank">Gradle</a>:
 * <pre>{@code def sokletVersion = "2.0.0" // (use your actual version)
 *
 * dependencies {
 *   // Soklet used by your code at compile/run time
 *   implementation "com.soklet:soklet:${sokletVersion}"
 *
 *   // Same artifact also provides the annotation processor
 *   annotationProcessor "com.soklet:soklet:${sokletVersion}"
 *
 *   // If tests also need processing (optional)
 *   testAnnotationProcessor "com.soklet:soklet:${sokletVersion}"
 * }}</pre>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@SupportedSourceVersion(SourceVersion.RELEASE_17)
@SupportedAnnotationTypes({"com.soklet.annotation.ServerSentEventSource"})
public final class SokletProcessor extends AbstractProcessor {
	private Types types;
	private Elements elements;
	private Messager messager;
	private TypeMirror handshakeResultType; // com.soklet.HandshakeResult

	@Override
	public synchronized void init(@Nonnull ProcessingEnvironment processingEnvironment) {
		Objects.requireNonNull(processingEnvironment);

		super.init(processingEnvironment);

		this.types = processingEnvironment.getTypeUtils();
		this.elements = processingEnvironment.getElementUtils();
		this.messager = processingEnvironment.getMessager();

		TypeElement handshakeResultTypeElement = elements.getTypeElement("com.soklet.HandshakeResult");

		if (handshakeResultTypeElement != null) {
			this.handshakeResultType = handshakeResultTypeElement.asType();
		} else {
			// If the type isn't on the annotation‐processing path, we can still proceed
			// but will skip the check (and warn once).
			this.messager.printMessage(Diagnostic.Kind.WARNING,
					format("%s: %s not found on processor classpath; SSE return-type validation will be skipped.",
							getClass().getSimpleName(), HandshakeResult.class.getName()));
		}
	}

	@Override
	public boolean process(@Nonnull Set<? extends TypeElement> annotations,
												 @Nonnull RoundEnvironment roundEnvironment) {
		enforceSseReturnTypes(roundEnvironment);
		return false; // let others process too, if needed
	}

	private void enforceSseReturnTypes(@Nonnull RoundEnvironment roundEnvironment) {
		if (handshakeResultType == null)
			return; // nothing to validate

		for (Element element : roundEnvironment.getElementsAnnotatedWith(ServerSentEventSource.class)) {
			if (element.getKind() != ElementKind.METHOD) {
				messager.printMessage(Diagnostic.Kind.ERROR, format("@%s can only be applied to methods.",
						ServerSentEventSource.class.getSimpleName()), element);
				continue;
			}

			ExecutableElement method = (ExecutableElement) element;
			TypeMirror returnType = method.getReturnType();

			// Must be: HandshakeResult or any subclass thereof (i.e., types.isAssignable(sub, super))
			boolean ok = isReturnTypeHandshakeResultOrSubtype(returnType);

			if (!ok) {
				messager.printMessage(
						Diagnostic.Kind.ERROR,
						format("Soklet Resource Methods annotated with @%s must specify a return type of %s (found: %s). " +
										"See documentation at https://www.soklet.com/docs/server-sent-events",
								ServerSentEventSource.class.getSimpleName(), HandshakeResult.class.getSimpleName(), prettyType(returnType)),
						element
				);
			}
		}
	}

	private boolean isReturnTypeHandshakeResultOrSubtype(@Nonnull TypeMirror returnType) {
		// Disallow void/primitive outright
		if (returnType.getKind().isPrimitive() || returnType.getKind() == TypeKind.VOID)
			return false;

		// Allow exact type or subclass
		// (sub -> super) assignable must be true
		return types.isAssignable(returnType, handshakeResultType);
	}

	private String prettyType(@Nullable TypeMirror typeMirror) {
		// Produces user-friendly names (handles e.g. generics if they appear)
		return typeMirror == null ? "null" : types.erasure(typeMirror).toString();
	}
}
