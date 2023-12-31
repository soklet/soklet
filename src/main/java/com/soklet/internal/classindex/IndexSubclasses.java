/*
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
package com.soklet.internal.classindex;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ServiceLoader;

import com.soklet.internal.classindex.processor.ClassIndexProcessor;

/**
 * Index all subclasses of the annotated class or package.
 *
 * <p>
 * During compilation {@link ClassIndexProcessor} creates a resource files listing all classes
 * extending annotated class or located inside annotated package.
 * </p>
 * <p>
 * You can retrieve the list at runtime using either {@link ClassIndex#getSubclasses(Class)}
 * or {@link ClassIndex#getPackageClasses(String)}.
 * </p>
 * <p>
 * For subclasses of the annotated class the resource file name is compatible with
 * what {@link ServiceLoader} expects. So if all the subclasses have a zero-argument constructor
 * you can use {@link ServiceLoader}. For subclasses of given package index file is named
 * "jaxb.index", it is located inside the package folder and it's format is compatible with
 * what {@link javax.xml.bind.JAXBContext#newInstance(String) } expects.
 * </p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.PACKAGE})
public @interface IndexSubclasses {
	/**
	 * Specifies whether to store Javadoc for runtime retrieval.
	 *
	 * <p>
	 * You can retrieve the stored Javadoc summary using {@link ClassIndex#getClassSummary(Class)}.
	 * </p>
	 */
	boolean storeJavadoc() default false;
}
