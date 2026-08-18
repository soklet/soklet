#!/usr/bin/env node

import { writeFileSync } from 'node:fs';
import net from 'node:net';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

function fail(message) {
  throw new Error(message);
}

export async function createLoopbackPortReservation(outputPath) {
  if (typeof outputPath !== 'string' || outputPath === '')
    fail('Loopback port reservation output path must not be empty');

  const absoluteOutputPath = resolve(outputPath);
  const server = net.createServer({ pauseOnConnect: true }, (socket) => socket.destroy());

  await new Promise((resolveListening, rejectListening) => {
    const onError = (error) => rejectListening(error);
    server.once('error', onError);
    server.listen({ exclusive: true, host: '127.0.0.1', port: 0 }, () => {
      server.off('error', onError);
      resolveListening();
    });
  });

  const address = server.address();
  if (address === null || typeof address === 'string'
      || address.address !== '127.0.0.1'
      || !Number.isInteger(address.port) || address.port < 1 || address.port > 65535) {
    await new Promise((resolveClose) => server.close(resolveClose));
    fail('Unable to reserve an ephemeral IPv4 loopback port');
  }

  let closePromise;
  const close = () => {
    if (closePromise === undefined) {
      closePromise = new Promise((resolveClose, rejectClose) => {
        server.close((error) => {
          if (error === undefined)
            resolveClose();
          else
            rejectClose(error);
        });
      });
    }
    return closePromise;
  };

  try {
    writeFileSync(absoluteOutputPath, `${address.port}\n`, {
      encoding: 'utf8',
      flag: 'wx',
      mode: 0o600,
    });
  } catch (error) {
    await close();
    throw error;
  }

  return Object.freeze({ close, port: address.port });
}

async function main(args) {
  if (args.length !== 1) {
    console.error('Usage: node scripts/reserve-loopback-port.mjs <exclusive-port-output-file>');
    process.exitCode = 64;
    return;
  }

  const reservation = await createLoopbackPortReservation(args[0]);
  await new Promise((resolveShutdown, rejectShutdown) => {
    let stopping = false;
    const stop = () => {
      if (stopping)
        return;
      stopping = true;
      reservation.close().then(resolveShutdown, rejectShutdown);
    };
    process.once('SIGHUP', stop);
    process.once('SIGINT', stop);
    process.once('SIGTERM', stop);
  });
}

if (process.argv[1] !== undefined
    && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    await main(process.argv.slice(2));
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  }
}
