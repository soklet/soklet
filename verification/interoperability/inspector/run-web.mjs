#!/usr/bin/env node
import { runHarness } from './run.mjs';

try {
  const args = process.argv.slice(2);
  if (args.length !== 10 || args[0] !== '--candidate-jar' || args[2] !== '--candidate-pom'
      || args[4] !== '--java' || args[6] !== '--browser' || args[8] !== '--work-dir')
    throw new Error('USAGE');
  const receipt = await runHarness({ candidateJar: args[1], candidatePom: args[3],
    java: args[5], browserExecutable: args[7], workDirectory: args[9], surface: 'web' });
  console.log(`Inspector web harness: ${receipt.status}`);
  process.exitCode = receipt.status === 'PASSED' ? 0 : 1;
} catch {
  console.error('Inspector web harness failed before a qualified receipt; inspect only sanitized artifacts.');
  process.exitCode = 1;
}
