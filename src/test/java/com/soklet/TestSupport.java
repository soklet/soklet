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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
final class TestSupport {
	/**
	 * Ports are handed out from a fixed private range that sits below every
	 * platform's ephemeral range (macOS and Linux both start at 32768 or higher),
	 * so the operating system can never assign the same number to an outbound
	 * client socket between this probe and the caller's own bind.
	 */
	private static final int PORT_RANGE_SPAN = 4_096;
	private static final int PORT_RANGE_START = 20_000
			+ (forkIndex() * PORT_RANGE_SPAN);
	private static final Set<Integer> ISSUED_PORTS = ConcurrentHashMap.newKeySet();
	private static final AtomicInteger PORT_CURSOR = new AtomicInteger();

	private TestSupport() {}

	/**
	 * Returns a port that is bindable right now and that has not already been
	 * handed to another test in this JVM.
	 * <p>
	 * The obvious implementation - bind {@code new ServerSocket(0)}, close it, and
	 * return the number - is racy: the port it reports comes from the ephemeral
	 * range, so between the close and the caller's bind the kernel is free to hand
	 * that exact number to any outbound connection the suite makes, and the caller
	 * then fails with "port N is already in use". Drawing from a private range and
	 * never reusing a number removes both halves of that race.
	 */
	static int findFreePort() throws IOException {
		for (int attempt = 0; attempt < PORT_RANGE_SPAN; ++attempt) {
			int candidate = PORT_RANGE_START
					+ Math.floorMod(PORT_CURSOR.getAndIncrement(), PORT_RANGE_SPAN);

			if (!ISSUED_PORTS.add(candidate))
				continue;

			if (isBindable(candidate))
				return candidate;
		}

		throw new IOException(String.format(
				"Unable to find a free port in [%d, %d].", PORT_RANGE_START,
				PORT_RANGE_START + PORT_RANGE_SPAN - 1));
	}

	/**
	 * Probes the way the servers under test bind - the wildcard address, without
	 * {@code SO_REUSEADDR} - so a port that is still held, or lingering in
	 * {@code TIME_WAIT}, is skipped here instead of failing inside the server.
	 */
	private static boolean isBindable(int port) {
		try (ServerSocket serverSocket = new ServerSocket()) {
			serverSocket.setReuseAddress(false);
			serverSocket.bind(new InetSocketAddress(port), 1);
			return true;
		} catch (IOException ignored) {
			return false;
		}
	}

	/**
	 * Surefire numbers forks from one and leaves the property unset when it does
	 * not fork, so each fork gets its own slice and concurrent forks cannot collide.
	 */
	private static int forkIndex() {
		try {
			int forkNumber = Integer.parseInt(
					System.getProperty("surefire.forkNumber", "1"));
			return Math.max(0, Math.min(forkNumber - 1, 2));
		} catch (NumberFormatException ignored) {
			return 0;
		}
	}

	static byte[] readAll(InputStream in) throws IOException {
		if (in == null) return new byte[0];
		try (InputStream is = in) {
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = is.read(buf)) != -1) {
				bos.write(buf, 0, n);
			}
			return bos.toByteArray();
		}
	}

	static Socket connectWithRetry(String host, int port, int timeoutMs) throws IOException, InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		IOException last = null;
		while (System.currentTimeMillis() < deadline) {
			try {
				Socket s = new Socket();
				s.connect(new InetSocketAddress(host, port), Math.max(250, timeoutMs / 2));
				return s;
			} catch (IOException e) {
				last = e;
				Thread.sleep(30);
			}
		}
		throw (last != null ? last : new IOException("Unable to connect to " + host + ":" + port));
	}
}
