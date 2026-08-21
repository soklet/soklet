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
import java.util.List;
import java.util.Optional;

/**
 * Immutable request-specific context for a dynamic {@code resources/list}
 * operation.
 *
 * <p>Soklet preserves a cursor as an opaque, bounded string. The application
 * decides whether it is authentic, unexpired, authorized for the current
 * principal, bound to a retained snapshot and catalog revision, and portable
 * to the instance handling this request.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpResourceListContext {
	/**
	 * Returns the opaque cursor supplied by the client.
	 *
	 * <p>The application owns the cursor's format, integrity, lifetime,
	 * authorization binding, and cross-instance portability. A present empty
	 * string is preserved as a present protocol value. Invalid, expired,
	 * tampered, cross-principal, missing-snapshot, and wrong-revision values
	 * should produce the same neutral application error; Soklet does not
	 * classify those failures.
	 *
	 * @return client cursor, or empty when the request omitted {@code cursor}
	 */
	@NonNull
	Optional<@NonNull String> getCursor();

	/**
	 * Returns descriptors derived from this endpoint's exact-URI resource
	 * registrations.
	 *
	 * <p>The immutable list is in deterministic registration order and excludes
	 * URI-template registrations. It is convenience registration data, not an
	 * authorization-filtered result. A custom handler remains the sole authority
	 * for the returned page.
	 *
	 * @return immutable exact-resource descriptors in registration order
	 */
	@NonNull
	List<@NonNull McpResourceDescriptor> getRegisteredResourceDescriptors();
}
