#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  chmodSync,
  cpSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readlinkSync,
  readdirSync,
  renameSync,
  rmSync,
  symlinkSync,
  utimesSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  BASELINE_PATH,
  canonicalJson,
  deriveTransportBaselineAtRoot,
  summaryForDependencies,
  verifyTransportDependenciesAtRoot,
} from './verify-mcp-transport-dependencies.mjs';

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const verifierPath = resolve(projectRoot,
  'scripts/verify-mcp-transport-dependencies.mjs');
const temporaryRoot = mkdtempSync(join(tmpdir(),
  'soklet-mcp-transport-dependencies-self-test-'));
const goldenRoot = resolve(temporaryRoot, 'golden');
const EXPECTED_CASE_COUNT = 80;
let passedCases = 0;

const FIXTURE_SOURCES = Object.freeze([
  'src/main/java/com/soklet/DefaultMcpServer.java',
  'src/main/java/com/soklet/McpServer.java',
  'src/main/java/com/soklet/Soklet.java',
  'src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java',
  'src/main/java/com/soklet/internal/mcp/protocol/McpApplicationRequestRouter.java',
  'src/main/java/com/soklet/internal/mcp/protocol/McpRequestSseStream.java',
  'src/main/java/com/soklet/internal/mcp/protocol/McpServerRuntimeBridge.java',
  'src/main/java/com/soklet/internal/mcp/schema/McpSchemaEvaluationLimits.java',
  'src/main/java/com/soklet/internal/mcp/transport/McpOutboundChannel.java',
  'src/main/java/com/soklet/internal/microhttp/ConnectionEventLoop.java',
  'src/main/java/com/soklet/internal/microhttp/WritableSource.java',
]);

const RUNTIME =
  'src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java';
const REQUEST_STREAM =
  'src/main/java/com/soklet/internal/mcp/protocol/McpRequestSseStream.java';
const ROUTER =
  'src/main/java/com/soklet/internal/mcp/protocol/McpApplicationRequestRouter.java';
const BRIDGE =
  'src/main/java/com/soklet/internal/mcp/protocol/McpServerRuntimeBridge.java';
const EVENT_LOOP =
  'src/main/java/com/soklet/internal/microhttp/ConnectionEventLoop.java';

function write(root, path, value) {
  const absolute = resolve(root, path);
  mkdirSync(dirname(absolute), { recursive: true });
  writeFileSync(absolute, value);
}

function createGoldenFixture() {
  mkdirSync(goldenRoot, { recursive: true });
  for (const path of FIXTURE_SOURCES) {
    const destination = resolve(goldenRoot, path);
    mkdirSync(dirname(destination), { recursive: true });
    cpSync(resolve(projectRoot, path), destination);
  }
  mkdirSync(resolve(goldenRoot, 'src/main/java/com/soklet/internal/mcp/empty'),
    { recursive: true });
  write(goldenRoot, BASELINE_PATH,
    canonicalJson(deriveTransportBaselineAtRoot(goldenRoot)));
  write(goldenRoot, 'target/mcp-api-freezes/sentinel',
    'signature directory must remain untouched\n');
  write(goldenRoot, 'target/mcp-api-freezes/nested/signature.txt',
    'nested signature bytes must remain untouched\n');
}

