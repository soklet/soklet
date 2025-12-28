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

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.InvocationTargetException;

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

	@NonNull
	public static DefaultInstanceProvider defaultInstance() {
		return DEFAULT_INSTANCE;
	}

	@NonNull
	@Override
	public <T> T provide(@NonNull Class<T> instanceClass) {
		requireNonNull(instanceClass);

		try {
			return instanceClass.getDeclaredConstructor().newInstance();
		} catch (NoSuchMethodException e) {
			throw new RuntimeException(format("Unable to create an instance of %s because no default constructor was found. " +
					"Consider supplying your own %s implementation to Soklet - see https://www.soklet.com/docs/instance-creation for details", instanceClass, InstanceProvider.class.getSimpleName()), e);
		} catch (InvocationTargetException | InstantiationException | IllegalAccessException e) {
			throw new RuntimeException(format("Unable to create an instance of %s", instanceClass), e);
		}
	}
}
