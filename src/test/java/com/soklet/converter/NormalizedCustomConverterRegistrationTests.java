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

import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class NormalizedCustomConverterRegistrationTests {
    @Test
    void duplicateExactCustomKeysAreRejectedInEitherOrder() {
        assertRejected(String.class, Integer.class, String.class, Integer.class);
    }

    @Test
    void primitiveAndWrapperTargetsAreOneCustomRegistrationKey() {
        assertRejected(String.class, int.class, String.class, Integer.class);
    }

    @Test
    void primitiveAndWrapperSourcesAreOneCustomRegistrationKey() {
        assertRejected(int.class, String.class, Integer.class, String.class);
    }

    @Test
    void primitiveAndWrapperAliasesOnBothSidesAreRejected() {
        assertRejected(int.class, int.class, Integer.class, Integer.class);
    }

    @Test
    void oneCustomConverterStillOverridesBuiltinsAndSharesAliasLookups() throws Exception {
        ValueConverter<String, Integer> custom = converter(String.class, int.class, ignored -> Optional.of(17));
        for (var factory : factories()) {
            ValueConverterRegistry registry = factory.apply(Set.of(custom));
            for (Type target : List.of(int.class, Integer.class)) {
                assertSame(custom, registry.get(String.class, target).orElseThrow());
                assertEquals(Optional.of(17), registry.get(String.class, target).orElseThrow().convert("5"));
            }
        }
        ValueConverter<String, String> sameType = converter(String.class, String.class,
                value -> Optional.of("custom<" + value + ">"));
        for (var factory : factories()) {
            ValueConverterRegistry registry = factory.apply(Set.of(sameType));
            assertSame(sameType, registry.get(String.class, String.class).orElseThrow());
            assertEquals(Optional.of("custom< raw >"),
                    registry.get(String.class, String.class).orElseThrow().convert(" raw "));
        }
    }

    @Test
    void repeatedReferencesToTheSameConverterRemainOneSetRegistration() {
        ValueConverter<String, Integer> custom = converter(String.class, Integer.class, ignored -> Optional.of(7));
        Set<ValueConverter<?, ?>> converters = new LinkedHashSet<>();
        converters.add(custom);
        converters.add(custom);
        assertEquals(1, converters.size());
        for (var factory : factories())
            assertSame(custom, factory.apply(converters).get(String.class, Integer.class).orElseThrow());
    }

    @Test
    void duplicateDiagnosticsDoNotRenderApplicationTypesOrConverters() {
        Type privateType = new Type() {
            @Override public String getTypeName() { throw new AssertionError("Application Type renderer ran"); }
            @Override public String toString() { throw new AssertionError("Application Type renderer ran"); }
        };
        ValueConverter<?, ?> first = hostileConverter(privateType);
        ValueConverter<?, ?> second = hostileConverter(privateType);
        for (var factory : factories()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> factory.apply(Set.of(first, second)));
            assertTrue(failure.getMessage().contains("primitive/wrapper-normalized type pair"));
            assertNull(failure.getCause());
        }
    }

    private static void assertRejected(Type firstFrom, Type firstTo, Type secondFrom, Type secondTo) {
        ValueConverter<Object, Object> first = converter(firstFrom, firstTo, ignored -> Optional.of("first"));
        ValueConverter<Object, Object> second = converter(secondFrom, secondTo, ignored -> Optional.of("second"));
        String message = null;
        for (var factory : factories()) {
            for (List<ValueConverter<?, ?>> ordered : List.of(
                    List.<ValueConverter<?, ?>>of(first, second), List.<ValueConverter<?, ?>>of(second, first))) {
                Set<ValueConverter<?, ?>> converters = new LinkedHashSet<>(ordered);
                IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                        () -> factory.apply(converters));
                assertTrue(failure.getMessage().contains("primitive/wrapper-normalized type pair"), failure.getMessage());
                assertTrue(failure.getMessage().contains("Register only one custom converter"), failure.getMessage());
                if (message == null) message = failure.getMessage();
                else assertEquals(message, failure.getMessage(), "Factory and Set iteration order cannot select a winner.");
            }
        }
    }

    private static List<Function<Set<ValueConverter<?, ?>>, ValueConverterRegistry>> factories() {
        return List.of(ValueConverterRegistry::fromDefaultsSupplementedBy,
                ValueConverterRegistry::fromBlankSlateSupplementedBy);
    }

    private static <F, T> ValueConverter<F, T> converter(Type from, Type to, Function<F, Optional<T>> conversion) {
        return new ValueConverter<>() {
            @Override public Type getFromType() { return from; }
            @Override public Type getToType() { return to; }
            @Override public Optional<T> convert(F value) { return conversion.apply(value); }
        };
    }

    private static ValueConverter<Object, Object> hostileConverter(Type type) {
        return new ValueConverter<>() {
            @Override public Type getFromType() { return type; }
            @Override public Type getToType() { return type; }
            @Override public Optional<Object> convert(Object value) { return Optional.empty(); }
            @Override public String toString() { throw new AssertionError("Application converter renderer ran"); }
        };
    }
}
