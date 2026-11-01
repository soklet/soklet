package com.soklet;

import com.soklet.McpLegacySessionTransportPublicRuntimeTests.Fixture;
import com.soklet.McpLegacySessionTransportPublicRuntimeTests.Head;
import com.soklet.McpLegacySessionTransportPublicRuntimeTests.RawClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.EOFException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(150)
class McpLegacyGetWireTerminationTests {
	@Test
	void shedLegacyGetClosesItsHttpConnectionImmediately() throws Exception {
		assertWireClosed(false);
	}

	@Test
	void expiredGracePeriodClosesItsHttpConnectionImmediately() throws Exception {
		assertWireClosed(true);
	}

	private void assertWireClosed(boolean graceExpired) throws Exception {
		for (McpProtocolVersion version : List.of(McpProtocolVersion.V2025_06_18, McpProtocolVersion.V2025_11_25)) {
			{
				Fixture fixture = new Fixture(true, builder -> {});
				Soklet.run(fixture.config, () -> {
					String id = fixture.initialize("/mcp", version);
					try (RawClient client = fixture.openControl("GET", "/mcp", version, id, "alice", "", List.of())) {
						Head head = client.readHead();
						assertEquals(200, head.status());
						assertEquals("chunked", head.header("Transfer-Encoding"));
						// Select the same established control used by notification-pressure
						// eviction, without depending on kernel send-buffer sizing or load.
						Object runtime = field(field(fixture.server, "runtimeBridge"), "runtime");
						Map<?, ?> controls = (Map<?, ?>) field(runtime, "legacyGetControls");
						assertEquals(1, controls.size());
						Object get = controls.values().iterator().next();
						if (graceExpired) {
							Object control = field(get, "control");
							synchronized (field(control, "lock")) {
								set(get, "closing", true);
								set(get, "closeReason", McpStreamTerminationReason.SERVER_STOPPING);
								set(get, "closeDeadlineNanos", 0L);
							}
							invoke(get, "onTimer", new Class<?>[]{long.class}, System.nanoTime());
						} else invoke(get, "shedNotificationPressure", new Class<?>[0]);
						// An unfinished chunked stream cannot be reused as an HTTP keepalive
						// connection. It must end in EOF, rather than hang until header timeout.
						assertThrows(EOFException.class, () -> client.readBody(head));
						long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
						while (fixture.server.getDiagnostics().getActiveRequestStreams() != 0 && System.nanoTime() < deadline)
							Thread.sleep(5);
						assertEquals(0, fixture.server.getDiagnostics().getActiveRequestStreams());
					}
				});
			}
		}
	}

	private static Object field(Object owner, String name) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}
	private static void set(Object owner, String name, Object value) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(owner, value);
	}
	private static void invoke(Object owner, String name, Class<?>[] types, Object... arguments) throws Exception {
		Method method = owner.getClass().getDeclaredMethod(name, types);
		method.setAccessible(true);
		method.invoke(owner, arguments);
	}
}
