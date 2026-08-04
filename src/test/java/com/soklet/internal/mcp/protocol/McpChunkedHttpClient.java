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

package com.soklet.internal.mcp.protocol;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class McpChunkedHttpClient implements AutoCloseable {
	private static final String LOOPBACK = "127.0.0.1";
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final int MAXIMUM_HEAD_BYTES = 64 * 1_024;
	private static final int MAXIMUM_CHUNK_BYTES = 20 * 1_024 * 1_024;
	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private final Socket socket;
	private final InputStream inputStream;
	private final int port;
	private boolean terminalChunkRead;

	private McpChunkedHttpClient(int port, int receiveBufferBytes) throws IOException {
		this.socket = new Socket();
		this.port = port;
		if (receiveBufferBytes > 0)
			socket.setReceiveBufferSize(receiveBufferBytes);
		socket.setTcpNoDelay(true);
		socket.setSoTimeout((int) TIMEOUT.toMillis());
		socket.connect(new InetSocketAddress(LOOPBACK, port),
				(int) TIMEOUT.toMillis());
		this.inputStream = socket.getInputStream();
	}

	static McpChunkedHttpClient postMcp(int port, String idJson, String method)
			throws IOException {
		return postMcp(port, idJson, method, 0);
	}

	static McpChunkedHttpClient postMcp(int port, String idJson, String method,
			int receiveBufferBytes) throws IOException {
		byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":" + idJson
					+ ",\"method\":\"" + method + "\",\"params\":{\"_meta\":{"
					+ "\"io.modelcontextprotocol/protocolVersion\":\""
					+ PROTOCOL_VERSION + "\","
					+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}")
					.getBytes(StandardCharsets.UTF_8);
		return postMcpMessage(port, new String(body, StandardCharsets.UTF_8),
				List.of(new RequestHeader("MCP-Protocol-Version", PROTOCOL_VERSION),
						new RequestHeader("Mcp-Method", method)), receiveBufferBytes);
	}

	static McpChunkedHttpClient postMcpMessage(int port, String body,
			List<RequestHeader> headers) throws IOException {
		return postMcpMessage(port, body, headers, 0);
	}

	static McpChunkedHttpClient postMcpMessage(int port, String body,
			List<RequestHeader> headers, int receiveBufferBytes) throws IOException {
		McpChunkedHttpClient client = new McpChunkedHttpClient(port, receiveBufferBytes);
		try {
			client.writeMcpMessage(body, headers);
			return client;
		} catch (IOException | RuntimeException | Error throwable) {
			try {
				client.close();
			} catch (Throwable suppressed) {
				throwable.addSuppressed(suppressed);
			}
			throw throwable;
		}
	}

	void writeMcpMessage(String body, List<RequestHeader> headers) throws IOException {
		writeMcpMessage(body, LOOPBACK + ':' + port, headers);
	}

	void writeMcpMessage(String body, String hostAuthority,
			List<RequestHeader> headers) throws IOException {
		writeRequest("POST", "/mcp", hostAuthority, body, headers);
	}

	void writeRequest(String method, String path, String hostAuthority, String body,
			List<RequestHeader> headers) throws IOException {
		byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
		StringBuilder headText = new StringBuilder()
				.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
				.append("Host: ").append(hostAuthority).append("\r\n")
				.append("Content-Type: application/json; charset=UTF-8\r\n")
				.append("Accept: application/json, text/event-stream\r\n");
		for (RequestHeader header : headers)
			headText.append(header.name()).append(": ").append(header.value()).append("\r\n");
		headText.append("Content-Length: ").append(bodyBytes.length).append("\r\n\r\n");
		byte[] head = headText.toString().getBytes(StandardCharsets.ISO_8859_1);
		socket.getOutputStream().write(head);
		socket.getOutputStream().write(bodyBytes);
		socket.getOutputStream().flush();
	}

	record RequestHeader(String name, String value) {
	}

	HttpResponseHead readHead() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		int matched = 0;
		while (bytes.size() < MAXIMUM_HEAD_BYTES) {
			int value = inputStream.read();
			if (value < 0)
				throw new EOFException(
						"Socket closed before the HTTP response head was complete.");
			bytes.write(value);
			matched = switch (matched) {
				case 0 -> value == '\r' ? 1 : 0;
				case 1 -> value == '\n' ? 2 : value == '\r' ? 1 : 0;
				case 2 -> value == '\r' ? 3 : 0;
				case 3 -> value == '\n' ? 4 : 0;
				default -> matched;
			};
			if (matched == 4)
				break;
		}
		if (matched != 4)
			throw new IOException(
					"HTTP response head exceeded the test byte bound.");

		String raw = bytes.toString(StandardCharsets.ISO_8859_1);
		String[] lines = raw.substring(0, raw.length() - 4).split("\\r\\n");
		String[] statusParts = lines[0].split(" ", 3);
		if (statusParts.length < 2)
			throw new IOException("Malformed HTTP status line: " + lines[0]);
		Map<String, List<String>> headers = new LinkedHashMap<>();
		for (int index = 1; index < lines.length; index++) {
			int colon = lines[index].indexOf(':');
			if (colon < 1)
				throw new IOException("Malformed response header: " + lines[index]);
			String name = lines[index].substring(0, colon).trim()
					.toLowerCase(Locale.ROOT);
			String value = lines[index].substring(colon + 1).trim();
			headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
		}
		Map<String, List<String>> copied = new LinkedHashMap<>();
		headers.forEach((name, values) -> copied.put(name, List.copyOf(values)));
		return new HttpResponseHead(raw, Integer.parseInt(statusParts[1]),
				Map.copyOf(copied));
	}

	String readChunkText() throws IOException {
		byte[] chunk = readChunk();
		if (chunk == null)
			throw new EOFException("Expected another HTTP chunk.");
		return new String(chunk, StandardCharsets.UTF_8);
	}

	String readFixedBody(HttpResponseHead head) throws IOException {
		String contentLength = head.singleHeader("Content-Length");
		return new String(readExactly(Integer.parseInt(contentLength)),
				StandardCharsets.UTF_8);
	}

	byte[] readChunk() throws IOException {
		if (terminalChunkRead)
			return null;
		String sizeLine = readCrlfLine();
		int extension = sizeLine.indexOf(';');
		String hexadecimal = (extension < 0 ? sizeLine
				: sizeLine.substring(0, extension)).trim();
		long size;
		try {
			size = Long.parseLong(hexadecimal, 16);
		} catch (NumberFormatException exception) {
			throw new IOException("Malformed HTTP chunk size: " + sizeLine, exception);
		}
		if (size == 0L) {
			String trailer;
			do {
				trailer = readCrlfLine();
			} while (!trailer.isEmpty());
			terminalChunkRead = true;
			return null;
		}
		if (size < 0L || size > MAXIMUM_CHUNK_BYTES)
			throw new IOException("HTTP chunk exceeds the test bound: " + size);
		byte[] payload = readExactly((int) size);
		if (inputStream.read() != '\r' || inputStream.read() != '\n')
			throw new IOException("HTTP chunk payload was not followed by CRLF.");
		return payload;
	}

	void closeWithReset() throws IOException {
		if (!socket.isClosed()) {
			socket.setSoLinger(true, 0);
			socket.close();
		}
	}

	boolean awaitTransportClosure() throws IOException {
		try {
			while (inputStream.read() >= 0) {
				// Discard until the transport closes without a terminal chunk.
			}
			return true;
		} catch (SocketException exception) {
			return true;
		}
	}

	private String readCrlfLine() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		boolean carriageReturn = false;
		while (bytes.size() < MAXIMUM_HEAD_BYTES) {
			int value = inputStream.read();
			if (value < 0)
				throw new EOFException("Socket closed while reading an HTTP chunk line.");
			if (carriageReturn && value == '\n') {
				byte[] line = bytes.toByteArray();
				return new String(line, 0, line.length - 1,
						StandardCharsets.US_ASCII);
			}
			bytes.write(value);
			carriageReturn = value == '\r';
		}
		throw new IOException("HTTP chunk line exceeded the test byte bound.");
	}

	private byte[] readExactly(int length) throws IOException {
		byte[] bytes = new byte[length];
		int offset = 0;
		while (offset < bytes.length) {
			int read = inputStream.read(bytes, offset, bytes.length - offset);
			if (read < 0)
				throw new EOFException("Socket closed with "
						+ (bytes.length - offset) + " bytes remaining.");
			offset += read;
		}
		return bytes;
	}

	@Override
	public void close() throws IOException {
		socket.close();
	}

	record HttpResponseHead(String raw, int status,
			Map<String, List<String>> headers) {
		String singleHeader(String name) {
			List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
			if (values == null || values.size() != 1)
				throw new AssertionError("Expected exactly one " + name
						+ " header, found " + values + "; response=" + raw);
			return values.get(0);
		}

		boolean hasHeader(String name) {
			return headers.containsKey(name.toLowerCase(Locale.ROOT));
		}
	}
}
