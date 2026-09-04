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

/**
 * Additional runner-scoped events that can request shutdown from a blocking
 * {@link SokletApplication} run.
 * <p>
 * The application runner installs and later removes each selected trigger for
 * the duration of its one run. These triggers are not part of
 * {@link SokletConfig}, and direct {@link Soklet} users remain responsible for
 * their own process-signal and input integration. The runner's JVM shutdown
 * hook is always installed independently of this enum; the constants here add
 * to that built-in trigger.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 * @see SokletApplication#run(SokletConfig, ShutdownTrigger...)
 */
public enum ShutdownTrigger {
	/**
	 * Requests shutdown when the runner observes an Enter key from standard
	 * input. The runner owns the input registration; selecting this trigger does
	 * not transfer ownership of or close standard input.
	 */
	ENTER_KEY
}
