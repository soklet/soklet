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

import com.soklet.EffectiveOriginResolver.TrustPolicy;
import com.soklet.internal.spring.LinkedCaseInsensitiveMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static java.util.Objects.requireNonNull;

/**
 * Resolves a client's effective IP address from a request's socket peer and forwarded headers.
 * <p>
 * Forwarded headers can be spoofed if Soklet is reachable directly. Choose a {@link TrustPolicy} that matches your
 * deployment and, for {@link TrustPolicy#TRUST_PROXY_ALLOWLIST}, provide a trusted proxy predicate or allowlist.
 * If the remote address is missing or not trusted, forwarded headers are ignored and the socket peer is returned when available.
 * <p>
 * Extraction order is: trusted {@code Forwarded for=} values, trusted {@code X-Forwarded-For} values, then the socket peer.
 * Only IP literals are accepted from forwarded headers; hostnames, obfuscated identifiers, {@code unknown}, and malformed values are ignored.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
public final class EffectiveClientIpResolver {
	@NonNull
	private final Map<@NonNull String, @NonNull Set<@NonNull String>> headers;
	@NonNull
	private final TrustPolicy trustPolicy;
	@Nullable
	private InetSocketAddress remoteAddress;
	@Nullable
	private Predicate<InetSocketAddress> trustedProxyPredicate;

	/**
	 * Acquires a resolver seeded with raw request headers and a trust policy.
	 *
	 * @param headers     HTTP request headers
	 * @param trustPolicy how forwarded headers should be trusted
	 * @return the resolver
	 */
	@NonNull
	public static EffectiveClientIpResolver withHeaders(@NonNull Map<@NonNull String, @NonNull Set<@NonNull String>> headers,
																											@NonNull TrustPolicy trustPolicy) {
		requireNonNull(headers);
		requireNonNull(trustPolicy);
		return new EffectiveClientIpResolver(headers, trustPolicy);
	}

	/**
	 * Acquires a resolver seeded with a {@link Request} and a trust policy.
	 *
	 * @param request     the current request
	 * @param trustPolicy how forwarded headers should be trusted
	 * @return the resolver
	 */
	@NonNull
	public static EffectiveClientIpResolver withRequest(@NonNull Request request,
																											@NonNull TrustPolicy trustPolicy) {
		requireNonNull(request);
		EffectiveClientIpResolver resolver = withHeaders(request.getHeaders(), trustPolicy);
		resolver.remoteAddress = request.getRemoteAddress().orElse(null);
		return resolver;
	}

	private EffectiveClientIpResolver(@NonNull Map<@NonNull String, @NonNull Set<@NonNull String>> headers,
																		@NonNull TrustPolicy trustPolicy) {
		this.headers = new LinkedCaseInsensitiveMap<>(headers);
		this.trustPolicy = trustPolicy;
	}

	/**
	 * Resolves the effective client IP address.
	 *
	 * @return the effective client IP address, or {@link Optional#empty()} if no client IP could be determined
	 */
	@NonNull
	public Optional<InetAddress> resolve() {
		return Utilities.extractEffectiveClientIp(this);
	}

	/**
	 * The remote address of the client connection.
	 *
	 * @param remoteAddress the remote address, or {@code null} if unavailable
	 * @return this resolver
	 */
	@NonNull
	public EffectiveClientIpResolver remoteAddress(@Nullable InetSocketAddress remoteAddress) {
		this.remoteAddress = remoteAddress;
		return this;
	}

	/**
	 * Predicate used when {@link TrustPolicy#TRUST_PROXY_ALLOWLIST} is in effect.
	 *
	 * @param trustedProxyPredicate predicate that returns {@code true} for trusted proxies
	 * @return this resolver
	 */
	@NonNull
	public EffectiveClientIpResolver trustedProxyPredicate(@Nullable Predicate<InetSocketAddress> trustedProxyPredicate) {
		this.trustedProxyPredicate = trustedProxyPredicate;
		return this;
	}

	/**
	 * Allows specifying an IP allowlist for trusted proxies.
	 *
	 * @param trustedProxyAddresses IP addresses of trusted proxies
	 * @return this resolver
	 */
	@NonNull
	public EffectiveClientIpResolver trustedProxyAddresses(@NonNull Set<@NonNull InetAddress> trustedProxyAddresses) {
		requireNonNull(trustedProxyAddresses);
		Set<InetAddress> normalizedAddresses = Set.copyOf(trustedProxyAddresses);
		this.trustedProxyPredicate = remoteAddress -> {
			if (remoteAddress == null)
				return false;

			InetAddress address = remoteAddress.getAddress();
			return address != null && normalizedAddresses.contains(address);
		};
		return this;
	}

	@NonNull
	Map<@NonNull String, @NonNull Set<@NonNull String>> getHeaders() {
		return this.headers;
	}

	@NonNull
	TrustPolicy getTrustPolicy() {
		return this.trustPolicy;
	}

	@Nullable
	InetSocketAddress getRemoteAddress() {
		return this.remoteAddress;
	}

	@Nullable
	Predicate<InetSocketAddress> getTrustedProxyPredicate() {
		return this.trustedProxyPredicate;
	}
}
