import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const repository = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const directory = resolve(repository, "src/main/javadoc/links");
const manifest = JSON.parse(readFileSync(resolve(directory, "manifest.json"), "utf8"));
const pom = readFileSync(resolve(repository, "pom.xml"), "utf8");
const configured = new Map();
for (const match of pom.matchAll(/<offlineLink>\s*<url>([^<]+)<\/url>\s*<location>\$\{project.basedir\}\/src\/main\/javadoc\/links\/([^<]+)<\/location>\s*<\/offlineLink>/g))
  configured.set(match[2], match[1]);
if (!Array.isArray(manifest.indexes) || manifest.indexes.length === 0
    || manifest.indexes.length !== configured.size)
  throw new Error("Manifest must cover every pinned POM Javadoc index.");
const paths = new Set();

for (const index of manifest.indexes) {
  if (!/^[a-f0-9]{64}$/.test(index.sha256)
      || !/\/(?:element-list|package-list)$/.test(index.path)
      || configured.get(dirname(index.path)) !== index.url)
    throw new Error("Invalid index pin or POM/manifest mismatch: " + index.path);
  const path = resolve(directory, index.path);
  if (!path.startsWith(directory + sep) || paths.has(path))
    throw new Error("Duplicate or out-of-directory Javadoc index: " + index.path);
  paths.add(path);
  const digest = createHash("sha256").update(readFileSync(path)).digest("hex");
  if (digest !== index.sha256)
    throw new Error("Javadoc index checksum mismatch: " + index.path);
}

console.log("Verified " + paths.size + " pinned Javadoc indexes.");
