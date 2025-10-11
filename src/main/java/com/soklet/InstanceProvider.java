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

import javax.annotation.Nonnull;
import java.lang.reflect.Parameter;

import static java.util.Objects.requireNonNull;

/**
 * Contract for concrete instance generation given type information.
 * <p>
 * A standard threadsafe implementation can be acquired via the {@link #defaultInstance()} factory method.
 * <p>
 * See <a href="https://www.soklet.com/docs/instance-creation">https://www.soklet.com/docs/instance-creation</a> for detailed documentation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@FunctionalInterface
public interface InstanceProvider {
	/**
	 * Vends an instance of the given class.
	 * <p>
	 * The instance does not necessarily have to be new for every invocation (for example, implementors might return cached instances).
	 *
	 * @param instanceClass type token which represents the class to instantiate
	 * @param <T>           the type of class to instantiate
	 * @return an instance of {@code T}
	 */
	@Nonnull
	<T> T provide(@Nonnull Class<T> instanceClass);

	/**
	 * Vends an instance appropriate for the supplied {@link Parameter}.
	 * <p>
	 * This is useful for solving the "robot legs" problem, where you might examine a qualifying annotation on a parameter to disambiguate vended instances.
	 * <p>
	 * For example, given this <em>Resource Method</em>:
	 * <pre>{@code @GET("/robot-generator")
	 * public Robot robotGenerator(
	 *   @Left LegFactory leftLegFactory,   // Custom annotation
	 *   @Right LegFactory rightLegFactory, // Custom annotation
	 *   BodyFactory bodyFactory
	 * ) {
	 *   // Build a robot
	 *   LeftLeg leftLeg = leftLegFactory.buildLeg();
	 *   LeftLeg rightLeg = rightLegFactory.buildLeg();
	 *   Body body = bodyFactory.build();
	 *   return new Robot(leftLeg, rightLeg, body);
	 * }}</pre>
	 * <p>
	 * Your implementation might look like this:
	 * <pre>{@code @Override
	 * @SuppressWarnings("unchecked")
	 * public <T> T provide(@Nonnull Parameter parameter) {
	 *   Class<T> type = (Class<T>) parameter.getType();
	 *
	 *   if (type == LegFactory.class) {
	 *     if (parameter.isAnnotationPresent(Left.class))
	 *       return (T) LEFT_INSTANCE; // a reference to your left instance
	 *     if (parameter.isAnnotationPresent(Right.class))
	 *       return (T) RIGHT_INSTANCE; // a reference to your right instance
	 *
	 *     throw new IllegalArgumentException("LegFactory requires @Left or @Right");
	 *   }
	 *
	 *   // No qualifier logic needed - use the class-based path
	 *   return provide(type);
	 * }}</pre>
	 * <p>
	 * The default implementation delegates to {@link #provide(Class)} using the parameter's raw type.
	 *
	 * @param parameter the parameter instance to instantiate
	 * @param <T>       the type of class to instantiate (by default, the value of {@link Parameter#getType()})
	 * @return an instance of {@code T}
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	default <T> T provide(@Nonnull Parameter parameter) {
		requireNonNull(parameter);
		return provide((Class<T>) parameter.getType());
	}

	/**
	 * Acquires a threadsafe {@link InstanceProvider} with a reflection-based {@code instanceClass.getDeclaredConstructor().newInstance()} instantiation strategy.
	 * <p>
	 * The returned instance is guaranteed to be a JVM-wide singleton.
	 *
	 * @return an {@code InstanceProvider} with a reflection-based instantiation strategy
	 */
	@Nonnull
	static InstanceProvider defaultInstance() {
		return DefaultInstanceProvider.defaultInstance();
	}
}