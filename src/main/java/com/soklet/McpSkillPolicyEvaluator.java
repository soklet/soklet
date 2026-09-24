/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet;

import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static com.soklet.internal.ObjectIdentity.sameInstance;
import static java.util.Objects.requireNonNull;

/**
 * Private request-policy core for subsequent Skills runtime adapters. It does
 * not dispatch work, publish a page or register a route. The caller must invoke
 * it on the bounded admitted executor with one overall deadline, the invocation's
 * existing features/localization context, and its already-bounded language view.
 * Every call is a fresh decision; no authorization survives between requests.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpSkillPolicyEvaluator {
	private final McpEndpoint endpoint;
	private final McpSkillAccessPolicy accessPolicy;
	private final @Nullable McpSkillVariantSelector selector;

	McpSkillPolicyEvaluator(McpEndpoint endpoint, McpSkillAccessPolicy accessPolicy,
			@Nullable McpSkillVariantSelector selector) {
		this.endpoint = requireNonNull(endpoint);
		this.accessPolicy = requireNonNull(accessPolicy);
		this.selector = selector;
		if (selector == null && endpoint.getSkillGroups().stream()
				.anyMatch(group -> group.getSkillRegistrations().size() > 1))
			throw new IllegalStateException("An MCP Skills variant selector must be explicitly configured for multivariant groups.");
	}

	/** Ordered eligible listing registrations, not yet a bounded/serialized page. */
	List<McpSkillRegistration> discover(McpRequestContext requestContext,
			List<Locale.LanguageRange> languageRanges, McpInvocationFeatures features,
			BooleanSupplier pastDeadline) throws InterruptedException {
		requireRequest(requestContext, features, pastDeadline);
		List<Locale.LanguageRange> ranges = List.copyOf(languageRanges);
		List<McpSkillRegistration> visible = new ArrayList<>();
		for (McpSkillRegistration registration : this.endpoint.getSkillRegistrations())
			if (discoverable(registration, requestContext, features, pastDeadline)) visible.add(registration);
		for (McpSkillGroup group : this.endpoint.getSkillGroups()) {
			List<McpSkillRegistration> candidates = new ArrayList<>();
			for (McpSkillRegistration registration : group.getSkillRegistrations())
				if (discoverable(registration, requestContext, features, pastDeadline)) candidates.add(registration);
			if (candidates.isEmpty()) continue;
			if (this.selector == null) {
				McpSkillRegistration candidate = candidates.get(0);
				if (candidate.getLocale().map(locale -> !excluded(locale, ranges)).orElse(ranges.isEmpty()))
					visible.add(candidate);
			} else {
				McpSkillVariantSelectionContext context = McpSkillVariantSelectionContext.from(
						group.getKey(), candidates, ranges);
				requireActive(features, pastDeadline);
				Optional<McpSkillRegistration> selected;
				try {
					selected = requireNonNull(this.selector.select(requestContext, context, features));
					// Public registrations are structural values. Authorization membership
					// is deliberately stronger: only an exact supplied instance is valid.
					if (selected.isPresent() && candidates.stream().noneMatch(candidate -> sameInstance(candidate, selected.get())))
						throw failure();
				} catch (Throwable throwable) {
					if (throwable instanceof InterruptedException) {
						Thread.currentThread().interrupt();
						throw canceled();
					}
					throw failure();
				}
				requireActive(features, pastDeadline);
				selected.ifPresent(visible::add);
			}
		}
		requireActive(features, pastDeadline);
		return List.copyOf(visible);
	}

	/** Exact skill lookup ignores discovery and locale selection, and checks anew. */
	Optional<McpSkillRegistration> findAccessibleSkill(URI uri, McpRequestContext requestContext,
			McpInvocationFeatures features, BooleanSupplier pastDeadline) throws InterruptedException {
		requireRequest(requestContext, features, pastDeadline);
		Optional<McpSkillRegistration> registration = this.endpoint.skillIndex().findRegistration(requireNonNull(uri));
		if (registration.isEmpty()) return Optional.empty();
		Decision decision = access(registration.get(), requestContext, features, pastDeadline);
		if (decision == Decision.FAILED) throw failure();
		return decision == Decision.ALLOWED ? registration : Optional.empty();
	}

	/**
	 * Every owner is evaluated once, in aggregate order, even after a grant or
	 * policy failure. Cancellation/deadline expiration stops all further work.
	 * A denied owner supplies no grant; any failed owner prevents publication.
	 */
	Optional<McpSkillEndpointIndex.File> findAccessibleFile(URI uri, McpRequestContext requestContext,
			McpInvocationFeatures features, BooleanSupplier pastDeadline) throws InterruptedException {
		requireRequest(requestContext, features, pastDeadline);
		Optional<McpSkillEndpointIndex.File> file = this.endpoint.skillIndex().findFile(requireNonNull(uri));
		if (file.isEmpty()) return Optional.empty();
		boolean allowed = false;
		boolean failed = false;
		for (McpSkillRegistration owner : file.get().owners()) {
			Decision decision = access(owner, requestContext, features, pastDeadline);
			allowed |= decision == Decision.ALLOWED;
			failed |= decision == Decision.FAILED;
		}
		requireActive(features, pastDeadline);
		if (failed) throw failure();
		return allowed ? file : Optional.empty();
	}

	/**
	 * Validates one application-produced page without publishing a partial result.
	 * First pages must retain the selected order. Continuations recheck current
	 * access and discoverability but deliberately do not rerun variant selection.
	 */
	List<McpSkillRegistration> validatePage(List<McpSkillRegistration> pageRegistrations,
			Optional<List<McpSkillRegistration>> initialSkillRegistrations,
			McpRequestContext requestContext, McpInvocationFeatures features,
			BooleanSupplier pastDeadline) throws InterruptedException {
		requireRequest(requestContext, features, pastDeadline);
		if (pageRegistrations == null || initialSkillRegistrations == null) throw failure();
		List<McpSkillRegistration> page;
		try {
			page = List.copyOf(pageRegistrations);
		} catch (RuntimeException exception) {
			throw failure();
		}
		Set<URI> uris = new HashSet<>();
		Set<String> names = new HashSet<>();
		for (McpSkillRegistration registration : page) {
			McpSkillRegistration canonical = this.endpoint.skillIndex()
					.findRegistration(registration.getUri()).orElse(null);
			if (!sameInstance(canonical, registration) || !uris.add(registration.getUri())
					|| !names.add(registration.getSkillBundle().getName())) throw failure();
		}

		if (initialSkillRegistrations.isPresent()) {
			List<McpSkillRegistration> initial = initialSkillRegistrations.orElseThrow();
			int initialIndex = 0;
			for (McpSkillRegistration registration : page) {
				while (initialIndex < initial.size() && !sameInstance(initial.get(initialIndex), registration))
					++initialIndex;
				if (initialIndex == initial.size()) throw failure();
				++initialIndex;
			}
		} else {
			for (McpSkillRegistration registration : page)
				if (!discoverable(registration, requestContext, features, pastDeadline)) throw failure();
		}
		requireActive(features, pastDeadline);
		return page;
	}

	private boolean discoverable(McpSkillRegistration registration, McpRequestContext requestContext,
			McpInvocationFeatures features, BooleanSupplier pastDeadline) throws InterruptedException {
		Decision access = access(registration, requestContext, features, pastDeadline);
		if (access == Decision.FAILED) throw failure();
		if (access == Decision.DENIED) return false;
		Decision discovery = evaluate(() -> this.accessPolicy.isSkillDiscoverable(requestContext, registration, features),
				features, pastDeadline);
		if (discovery == Decision.FAILED) throw failure();
		return discovery == Decision.ALLOWED;
	}

	private Decision access(McpSkillRegistration registration, McpRequestContext requestContext,
			McpInvocationFeatures features, BooleanSupplier pastDeadline) throws InterruptedException {
		return evaluate(() -> this.accessPolicy.isSkillAccessible(requestContext, registration, features),
				features, pastDeadline);
	}

	private static Decision evaluate(Evaluation evaluation, McpInvocationFeatures features,
			BooleanSupplier pastDeadline) throws InterruptedException {
		requireActive(features, pastDeadline);
		Decision decision;
		try {
			decision = evaluation.evaluate() ? Decision.ALLOWED : Decision.DENIED;
		} catch (Throwable throwable) {
			if (throwable instanceof InterruptedException) {
				Thread.currentThread().interrupt();
				throw canceled();
			}
			// Never retain an untrusted callback throwable, its cause or message.
			decision = Decision.FAILED;
		}
		requireActive(features, pastDeadline);
		return decision;
	}

	private void requireRequest(McpRequestContext requestContext, McpInvocationFeatures features,
			BooleanSupplier pastDeadline) throws InterruptedException {
		requireNonNull(requestContext);
		requireNonNull(features);
		requireNonNull(pastDeadline);
		if (requestContext.getEndpoint() != this.endpoint) throw failure();
		requireActive(features, pastDeadline);
	}

	private static void requireActive(McpInvocationFeatures features, BooleanSupplier pastDeadline)
			throws InterruptedException {
		if (Thread.currentThread().isInterrupted() || features.getCancelationToken().isCanceled()
				|| pastDeadline.getAsBoolean()) throw canceled();
	}

	/** RFC 4647 basic matching; most-specific match wins, then earliest range. */
	private static boolean excluded(Locale locale, List<Locale.LanguageRange> ranges) {
		String tag = locale.toLanguageTag().toLowerCase(Locale.ROOT);
		int specificity = -1;
		boolean excluded = false;
		for (Locale.LanguageRange range : ranges) {
			String value = range.getRange();
			int length = value.equals("*") ? 0 : value.length();
			if (length <= specificity) continue;
			if (length == 0 || tag.equals(value) || tag.startsWith(value + "-")) {
				specificity = length;
				excluded = range.getWeight() == 0;
			}
		}
		return excluded;
	}

	private static IllegalStateException failure() {
		return new IllegalStateException("MCP Skills policy evaluation failed.");
	}

	private static InterruptedException canceled() {
		return new InterruptedException("MCP Skills policy evaluation was canceled.");
	}

	private enum Decision { ALLOWED, DENIED, FAILED }
	@FunctionalInterface private interface Evaluation { boolean evaluate() throws Exception; }
	@Override public String toString() { return "McpSkillPolicyEvaluator[redacted]"; }
}
