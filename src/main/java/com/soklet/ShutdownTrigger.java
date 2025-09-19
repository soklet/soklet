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

import java.time.Duration;

/**
 * Events that might trigger a {@link Soklet} instance to shut down.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 * @see Soklet#awaitShutdown(ShutdownTrigger...)
 * @see Soklet#awaitShutdown(Duration, ShutdownTrigger...)
 */
public enum ShutdownTrigger {
	/**
	 * When the user presses 'Enter' in the console.
	 */
	ENTER_KEY,
	/**
	 * When the JVM is shutting down: {@code SIGTERM}, CTRL-C, {@code System.exit}, and others.
	 */
	JVM_SHUTDOWN
}