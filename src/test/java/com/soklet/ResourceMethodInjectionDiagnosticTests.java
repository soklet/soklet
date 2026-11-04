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

import com.soklet.annotation.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Socket-free startup diagnostics; no transport lifecycle is attached. */
final class ResourceMethodInjectionDiagnosticTests {
	@Test
	void aliasesWarnOncePerMethodParameterAndSuccessfulSetupReplayIsSilent() throws Exception {
		ResourceMethod first = method("values", "/first");
		ResourceMethod alias = ResourceMethod.fromComponents(HttpMethod.GET,
				ResourcePathDeclaration.fromPath("/alias"), first.getMethod(), false);
		List<LogEvent> events = new ArrayList<>();
		SokletConfig config = builder(Set.of(first, alias), events).build();
		Assertions.assertTrue(events.isEmpty(), "Config construction must not emit setup diagnostics");
		SokletFrameworkSetup setup = new SokletFrameworkSetup(config);
		run(setup);
		run(setup);
		Assertions.assertEquals(8, events.size());
		for (int index = 0; index < 8; index++) {
			final int expectedIndex = index;
			Assertions.assertEquals(1L, events.stream().filter(event -> event.getMessage()
					.contains("index " + expectedIndex + " (")).count());
		}
		for (LogEvent event : events) {
			Assertions.assertEquals(LogEventType.RESOURCE_METHOD_UNANNOTATED_VALUE_PARAMETER, event.getLogEventType());
			Assertions.assertTrue(event.getMessage().contains(first.getMethod().toString()));
			Assertions.assertTrue(event.getMessage().contains("default InstanceProvider"));
			Assertions.assertTrue(event.getThrowable().isEmpty());
			Assertions.assertTrue(event.getRequest().isEmpty());
			Assertions.assertTrue(event.getResourceMethod().isEmpty());
			Assertions.assertTrue(event.getMarshaledResponse().isEmpty());
		}
		Assertions.assertEquals(0, Values.constructions.get());
	}

	@Test
	void intentionalBindingsAndImplicitMultipartAreExempt() throws Exception {
		List<LogEvent> events = new ArrayList<>();
		run(new SokletFrameworkSetup(builder(Set.of(method("bound", "/bound/{id}"),
				method("builtins", "/builtins"), method("implicitMultipart", "/multipart")), events).build()));
		Assertions.assertTrue(events.isEmpty());
		Assertions.assertEquals(0, Values.constructions.get());
	}

	@Test
	void servicesAndApplicationCollectionOrNumberSubclassesStayQuiet() throws Exception {
		List<LogEvent> events = new ArrayList<>();
		run(new SokletFrameworkSetup(builder(Set.of(method("services", "/services")), events).build()));
		Assertions.assertTrue(events.isEmpty(), "Do not treat all JDK types or application value-like services as missing annotations");
		Assertions.assertEquals(0, Values.constructions.get());
	}

	@Test
	void customProvidersBypassWarningsAndAreNeverInvokedDuringSetup() throws Exception {
		ResourceMethod method = method("values", "/values");
		AtomicInteger providerCalls = new AtomicInteger();
		InstanceProvider customInstances = new InstanceProvider() {
			@Override public <T> T provide(Class<T> type) {
				providerCalls.incrementAndGet();
				throw new AssertionError("Setup must not invoke application factories");
			}
		};
		List<LogEvent> instanceEvents = new ArrayList<>();
		run(new SokletFrameworkSetup(builder(Set.of(method), instanceEvents).instanceProvider(customInstances).build()));
		List<LogEvent> parameterEvents = new ArrayList<>();
		run(new SokletFrameworkSetup(builder(Set.of(method), parameterEvents)
				.resourceMethodParameterProvider((request, selected) -> {
					providerCalls.incrementAndGet();
					throw new AssertionError("Setup must not invoke application parameter providers");
				}).build()));
		Assertions.assertTrue(instanceEvents.isEmpty());
		Assertions.assertTrue(parameterEvents.isEmpty());
		Assertions.assertEquals(0, providerCalls.get());
	}

	@Test
	void sharedMethodsDoNotPoisonIndependentConfigsAndBorrowedProvidersFollowTheirCapturedConfig() throws Exception {
		ResourceMethod shared = method("defaults", "/defaults");
		InstanceProvider custom = new InstanceProvider() {
			@Override public <T> T provide(Class<T> type) { throw new AssertionError("Must not instantiate"); }
		};
		List<LogEvent> customEvents = new ArrayList<>();
		SokletConfig customConfig = builder(Set.of(shared), customEvents).instanceProvider(custom).build();
		run(new SokletFrameworkSetup(customConfig));
		List<LogEvent> defaultEvents = new ArrayList<>();
		run(new SokletFrameworkSetup(builder(Set.of(shared), defaultEvents).build()));
		Assertions.assertEquals(2, defaultEvents.size());
		List<LogEvent> independentEvents = new ArrayList<>();
		run(new SokletFrameworkSetup(builder(Set.of(shared), independentEvents).build()));
		Assertions.assertEquals(2, independentEvents.size());
		List<LogEvent> borrowedEvents = new ArrayList<>();
		run(new SokletFrameworkSetup(builder(Set.of(shared), borrowedEvents)
				.resourceMethodParameterProvider(customConfig.getResourceMethodParameterProvider()).build()));
		Assertions.assertTrue(customEvents.isEmpty());
		Assertions.assertTrue(borrowedEvents.isEmpty(), "The captured custom InstanceProvider controls actual fallback");
	}

