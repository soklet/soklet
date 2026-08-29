#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  copyFileSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readlinkSync,
  readdirSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import {
  canonicalJson,
  verifyCandidateRoot,
} from './verify-mcp-roadmap-readiness.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const temporary = mkdtempSync(join(tmpdir(), 'soklet-roadmap-readiness-'));
const fixture = join(temporary, 'candidate');
const MUTABLE_PATHS = [
  'conformance/soklet-4.0-planning-authority.json',
  'conformance/roadmap-readiness-deferred-features.json',
  'conformance/MCP_ROADMAP_READINESS_POLICY.md',
  'conformance/mcp-protocol-openness-inventory.json',
  'conformance/MCP_PROTOCOL_OPENNESS_INVENTORY_2026-07-28.md',
  'conformance/roadmap-readiness-active-text-rules.json',
  'conformance/MCP_ROADMAP_ACTIVE_TEXT_AUDIT.md',
  'MCP.md',
  'README.md',
  'SECURITY.md',
  'api/mcp/README.md',
  'CHANGELOG.md',
  'release/README.md',
  'src/main/java/com/soklet/DefaultMcpServer.java',
  'src/main/java/com/soklet/McpClientCapabilities.java',
];
const INTERNAL_SCAN_ROOT = 'src/main/java/com/soklet/internal/mcp';

function fixturePath(relativePath) {
  return join(fixture, relativePath);
}

function copyEntry(source, destination, label) {
  const status = lstatSync(source);
  if (status.isSymbolicLink())
    throw new Error(`Self-test copy source must not be a symlink: ${label}.`);
  if (status.isDirectory()) {
    mkdirSync(destination, { recursive: true });
    for (const entry of readdirSync(source).sort())
      copyEntry(join(source, entry), join(destination, entry), `${label}/${entry}`);
  } else if (status.isFile()) {
    mkdirSync(dirname(destination), { recursive: true });
    copyFileSync(source, destination);
  } else {
    throw new Error(`Self-test copy source must be a regular file or directory: ${label}.`);
  }
}

function copy(relativePath) {
  copyEntry(join(root, relativePath), fixturePath(relativePath), relativePath);
}

function readJson(relativePath) {
  return JSON.parse(readFileSync(fixturePath(relativePath), 'utf8'));
}

function writeJson(relativePath, value) {
  writeFileSync(fixturePath(relativePath), canonicalJson(value), 'utf8');
}

function mutateJson(relativePath, mutator) {
  const value = readJson(relativePath);
  mutator(value);
  writeJson(relativePath, value);
}

function write(relativePath, text) {
  const path = fixturePath(relativePath);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, text, 'utf8');
}

function snapshotTree(snapshotRoot) {
  const rows = [];
  function visit(path) {
    const entry = lstatSync(path, { bigint: true });
    const name = relative(snapshotRoot, path).split(sep).join('/') || '.';
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
      for (const child of readdirSync(path).sort()) visit(join(path, child));
      return;
    }
    rows.push({
      ...metadata,
      hash: createHash('sha256').update(readFileSync(path)).digest('hex'),
      size: entry.size.toString(),
      type: 'file',
    });
  }
  visit(snapshotRoot);
  return rows;
}

for (const relativePath of MUTABLE_PATHS) copy(relativePath);
copy(INTERNAL_SCAN_ROOT);
for (const entry of readdirSync(join(root, 'src/main/java/com/soklet'),
  { withFileTypes: true })) {
  if (entry.isFile() && /^Mcp.*\.java$/u.test(entry.name))
    copy(`src/main/java/com/soklet/${entry.name}`);
}
write('target/mcp-api-freezes/sentinel',
  'roadmap verifier must not write signature evidence\n');
write('target/mcp-api-freezes/nested/signature.txt',
  'nested signature bytes must remain untouched\n');

const originals = new Map(MUTABLE_PATHS.map((relativePath) =>
  [relativePath, readFileSync(fixturePath(relativePath))]));
const scratchJava =
  'src/main/java/com/soklet/internal/mcp/protocol/McpOpennessSelfTestFixture.java';

