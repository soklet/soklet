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

package com.soklet.core.impl;

import com.soklet.core.InstanceProvider;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.InvocationTargetException;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class DefaultInstanceProvider implements InstanceProvider {
	@Nonnull
	private static final DefaultInstanceProvider SHARED_INSTANCE;

	static {
		SHARED_INSTANCE = new DefaultInstanceProvider();
	}

	@Nonnull
	public static DefaultInstanceProvider sharedInstance() {
		return SHARED_INSTANCE;
	}

	@Nonnull
	@Override
	public <T> T provide(@Nonnull Class<T> instanceClass) {
		requireNonNull(instanceClass);

		try {
			return instanceClass.getDeclaredConstructor().newInstance();
		} catch (NoSuchMethodException e) {
			throw new RuntimeException(format("Unable to create an instance of %s because no default constructor was found.", instanceClass), e);
		} catch (InvocationTargetException | InstantiationException | IllegalAccessException e) {
			throw new RuntimeException(format("Unable to create an instance of %s", instanceClass), e);
		}
	}
}
