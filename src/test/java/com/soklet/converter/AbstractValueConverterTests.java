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

package com.soklet.converter;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generic hierarchy coverage for {@link AbstractValueConverter}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class AbstractValueConverterTests {
	@Test
	public void semanticTypeVariableFixedPointDoesNotRequireReferenceIdentity() throws Exception {
		TypeVariable<?> variable = equalVariableCopy(GenericListConverter.class.getTypeParameters()[0]);
		TypeVariable<?> equalCopy = equalVariableCopy(variable);
		Assertions.assertNotSame(variable, equalCopy);
		Assertions.assertEquals(variable, equalCopy);
		AtomicInteger lookups = new AtomicInteger();
		// A Map may return equal, non-identical values. Bound the negative control so
		// a resolver relying on reflection-object identity fails instead of overflowing.
		Map<TypeVariable<?>, Type> substitutions = new AbstractMap<>() {
			@Override
			public Type get(Object key) {
				if (!variable.equals(key))
					return null;
				if (lookups.incrementAndGet() > 2)
					throw new AssertionError("Equal type-variable substitution must terminate");
				return equalVariableCopy(variable);
			}

			@Override
			public Set<Entry<TypeVariable<?>, Type>> entrySet() {
				return Set.of(Map.entry(variable, equalCopy));
			}
		};
		Method resolve = AbstractValueConverter.class.getDeclaredMethod("resolve", Type.class, Map.class);
		resolve.setAccessible(true);
		Assertions.assertSame(variable, resolve.invoke(null, variable, substitutions));
		Assertions.assertEquals(1, lookups.get());
	}

	private static TypeVariable<?> equalVariableCopy(TypeVariable<?> variable) {
		return (TypeVariable<?>) Proxy.newProxyInstance(TypeVariable.class.getClassLoader(),
				new Class<?>[]{TypeVariable.class}, (proxy, method, arguments) -> {
					if (method.getName().equals("equals"))
						return arguments[0] instanceof TypeVariable<?> candidate
								&& variable.getName().equals(candidate.getName())
								&& variable.getGenericDeclaration().equals(candidate.getGenericDeclaration());
					return method.invoke(variable, arguments);
				});
	}

	@Test
	public void unrelatedInterfaceDoesNotHideParameterizedSuperclass() {
		ValueConverter<String, Token> converter = new MarkerConverter();

		Assertions.assertEquals(String.class, converter.getFromType());
		Assertions.assertEquals(Token.class, converter.getToType());
	}

	@Test
	public void concreteAndAnonymousSubclassesRetainInheritedTypes() {
		ValueConverter<String, Token> concrete = new TenantTokenConverter();
		ValueConverter<String, Token> anonymous = new BaseTokenConverter() {};

		Assertions.assertEquals(String.class, concrete.getFromType());
		Assertions.assertEquals(Token.class, concrete.getToType());
		Assertions.assertEquals(String.class, anonymous.getFromType());
		Assertions.assertEquals(Token.class, anonymous.getToType());
	}

	@Test
	public void intermediateGenericBaseSubstitutesNestedTypeVariables() {
		ValueConverter<String, List<Token>> converter = new TokenListConverter();
		Type expected = new TypeReference<List<Token>>() {}.getType();

		Assertions.assertEquals(String.class, converter.getFromType());
		Assertions.assertEquals(expected, converter.getToType());
		Assertions.assertEquals(converter.getToType(), expected);
		Assertions.assertEquals(expected.hashCode(), converter.getToType().hashCode());
	}

	@Test
	public void intermediateGenericBaseResolvesConcreteGenericArrayClass() {
		ValueConverter<String, Token[]> converter = new TokenArrayConverter();

		Assertions.assertEquals(Token[].class, converter.getToType());
	}

	@Test
	public void intermediateGenericBaseSubstitutesWildcardBounds() {
		ValueConverter<String, List<? extends Token>> converter =
				new TokenWildcardConverter();
		Type expected = new TypeReference<List<? extends Token>>() {}.getType();

		Assertions.assertEquals(expected, converter.getToType());
		Assertions.assertEquals(converter.getToType(), expected);
		Assertions.assertEquals(expected.hashCode(), converter.getToType().hashCode());
	}

	@Test
	public void directAbstractSubclassResolvesBothTypes() {
		ValueConverter<Long, Token> converter = new DirectConverter();

		Assertions.assertEquals(Long.class, converter.getFromType());
		Assertions.assertEquals(Token.class, converter.getToType());
	}

	@Test
	public void unresolvedTypeNamesTheConverterClassWithoutRenderingIt() {
		IllegalStateException exception = Assertions.assertThrows(
				IllegalStateException.class,
				() -> new UnresolvedConverter<Token>());

		Assertions.assertTrue(exception.getMessage()
				.contains(UnresolvedConverter.class.getName()));
		Assertions.assertFalse(exception.getMessage().contains("fromType=null"));
	}

	@Test
	public void unresolvedWildcardBoundFailsConstruction() {
		Assertions.assertThrows(IllegalStateException.class,
				() -> new UnresolvedWildcardConverter<Token>());
	}

	@Test
	public void conversionFailureMessageDoesNotRenderTheInputValue() {
		ValueConversionException exception = Assertions.assertThrows(
				ValueConversionException.class,
				() -> new FailingConverter().convert("secret-input"));

		Assertions.assertFalse(exception.getMessage().contains("secret-input"));
		Assertions.assertEquals("secret-input",
				exception.getFromValue().orElseThrow());
	}

	private interface Marker {
	}

	private record Token(@NonNull String value) {
	}

	private static final class MarkerConverter
			extends FromStringValueConverter<Token> implements Marker {
		@Override
		@NonNull
		public Optional<@NonNull Token> performConversion(@Nullable String from) {
			return Optional.ofNullable(from).map(Token::new);
		}
	}

	private static class BaseTokenConverter
			extends FromStringValueConverter<Token> {
		@Override
		@NonNull
		public Optional<@NonNull Token> performConversion(@Nullable String from) {
			return Optional.ofNullable(from).map(Token::new);
		}
	}

	private static final class TenantTokenConverter extends BaseTokenConverter {
	}

	private abstract static class GenericListConverter<T>
			extends FromStringValueConverter<List<T>> {
		@Override
		@NonNull
		public Optional<@NonNull List<T>> performConversion(@Nullable String from) {
			return Optional.empty();
		}
	}

	private static final class TokenListConverter
			extends GenericListConverter<Token> {
	}

	private abstract static class GenericArrayConverter<T>
			extends FromStringValueConverter<T[]> {
		@Override
		@NonNull
		public Optional<T @NonNull []> performConversion(@Nullable String from) {
			return Optional.empty();
		}
	}

	private static final class TokenArrayConverter
			extends GenericArrayConverter<Token> {
	}

	private abstract static class GenericWildcardConverter<T>
			extends FromStringValueConverter<List<? extends T>> {
		@Override
		@NonNull
		public Optional<@NonNull List<? extends T>> performConversion(
				@Nullable String from) {
			return Optional.empty();
		}
	}

	private static final class TokenWildcardConverter
			extends GenericWildcardConverter<Token> {
	}

	private static final class DirectConverter
			extends AbstractValueConverter<Long, Token> implements Marker {
		@Override
		@NonNull
		public Optional<@NonNull Token> performConversion(@Nullable Long from) {
			return Optional.ofNullable(from)
					.map(value -> new Token(value.toString()));
		}
	}

	private static final class UnresolvedConverter<T>
			extends FromStringValueConverter<T> {
		@Override
		@NonNull
		public Optional<@NonNull T> performConversion(@Nullable String from) {
			return Optional.empty();
		}
	}

	private static final class UnresolvedWildcardConverter<T>
			extends FromStringValueConverter<List<? extends T>> {
		@Override
		@NonNull
		public Optional<@NonNull List<? extends T>> performConversion(
				@Nullable String from) {
			return Optional.empty();
		}
	}

	private static final class FailingConverter
			extends FromStringValueConverter<Token> {
		@Override
		@NonNull
		public Optional<@NonNull Token> performConversion(@Nullable String from) {
			throw new IllegalStateException("conversion failed");
		}
	}
}
