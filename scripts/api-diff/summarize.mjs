#!/usr/bin/env node

import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value !== null && typeof value === 'object')
    return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
  return value;
}

function records(text, label) {
  const result = new Map();
  for (const [index, line] of text.split(/\r?\n/u).entries()) {
    if (line.trim() === '') continue;
    const value = JSON.parse(line);
    if (typeof value?.id !== 'string' || value.id === '' || result.has(value.id))
      throw new Error(`${label}:${index + 1} must have a unique nonempty symbol id`);
    result.set(value.id, value);
  }
  return result;
}

export function compareIncompatibilities(referenceText, currentText) {
  const reference = records(referenceText, 'reference');
  const current = records(currentText, 'current');
  const added = [], removed = [], changed = [];
  for (const id of [...new Set([...reference.keys(), ...current.keys()])].sort()) {
    if (!reference.has(id)) added.push(id);
    else if (!current.has(id)) removed.push(id);
    else if (JSON.stringify(canonical(reference.get(id))) !== JSON.stringify(canonical(current.get(id))))
      changed.push(id);
  }
  return { added, removed, changed, referenceCount: reference.size, currentCount: current.size };
}

function html(text) {
  return text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
    .replaceAll('|', '&#124;').replaceAll('\n', ' ');
}

export function incompatibilitySummary(delta) {
  const lines = [
    '## Advisory API incompatibility changes', '',
    `Current report: ${delta.currentCount} symbols. Reference ledger: ${delta.referenceCount} symbols.`, '',
    `Added: **${delta.added.length}**; removed: **${delta.removed.length}**; changed: **${delta.changed.length}**.`, '',
    'Differences are informational; no ledger refresh is required.', '',
  ];
  const rows = [
    ...delta.added.map(id => ['Added', id]),
    ...delta.removed.map(id => ['Removed', id]),
    ...delta.changed.map(id => ['Changed', id]),
  ];
  if (rows.length === 0) lines.push('No differences from the reference ledger.', '');
  else {
    lines.push('| Change | Symbol |', '| --- | --- |');
    for (const [kind, id] of rows.slice(0, 100))
      lines.push(`| ${kind} | <code>${html(id)}</code> |`);
    if (rows.length > 100)
      lines.push('', `Showing the first 100 of ${rows.length} differences; the full JSONL report is attached.`);
    lines.push('');
  }
  return lines.join('\n');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const [referencePath, currentPath, outputPath, ...extra] = process.argv.slice(2);
    if (!referencePath || !currentPath || extra.length)
      throw new Error('Usage: summarize.mjs <reference-jsonl> <current-jsonl> [output-markdown]');
    const summary = incompatibilitySummary(compareIncompatibilities(
      readFileSync(referencePath, 'utf8'), readFileSync(currentPath, 'utf8'),
    ));
    if (outputPath) writeFileSync(outputPath, summary);
    process.stdout.write(summary);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
