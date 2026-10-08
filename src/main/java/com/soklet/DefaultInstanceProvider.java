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

import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultInstanceProvider implements InstanceProvider {
	@NonNull
	private static final DefaultInstanceProvider DEFAULT_INSTANCE;

	static {
		DEFAULT_INSTANCE = new DefaultInstanceProvider();
	}

	private DefaultInstanceProvider() {
		// Singleton
	}

	@NonNull
	public static DefaultInstanceProvider defaultInstance() {
		return DEFAULT_INSTANCE;
	}

	@NonNull
	@Override
	public <T> T provide(@NonNull Class<T> instanceClass) {
		requireNonNull(instanceClass);
		Constructor<T> constructor = constructorFor(instanceClass);

		try {
			return constructor.newInstance();
		} catch (InvocationTargetException | InstantiationException | IllegalAccessException e) {
			throw new RuntimeException(format("Unable to create an instance of %s", instanceClass), e);
		}
	}

	static void validateInstanceClass(@NonNull Class<?> instanceClass) {
		constructorFor(instanceClass);
	}

	@NonNull
	private static <T> Constructor<T> constructorFor(@NonNull Class<T> instanceClass) {
		requireNonNull(instanceClass);
		if (instanceClass.isInterface() || Modifier.isAbstract(instanceClass.getModifiers()))
			throw new IllegalArgumentException(format("The default InstanceProvider requires a concrete class for %s; configure a custom InstanceProvider", instanceClass.getName()));
		try {
			Constructor<T> constructor = instanceClass.getDeclaredConstructor();
			if (!constructor.canAccess(null))
				throw new IllegalArgumentException(format("The default InstanceProvider requires an accessible no-argument constructor for %s; configure a custom InstanceProvider", instanceClass.getName()));
			return constructor;
		} catch (NoSuchMethodException e) {
			throw new RuntimeException(format("Unable to create an instance of %s because no default constructor was found. " +
					"Consider supplying your own %s implementation to Soklet - see https://www.soklet.com/docs/instance-creation for details", instanceClass, InstanceProvider.class.getSimpleName()), e);
		}
	}
}
