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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Verifies the explicit JSpecify contract of Soklet's annotation elements.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class AnnotationNullnessContractTests {

	@Test
	void referenceValuedElementsHaveExplicitNonNullTypeUse() throws Exception {
		for (Class<? extends Annotation> annotationType : annotationTypes())
			for (Method element : annotationType.getDeclaredMethods()) {
				if (element.getReturnType().isPrimitive())
					continue;
				AnnotatedType returnType = element.getAnnotatedReturnType();
				Assertions.assertTrue(hasExactNonNull(returnType),
						() -> annotationType.getName() + "#" + element.getName()
								+ " must have a non-null return type");
				if (returnType instanceof AnnotatedArrayType arrayType)
					Assertions.assertTrue(hasExactNonNull(arrayType
							.getAnnotatedGenericComponentType()),
							() -> annotationType.getName() + "#"
									+ element.getName()
									+ " must have non-null array components");
			}
	}

	private static List<Class<? extends Annotation>> annotationTypes() throws Exception {
		Path sourceRoot = Path.of("src/main/java/com/soklet/annotation");
		List<Path> sources;
		try (var paths = Files.walk(sourceRoot)) {
			sources = paths.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(".java"))
					.filter(path -> !path.getFileName().toString().equals("package-info.java"))
					.sorted().toList();
		}
		List<Class<? extends Annotation>> result = new ArrayList<>();
		for (Path source : sources) {
			String relative = sourceRoot.relativize(source).toString()
					.replace(source.getFileSystem().getSeparator(), ".");
			String name = "com.soklet.annotation." + relative.substring(0,
					relative.length() - ".java".length());
			appendAnnotations(Class.forName(name, false,
					AnnotationNullnessContractTests.class.getClassLoader()), result);
		}
		result.sort(Comparator.comparing(Class::getName));
		Assertions.assertFalse(result.isEmpty(), "No public annotation types were discovered");
		return List.copyOf(result);
	}

	private static void appendAnnotations(Class<?> type,
			List<Class<? extends Annotation>> result) {
		if (!Modifier.isPublic(type.getModifiers()))
			return;
		if (type.isAnnotation())
			result.add(type.asSubclass(Annotation.class));
		for (Class<?> nested : type.getDeclaredClasses())
			appendAnnotations(nested, result);
	}

	private static boolean hasExactNonNull(AnnotatedType type) {
		return type.isAnnotationPresent(NonNull.class)
				&& !type.isAnnotationPresent(Nullable.class);
	}
}
