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

import com.google.errorprone.annotations.CheckReturnValue;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;

import static java.util.Objects.requireNonNull;

/**
 * Stable, opaque reference identity for one complete transport lifecycle graph.
 * Independent engines retain one token; decorators return their delegate's exact
 * token unchanged. Soklet permanently claims the token for the lifecycle that
 * first accepts its configuration, so a token must not be reused for a distinct
 * transport graph or lifecycle generation.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class TransportIdentity {
	@NonNull
	private final InternalTransportIdentity internalIdentity;

	TransportIdentity(@NonNull InternalTransportIdentity internalIdentity) {
		this.internalIdentity = requireNonNull(internalIdentity);
	}

	/**
	 * Creates a fresh reference-identity token distinct from every token previously
	 * returned. Identity tokens intentionally do not have structural equality or
	 * hash-code semantics.
	 *
	 * @return a fresh transport identity
	 */
	@NonNull
	@CheckReturnValue
	public static TransportIdentity create() {
		return InternalTransportIdentity.create().publicIdentity();
	}

	@NonNull
	InternalTransportIdentity internalIdentity() {
		return this.internalIdentity;
	}
}
