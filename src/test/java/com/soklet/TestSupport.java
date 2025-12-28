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

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
final class TestSupport {
	private TestSupport() {}

	static int findFreePort() throws IOException {
		try (ServerSocket ss = new ServerSocket(0)) {
			ss.setReuseAddress(true);
			return ss.getLocalPort();
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