function restore() {
  for (const relativePath of ['conformance', INTERNAL_SCAN_ROOT]) {
    const path = fixturePath(relativePath);
    try {
      if (lstatSync(path).isSymbolicLink()) rmSync(path, { force: true });
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
    }
  }
  for (const [relativePath, bytes] of originals) {
    const path = fixturePath(relativePath);
    rmSync(path, { force: true, recursive: true });
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, bytes);
  }
  const internalPath = fixturePath(INTERNAL_SCAN_ROOT);
  let restoreInternal = false;
  try {
    restoreInternal = !lstatSync(internalPath).isDirectory();
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
    restoreInternal = true;
  }
  if (restoreInternal) {
    rmSync(internalPath, { force: true, recursive: true });
    copy(INTERNAL_SCAN_ROOT);
  }
  for (const relativePath of [scratchJava, 'escaped-active'])
    rmSync(fixturePath(relativePath), { force: true, recursive: true });
}

function expectRejected(label, mutator, pattern) {
  restore();
  mutator();
  assert.throws(() => verifyCandidateRoot(fixture), pattern, label);
}

try {
  const initialSnapshot = snapshotTree(fixture);
  const result = verifyCandidateRoot(fixture);
  assert.deepEqual(snapshotTree(fixture), initialSnapshot,
    'A clean roadmap verification must not mutate candidate or signature bytes/metadata.');
  assert.equal(result.negativeInventoryCount, 14);
  assert.equal(result.deferredFeatureCount, 15);
  assert.equal(result.opennessValidatorCount, 49);
  assert.equal(result.activeTextRuleCount, 22);

  expectRejected('planning-authority JSON must be canonical', () => {
    const value = readJson('conformance/soklet-4.0-planning-authority.json');
    write('conformance/soklet-4.0-planning-authority.json',
      `${JSON.stringify(value)}\n`);
  }, /not canonical/u);

  expectRejected('approved core identity drift must fail', () => {
    mutateJson('conformance/soklet-4.0-planning-authority.json', (value) => {
      value.postD2Core.tree = '0'.repeat(40);
    });
  }, /Post-D2 core identity/u);

  expectRejected('approved decision ordering drift must fail', () => {
    mutateJson('conformance/soklet-4.0-planning-authority.json', (value) => {
      value.approvedDecisionIds.reverse();
    });
  }, /Approved decision IDs/u);

  expectRejected('roadmap snapshot digest drift must fail', () => {
    mutateJson('conformance/roadmap-readiness-deferred-features.json', (value) => {
      value.planningAuthoritySnapshotSha256 = '0'.repeat(64);
    });
  }, /planningAuthoritySnapshotSha256/u);

  expectRejected('roadmap JSON and policy drift must fail', () => {
    mutateJson('conformance/roadmap-readiness-deferred-features.json', (value) => {
      value.deferredFeatures[0].direction += ' changed';
    });
  }, /policy is stale/u);

  expectRejected('roadmap Markdown drift must fail', () => {
    writeFileSync(fixturePath('conformance/MCP_ROADMAP_READINESS_POLICY.md'),
      '\nmanual drift\n', { flag: 'a' });
  }, /policy is stale/u);

  expectRejected('duplicate NI IDs must fail', () => {
    mutateJson('conformance/roadmap-readiness-deferred-features.json', (value) => {
      value.negativeInventory[1].id = value.negativeInventory[0].id;
    });
  }, /duplicate ID/u);

  expectRejected('malformed NI IDs must fail', () => {
    mutateJson('conformance/roadmap-readiness-deferred-features.json', (value) => {
      value.negativeInventory[0].id = 'NI-1';
    });
  }, /malformed ID/u);

  expectRejected('duplicate DF IDs must fail', () => {
    mutateJson('conformance/roadmap-readiness-deferred-features.json', (value) => {
      value.deferredFeatures[1].id = value.deferredFeatures[0].id;
    });
  }, /duplicate ID/u);

  expectRejected('unknown NI mappings must fail', () => {
    mutateJson('conformance/roadmap-readiness-deferred-features.json', (value) => {
      value.deferredFeatures[1].negativeInventoryKeys = ['NI-99'];
    });
  }, /unknown negative inventory/u);

  expectRejected('duplicate NI mappings must fail', () => {
    mutateJson('conformance/roadmap-readiness-deferred-features.json', (value) => {
      value.deferredFeatures[1].negativeInventoryKeys = ['NI-02', 'NI-02'];
    });
  }, /contains a duplicate/u);

  expectRejected('empty mappings require a reviewed reason', () => {
    mutateJson('conformance/roadmap-readiness-deferred-features.json', (value) => {
      value.deferredFeatures[0].negativeInventoryReason = null;
    });
  }, /requires a reviewed negativeInventoryReason/u);

  expectRejected('mapped rows prohibit a no-mapping reason', () => {
    mutateJson('conformance/roadmap-readiness-deferred-features.json', (value) => {
      value.deferredFeatures[1].negativeInventoryReason = 'not permitted';
    });
  }, /must be null when mappings are present/u);

  expectRejected('U6 cannot promote planned evidence', () => {
    mutateJson('conformance/roadmap-readiness-deferred-features.json', (value) => {
      value.deferredFeatures[0].evidenceClassification = 'implemented';
    });
  }, /prematurely promoted/u);

  expectRejected('omitted openness row must fail', () => {
    mutateJson('conformance/mcp-protocol-openness-inventory.json', (value) => {
      value.validators.pop();
    });
  }, /differs from source derivation/u);

  expectRejected('extra openness row must fail', () => {
    mutateJson('conformance/mcp-protocol-openness-inventory.json', (value) => {
      value.validators.push({
        ...value.validators[0],
        id: 'OPEN-999',
        key: 'src/main/java/com/soklet/McpMissing.java#com.soklet.McpMissing#missing',
        file: 'src/main/java/com/soklet/McpMissing.java',
        owner: 'com.soklet.McpMissing',
        method: 'missing',
      });
    });
  }, /differs from source derivation/u);

  expectRejected('malformed openness row must fail', () => {
    mutateJson('conformance/mcp-protocol-openness-inventory.json', (value) => {
      value.validators[0].id = 'OPEN-1';
    });
  }, /malformed or duplicated/u);

  expectRejected('duplicate openness row must fail', () => {
    mutateJson('conformance/mcp-protocol-openness-inventory.json', (value) => {
      value.validators[1].id = value.validators[0].id;
    });
  }, /malformed or duplicated/u);

  expectRejected('openness row order drift must fail', () => {
    mutateJson('conformance/mcp-protocol-openness-inventory.json', (value) => {
      [value.validators[0], value.validators[1]] =
        [value.validators[1], value.validators[0]];
    });
  }, /derived ASCII key order/u);

  expectRejected('openness matcher classification drift must fail', () => {
    mutateJson('conformance/mcp-protocol-openness-inventory.json', (value) => {
      value.validators[0].matcherRuleId = 'OPEN-MATCH-001';
    });
  }, /matcher classification differs from source derivation/u);

  expectRejected('openness Markdown drift must fail', () => {
    writeFileSync(fixturePath(
      'conformance/MCP_PROTOCOL_OPENNESS_INVENTORY_2026-07-28.md'),
    '\nmanual drift\n', { flag: 'a' });
  }, /rendering is stale/u);

  const matcherFixtures = [
    ['unknown-key helper', `
      package com.soklet.internal.mcp.protocol;
      final class McpOpennessSelfTestFixture {
        void validate(Object fields) { rejectUnknownKeys(fields); }
      }
    `, 'OPEN-MATCH-001'],
    ['exact allowed set', `
      package com.soklet.internal.mcp.protocol;
      import java.util.Set;
      final class McpOpennessSelfTestFixture {
        void validate(Object fields) {
          validateAllowedFields(fields, Set.of("known"));
        }
      }
    `, 'OPEN-MATCH-002'],
    ['named exact allowed set', `
      package com.soklet.internal.mcp.protocol;
      import java.util.Set;
      final class McpOpennessSelfTestFixture {
        static final Set<String> ROLES = Set.of("assistant", "user");
        void validate(Object value) { requireStringValue(value, ROLES); }
      }
    `, 'OPEN-MATCH-002'],
    ['named exact allowed set supplied through an array validator', `
      package com.soklet.internal.mcp.protocol;
      import java.util.Set;
      final class McpOpennessSelfTestFixture {
        static final Set<String> ROLES = Set.of("assistant", "user");
        void validate(Object fields) {
          optionalStringArrayValues(fields, "audience", ROLES);
        }
      }
    `, 'OPEN-MATCH-002'],
    ['versioned exact field set', `
      package com.soklet.internal.mcp.protocol;
      import java.util.Map;
      import java.util.Set;
      final class McpOpennessSelfTestFixture {
        static final Set<String> VERSION_2_FIELDS = Set.of("version", "value");
        void validate(Map<String, Object> fields) {
          if (!fields.keySet().equals(VERSION_2_FIELDS))
            throw new IllegalArgumentException();
        }
      }
    `, 'OPEN-MATCH-002'],
    ['selector switch', `
      package com.soklet.internal.mcp.protocol;
      final class McpOpennessSelfTestFixture {
        int validate(String method) {
          return switch (method) {
            case "known" -> 1;
            default -> throw new IllegalArgumentException();
          };
        }
      }
    `, 'OPEN-MATCH-003'],
    ['wrapped selector switch', `
      package com.soklet.internal.mcp.protocol;
      final class McpOpennessSelfTestFixture {
        enum Capability { KNOWN }
        int validate(Capability capability) {
          return switch (requireNonNull(capability)) {
            case KNOWN -> 1;
          };
        }
        static <T> T requireNonNull(T value) { return value; }
      }
    `, 'OPEN-MATCH-003'],
    ['wrapped closed lookup', `
      package com.soklet.internal.mcp.protocol;
      import java.util.Set;
      final class McpOpennessSelfTestFixture {
        static final Set<String> INPUT_REQUIRED_CLIENT_METHODS = Set.of("known");
        boolean validate(String clientRequestMethod) {
          return INPUT_REQUIRED_CLIENT_METHODS.contains(
              requireNonNull(clientRequestMethod));
        }
        static <T> T requireNonNull(T value) { return value; }
      }
    `, 'OPEN-MATCH-003'],
    ['closed keyword lookup', `
      package com.soklet.internal.mcp.protocol;
      import java.util.Set;
      final class McpOpennessSelfTestFixture {
        static final Set<String> SUPPORTED_KEYWORDS = Set.of("type");
        void validate(String keyword) {
          if (!SUPPORTED_KEYWORDS.contains(keyword))
            throw new IllegalArgumentException();
        }
      }
    `, 'OPEN-MATCH-003'],
    ['closed method-set containment', `
      package com.soklet.internal.mcp.protocol;
      import java.util.Set;
      final class McpOpennessSelfTestFixture {
        static final Set<String> HTTP_METHODS = Set.of("POST", "OPTIONS");
        void validate(Set<String> allowedMethods) {
          if (!HTTP_METHODS.containsAll(allowedMethods))
            throw new IllegalArgumentException();
        }
      }
    `, 'OPEN-MATCH-003'],
    ['enum wire-value lookup', `
      package com.soklet.internal.mcp.protocol;
      final class McpOpennessSelfTestFixture {
        enum Level {
          INFO;
          String wireValue() { return "info"; }
        }
        Level validate(String wireValue) {
          for (Level level : Level.values()) {
            if (level.wireValue().equals(wireValue)) return level;
          }
          throw new IllegalArgumentException();
        }
      }
    `, 'OPEN-MATCH-003'],
    ['unqualified enum schema-name lookup', `
      package com.soklet.internal.mcp.protocol;
      import java.util.Optional;
      enum McpOpennessSelfTestFixture {
        OBJECT("object");
        private final String schemaName;
        McpOpennessSelfTestFixture(String schemaName) {
          this.schemaName = schemaName;
        }
        static Optional<McpOpennessSelfTestFixture> validate(String name) {
          for (McpOpennessSelfTestFixture type : values()) {
            if (type.schemaName.equals(name)) return Optional.of(type);
          }
          return Optional.empty();
        }
      }
    `, 'OPEN-MATCH-003'],
    ['literal equality cascade', `
      package com.soklet.internal.mcp.protocol;
      final class McpOpennessSelfTestFixture {
        boolean validate(String method) {
          if ("first".equals(method) || "second".equals(method)) return true;
          return false;
        }
      }
    `, 'OPEN-MATCH-004'],
    ['overlapping closure uses deterministic primary matcher', `
      package com.soklet.internal.mcp.protocol;
      import java.util.Set;
      final class McpOpennessSelfTestFixture {
        boolean validate(String method, Object fields) {
          validateAllowedFields(fields, Set.of("known"));
          if ("first".equals(method) || "second".equals(method)) return true;
          return false;
        }
      }
    `, 'OPEN-MATCH-004'],
  ];
  for (const [label, source, matcher] of matcherFixtures) {
    expectRejected(`uninventoried ${label} fixture must fail`, () => {
      write(scratchJava, source);
    }, new RegExp(`omitted=\\[[^\\]]*${matcher}`, 'u'));
  }

  restore();
  write(scratchJava, `
    package com.soklet.internal.mcp.protocol;
    import java.util.Set;
    final class McpOpennessSelfTestFixture {
      void preserveExtensionFields(Object fields) {
        requireExtensionFields(fields, Set.of("reserved"));
      }
    }
  `);
  verifyCandidateRoot(fixture);

  restore();
  write(scratchJava, `
    package com.soklet.internal.mcp.protocol;
    import java.util.Map;
    import java.util.Set;
    final class McpOpennessSelfTestFixture {
      static final Set<String> REQUEST_FIELDS = Set.of("known");
      Map<String, Object> preserveOpenFields(Map<String, Object> fields) {
        return fieldsExcept(fields, REQUEST_FIELDS);
      }
    }
  `);
  verifyCandidateRoot(fixture);

  restore();
  write(scratchJava, `
    package com.soklet.internal.mcp.protocol;
    import java.util.Set;
    final class McpOpennessSelfTestFixture {
      static final Set<String> RESERVED_DESCRIPTOR_FIELDS = Set.of("reserved");
      boolean isExtensionName(String name) {
        return !RESERVED_DESCRIPTOR_FIELDS.contains(name);
      }
    }
  `);
  verifyCandidateRoot(fixture);

  restore();
  write(scratchJava, `
    package com.soklet.internal.mcp.protocol;
    import java.util.Set;
    final class McpOpennessSelfTestFixture {
      static final Set<String> VISITED_TYPES = Set.of("object");
      boolean graphAlreadyVisited(String type) {
        return VISITED_TYPES.contains(type);
      }
    }
  `);
  verifyCandidateRoot(fixture);

  restore();
  write(scratchJava, `
    package com.soklet.internal.mcp.protocol;
    final class McpOpennessSelfTestFixture {
      interface Header { String name(); }
      boolean normalize(Header header) {
        if ("Vary".equalsIgnoreCase(header.name())) return true;
        if ("Content-Language".equalsIgnoreCase(header.name())) return true;
        return false;
      }
    }
  `);
  verifyCandidateRoot(fixture);

  expectRejected('missing OPEN-EX-001 row must fail', () => {
    mutateJson('conformance/mcp-protocol-openness-inventory.json', (value) => {
      value.reviewedExclusions = [];
    });
  }, /exactly one reviewed exclusion/u);

  expectRejected('mutated OPEN-EX-001 row must fail', () => {
    mutateJson('conformance/mcp-protocol-openness-inventory.json', (value) => {
      value.reviewedExclusions[0].consumer = 'different(java.lang.String)';
    });
  }, /OPEN-EX-001/u);

  expectRejected('duplicate OPEN-EX-001 row must fail', () => {
    mutateJson('conformance/mcp-protocol-openness-inventory.json', (value) => {
      value.reviewedExclusions.push({ ...value.reviewedExclusions[0] });
    });
  }, /exactly one reviewed exclusion/u);

  expectRejected('missing OPEN-EX-001 source field must fail', () => {
    const path = fixturePath('src/main/java/com/soklet/DefaultMcpServer.java');
    writeFileSync(path, readFileSync(path, 'utf8').replace(
      'BOUNDED_METRIC_METHODS', 'REMOVED_METRIC_METHODS'));
  }, /OPEN-EX-001/u);

  expectRejected('mutated OPEN-EX-001 source consumer must fail', () => {
    const path = fixturePath('src/main/java/com/soklet/DefaultMcpServer.java');
    writeFileSync(path, readFileSync(path, 'utf8').replace(
      'McpMetricsEvent.UNRECOGNIZED_JSON_RPC_METHOD', 'jsonRpcMethod'));
  }, /exact bounded-metrics use/u);

  expectRejected('duplicate OPEN-EX-001 source field must fail', () => {
    const path = fixturePath('src/main/java/com/soklet/DefaultMcpServer.java');
    writeFileSync(path, `${readFileSync(path, 'utf8')}\nBOUNDED_METRIC_METHODS = Set.of(\n`);
  }, /OPEN-EX-001/u);

  expectRejected('moved OPEN-EX-001 owner must fail', () => {
    const path = fixturePath('src/main/java/com/soklet/DefaultMcpServer.java');
    writeFileSync(path, readFileSync(path, 'utf8').replace(
      'final class DefaultMcpServer implements McpServer',
      'final class MovedDefaultMcpServer implements McpServer'));
  }, /exact owner com\.soklet\.DefaultMcpServer/u);

  expectRejected('active-text allowed fingerprint drift must fail', () => {
    mutateJson('conformance/roadmap-readiness-active-text-rules.json', (value) => {
      value.rules.find(({ id }) => id === 'PROFILE-001')
        .allowedMatches[0].matchedText = 'mutated match';
    });
  }, /fingerprint mismatch/u);

  restore();
  writeFileSync(fixturePath('MCP.md'),
    '\n<!-- Soklet automatically selects the latest profile. -->\n',
    { flag: 'a' });
  assert.equal(verifyCandidateRoot(fixture).activeTextRuleCount, 22,
    'Roadmap integration must ignore inactive HTML-comment claims.');

  expectRejected(
    'active-text lifecycle support hidden in an HTML comment must fail', () => {
      const path = fixturePath('MCP.md');
      const text = readFileSync(path, 'utf8');
      const start =
        'SEP-2577 marks Roots, Sampling, and Logging deprecated in MCP `2026-07-28`,';
      const end =
        'approved default-off, bounded, redacted diagnostic policy.';
      const startOffset = text.indexOf(start);
      const endOffset = text.indexOf(end, startOffset) + end.length;
      assert.ok(startOffset >= 0 && endOffset >= end.length);
      writeFileSync(path, `${text.slice(0, startOffset)}<!--\n${text.slice(
        startOffset, endOffset)}\n-->${text.slice(endOffset)}`);
    }, /LIFECYCLE-001 fingerprint mismatch/u);

  expectRejected(
    'active-text required target omission plus prohibited claim must fail', () => {
      mutateJson('conformance/roadmap-readiness-active-text-rules.json',
        (value) => {
          const rule = value.rules.find(({ id }) => id === 'PROFILE-002');
          rule.files = rule.files.filter(({ path }) => path !== 'SECURITY.md');
        });
      writeFileSync(fixturePath('SECURITY.md'),
        '\nSoklet automatically selects the latest profile.\n', { flag: 'a' });
    }, /PROFILE-002 is missing required governed scope SECURITY\.md/u);

  expectRejected(
    'active-text reverse automatic latest-profile claim must fail', () => {
      writeFileSync(fixturePath('MCP.md'),
        '\nThe latest MCP profile is selected automatically.\n', { flag: 'a' });
    }, /PROFILE-002 expected zero matches/u);

  expectRejected(
    'active-text plain exact event-variant count must fail', () => {
      writeFileSync(fixturePath('MCP.md'),
        '\nSoklet exposes exactly 23 event variants.\n', { flag: 'a' });
    }, /COUNT-001 fingerprint mismatch/u);

  expectRejected(
    'active-text noun-first server-extension support must fail', () => {
      writeFileSync(fixturePath('MCP.md'),
        '\nSoklet server extensions are supported.\n', { flag: 'a' });
    }, /EXTENSION-001 expected zero matches/u);

  expectRejected(
    'active-text compatibility claim moved into a default path must fail', () => {
      const path = fixturePath('MCP.md');
      const text = readFileSync(path, 'utf8');
      const moved = 'Retained Sampling and Roots declarations remain validated\n'
        + 'and must be registered.';
      assert.ok(text.includes(moved));
      writeFileSync(path, text.replace(moved, '').replace(
        '## Multi-round-trip input and request state\n',
        `## Multi-round-trip input and request state\n\n${moved}\n`));
    }, /LIFECYCLE-002 expected zero matches/u);

  expectRejected('active-text audit drift must fail', () => {
    writeFileSync(fixturePath('conformance/MCP_ROADMAP_ACTIVE_TEXT_AUDIT.md'),
      '\nmanual drift\n', { flag: 'a' });
  }, /audit is stale/u);

  expectRejected('active-text API inventory overclaim must fail', () => {
    writeFileSync(fixturePath('api/mcp/README.md'),
      '\nSoklet Automatically selects the latest MCP profile.\n',
      { flag: 'a' });
  }, /PROFILE-002 expected zero matches/u);

  expectRejected('active-text release-note overclaim must fail', () => {
    writeFileSync(fixturePath('CHANGELOG.md'),
      '\nBuilt-in DPoP support is available.\n', { flag: 'a' });
  }, /DPOP-001 expected zero matches/u);

  expectRejected('active-text release guidance overclaim must fail', () => {
    writeFileSync(fixturePath('release/README.md'),
      '\nArbitrary extension methods are supported.\n', { flag: 'a' });
  }, /EXTENSION-001 expected zero matches/u);

  expectRejected('active-text parent traversal must fail without sibling reads', () => {
    const outside = join(temporary, 'outside-active.md');
    writeFileSync(outside, 'sibling sentinel', 'utf8');
    mutateJson('conformance/roadmap-readiness-active-text-rules.json', (value) => {
      value.rules.find(({ id }) => id === 'PROFILE-002')
        .files[0].path = '../outside-active.md';
    });
  }, /contained POSIX root-relative path/u);

  expectRejected('active-text absolute path must fail', () => {
    const outside = join(temporary, 'absolute-active.md');
    writeFileSync(outside, 'absolute sentinel', 'utf8');
    mutateJson('conformance/roadmap-readiness-active-text-rules.json', (value) => {
      value.rules.find(({ id }) => id === 'PROFILE-002')
        .files[0].path = resolve(outside);
    });
  }, /POSIX root-relative path/u);

  expectRejected('active-text file symlink must fail', () => {
    const outside = join(temporary, 'linked-active.md');
    writeFileSync(outside, originals.get('MCP.md'));
    const path = fixturePath('MCP.md');
    rmSync(path, { force: true });
    symlinkSync(outside, path, 'file');
  }, /must not traverse a symbolic link/u);

  expectRejected('active-text directory symlink must fail', () => {
    const outside = join(temporary, 'linked-active-directory');
    mkdirSync(outside, { recursive: true });
    writeFileSync(join(outside, 'claim.md'), 'directory sentinel', 'utf8');
    symlinkSync(outside, fixturePath('escaped-active'), 'dir');
    mutateJson('conformance/roadmap-readiness-active-text-rules.json', (value) => {
      value.rules.find(({ id }) => id === 'PROFILE-002')
        .files[0].path = 'escaped-active/claim.md';
    });
  }, /must not traverse a symbolic link/u);

  restore();
  const candidateLink = join(temporary, 'candidate-link');
  symlinkSync(fixture, candidateLink, 'dir');
  assert.throws(() => verifyCandidateRoot(candidateLink),
    /must not traverse a symbolic link/u,
    'candidate-root symlink must fail');

  expectRejected('fixed planning artifact file symlink must fail', () => {
    const relativePath = 'conformance/soklet-4.0-planning-authority.json';
    const outside = join(temporary, 'linked-planning-authority.json');
    writeFileSync(outside, originals.get(relativePath));
    const path = fixturePath(relativePath);
    rmSync(path, { force: true });
    symlinkSync(outside, path, 'file');
  }, /must not traverse a symbolic link/u);

  expectRejected('fixed artifact directory symlink must fail', () => {
    const outside = join(temporary, 'linked-conformance');
    copyEntry(fixturePath('conformance'), outside, 'fixture conformance');
    rmSync(fixturePath('conformance'), { force: true, recursive: true });
    symlinkSync(outside, fixturePath('conformance'), 'dir');
  }, /must not traverse a symbolic link/u);

  expectRejected('internal Java scan-root symlink must fail', () => {
    const outside = join(temporary, 'linked-internal-scan-root');
    copyEntry(join(root, INTERNAL_SCAN_ROOT), outside, INTERNAL_SCAN_ROOT);
    rmSync(fixturePath(INTERNAL_SCAN_ROOT), { force: true, recursive: true });
    symlinkSync(outside, fixturePath(INTERNAL_SCAN_ROOT), 'dir');
  }, /must not traverse a symbolic link/u);

  expectRejected('direct public Java scan file symlink must fail', () => {
    const relativePath = 'src/main/java/com/soklet/McpClientCapabilities.java';
    const outside = join(temporary, 'linked-public-scan.java');
    writeFileSync(outside, originals.get(relativePath));
    const path = fixturePath(relativePath);
    rmSync(path, { force: true });
    symlinkSync(outside, path, 'file');
  }, /regular non-symlink file/u);

  expectRejected('OPEN-EX-001 source symlink must fail', () => {
    const relativePath = 'src/main/java/com/soklet/DefaultMcpServer.java';
    const outside = join(temporary, 'linked-open-ex.java');
    writeFileSync(outside, originals.get(relativePath));
    const path = fixturePath(relativePath);
    rmSync(path, { force: true });
    symlinkSync(outside, path, 'file');
  }, /must not traverse a symbolic link/u);

  const unsafeCopyTarget = join(temporary, 'unsafe-copy-target.txt');
  const unsafeCopySource = join(temporary, 'unsafe-copy-source.txt');
  writeFileSync(unsafeCopyTarget, 'copy sentinel', 'utf8');
  symlinkSync(unsafeCopyTarget, unsafeCopySource, 'file');
  assert.throws(() => copyEntry(unsafeCopySource,
    join(temporary, 'unsafe-copy-destination.txt'), 'unsafe copy fixture'),
  /copy source must not be a symlink/u,
  'self-test copy helper must reject symlinks');

  const siblingBlind = spawnSync(process.execPath, [
    join(root, 'scripts/verify-mcp-roadmap-readiness.mjs'),
    '--mode', 'candidate',
    '--root', fixture,
    '--external-root', temporary,
  ], { encoding: 'utf8' });
  assert.notEqual(siblingBlind.status, 0);
  assert.match(`${siblingBlind.stdout}${siblingBlind.stderr}`,
    /rejects --external-root and never reads sibling bytes/u);

  restore();
  const finalSnapshot = snapshotTree(fixture);
  verifyCandidateRoot(fixture);
  assert.deepEqual(snapshotTree(fixture), finalSnapshot,
    'Repeated roadmap verification must remain wholly read-only.');
  console.log('MCP roadmap readiness verifier self-test passed.');
} finally {
  rmSync(temporary, { force: true, recursive: true });
}
