#!/usr/bin/env python3
"""Compile the M2 signature candidates and reject legacy/unsafe caller shapes.

No production API is replaced, and none of the signature stubs is executed.
Requires already-compiled core classes and locally cached annotation jars.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def bundle_digest(directory):
    result = hashlib.sha256()
    for path in sorted(directory.rglob("*.class")):
        result.update(str(path.relative_to(directory)).encode() + b"\0")
        result.update(path.read_bytes())
    return result.hexdigest()


def run(command, cwd):
    return subprocess.run(command, cwd=cwd, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, check=False)


def main():
    fixture = Path(__file__).resolve().parent
    repository = fixture.parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--maven-repo", type=Path, default=Path.home() / ".m2/repository")
    parser.add_argument("--core-classes", type=Path, default=repository / "target/classes")
    args = parser.parse_args()
    javac = str(Path(args.java_home) / "bin/javac") if args.java_home else shutil.which("javac")
    if not javac:
        raise RuntimeError("Set JAVA_HOME or --java-home to a JDK 17+ installation")
    core = args.core_classes.resolve()
    if not (core / "com/soklet/Request.class").is_file():
        raise RuntimeError("Compile core first (mvn -DskipTests compile), or set --core-classes")
    dependencies = [args.maven_repo / "org/jspecify/jspecify/1.0.1/jspecify-1.0.1.jar",
                    args.maven_repo / "com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.jar"]
    for dependency in dependencies:
        if not dependency.is_file():
            raise RuntimeError(f"Missing cached dependency: {dependency}")
    dependencies = [path.resolve() for path in dependencies]
    output = repository / "target/streaming-api-milestone-2"
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    empty_sources = output / "empty-sourcepath"
    empty_sources.mkdir()
    common = ["--release", "17", "-proc:none", "-parameters", "-XDrawDiagnostics",
              "-Xlint:unchecked", "-Werror", "-sourcepath", str(empty_sources)]
    source_groups = {group: sorted((fixture / group).rglob("*.java"))
                     for group in ("api", "positive", "comparisons", "negative")}
    for group, sources in source_groups.items():
        if not sources:
            raise RuntimeError(f"No {group} sources found")
    version = run([javac, "-version"], repository)
    if version.returncode:
        raise RuntimeError(version.stdout)
    report = {
        "kind": "compile-only-api-decision-fixtures",
        "verifiedAt": datetime.now(timezone.utc).isoformat(),
        "javac": version.stdout.strip(),
        "baselineCommit": run(["git", "rev-parse", "HEAD"], repository).stdout.strip(),
        "compilerOptions": common[:-2],
        "annotationJars": {str(path.relative_to(args.maven_repo.resolve())): digest(path)
                           for path in dependencies},
        "coreClassBundleSha256": bundle_digest(core),
        "sourceSha256": {str(path.relative_to(fixture)): digest(path)
                         for paths in source_groups.values() for path in paths},
        "verifierSha256": digest(Path(__file__)),
        "compiled": {},
        "rejected": {},
    }
    for group in ("api", "positive", "comparisons"):
        destination = output / group
        destination.mkdir()
        classpath = ([] if group == "api" else [output / "api"]) + [core] + dependencies
        result = run([javac, *common, "-classpath", os.pathsep.join(map(str, classpath)),
                      "-d", str(destination), *map(str, source_groups[group])], repository)
        (output / f"{group}.log").write_text(result.stdout)
        if result.returncode:
            raise RuntimeError(f"{group} compilation failed:\n{result.stdout}")
        report["compiled"][group] = len(source_groups[group])
    classpath = [output / "api", output / "positive", core] + dependencies
    for source in source_groups["negative"]:
        marked = [(line, text.split("// EXPECT-ERROR:", 1)[1].strip())
                  for line, text in enumerate(source.read_text().splitlines(), 1)
                  if "// EXPECT-ERROR:" in text]
        if len(marked) != 1:
            raise RuntimeError(f"{source.name} needs one EXPECT-ERROR: code | fragments line")
        expected_line, expectation = marked[0]
        expected_code, *expected_fragments = [part.strip() for part in expectation.split("|")]
        destination = output / "negative" / source.stem
        destination.mkdir(parents=True)
        result = run([javac, *common, "-classpath", os.pathsep.join(map(str, classpath)),
                      "-d", str(destination), str(source)], repository)
        (destination / "diagnostics.log").write_text(result.stdout)
        errors = re.findall(r"^([^\n:]+\.java):(\d+):\d+: (compiler\.err\.[^\n]+)",
                            result.stdout, re.MULTILINE)
        if (result.returncode == 0 or len(errors) != 1
                or re.search(r"^compiler\.err\.", result.stdout, re.MULTILINE)
                or Path(errors[0][0]).name != source.name or int(errors[0][1]) != expected_line
                or errors[0][2].split(":", 1)[0] != expected_code
                or any(fragment not in errors[0][2] for fragment in expected_fragments)):
            raise RuntimeError(f"{source.name} failed outside its intended diagnostic:\n{result.stdout}")
        report["rejected"][source.name] = {"line": expected_line, "diagnostic": errors[0][2]}
    report["result"] = "PASS"
    (output / "verification.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"PASS: {report['compiled']}; {len(report['rejected'])} expected rejections")
    print(f"Evidence: {output / 'verification.json'}")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
