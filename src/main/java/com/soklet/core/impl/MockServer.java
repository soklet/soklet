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
	public void start() throws Exception {
		getLock().lock();

		try {
			if (isStarted())
				return;

			this.started = true;
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
	public void close() throws Exception {
		getLock().lock();

		try {
			if (!isStarted())
				return;

			this.started = false;
		} finally {
			getLock().unlock();
		}
	}

	public MarshaledResponse simulateRequest(@Nonnull Request request) throws Exception {
		AtomicReference<MarshaledResponse> marshaledResponseHolder = new AtomicReference<>();

		getRequestHandler().get().handleRequest(request, (marshaledResponse -> {
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
