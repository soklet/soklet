/*
 * Copyright 2022-2024 Revetware LLC.
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

package com.soklet.annotation;

import com.soklet.internal.classindex.processor.ClassIndexProcessor;

/**
 * Soklet's standard Annotation Processor which is used to generate lookup tables of Resource Method definitions at compile time.
 * <p>
 * Soklet applications should not need to reference this class directly - the compiler should automatically detect this Annotation Processor and apply it.
 * <p>
 * However, future versions of Java might require explicit configuration of Annotation Processors at compile time.  Should this become necessary, follow the instructions below to make your application conformant:
 * <p>
 * Using {@code javac} directly:
 * <pre>javac -processor com.soklet.annotation.SokletProcessor ...[rest of javac parameters elided]</pre>
 * Using <a href="https://maven.apache.org">Maven</a>:
 * <pre>{@code <plugin>
 *     <groupId>org.apache.maven.plugins</groupId>
 *     <artifactId>maven-compiler-plugin</artifactId>
 *     <version>...</version>
 *     <configuration>
 *         <release>...</release>
 *         <compilerArgs>
 *             <arg>-processor</arg>
 *             <arg>com.soklet.annotation.SokletProcessor</arg>
 *         </compilerArgs>
 *     </configuration>
 * </plugin>}</pre>
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class SokletProcessor extends ClassIndexProcessor {
	// No extra functionality
}