	@Test
	void warningsDoNotChangeDefaultInjectionOrRepeatDuringParameterExtraction() throws Exception {
		ResourceMethod method = method("defaults", "/defaults");
		List<LogEvent> events = new ArrayList<>();
		SokletConfig config = builder(Set.of(method), events).build();
		run(new SokletFrameworkSetup(config));
		for (int iteration = 0; iteration < 2; iteration++) {
			List<?> values = config.getResourceMethodParameterProvider().parameterValuesForResourceMethod(
					Request.withPath(HttpMethod.GET, "/defaults").build(), method);
			Assertions.assertEquals("", values.get(0));
			Assertions.assertEquals(List.of(), values.get(1));
		}
		Assertions.assertEquals(2, events.size());
		Assertions.assertEquals(0, Values.constructions.get());
	}

	@Test
	void omittedDynamicMethodsKeepExistingBehaviorWithoutNewDispatchDiagnostics() throws Exception {
		ResourceMethod health = method("health", "/health");
		ResourceMethod dynamic = method("defaults", "/dynamic");
		List<LogEvent> events = new ArrayList<>();
		SokletConfig config = builder(Set.of(health), events).build();
		run(new SokletFrameworkSetup(config));
		SokletFrameworkSetup.validateConfiguredResourceMethod(config, dynamic);
		Assertions.assertTrue(events.isEmpty());
	}

	@Test
	void throwingDiagnosticObserverDoesNotTurnWarningsIntoStartupErrors() throws Exception {
		ResourceMethod method = method("defaults", "/defaults");
		AtomicInteger attempts = new AtomicInteger();
		SokletConfig config = builder(Set.of(method), new ArrayList<>()).lifecycleObservers(List.of(new LifecycleObserver() {
			@Override public void didReceiveLogEvent(LogEvent event) {
				attempts.incrementAndGet();
				throw new IllegalStateException("Intentional observer failure");
			}
		})).build();
		Assertions.assertDoesNotThrow(() -> run(new SokletFrameworkSetup(config)));
		Assertions.assertEquals(2, attempts.get());
	}

	private static ResourceMethod method(String name, String path) {
		Method method = Arrays.stream(Values.class.getDeclaredMethods()).filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
		return ResourceMethod.fromComponents(HttpMethod.GET, ResourcePathDeclaration.fromPath(path), method, false);
	}

	private static SokletConfig.Builder builder(Set<ResourceMethod> methods, List<LogEvent> events) {
		HttpServer http = new HttpServer() {
			private final TransportIdentity identity = TransportIdentity.create();
			@Override public TransportIdentity getTransportIdentity() { return this.identity; }
			@Override public TransportRuntime attach(HttpTransportAttachmentContext context, StartupContext startup) {
				throw new AssertionError("Pure framework-setup test must not attach any transport");
			}
		};
		ResourceMethodResolver resolver = new ResourceMethodResolver() {
			@Override public Set<ResourceMethod> getResourceMethods() { return methods; }
			@Override public Optional<ResourceMethod> resourceMethodForRequest(Request request, ServerType type) { return Optional.empty(); }
		};
		return SokletConfig.withHttpServer(http).resourceMethodResolver(resolver)
				.metricsCollector(MetricsCollector.disabledInstance()).lifecycleObservers(List.of(new LifecycleObserver() {
					@Override public void didReceiveLogEvent(LogEvent event) { events.add(event); }
				}));
	}

	private static Set<ResourceMethod> run(SokletFrameworkSetup setup) {
		NanoClock clock = NanoClock.system();
		return setup.run(new StartupContext(clock, Long.MAX_VALUE, Long.MAX_VALUE, () -> false), new DeadlineWaiter(clock));
	}

	public static final class Values {
		static final AtomicInteger constructions = new AtomicInteger();
		public Values() { constructions.incrementAndGet(); }
		public String health() { return "ok"; }
		public String values(String text, List<String> list, Optional<Integer> number, Kind kind,
				Map<String, String> map, ArrayList<String> concrete, BigDecimal decimal, long primitive) { return "ok"; }
		public String defaults(String text, ArrayList<String> list) { return "ok"; }
		public String bound(@PathParameter(name="id") String id, @QueryParameter(name="q") String query,
				@FormParameter(name="f") String form, @RequestHeader(name="h") String header,
				@RequestCookie(name="c") UUID cookie, @RequestBody List<String> body,
				@Multipart(name="field") String field) { return "ok"; }
		public String builtins(@QueryParameter(name="ignored") Request request, ResourceMethod method,
				InstanceProvider instances, LifecycleObserver observer, ObserverList list) { return "ok"; }
		public String implicitMultipart(MultipartField field, List<MultipartField> fields, Optional<MultipartField> optional) { return "ok"; }
		public String services(Random random, Service service, Optional<Service> optional,
				ApplicationList list, ApplicationMap map, DomainNumber number, Optional<ApplicationList> optionalList) { return "ok"; }
	}
	public enum Kind { EXAMPLE }
	public static final class Service { public Service() {} }
	public static final class ApplicationList extends ArrayList<String> { private static final long serialVersionUID = 1L; }
	public static final class ObserverList extends ArrayList<String> implements LifecycleObserver { private static final long serialVersionUID = 1L; }
	public static final class ApplicationMap extends HashMap<String, String> { private static final long serialVersionUID = 1L; }
	public static final class DomainNumber extends Number {
		private static final long serialVersionUID = 1L;
		@Override public int intValue() { return 0; }
		@Override public long longValue() { return 0L; }
		@Override public float floatValue() { return 0.0f; }
		@Override public double doubleValue() { return 0.0; }
	}
}
