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
import java.util.Locale;
import java.util.Optional;

/**
 * Immutable, node-local localization context for one admitted
 * localization-capable MCP operation.
 * <p>
 * This is not a distributed session. It has no ID, is never serialized or
 * recovered on another node, and requires no routing affinity. The context
 * captures one locale and one immutable translation snapshot. A later request
 * on another node creates a new context from portable request or continuation
 * facts.
 * <p>
 * The context is not closeable and must not own resources whose correctness
 * depends on an exact close callback. Its observable behavior must be safe for
 * concurrent calls. Locale and revision remain equal for its full lifetime;
 * equal localizable-text inputs produce equal results independently of lookup
 * order, including under concurrent calls.
 * <p>
 * This is a borrowed invocation-scoped feature. An application handler or
 * interceptor must not retain it, its feature carrier, or its captured
 * translation snapshot after invocation termination. Context-provider
 * implementations should capture only the minimum immutable lookup snapshot
 * and must not capture the request or application object graph. Every
 * localization lookup must use that already-loaded snapshot and perform
 * bounded, in-memory, nonblocking work without remote TMS/network I/O, lazy
 * unbounded loading, or unbounded-executor dispatch.
 * <p>
 * The exact provider-returned instance is exposed to application handlers and
 * interceptors. Applications may therefore use a public subtype with
 * additional application-owned lookup methods, while portable code depends
 * only on this interface.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpLocalizationContext {
	/** @return canonical selected catalog locale */
	@NonNull
	Locale getLocale();

	/**
	 * Returns optional non-secret identity for the captured catalog snapshot.
	 * Revisions are not MCP wire values or distributed-session identifiers.
	 *
	 * @return immutable snapshot revision, or empty
	 */
	@NonNull
	default Optional<@NonNull McpLocalizationRevision> getRevision() {
		return Optional.empty();
	}

	/**
	 * Localizes one framework-owned source-text field against this context's
	 * captured snapshot. The lookup must be bounded, in-memory, and nonblocking;
	 * it must not perform remote I/O or unbounded loading.
	 * <p>
	 * An implementation reports an unexpected lookup failure with
	 * {@link McpLocalizationResult#fromFailure()}; it must not throw to report an
	 * operational lookup failure. If an unchecked contract violation nevertheless
	 * escapes while Soklet invokes this callback, Soklet treats it as untrusted
	 * localization data and does not forward it through framework-owned
	 * lifecycle, simulation, logging, response-throwable, or cause surfaces.
	 * Direct application invocation is application-owned and has normal
	 * application failure semantics.
	 *
	 * @param text structured coordinate and canonical source text
	 * @return non-null localization result
	 */
	@NonNull
	McpLocalizationResult localize(@NonNull McpLocalizableText text);
}
