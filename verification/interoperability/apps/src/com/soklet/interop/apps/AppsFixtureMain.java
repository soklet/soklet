/*
 * Copyright 2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.soklet.interop.apps;

import com.soklet.LifecycleObserver;
import com.soklet.LogEvent;
import com.soklet.McpServer;
import com.soklet.McpServerStatus;
import com.soklet.ShutdownComponentDisposition;
import com.soklet.ShutdownComponentType;
import com.soklet.ShutdownResult;
import com.soklet.Soklet;
import com.soklet.SokletConfig;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Disposable loopback listener for the separately supervised browser probe.
 * The only argument is an absolute shell path. The credential is the first
 * stdin line; subsequent EOF requests graceful shutdown. No credential, request,
 * payload, exception, or framework log is written to the control streams.
 */
public final class AppsFixtureMain {
	private static final int MAXIMUM_SHELL_BYTES = 512 * 1024;
	private static final String FAILURE =
			"{\"format\":1,\"event\":\"failed\",\"code\":\"APPS_FIXTURE_FAILED\"}";
	private static final LifecycleObserver QUIET_OBSERVER = new LifecycleObserver() {
		@Override
		public void didReceiveLogEvent(LogEvent event) {
			// Public callback intentionally discards raw fixture diagnostics.
		}
	};

	private AppsFixtureMain() {
	}

	public static void main(String[] arguments) {
		Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> haltFailed(1));
		ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "apps-fixture-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		// Independent of stdin, server startup, shutdown, and the parent's process bound.
		watchdog.schedule(() -> haltFailed(124), 150, TimeUnit.SECONDS);
		Soklet soklet = null;
		McpServer server = null;
		boolean failed = false;
		try {
			if (arguments.length != 1)
				throw new IllegalArgumentException();
			String shell = readShell(arguments[0]);
			ScheduledFuture<?> inputDeadline = watchdog.schedule(() -> haltFailed(124), 10, TimeUnit.SECONDS);
			String token = readToken(System.in);
			inputDeadline.cancel(false);
			AppsFixture fixture = new AppsFixture(shell,
					Map.of(token, new AppsFixture.Caller("browser-probe", "alpha", "en-US", true)));
			SokletConfig config = fixture.serverConfig(0, QUIET_OBSERVER);
			server = config.getMcpServer().orElseThrow();
			soklet = Soklet.fromConfig(config);
			soklet.start();
			InetSocketAddress address = server.getDiagnostics().getBoundAddress().orElseThrow();
			if (!"127.0.0.1".equals(address.getAddress().getHostAddress()) || address.getPort() < 1)
				throw new IllegalStateException();
			control("{\"format\":1,\"event\":\"ready\",\"host\":\"127.0.0.1\",\"port\":"
					+ address.getPort() + ",\"path\":\"/apps\"}");
			if (System.in.read() != -1)
				throw new IllegalArgumentException();
		} catch (Throwable failure) {
			failed = true;
		} finally {
			if (soklet != null) {
				// Also bounds failures in a shutdown() call before it returns its future.
				ScheduledFuture<?> shutdownDeadline = watchdog.schedule(() -> haltFailed(124), 5, TimeUnit.SECONDS);
				try {
					ShutdownResult result = soklet.shutdown().toCompletableFuture().get(5, TimeUnit.SECONDS);
					if (server == null || server.getDiagnostics().getStatus() != McpServerStatus.TERMINATED
							|| result.getShutdownComponentResult(ShutdownComponentType.MCP).orElseThrow()
									.getShutdownComponentDisposition() != ShutdownComponentDisposition.GRACEFUL_TERMINATION)
						failed = true;
				} catch (Throwable failure) {
					failed = true;
				} finally {
					shutdownDeadline.cancel(false);
				}
			}
		}
		if (failed) {
			System.err.println(FAILURE);
			System.err.flush();
		} else {
			control("{\"format\":1,\"event\":\"stopped\",\"clean\":true}");
		}
		// Keep the independent daemon watchdog armed through process termination.
		System.exit(failed ? 1 : 0);
	}

	private static String readShell(String argument) throws Exception {
		Path path = Path.of(argument);
		if (!path.isAbsolute() || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
			throw new IllegalArgumentException();
		byte[] bytes;
		try (InputStream input = Files.newInputStream(path)) {
			bytes = input.readNBytes(MAXIMUM_SHELL_BYTES + 1);
		}
		if (bytes.length == 0 || bytes.length > MAXIMUM_SHELL_BYTES)
			throw new IllegalArgumentException();
		return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
	}

	private static String readToken(InputStream input) throws Exception {
		StringBuilder token = new StringBuilder(128);
		while (true) {
			int next = input.read();
			if (next == '\n') {
				if (token.length() < 8)
					throw new IllegalArgumentException();
				return token.toString();
			}
			if (next < 0 || token.length() >= 128
					|| !((next >= 'A' && next <= 'Z') || (next >= 'a' && next <= 'z')
							|| (next >= '0' && next <= '9') || next == '_' || next == '-'))
				throw new IllegalArgumentException();
			token.append((char) next);
		}
	}

	private static void control(String line) {
		System.out.println(line);
		System.out.flush();
		if (System.out.checkError())
			throw new IllegalStateException();
	}

	private static void haltFailed(int code) {
		System.err.println(FAILURE);
		System.err.flush();
		Runtime.getRuntime().halt(code);
	}
}
