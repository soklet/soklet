/*
 * Copyright 2022 Revetware LLC.
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

package com.soklet.core.impl;

import com.soklet.core.MarshaledResponse;
import com.soklet.core.Request;
import com.soklet.core.RequestHandler;
import com.soklet.core.Server;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Mock server that doesn't touch the network at all, useful for testing.
 *
 * @author <a href="https://www.revetware.com">Mark Allen</a>
 */
@ThreadSafe
public class MockServer implements Server {
	@Nonnull
	private final ReentrantLock lock;
	@Nullable
	private RequestHandler requestHandler;
	@Nonnull
	private Boolean started;

	public MockServer() {
		this.lock = new ReentrantLock();
		this.started = false;
	}

	@Override
	public void start() {
		getLock().lock();

		try {
			if (isStarted())
				return;

			this.started = true;
		} finally {
			getLock().unlock();
		}
	}

	@Override
	public void stop() {
		getLock().lock();

		try {
			if (!isStarted())
				return;

			this.started = false;
		} finally {
			getLock().unlock();
		}
	}

	@Nonnull
	@Override
	public Boolean isStarted() {
		getLock().lock();

		try {
			return this.started;
		} finally {
			getLock().unlock();
		}
	}

	@Override
	public void close() {
		stop();
	}

	@Nonnull
	public MarshaledResponse simulateRequest(@Nonnull Request request) {
		AtomicReference<MarshaledResponse> marshaledResponseHolder = new AtomicReference<>();
		RequestHandler requestHandler = getRequestHandler().orElse(null);

		if (requestHandler == null)
			throw new IllegalStateException("You must register a request handler prior to simulating requests");

		requestHandler.handleRequest(request, (marshaledResponse -> {
			marshaledResponseHolder.set(marshaledResponse);
		}));

		return marshaledResponseHolder.get();
	}

	@Override
	public void registerRequestHandler(@Nullable RequestHandler requestHandler) {
		this.requestHandler = requestHandler;
	}

	@Nonnull
	protected ReentrantLock getLock() {
		return this.lock;
	}

	@Nullable
	protected Optional<RequestHandler> getRequestHandler() {
		return Optional.ofNullable(this.requestHandler);
	}
}
