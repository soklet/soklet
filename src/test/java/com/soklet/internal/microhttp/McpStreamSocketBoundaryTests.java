/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet.internal.microhttp;

import com.soklet.StreamTerminationReason;
import com.soklet.internal.mcp.transport.McpOutboundChannel;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class McpStreamSocketBoundaryTests {
	@Test
	public void everyMcpChunkKindPreservesTheTypedSocketFailureBoundary() throws Exception {
		for (String chunkKind : new String[] {"progress", "terminal", "empty-terminal"}) {
			McpOutboundChannel channel = new McpOutboundChannel(1, 32, 32,
					System::nanoTime, new McpOutboundChannel.Listener() {
				@Override public void didWrite(long byteCount, long timestampNanos) { }
				@Override public void didApplyBackpressure() { }
				@Override public void didTerminate(StreamTerminationReason reason, @Nullable Throwable cause) { }
			});
			byte[] payload = "message".getBytes(StandardCharsets.US_ASCII);
			assertTrue(switch (chunkKind) {
				case "progress" -> channel.enqueue(payload);
				case "terminal" -> channel.complete(payload);
				default -> channel.completeWithoutPayload();
			});
			WritableSource source = channel.newWritableSource();
			source.start();
			try (SocketChannel socketChannel = SocketChannel.open()) {
				socketChannel.close();
				// A closed real channel deterministically makes the write side fail,
				// without racing disconnect monitoring or relying on OS message text.
				SocketChannelIo.SocketIoException failure = assertThrows(
						SocketChannelIo.SocketIoException.class,
						() -> source.writeTo(socketChannel, 64L), chunkKind);
				assertInstanceOf(ClosedChannelException.class, failure.getCause());
			} finally {
				source.close(StreamTerminationReason.CLIENT_DISCONNECTED, null);
			}
		}
	}
}
