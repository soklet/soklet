const defaultTimeoutMs = 10000;
const defaultMaxMessageBytes = 4 * 1024 * 1024;
const maxAggregateBytes = 64 * 1024 * 1024;
const maxPendingCommands = 32;
const maxEventListeners = 128;
const maxPendingEventCallbacks = 128;
const methodPattern = /^[A-Za-z][A-Za-z0-9]*\.[A-Za-z][A-Za-z0-9]*$/;
const sessionPattern = /^[A-Za-z0-9_-]{1,128}$/;

function record(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function error(code) {
  return new Error(code);
}

function validMethod(method) {
  return typeof method === 'string' && method.length <= 200
    && methodPattern.exec(method)?.[0] === method;
}

function validSession(sessionId) {
  return sessionId === undefined
    || (typeof sessionId === 'string' && sessionPattern.exec(sessionId)?.[0] === sessionId);
}

function validEndpoint(wsUrl) {
  if (typeof wsUrl !== 'string' || wsUrl.length > 256)
    return false;
  const match = /^ws:\/\/127\.0\.0\.1:([1-9][0-9]{0,4})\/devtools\/browser\/([A-Za-z0-9_-]{1,128})$/.exec(wsUrl);
  return match !== null && match[0] === wsUrl && Number(match[1]) <= 65535;
}

/**
 * A bounded CDP connection for a fresh, isolated Chrome browser endpoint.
 * Raw protocol errors, URLs, command parameters, and events are never logged or
 * copied into errors. Browser lifetime is supervised separately by the caller.
 */
export async function connectCdp(wsUrl, options = {}) {
  if (!record(options) || !validEndpoint(wsUrl))
    throw error('CDP_INVALID_OPTIONS');
  const {
    WebSocketImpl = globalThis.WebSocket,
    timeoutMs = defaultTimeoutMs,
    maxMessageBytes = defaultMaxMessageBytes,
  } = options;
  if (typeof WebSocketImpl !== 'function'
      || !Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 60000
      || !Number.isSafeInteger(maxMessageBytes) || maxMessageBytes < 1
      || maxMessageBytes > 16 * 1024 * 1024)
    throw error('CDP_INVALID_OPTIONS');

  let socket;
  try {
    socket = new WebSocketImpl(wsUrl);
  } catch {
    throw error('CDP_CONNECT_FAILED');
  }
  if (typeof socket?.addEventListener !== 'function'
      || typeof socket?.removeEventListener !== 'function'
      || typeof socket?.send !== 'function' || typeof socket?.close !== 'function')
    throw error('CDP_CONNECT_FAILED');

  let state = 'connecting';
  let failureCode;
  let nextId = 1;
  let receivedBytes = 0;
  let sentBytes = 0;
  let listenerCount = 0;
  let pendingEventCallbacks = 0;
  const pending = new Map();
  const listeners = new Map();
  let connectResolve;
  let connectReject;
  const connected = new Promise((resolve, reject) => {
    connectResolve = resolve;
    connectReject = reject;
  });
  let closePromise;
  let closeResolve;
  let closeReject;
  let connectTimer;
  let closeTimer;

  function rejectPending(code) {
    for (const command of pending.values()) {
      clearTimeout(command.timer);
      command.reject(error(code));
    }
    pending.clear();
  }

  function detach() {
    socket.removeEventListener('open', onOpen);
    socket.removeEventListener('message', onMessage);
    socket.removeEventListener('error', onError);
    socket.removeEventListener('close', onClose);
    listeners.clear();
    listenerCount = 0;
  }

  function finishClose(code) {
    if (state === 'closed')
      return;
    state = 'closed';
    clearTimeout(connectTimer);
    clearTimeout(closeTimer);
    detach();
    if (code !== undefined) {
      failureCode ??= code;
      closeReject?.(error(code));
    } else {
      closeResolve?.();
    }
  }

  function close() {
    if (closePromise !== undefined)
      return closePromise;
    if (state === 'closed')
      return Promise.resolve();
    closePromise = new Promise((resolve, reject) => {
      closeResolve = resolve;
      closeReject = reject;
    });
    // Failure-triggered closure may finish before the caller awaits close().
    void closePromise.catch(() => {});
    state = 'closing';
    clearTimeout(connectTimer);
    connectReject(error(failureCode ?? 'CDP_CLOSED'));
    rejectPending(failureCode ?? 'CDP_CLOSED');
    listeners.clear();
    listenerCount = 0;
    closeTimer = setTimeout(() => finishClose('CDP_CLOSE_TIMEOUT'),
      Math.min(timeoutMs, 2000));
    try {
      socket.close();
    } catch {
      finishClose('CDP_CLOSE_FAILED');
    }
    return closePromise;
  }

  function fail(code) {
    failureCode ??= code;
    connectReject(error(failureCode));
    rejectPending(failureCode);
    void close().catch(() => {});
  }

  function onOpen() {
    if (state !== 'connecting')
      return;
    state = 'open';
    clearTimeout(connectTimer);
    connectResolve();
  }

  function onError() {
    fail('CDP_SOCKET_ERROR');
  }

  function onClose() {
    if (state !== 'closing' && state !== 'closed') {
      failureCode ??= 'CDP_CLOSED';
      connectReject(error(failureCode));
      rejectPending(failureCode);
    }
    finishClose();
  }

  function dispatchEvent(message) {
    const callbacks = listeners.get(message.method);
    if (callbacks === undefined)
      return;
    for (const callback of [...callbacks]) {
      if (state !== 'open')
        return;
      if (pendingEventCallbacks >= maxPendingEventCallbacks) {
        fail('CDP_EVENT_LIMIT');
        return;
      }
      pendingEventCallbacks += 1;
      try {
        Promise.resolve(callback(message.params ?? {}, message.sessionId))
          .catch(() => fail('CDP_EVENT_HANDLER_FAILED'))
          .finally(() => { pendingEventCallbacks -= 1; });
      } catch {
        pendingEventCallbacks -= 1;
        fail('CDP_EVENT_HANDLER_FAILED');
        return;
      }
    }
  }

  function onMessage(event) {
    if (state !== 'open')
      return;
    if (typeof event.data !== 'string') {
      fail('CDP_INVALID_MESSAGE');
      return;
    }
    const bytes = Buffer.byteLength(event.data, 'utf8');
    receivedBytes += bytes;
    if (bytes > maxMessageBytes || receivedBytes > maxAggregateBytes) {
      fail('CDP_MESSAGE_LIMIT');
      return;
    }
    let message;
    try {
      message = JSON.parse(event.data);
    } catch {
      fail('CDP_INVALID_MESSAGE');
      return;
    }
    if (!record(message) || !validSession(message.sessionId)) {
      fail('CDP_INVALID_MESSAGE');
      return;
    }
    if (Object.hasOwn(message, 'id')) {
      const command = pending.get(message.id);
      if (!Number.isSafeInteger(message.id) || command === undefined
          || message.sessionId !== command.sessionId
          || Object.hasOwn(message, 'method')
          || (Object.hasOwn(message, 'result') === Object.hasOwn(message, 'error'))
          || (Object.hasOwn(message, 'result') && !record(message.result))
          || (Object.hasOwn(message, 'error') && (!record(message.error)
            || !Number.isInteger(message.error.code) || typeof message.error.message !== 'string'))) {
        fail('CDP_INVALID_MESSAGE');
        return;
      }
      if (Object.hasOwn(message, 'error')) {
        fail('CDP_COMMAND_ERROR');
        return;
      }
      pending.delete(message.id);
      clearTimeout(command.timer);
      command.resolve(message.result);
      return;
    }
    if (!validMethod(message.method)
        || (Object.hasOwn(message, 'params') && !record(message.params))
        || Object.hasOwn(message, 'result') || Object.hasOwn(message, 'error')) {
      fail('CDP_INVALID_MESSAGE');
      return;
    }
    dispatchEvent(message);
  }

  function send(method, params = {}, sessionId) {
    if (state !== 'open')
      return Promise.reject(error(failureCode ?? 'CDP_CLOSED'));
    if (!validMethod(method) || !record(params) || !validSession(sessionId))
      return Promise.reject(error('CDP_INVALID_COMMAND'));
    if (pending.size >= maxPendingCommands || nextId > 1000000) {
      fail('CDP_COMMAND_LIMIT');
      return Promise.reject(error(failureCode));
    }
    const id = nextId++;
    let payload;
    try {
      payload = JSON.stringify({ id, method, params, ...(sessionId === undefined ? {} : { sessionId }) });
      if (!record(JSON.parse(payload).params))
        return Promise.reject(error('CDP_INVALID_COMMAND'));
    } catch {
      return Promise.reject(error('CDP_INVALID_COMMAND'));
    }
    const bytes = Buffer.byteLength(payload, 'utf8');
    if (bytes > maxMessageBytes || sentBytes + bytes > maxAggregateBytes
        || (typeof socket.bufferedAmount === 'number' && socket.bufferedAmount + bytes > maxMessageBytes)) {
      fail('CDP_MESSAGE_LIMIT');
      return Promise.reject(error(failureCode));
    }
    sentBytes += bytes;
    const completion = new Promise((resolve, reject) => {
      pending.set(id, {
        resolve,
        reject,
        sessionId,
        timer: setTimeout(() => fail('CDP_COMMAND_TIMEOUT'), timeoutMs),
      });
    });
    // Caller-controlled scheduling must not turn eager socket failure into an
    // unhandled rejection before the caller can await its command promise.
    void completion.catch(() => {});
    try {
      socket.send(payload);
    } catch {
      fail('CDP_SEND_FAILED');
    }
    return completion;
  }

  function on(method, listener) {
    if (state !== 'open')
      throw error(failureCode ?? 'CDP_CLOSED');
    if (!validMethod(method) || typeof listener !== 'function')
      throw error('CDP_INVALID_LISTENER');
    if (listenerCount >= maxEventListeners)
      throw error('CDP_LISTENER_LIMIT');
    const callbacks = listeners.get(method) ?? new Set();
    listeners.set(method, callbacks);
    if (!callbacks.has(listener)) {
      callbacks.add(listener);
      listenerCount += 1;
    }
    return () => {
      if (listeners.get(method) !== callbacks)
        return;
      if (callbacks.delete(listener)) {
        listenerCount -= 1;
        if (callbacks.size === 0)
          listeners.delete(method);
      }
    };
  }

  socket.addEventListener('open', onOpen);
  socket.addEventListener('message', onMessage);
  socket.addEventListener('error', onError);
  socket.addEventListener('close', onClose);
  connectTimer = setTimeout(() => fail('CDP_CONNECT_TIMEOUT'), timeoutMs);
  try {
    await connected;
  } catch {
    await close().catch(() => {});
    throw error(failureCode ?? 'CDP_CONNECT_FAILED');
  }
  return { send, on, close, failure: () => failureCode };
}
