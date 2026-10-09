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

package com.soklet.internal.microhttp;
import com.soklet.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.channels.spi.SelectorProvider;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

public class StreamingPayloadMetricsTests {
    @Test public void directSocketFailuresKeepTypedOriginAndExactLocalizedCause() throws Exception {
        IOException localized = new IOException("La connexion a été interrompue");
        try (RecordingSocketChannel socket = new RecordingSocketChannel(0, localized)) {
            var source = new ByteBufferWritableSource(ByteBuffer.wrap(new byte[]{1}));
            var writeFailure = assertThrows(SocketChannelIo.SocketIoException.class, () -> source.writeTo(socket, 1));
            assertSame(localized, writeFailure.getCause());
            socket.readFailure = localized;
            var readFailure = assertThrows(SocketChannelIo.SocketIoException.class, () -> SocketChannelIo.read(socket, ByteBuffer.allocate(1)));
            assertSame(localized, readFailure.getCause());
        }
    }
    @Test public void partialPayloadBeforeLaterSocketFailureExcludesHeadersAndChunkFraming() throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor();
        ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
        CountDownLatch produced=new CountDownLatch(1);
        AtomicLong bytes=new AtomicLong(-1), nanos=new AtomicLong();
        WritableSource source=null;
        try {
            MicrohttpResponse response=StreamingMicrohttpResponses.withStreamingBody(200,"OK",List.of(),
                Request.withPath(HttpMethod.GET,"/partial").build(),StreamingResponseBody.fromWriter(stream->{
                    stream.write(new byte[]{1,2,3,4,5});stream.flush();produced.countDown();
                }),executor,timer,1024,1024,null,null,()->false,new StreamingMicrohttpResponses.TerminationListener(){
                    @Override public void didTerminate(Instant start,Duration duration,StreamTerminationReason reason,Throwable failure){ fail("Missing internal transport measurements"); }
                    @Override public void didTerminate(Instant start,Duration duration,StreamTerminationReason reason,Throwable failure,long terminalNanos,long payload){
                        bytes.set(payload);nanos.set(terminalNanos);
                    }
                },failure->fail(failure));
            byte[] head=response.serializeHead("HTTP/1.1",List.of());
            source=response.writableSource(head);source.start();assertTrue(produced.await(3,TimeUnit.SECONDS));
            try(RecordingSocketChannel socket=new RecordingSocketChannel(head.length+5)) {
                WritableSource captured=source;
                IOException failure=assertThrows(IOException.class,()->captured.writeTo(socket,1024));
                source.close(StreamTerminationReason.CLIENT_DISCONNECTED,failure);
                assertEquals(head.length+5,socket.outputStream.size());
                assertEquals(2L,bytes.get());assertTrue(nanos.get()>0);
                source.close();assertEquals(2L,bytes.get());
            }
        } finally {
            if(source!=null)source.close();executor.shutdownNow();timer.shutdownNow();
            assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));assertTrue(timer.awaitTermination(3,TimeUnit.SECONDS));
        }
    }
	private static final class RecordingSocketChannel extends SocketChannel {
		private final ByteArrayOutputStream outputStream;
        private int budget;
        private final IOException writeFailure;
        private IOException readFailure;

		private RecordingSocketChannel(int budget) {
            this(budget, new IOException("controlled-socket-write-failure"));
        }
        private RecordingSocketChannel(int budget, IOException writeFailure) {
            super(SelectorProvider.provider());
            this.budget = budget;
			this.writeFailure = writeFailure;
			this.outputStream = new ByteArrayOutputStream();
		}

		@Override
		public int read(ByteBuffer dst) throws IOException {
			if (this.readFailure != null) throw this.readFailure;
			return -1;
		}

		@Override
		public long read(ByteBuffer[] dsts, int offset, int length) {
			return -1L;
		}

		@Override
        public int write(ByteBuffer src) throws IOException {
            if (this.budget == 0) throw this.writeFailure;
            int size = Math.min(this.budget, src.remaining());
            byte[] bytes = new byte[size]; src.get(bytes); this.outputStream.writeBytes(bytes);
            this.budget -= size; return size;
        }

		@Override
		public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
			long written = 0L;

			for (int i = offset; i < offset + length; ++i)
				written += write(srcs[i]);

			return written;
		}

		@Override
		public SocketChannel bind(SocketAddress local) {
			return this;
		}

		@Override
		public <T> SocketChannel setOption(SocketOption<T> name, T value) {
			return this;
		}

		@Override
		public SocketChannel shutdownInput() {
			return this;
		}

		@Override
		public SocketChannel shutdownOutput() {
			return this;
		}

		@Override
		public Socket socket() {
			return new Socket();
		}

		@Override
		public boolean isConnected() {
			return true;
		}

		@Override
		public boolean isConnectionPending() {
			return false;
		}

		@Override
		public boolean connect(SocketAddress remote) {
			return true;
		}

		@Override
		public boolean finishConnect() {
			return true;
		}

		@Override
		public SocketAddress getRemoteAddress() {
			return null;
		}

		@Override
		public SocketAddress getLocalAddress() {
			return null;
		}

		@Override
		public <T> T getOption(SocketOption<T> name) {
			return null;
		}

		@Override
		public Set<SocketOption<?>> supportedOptions() {
			return Set.of();
		}

		@Override
		protected void implCloseSelectableChannel() {
			// No-op
		}

		@Override
		protected void implConfigureBlocking(boolean block) {
			// No-op
		}
	}
}