function cloneFixture(label) {
  const safe = label.replaceAll(/[^A-Za-z0-9.-]+/gu, '-');
  const root = resolve(temporaryRoot, `case-${passedCases}-${safe}`);
  cpSync(goldenRoot, root, { recursive: true });
  return root;
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function snapshotTree(root) {
  const rows = [];
  function visit(path) {
    const entry = lstatSync(path, { bigint: true });
    const name = relative(root, path).split(sep).join('/') || '.';
    const metadata = {
      ctimeNs: entry.ctimeNs.toString(),
      mode: Number(entry.mode),
      mtimeNs: entry.mtimeNs.toString(),
      name,
    };
    if (entry.isSymbolicLink()) {
      rows.push({ ...metadata, target: readlinkSync(path), type: 'symlink' });
      return;
    }
    if (entry.isDirectory()) {
      rows.push({ ...metadata, type: 'directory' });
      for (const child of readdirSync(path).sort()) {
        visit(resolve(path, child));
      }
      return;
    }
    rows.push({
      ...metadata,
      hash: sha256(readFileSync(path)),
      size: entry.size.toString(),
      type: 'file',
    });
  }
  visit(root);
  return rows;
}

function readJson(root) {
  return JSON.parse(readFileSync(resolve(root, BASELINE_PATH), 'utf8'));
}

function sortRows(rows) {
  rows.sort((left, right) => Buffer.compare(
    Buffer.from(`${left.file}\u0000${left.type}`, 'ascii'),
    Buffer.from(`${right.file}\u0000${right.type}`, 'ascii'),
  ));
}

function refreshSummary(baseline) {
  baseline.summary = summaryForDependencies(
    baseline.directMicrohttpDependencies,
    baseline.directSocketEventLoopDependencies,
  );
}

function mutateBaseline(root, mutator) {
  const baseline = readJson(root);
  mutator(baseline);
  write(root, BASELINE_PATH, canonicalJson(baseline));
}

function mutateSource(root, path, mutator) {
  const absolute = resolve(root, path);
  write(root, path, mutator(readFileSync(absolute, 'utf8')));
}

function addImport(source, type, spelling = `import ${type};`) {
  return source.replace(/^(package\s+[^;]+;)$/mu, `$1\n\n${spelling}`);
}

function appendType(source, body) {
  return `${source}\n${body}\n`;
}

function replaceAfter(source, marker, from, to) {
  const markerIndex = source.indexOf(marker);
  assert.notEqual(markerIndex, -1, `missing mutation marker ${marker}`);
  const fromIndex = source.indexOf(from, markerIndex);
  assert.notEqual(fromIndex, -1, `missing mutation text after ${marker}`);
  return source.slice(0, fromIndex) + to + source.slice(fromIndex + from.length);
}

function runCase(label, body) {
  body();
  passedCases++;
  process.stdout.write(`PASS ${label}\n`);
}

function expectRejected(label, mutate, pattern) {
  runCase(label, () => {
    const root = cloneFixture(label);
    mutate(root);
    assert.throws(() => verifyTransportDependenciesAtRoot(root), pattern, label);
  });
}

function expectAccepted(label, mutate) {
  runCase(label, () => {
    const root = cloneFixture(label);
    mutate(root);
    verifyTransportDependenciesAtRoot(root);
  });
}

try {
  createGoldenFixture();

  runCase('production baseline matches the final candidate tree', () => {
    const result = verifyTransportDependenciesAtRoot(projectRoot);
    const baseline = readJson(projectRoot);
    assert.deepEqual(result.directMicrohttp, baseline.summary.directMicrohttp);
    assert.deepEqual(result.directSocketEventLoop,
      baseline.summary.directSocketEventLoop);
    assert.deepEqual(result.directMicrohttp,
      { fileCount: 7, pairCount: 25, typeCount: 12 });
    assert.deepEqual(result.directSocketEventLoop,
      { fileCount: 6, pairCount: 7, typeCount: 3 });
    assert.equal(result.characterizationCount, 5);
  });

  runCase('positive fixture is deterministic and wholly read-only', () => {
    const fixtureBefore = snapshotTree(goldenRoot);
    const freezesRoot = resolve(goldenRoot, 'target/mcp-api-freezes');
    const freezesBefore = snapshotTree(freezesRoot);
    const firstDerivation = canonicalJson(
      deriveTransportBaselineAtRoot(goldenRoot));
    const secondDerivation = canonicalJson(
      deriveTransportBaselineAtRoot(goldenRoot));
    assert.equal(firstDerivation, secondDerivation);
    assert.equal(firstDerivation,
      readFileSync(resolve(goldenRoot, BASELINE_PATH), 'utf8'));
    assert.equal(verifyTransportDependenciesAtRoot(goldenRoot)
      .characterizationCount, 5);
    assert.deepEqual(snapshotTree(goldenRoot), fixtureBefore);
    assert.deepEqual(snapshotTree(freezesRoot), freezesBefore);
  });

  runCase('read-only snapshot detects content-identical and permission mutations',
    () => {
      const root = cloneFixture('read-only-snapshot-metadata');
      const path = resolve(root, BRIDGE);
      const beforeRewrite = snapshotTree(root);
      const bytes = readFileSync(path);
      writeFileSync(path, bytes);
      utimesSync(path, new Date(1_000), new Date(2_000));
      assert.notDeepEqual(snapshotTree(root), beforeRewrite);

      const beforePermissionChange = snapshotTree(root);
      const permissions = lstatSync(path).mode & 0o777;
      chmodSync(path, permissions ^ 0o100);
      assert.notDeepEqual(snapshotTree(root), beforePermissionChange);
    });

  expectRejected('omitted Microhttp pair', (root) => {
    mutateBaseline(root, (baseline) => {
      baseline.directMicrohttpDependencies.pop();
      refreshSummary(baseline);
    });
  }, /Microhttp dependency baseline differs from source/u);

  expectRejected('extra Microhttp pair', (root) => {
    mutateBaseline(root, (baseline) => {
      baseline.directMicrohttpDependencies.push({
        file: BRIDGE,
        type: 'com.soklet.internal.microhttp.UninventoriedTransportType',
      });
      sortRows(baseline.directMicrohttpDependencies);
      refreshSummary(baseline);
    });
  }, /Microhttp dependency baseline differs from source/u);

  expectRejected('duplicate dependency pair', (root) => {
    mutateBaseline(root, (baseline) => {
      baseline.directMicrohttpDependencies.splice(1, 0,
        { ...baseline.directMicrohttpDependencies[0] });
      refreshSummary(baseline);
    });
  }, /unique and strictly ASCII-sorted/u);

  expectRejected('malformed dependency row', (root) => {
    mutateBaseline(root, (baseline) => {
      baseline.directMicrohttpDependencies[0].type = 42;
    });
  }, /type outside its dependency family/u);

  expectRejected('stale derived summary', (root) => {
    mutateBaseline(root, (baseline) => {
      baseline.summary.directMicrohttp.pairCount++;
    });
  }, /summary is not derived/u);

  expectRejected('changed derivation rule', (root) => {
    mutateBaseline(root, (baseline) => {
      baseline.derivation.productionScope.recursiveInternalDirectory =
        'src/main/java';
    });
  }, /derivation rules changed/u);

  expectRejected('new dependency in DefaultMcpServer is in scope', (root) => {
    mutateSource(root, 'src/main/java/com/soklet/DefaultMcpServer.java',
      (source) => addImport(source,
        'com.soklet.internal.microhttp.UninventoriedDefaultDependency'));
  }, /Microhttp dependency baseline differs from source/u);

  expectRejected('new dependency in Soklet is in scope', (root) => {
    mutateSource(root, 'src/main/java/com/soklet/Soklet.java',
      (source) => addImport(source,
        'com.soklet.internal.microhttp.UninventoriedSokletDependency'));
  }, /Microhttp dependency baseline differs from source/u);

  expectRejected('new nested MCP source dependency', (root) => {
    write(root, 'src/main/java/com/soklet/internal/mcp/deeper/McpInjected.java',
      'package com.soklet.internal.mcp.deeper;\n\n'
      + 'import com.soklet.internal.microhttp.UninventoriedTransportType;\n');
  }, /Microhttp dependency baseline differs from source/u);

  expectRejected('fully qualified Microhttp use is inventoried', (root) => {
    mutateSource(root, BRIDGE, (source) => appendType(source,
      'final class AuditFqnUse { com.soklet.internal.microhttp.Header value; }'));
  }, /Microhttp dependency baseline differs from source/u);

  expectRejected('lowercase fully qualified Microhttp type is inventoried',
    (root) => {
      mutateSource(root, BRIDGE, (source) => appendType(source,
        'final class AuditLowercaseFqnUse { '
        + 'com.soklet.internal.microhttp.lowercaseDependency value; }'));
    }, /Microhttp dependency baseline differs from source/u);

  expectRejected('comment-split fully qualified Microhttp use is inventoried',
    (root) => {
      mutateSource(root, BRIDGE, (source) => appendType(source,
        'final class AuditSplitFqnUse { '
        + 'com.soklet.internal.microhttp./* split */Header value; }'));
    }, /Microhttp dependency baseline differs from source/u);

  expectRejected('fully qualified JDK network primitive is inventoried',
    (root) => {
      mutateSource(root, BRIDGE, (source) => appendType(source,
        'final class AuditNetworkFqnUse { java.nio.channels.Selector value; }'));
    }, /Socket\/event-loop dependency baseline differs from source/u);

  expectRejected('fully qualified JDK network factory call is inventoried',
    (root) => {
      mutateSource(root, BRIDGE, (source) => appendType(source,
        'final class AuditNetworkFactoryFqnUse { Object open() throws Exception {'
        + ' return java.nio.channels.SocketChannel.open(); } }'));
    }, /Socket\/event-loop dependency baseline differs from source/u);

  expectAccepted('network exact-type identifier prefix lookalike is ignored',
    (root) => {
      mutateSource(root, BRIDGE, (source) => appendType(source,
        'final class AuditNetworkPrefixLookalike { '
        + 'java.nio.channels.SocketChannelFactory value; }'));
    });

  expectRejected('fully qualified named event-loop family is inventoried',
    (root) => {
      mutateSource(root, BRIDGE, (source) => appendType(source,
        'final class AuditNettyFqnUse { io.netty.channel.EventLoop value; }'));
    }, /Socket\/event-loop dependency baseline differs from source/u);

  expectRejected('lowercase fully qualified named event-loop type is inventoried',
    (root) => {
      mutateSource(root, BRIDGE, (source) => appendType(source,
        'final class AuditLowercaseNettyFqnUse { '
        + 'io.netty.channel.eventLoop value; }'));
    }, /Socket\/event-loop dependency baseline differs from source/u);

  expectRejected('comment-injected reviewed import is normalized and inventoried', (root) => {
    mutateSource(root, REQUEST_STREAM, (source) => addImport(source,
      'com.soklet.internal.microhttp.CommentSplitDependency',
      'import com.soklet.internal.microhttp./* split */CommentSplitDependency;'));
  }, /Microhttp dependency baseline differs from source/u);

  expectRejected('line-split reviewed import is normalized and inventoried', (root) => {
    mutateSource(root, REQUEST_STREAM, (source) => addImport(source,
      'com.soklet.internal.microhttp.LineSplitDependency',
      'import com.soklet.internal.microhttp.\nLineSplitDependency;'));
  }, /Microhttp dependency baseline differs from source/u);

  expectRejected('Unicode escape import is translated before scanning',
    (root) => {
      mutateSource(root, BRIDGE, (source) => addImport(source,
        'com.soklet.internal.microhttp.UnicodeDependency',
        'im\\u0070ort com.soklet.internal.microhttp.UnicodeDependency;'));
    }, /Microhttp dependency baseline differs from source/u);

  expectRejected('Unicode newline cannot hide import behind line comment',
    (root) => {
      mutateSource(root, BRIDGE, (source) => source.replace(
        /^(package\s+[^;]+;)$/mu,
        '$1\n// comment ends here \\u000aimport com.soklet.internal.microhttp.UnicodeCommentEscape;'));
    }, /Microhttp dependency baseline differs from source/u);

  expectRejected('Microhttp wildcard import', (root) => {
    mutateSource(root, REQUEST_STREAM, (source) => source.replace(
      'import com.soklet.internal.microhttp.Header;',
      'import com.soklet.internal.microhttp.*;'));
  }, /explicit non-static imports/u);

  expectRejected('Microhttp static import', (root) => {
    mutateSource(root, REQUEST_STREAM, (source) => source.replace(
      'import com.soklet.internal.microhttp.Header;',
      'import static com.soklet.internal.microhttp.Header.fixture;'));
  }, /explicit non-static imports/u);

  expectRejected('network-family wildcard import', (root) => {
    mutateSource(root, BRIDGE, (source) => addImport(source,
      'java.nio.channels.*'));
  }, /explicit non-static imports/u);

  expectRejected('new direct selector dependency', (root) => {
    mutateSource(root, BRIDGE,
      (source) => addImport(source, 'java.nio.channels.Selector'));
  }, /Socket\/event-loop dependency baseline differs from source/u);

  expectRejected('named third-party network family import', (root) => {
    mutateSource(root, BRIDGE,
      (source) => addImport(source, 'org.xnio.XnioWorker'));
  }, /Socket\/event-loop dependency baseline differs from source/u);

  expectAccepted('FileChannel is explicitly not a network primitive', (root) => {
    mutateSource(root, BRIDGE,
      (source) => addImport(source, 'java.nio.channels.FileChannel'));
  });

  expectAccepted('WritableSource socket-floor parameter names are not semantic',
    (root) => {
      mutateSource(root,
        'src/main/java/com/soklet/internal/microhttp/WritableSource.java',
        (source) => source.replace(
          'long writeTo(SocketChannel socketChannel, long maxBytes) throws IOException;',
          'long writeTo(SocketChannel channel, long limit) throws IOException;'));
    });

  expectAccepted('WritableSourceFacade parameter names are not semantic',
    (root) => {
      mutateSource(root,
        'src/main/java/com/soklet/internal/mcp/transport/McpOutboundChannel.java',
        (source) => {
          const marker = 'public long writeTo(@NonNull SocketChannel socketChannel,';
          let mutated = replaceAfter(source, marker,
            'SocketChannel socketChannel', 'SocketChannel channel');
          mutated = replaceAfter(mutated,
            'public long writeTo(@NonNull SocketChannel channel,',
            'long maximumBytes', 'long limit');
          return replaceAfter(mutated,
            'public long writeTo(@NonNull SocketChannel channel,',
            'writeTo(socketChannel, maximumBytes)', 'writeTo(channel, limit)');
        });
    });

  expectRejected('WritableSourceFacade must return the outbound write result',
    (root) => {
      mutateSource(root,
        'src/main/java/com/soklet/internal/mcp/transport/McpOutboundChannel.java',
        (source) => replaceAfter(source,
          'public long writeTo(@NonNull SocketChannel socketChannel,',
          'return McpOutboundChannel.this.writeTo(socketChannel, maximumBytes);',
          'McpOutboundChannel.this.writeTo(socketChannel, maximumBytes);\n'
          + '\t\t\treturn 0L;'));
    }, /must return the exact McpOutboundChannel write result/u);

  expectRejected('reviewed EventLoop simple-name fallback is inventoried',
    (root) => {
      mutateSource(root, BRIDGE,
        (source) => addImport(source, 'com.example.EventLoop'));
    }, /Socket\/event-loop dependency baseline differs from source/u);

  expectRejected('reviewed EventLoopGroup simple-name fallback is inventoried',
    (root) => {
      mutateSource(root, BRIDGE,
        (source) => addImport(source, 'com.example.EventLoopGroup'));
    }, /Socket\/event-loop dependency baseline differs from source/u);

  expectAccepted('comments strings and hardened text blocks are dependency decoys',
    (root) => {
      mutateSource(root, BRIDGE, (source) => appendType(source, [
        'final class DependencyDecoys {',
        '  void inspect() {',
        '    // import com.soklet.internal.microhttp.CommentOnly;',
        '    /* java.nio.channels.Selector commentOnly; */',
        '    String ordinary = "com.soklet.internal.microhttp.StringOnly";',
        '    String block = """',
        '      import io.netty.channel.EventLoop;',
        '      \\""" still inside the text block',
        '      com.soklet.internal.microhttp.TextBlockOnly',
        '      """;',
        '  }',
        '}',
      ].join('\n')));
    });

  expectRejected('SessionStore type declaration is future storage state',
    (root) => {
      mutateSource(root, BRIDGE,
        (source) => appendType(source, 'final class SessionStore {}'));
    }, /state\/storage type declaration/u);

  expectRejected('sessionState field declaration is future storage state',
    (root) => {
      mutateSource(root, BRIDGE, (source) => source.replace(
        'public final class McpServerRuntimeBridge {',
        'public final class McpServerRuntimeBridge {\n\tprivate Object sessionState;'));
    }, /state\/storage field declaration/u);

  expectRejected('annotated sessionState field cannot evade field parsing',
    (root) => {
      mutateSource(root, BRIDGE, (source) => source.replace(
        'public final class McpServerRuntimeBridge {',
        'public final class McpServerRuntimeBridge {\n'
        + '\t@SuppressWarnings("unused")\n\tprivate Object sessionState;'));
    }, /state\/storage field declaration/u);

  expectRejected('single-domain sessions field is future storage state',
    (root) => {
      mutateSource(root, BRIDGE, (source) => source.replace(
        'public final class McpServerRuntimeBridge {',
        'public final class McpServerRuntimeBridge {\n\tprivate Object sessions;'));
    }, /state\/storage field declaration/u);

  expectRejected('lower-camel replayBuffer field is future storage state',
    (root) => {
      mutateSource(root, BRIDGE, (source) => source.replace(
        'public final class McpServerRuntimeBridge {',
        'public final class McpServerRuntimeBridge {\n\tprivate Object replayBuffer;'));
    }, /state\/storage field declaration/u);

  expectRejected('cross-domain taskSessions field is future storage state',
    (root) => {
      mutateSource(root, BRIDGE, (source) => source.replace(
        'public final class McpServerRuntimeBridge {',
        'public final class McpServerRuntimeBridge {\n\tprivate Object taskSessions;'));
    }, /state\/storage field declaration/u);

  expectRejected('cross-domain TaskSession record is future storage state',
    (root) => {
      mutateSource(root, BRIDGE, (source) => appendType(source,
        'record TaskSession(String id) {}'));
    }, /state\/storage type declaration/u);

  expectRejected('single-domain data-bearing Session record is future state',
    (root) => {
      mutateSource(root, BRIDGE, (source) => appendType(source,
        'record Session(String id) {}'));
    }, /Data-bearing future-domain record declaration/u);

  expectRejected('single-domain data-bearing Session class is future state',
    (root) => {
      mutateSource(root, BRIDGE, (source) => appendType(source,
        'final class Session { private final String id = "future-session"; }'));
    }, /Data-bearing future-domain class declaration/u);

  expectAccepted('McpTask capability and control declarations are harmless',
    (root) => {
      mutateSource(root, BRIDGE, (source) => appendType(source,
        'final class McpTaskCapability {}\nfinal class McpTaskControl {}'));
    });

  expectRejected('progress and subscriptions cannot split outbound channels',
    (root) => {
      mutateSource(root, REQUEST_STREAM, (source) => source
        .replace('private final McpOutboundChannel delegate;',
          'private final McpOutboundChannel delegate;\n'
          + '\t\tprivate final McpOutboundChannel subscriptionDelegate;')
        .replace(
          'requireNonNull(clock)::nanoTime, requireNonNull(listener));\n\t\t}',
          'requireNonNull(clock)::nanoTime, requireNonNull(listener));\n'
          + '\t\t\tthis.subscriptionDelegate = new McpOutboundChannel('
          + 'frameCapacity, maximumFrameBytes, maximumFrameBytes,\n'
          + '\t\t\t\t\trequireNonNull(clock)::nanoTime, '
          + 'requireNonNull(listener));\n\t\t}')
        .replace('return this.delegate.offerCoalescing(',
          'return this.subscriptionDelegate.offerCoalescing('));
    }, /exactly one direct McpOutboundChannel field/u);

  expectRejected('request-stream application enqueue cannot become a no-op',
    (root) => {
      mutateSource(root, REQUEST_STREAM, (source) => source.replace(
        'channel.enqueue(frame(requireNonNull(message)));',
        'requireNonNull(message);'));
    }, /enqueueMessage must use its installed channel/u);

  expectRejected('request-stream subscription offer cannot bypass its channel',
    (root) => {
      mutateSource(root, REQUEST_STREAM, (source) => source.replace(
        'return channel.offerCoalescing(frame(requireNonNull(message)),\n'
        + '\t\t\t\trequireNonNull(coalescingKey));',
        'requireNonNull(message);\n\t\trequireNonNull(coalescingKey);\n'
        + '\t\treturn McpOutboundChannel.OfferResult.ACCEPTED;'));
    }, /offerCoalescingMessage must use its installed channel/u);

  expectRejected('application notifications must use the installed response stream',
    (root) => {
      mutateSource(root, RUNTIME, (source) => replaceAfter(source,
        'private boolean writeApplicationNotification(',
        'stream = responseStream;', 'stream = newResponseStream();'));
    }, /source both observed stream reads from responseStream/u);

  expectRejected('subscription events must use the installed response stream',
    (root) => {
      mutateSource(root, RUNTIME, (source) => replaceAfter(source,
        'private void offerSubscriptionEvent(',
        'stream = responseStream;', 'stream = newResponseStream();'));
    }, /offerSubscriptionEvent must use the installed responseStream/u);

  expectRejected('runtime notification writer must retain RequestControl routing',
    (root) => {
      mutateSource(root, RUNTIME, (source) => replaceAfter(source,
        'public boolean writeNotification(',
        'return requestControl.writeApplicationNotification(\n'
        + '\t\t\t\t\t\t\t\tnotification, corsHeaders);',
        'return false;'));
    }, /runtime notification writer must route through RequestControl/u);

  expectRejected('application invocation must retain its notification slot',
    (root) => {
      mutateSource(root, ROUTER, (source) => replaceAfter(source,
        'new McpApplicationInvocation(',
        'this::writeNotification, this::requirePublicHandlerEntry,',
        'ignored -> false, this::requirePublicHandlerEntry,'));
    }, /application invocation notification slot must bind/u);

  expectRejected('live MCP streaming-monitor opt-in cannot be a comment decoy',
    (root) => {
      mutateSource(root, RUNTIME, (source) => replaceAfter(source,
        'monitorClientDisconnectsDuringStreamingResponse(',
        'return true;',
        '// return true;\n\t\t\t\t\treturn false;'));
    }, /live MCP handler must opt in/u);

  expectRejected('prepare path must invoke the streaming-monitor opt-in',
    (root) => {
      mutateSource(root, EVENT_LOOP, (source) => replaceAfter(source,
        'private void prepareToWriteResponse(',
        'handler.monitorClientDisconnectsDuringStreamingResponse(dispatch.request)',
        'false /* handler.monitorClientDisconnectsDuringStreamingResponse(dispatch.request) */'));
    }, /must call the handler streaming-monitor opt-in/u);

  expectRejected('prepare path cannot reset the installed monitor decision',
    (root) => {
      mutateSource(root, EVENT_LOOP, (source) => replaceAfter(source,
        'private void prepareToWriteResponse(',
        'monitorClientDisconnectsDuringStreamingResponse = monitorStreamingResponse;',
        'monitorClientDisconnectsDuringStreamingResponse = monitorStreamingResponse;\n'
        + '                monitorClientDisconnectsDuringStreamingResponse = false;'));
    }, /install the streaming-monitor decision exactly once without resetting/u);

  expectRejected('live readable dispatcher cannot bypass streaming discard',
    (root) => {
      mutateSource(root, EVENT_LOOP, (source) => source.replace(
        '            if (monitorClientDisconnectsDuringStreamingResponse && writableSource != null) {\n'
        + '                doOnReadableDuringStreamingResponse();\n'
        + '                return;\n'
        + '            }\n\n',
        ''));
    }, /live readable dispatcher must route committed monitored streams/u);

  expectRejected('discard accounting cannot be restored by a comment decoy',
    (root) => {
      mutateSource(root, EVENT_LOOP, (source) => replaceAfter(source,
        'private void doOnReadableDuringStreamingResponse(',
        'streamingResponseBytesDiscarded += numBytes;',
        'streamingResponseBytesDiscarded = numBytes; // streamingResponseBytesDiscarded += numBytes;'));
    }, /discarded bytes must be counted/u);

  expectRejected('discard close behavior cannot be restored by a comment decoy',
    (root) => {
      mutateSource(root, EVENT_LOOP, (source) => replaceAfter(source,
        'private void doOnReadableDuringStreamingResponse(',
        'closeAfterResponse = true;',
        'closeAfterResponse = false; // closeAfterResponse = true;'));
    }, /only closeAfterResponse assignment forces true/u);

  expectRejected('discard close behavior cannot be reset after its anchor',
    (root) => {
      mutateSource(root, EVENT_LOOP, (source) => replaceAfter(source,
        'private void doOnReadableDuringStreamingResponse(',
        'closeAfterResponse = true;',
        'closeAfterResponse = true;\n            closeAfterResponse = false;'));
    }, /only closeAfterResponse assignment forces true/u);

  expectRejected('response completion must consume closeAfterResponse by closing',
    (root) => {
      mutateSource(root, EVENT_LOOP, (source) => replaceAfter(source,
        'if (closeAfterResponse) { // non-persistent connection, close now',
        'failSafeClose();', 'disableReadInterest();'));
    }, /response-completion path must close/u);

  expectRejected('streaming input cannot reenter ByteTokenizer', (root) => {
    mutateSource(root, EVENT_LOOP, (source) => replaceAfter(source,
      'private void doOnReadableDuringStreamingResponse(',
      'streamingResponseBytesDiscarded += numBytes;',
      'streamingResponseBytesDiscarded += numBytes;\n            byteTokenizer.add(buffer);'));
  }, /must bypass ByteTokenizer/u);

  expectRejected('live Microhttp handler must submit admitted MCP requests',
    (root) => {
      mutateSource(root, RUNTIME, (source) => replaceAfter(source,
        'Handler handler = new Handler() {',
        'submitRequest(readyProcessor, readyApplicationExecution,\n'
        + '\t\t\t\t\t\t\t\tcandidateAddress.get(), request,\n'
        + '\t\t\t\t\t\t\t\ttrackedLifecycleAdmission, callback);',
        'callback.accept(emptyResponse(200, "OK", List.of()));'));
    }, /live Microhttp handler must submit each admitted request/u);

  expectRejected('network submit overload must retain contextual routing',
    (root) => {
      mutateSource(root, RUNTIME, (source) => replaceAfter(source,
        'private void submitRequest(',
        'submitRequest(processor, application, effectiveAddress, request, null,\n'
        + '\t\t\t\tnull, lifecycleAdmission, callback);',
        'callback.accept(emptyResponse(200, "OK", List.of()));'));
    }, /network submitRequest overload must enter the contextual/u);

  expectRejected('contextual submit task must invoke processRequest',
    (root) => {
      mutateSource(root, RUNTIME, (source) => replaceAfter(source,
        'private RequestControl submitRequest(',
        'MicrohttpResponse response = processRequest(requiredAddress, request,\n'
        + '\t\t\t\t\trequestControl, application);',
        'MicrohttpResponse response = emptyResponse(200, "OK", List.of());'));
    }, /contextual request-control task must invoke/u);

  expectRejected('processRequest cannot bypass the HTTP\/1.1-safe processor',
    (root) => {
      mutateSource(root, RUNTIME, (source) => replaceAfter(source,
        'private @Nullable MicrohttpResponse processRequest(',
        'MicrohttpResponse response = processRequestSafely(effectiveAddress, request,\n'
        + '\t\t\t\t\trequestControl, application);',
        'MicrohttpResponse response = emptyResponse(200, "OK", List.of());'));
    }, /live processRequest entry must route through processRequestSafely/u);

  expectRejected('dedicated MCP EventLoop must actually be started',
    (root) => {
      mutateSource(root, RUNTIME, (source) => source.replace(
        '\t\t\tcandidateEventLoop.start();\n', ''));
    }, /dedicated MCP EventLoop must be started exactly once/u);

  expectRejected('HTTP\/1.1 predicate must retain its exact 505 rejection',
    (root) => {
      mutateSource(root, RUNTIME, (source) => source.replace(
        'if (!"HTTP/1.1".equals(request.version()))\n'
        + '\t\t\treturn emptyResponse(505, "HTTP Version Not Supported", List.of());',
        'if (!"HTTP/1.1".equals(request.version()))\n\t\t\trequest.version();'));
    }, /exactly one HTTP-version read and use it to return the exact/u);

  expectRejected('legacy policy headers cannot be comment decoys', (root) => {
    mutateSource(root, RUNTIME, (source) => source.replace(
      '"mcp-session-id", "last-event-id"',
      '"other-session", "other-event" /* "mcp-session-id", "last-event-id" */'));
  }, /legacy session\/replay policy headers/u);

  expectRejected('legacy policy-header sentinel must remain in enforcement',
    (root) => {
      mutateSource(root, RUNTIME, (source) => source.replace(
        '\t\t\t\t\t|| FORBIDDEN_LEGACY_MCP_POLICY_HEADERS.contains(lowerName)\n',
        ''));
    }, /validated admission-policy headers must reject/u);

  expectRejected('noncanonical baseline bytes', (root) => {
    const path = resolve(root, BASELINE_PATH);
    write(root, BASELINE_PATH, `${readFileSync(path, 'utf8')}\n`);
  }, /not canonical two-space JSON/u);

  expectRejected('malformed baseline JSON', (root) => {
    write(root, BASELINE_PATH, '{ malformed\n');
  }, /not valid JSON/u);

  expectRejected('missing baseline file', (root) => {
    rmSync(resolve(root, BASELINE_PATH));
  }, /Missing transport dependency baseline/u);

  expectRejected('symlink inside recursive scan root', (root) => {
    symlinkSync('protocol/McpRequestSseStream.java', resolve(root,
      'src/main/java/com/soklet/internal/mcp/linked-source.java'));
  }, /must not contain symbolic links/u);

  runCase('candidate root itself must not be a symlink', () => {
    const link = resolve(temporaryRoot, 'candidate-root-link');
    symlinkSync(goldenRoot, link, 'dir');
    assert.throws(() => verifyTransportDependenciesAtRoot(link),
      /Candidate root must be a non-symlink directory/u);
  });

  expectRejected('baseline ancestor must not be a symlink', (root) => {
    renameSync(resolve(root, 'conformance'), resolve(root, 'conformance-real'));
    symlinkSync('conformance-real', resolve(root, 'conformance'), 'dir');
  }, /must not traverse a symbolic link/u);

  expectRejected('production source ancestor must not be a symlink', (root) => {
    renameSync(resolve(root, 'src'), resolve(root, 'src-real'));
    symlinkSync('src-real', resolve(root, 'src'), 'dir');
  }, /must not traverse a symbolic link/u);

  expectRejected('Microhttp characterization ancestor must not be a symlink',
    (root) => {
      const microhttp = resolve(root,
        'src/main/java/com/soklet/internal/microhttp');
      renameSync(microhttp, `${microhttp}-real`);
      symlinkSync('microhttp-real', microhttp, 'dir');
    }, /must not traverse a symbolic link/u);

  runCase('candidate verification ignores sibling workspace bytes', () => {
    const parent = resolve(temporaryRoot, 'sibling-blind');
    const core = resolve(parent, 'core');
    cpSync(goldenRoot, core, { recursive: true });
    write(parent, 'external/src/main/java/com/soklet/internal/mcp/Injected.java',
      'import com.soklet.internal.microhttp.SiblingOnly;\n');
    assert.equal(verifyTransportDependenciesAtRoot(core)
      .characterizationCount, 5);
  });

  runCase('candidate CLI rejects an external-root argument', () => {
    const result = spawnSync(process.execPath,
      [verifierPath, '--external-root', resolve(temporaryRoot, 'external')],
      { encoding: 'utf8' });
    assert.equal(result.status, 64);
    assert.match(result.stderr,
      /Usage: node scripts\/verify-mcp-transport-dependencies\.mjs/u);
  });

  assert.equal(passedCases, EXPECTED_CASE_COUNT,
    'transport dependency self-test case-count drift');
  process.stdout.write(
    `Verified MCP transport dependency verifier self-test (${passedCases} cases)\n`,
  );
} finally {
  rmSync(temporaryRoot, { force: true, recursive: true });
}
