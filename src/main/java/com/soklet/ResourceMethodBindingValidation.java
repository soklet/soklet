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

import com.soklet.annotation.FormParameter;
import com.soklet.annotation.Multipart;
import com.soklet.annotation.PathParameter;
import com.soklet.annotation.QueryParameter;
import com.soklet.annotation.RequestBody;
import com.soklet.annotation.RequestCookie;
import com.soklet.annotation.RequestHeader;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Optional;

/** Shared declaration rules; compiler and runtime adapt their existing parameter metadata. */
final class ResourceMethodBindingValidation {

	static final List<Class<? extends Annotation>> BINDING_ANNOTATIONS = List.of(
			PathParameter.class, QueryParameter.class, FormParameter.class, RequestHeader.class,
			RequestCookie.class, Multipart.class, RequestBody.class);

	private ResourceMethodBindingValidation() {}

	@Nullable
	static String failure(int bindingCount, boolean optionalPrimitive, boolean optionalPath, boolean nonStringVarargs) {
		if (bindingCount > 1)
			return "Only one Soklet binding annotation is allowed per parameter";
		if (optionalPrimitive)
			return "Optional binding parameters must not use primitive types; use a boxed type or Optional<T>";
		if (optionalPath)
			return "Path parameters must not use Optional";
		if (nonStringVarargs)
			return "Varargs path parameters must use String";
		return null;
	}

	static void validate(@NonNull ResourceMethod resourceMethod) {
		for (Parameter parameter : resourceMethod.getMethod().getParameters()) {
			int bindingCount = 0;
			boolean optionalPrimitive = false;
			for (Annotation annotation : parameter.getAnnotations()) {
				if (!BINDING_ANNOTATIONS.contains(annotation.annotationType()))
					continue;
				++bindingCount;
				boolean optional = (annotation instanceof QueryParameter query && query.optional())
						|| (annotation instanceof FormParameter form && form.optional())
						|| (annotation instanceof RequestHeader header && header.optional())
						|| (annotation instanceof RequestCookie cookie && cookie.optional())
						|| (annotation instanceof Multipart multipart && multipart.optional());
				optionalPrimitive |= optional && parameter.getType().isPrimitive();
			}
			PathParameter pathParameter = parameter.getAnnotation(PathParameter.class);
			String name = pathParameter == null ? "" : (pathParameter.name().isBlank() ? parameter.getName() : pathParameter.name());
			boolean varargs = pathParameter != null && resourceMethod.getResourcePathDeclaration().getVarargsComponent()
					.map(component -> component.getValue().equals(name)).orElse(false);
			String failure = failure(bindingCount, optionalPrimitive, pathParameter != null && parameter.getType().equals(Optional.class),
					varargs && !parameter.getType().equals(String.class));
			if (failure != null)
				throw new IllegalArgumentException(String.format("Resource Method %s has an invalid parameter binding: %s",
						resourceMethod.getMethod().toGenericString(), failure));
		}
	}
}
