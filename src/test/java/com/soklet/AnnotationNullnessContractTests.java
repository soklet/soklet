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

import com.soklet.annotation.DELETE;
import com.soklet.annotation.DELETEs;
import com.soklet.annotation.FormParameter;
import com.soklet.annotation.GET;
import com.soklet.annotation.GETs;
import com.soklet.annotation.HEAD;
import com.soklet.annotation.HEADs;
import com.soklet.annotation.McpHeader;
import com.soklet.annotation.McpMayRequestInput;
import com.soklet.annotation.McpPrompt;
import com.soklet.annotation.McpPromptArgument;
import com.soklet.annotation.McpResource;
import com.soklet.annotation.McpResourceList;
import com.soklet.annotation.McpResourceUriParameter;
import com.soklet.annotation.McpServerEndpoint;
import com.soklet.annotation.McpTool;
import com.soklet.annotation.McpToolArgument;
import com.soklet.annotation.McpToolProperty;
import com.soklet.annotation.Multipart;
import com.soklet.annotation.OPTIONS;
import com.soklet.annotation.OPTIONSes;
import com.soklet.annotation.PATCH;
import com.soklet.annotation.PATCHes;
import com.soklet.annotation.POST;
import com.soklet.annotation.POSTs;
import com.soklet.annotation.PUT;
import com.soklet.annotation.PUTs;
import com.soklet.annotation.PathParameter;
import com.soklet.annotation.QueryParameter;
import com.soklet.annotation.RequestBody;
import com.soklet.annotation.RequestCookie;
import com.soklet.annotation.RequestHeader;
import com.soklet.annotation.SseEventSource;
import com.soklet.annotation.SseEventSources;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Verifies the explicit JSpecify contract of Soklet's annotation elements.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class AnnotationNullnessContractTests {
	@NonNull
	private static final List<@NonNull Class<? extends Annotation>>
			ANNOTATION_TYPES = List.of(
			DELETE.class, DELETEs.class, FormParameter.class,
			GET.class, GETs.class, HEAD.class, HEADs.class,
			McpHeader.class, McpMayRequestInput.class, McpPrompt.class,
			McpPromptArgument.class, McpResource.class,
			McpResourceList.class, McpResourceUriParameter.class,
			McpServerEndpoint.class, McpTool.class, McpToolArgument.class,
			McpToolProperty.class, Multipart.class, OPTIONS.class,
			OPTIONSes.class, PATCH.class, PATCHes.class, POST.class,
			POSTs.class, PUT.class, PUTs.class, PathParameter.class,
			QueryParameter.class, RequestBody.class, RequestCookie.class,
			RequestHeader.class, SseEventSource.class,
			SseEventSources.class);

	@Test
	void annotationInventoryIsComplete() throws Exception {
		Set<String> expected;
		try (var paths = Files.list(Path.of(
				"src/main/java/com/soklet/annotation"))) {
			expected = paths.filter(path -> path.getFileName().toString()
					.endsWith(".java"))
					.map(path -> path.getFileName().toString())
					.filter(name -> !name.equals("package-info.java"))
					.map(name -> name.substring(0, name.length() - 5))
					.collect(Collectors.toUnmodifiableSet());
		}
		Set<String> actual = ANNOTATION_TYPES.stream()
				.map(Class::getSimpleName).collect(Collectors.toSet());
		Assertions.assertEquals(expected, actual,
				"Public annotation inventory changed");
	}

	@Test
	void referenceValuedElementsHaveExplicitNonNullTypeUse() {
		for (Class<? extends Annotation> annotationType : ANNOTATION_TYPES)
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

	private static boolean hasExactNonNull(AnnotatedType type) {
		return type.isAnnotationPresent(NonNull.class)
				&& !type.isAnnotationPresent(Nullable.class);
	}
}
