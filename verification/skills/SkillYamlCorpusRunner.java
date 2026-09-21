/* Copyright 2026 Revetware LLC. Licensed under the Apache License, Version 2.0. */
package com.soklet.internal.mcp.skills;

import com.soklet.internal.mcp.protocol.*;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Offline, report-only probe: a successful process exit is not qualification. */
public final class SkillYamlCorpusRunner {
	private static final String COMMIT = "6e6c296ae9c9d2d5c4134b4b64d01b29ac19ff6f";
	private static final String INPUT_HASH = "5a2ae514d798de737a34467827b6a1108e48813065b14192de061056eca87c6c";
	private static final String EXPECTATIONS_HASH = "ceb535e48c07ed1df2edc22b573d9fbe33ff18f97c5ff456f8f904279b371d3e";
	private static final String REFERENCE_HASH = "0b638c8806112215e02e58e95cbd9e9a69d4bd540991f47553d053919994cc94";
	// Explicit verification profile, not a public/default Skills policy.
	private static final SkillYamlLimits YAML_LIMITS = new SkillYamlLimits(
			4 * 1024 * 1024, 128, 1_000_000, 1024 * 1024, 4L * 1024 * 1024, 50_000_000);
	private static final McpJsonLimits JSON_LIMITS = McpJsonLimits.productionDefaults();
	private static final McpJsonCodec JSON = new McpJsonCodec(JSON_LIMITS);

	private SkillYamlCorpusRunner() {}

	public static void main(String[] args) throws Exception {
		if (args.length != 4) throw new IllegalArgumentException("Use run-corpus.sh.");
		verifyComparator();
		verifyJsonStream();
		SkillYamlEvents.verify();
		Path classes = Path.of(args[0]), corpus = Path.of(args[1]), output = Path.of(args[2]);
		String coreClassesHash = hashClasses(classes);
		List<Path> inputs;
		try (var paths = Files.walk(corpus)) {
			inputs = paths.filter(path -> path.getFileName().toString().equals("in.yaml"))
					.sorted((left, right) -> id(corpus, left).compareTo(id(corpus, right))).toList();
		}
		MessageDigest corpusDigest = digest(), expectationsDigest = digest();
		for (Path input : inputs) {
			update(corpusDigest, id(corpus, input), Files.readAllBytes(input));
			for (String name : List.of("error", "in.json", "test.event")) {
				Path expected = input.resolveSibling(name);
				if (Files.exists(expected)) update(expectationsDigest, id(corpus, input) + "/" + name,
						Files.readAllBytes(expected));
			}
		}
		String inputHash = hex(corpusDigest.digest());
		String expectationsHash = hex(expectationsDigest.digest());
		if (inputs.size() != 402 || !inputHash.equals(INPUT_HASH))
			throw new IllegalStateException("Corpus input pin mismatch: count=" + inputs.size() + ", sha256=" + inputHash);
		if (!expectationsHash.equals(EXPECTATIONS_HASH))
			throw new IllegalStateException("Corpus expectation pin mismatch: sha256=" + expectationsHash);
		String referenceHash = args[3].isEmpty() ? "" : hex(digest().digest(Files.readAllBytes(Path.of(args[3]))));
		if (!referenceHash.isEmpty() && !referenceHash.equals(REFERENCE_HASH))
			throw new IllegalStateException("Reference jar differs from the pinned SnakeYAML Engine 3.0.1 artifact.");
		SkillYamlReferenceEvents reference = args[3].isEmpty() ? null : new SkillYamlReferenceEvents();
		Map<String, Integer> counts = new TreeMap<>();
		Map<String, Integer> syntaxCounts = new TreeMap<>(), modelCounts = new TreeMap<>(), referenceCounts = new TreeMap<>();
		Map<String, Integer> expectedJsonCounts = new TreeMap<>(), syntaxReasons = new TreeMap<>(), modelReasons = new TreeMap<>();
		Map<String, Integer> documentCountCounts = new TreeMap<>();
		Map<String, Integer> eventCounts = new TreeMap<>(), referenceEventCounts = new TreeMap<>(), referenceCorpusEventCounts = new TreeMap<>();
		int crashes = 0;
		try (var results = Files.newBufferedWriter(output.resolve("cases.jsonl"), StandardCharsets.UTF_8)) {
			for (Path input : inputs) {
				Map<String, Object> result = runCase(corpus, input, reference);
				String classification = (String) result.get("classification");
				counts.merge(classification, 1, Integer::sum);
				count(syntaxCounts, result.get("expectedSyntax") + "-" + result.get("syntaxStatus"));
				count(modelCounts, result.get("expectedSyntax") + "-" + result.get("modelStatus"));
				count(referenceCounts, result.get("expectedSyntax") + "-" + result.get("referenceStatus"));
				count(expectedJsonCounts, (String) result.get("expectedJsonStatus"));
				count(documentCountCounts, (String) result.get("documentCountStatus"));
				count(eventCounts, (String) result.get("eventStatus"));
				count(referenceEventCounts, (String) result.get("referenceEventStatus"));
				count(referenceCorpusEventCounts, (String) result.get("referenceCorpusEventStatus"));
				if (result.get("syntaxStatus").equals("rejected")) count(syntaxReasons, (String) result.get("reason"));
				if (result.get("modelStatus").equals("rejected")) count(modelReasons, (String) result.get("reason"));
				if (classification.equals("crash") || result.get("referenceStatus").equals("crash")) ++crashes;
				results.write(JSON.toJson(json(result)));
				results.newLine();
			}
		}
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("qualified", false);
		summary.put("mode", "report-only; zero exit does not certify YAML compatibility");
		summary.put("corpusCommit", COMMIT);
		summary.put("corpusCases", inputs.size());
		summary.put("corpusInputsSha256", inputHash);
		summary.put("corpusExpectationsSha256", expectationsHash);
		if (!coreClassesHash.equals(hashClasses(classes)))
			throw new IllegalStateException("Core classes changed during the run; discard this incomplete report and retry after compilation.");
		summary.put("coreClassesSha256", coreClassesHash);
		try (InputStream bytes = SkillYamlCorpusRunner.class.getResourceAsStream("SkillYamlCorpusRunner.class")) {
			summary.put("runnerClassSha256", hex(digest().digest(bytes.readAllBytes())));
		}
		summary.put("verificationClassesSha256", hashClasses(output.resolve("classes")));
		summary.put("javaRuntime", System.getProperty("java.runtime.version"));
		summary.put("javaVendor", System.getProperty("java.vendor"));
		summary.put("javaVm", System.getProperty("java.vm.name"));
		summary.put("reference", reference == null ? "not requested" : "SnakeYAML Engine (test-only event parser)");
		if (reference != null) {
			summary.put("referenceVersion", "3.0.1");
			summary.put("referenceJarSha256", referenceHash);
		}
		summary.put("yamlVerificationLimits", Map.of("inputBytes", YAML_LIMITS.maximumInputBytes(),
				"depth", YAML_LIMITS.maximumNestingDepth(), "nodes", YAML_LIMITS.maximumNodes(),
				"scalarCharacters", YAML_LIMITS.maximumScalarCharacters(),
				"totalScalarCharacters", YAML_LIMITS.maximumTotalScalarCharacters(), "work", YAML_LIMITS.maximumWork()));
		summary.put("jsonLimits", "McpJsonLimits.productionDefaults() in the identified core classes");
		summary.put("classificationCounts", counts);
		summary.put("syntaxCounts", syntaxCounts);
		summary.put("modelCounts", modelCounts);
		summary.put("referenceCounts", referenceCounts);
		summary.put("expectedJsonCounts", expectedJsonCounts);
		summary.put("documentCountCounts", documentCountCounts);
		summary.put("eventCounts", eventCounts);
		summary.put("referenceEventCounts", referenceEventCounts);
		summary.put("referenceCorpusEventCounts", referenceCorpusEventCounts);
		summary.put("eventComparison", "normalized syntax events: document/collection structure, entry order, scalar style/value, tags, anchors and aliases; excludes explicit document marker flags, collection flow/block style and positions");
		summary.put("syntaxRejectionReasons", syntaxReasons);
		summary.put("modelRejectionReasons", modelReasons);
		summary.put("numericComparatorSelfTest", "passed");
		summary.put("jsonStreamSelfTest", "passed");
		summary.put("syntaxEventSelfTest", "passed");
		summary.put("crashes", crashes);
		String report = JSON.toJson(json(summary));
		Files.writeString(output.resolve("summary.json"), report + "\n", StandardCharsets.UTF_8);
		System.out.println(report);
		if (crashes > 0) System.exit(1);
	}

	private static Map<String, Object> runCase(Path corpus, Path input, SkillYamlReferenceEvents reference) throws Exception {
		byte[] bytes = Files.readAllBytes(input);
		boolean invalid = Files.exists(input.resolveSibling("error"));
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", id(corpus, input));
		result.put("inputSha256", hex(digest().digest(bytes)));
		result.put("expectedSyntax", invalid ? "invalid" : "valid");
		result.put("referenceStatus", "not-requested");
		result.put("syntaxStatus", "not-run");
		result.put("modelStatus", "not-run");
		result.put("documentCountStatus", "not-compared");
		result.put("eventStatus", "not-compared");
		result.put("referenceEventStatus", "not-compared");
		result.put("referenceCorpusEventStatus", "not-compared");
		Path events = input.resolveSibling("test.event"), expectedJson = input.resolveSibling("in.json");
		long documents = -1;
		if (Files.exists(events)) {
			try (var lines = Files.lines(events, StandardCharsets.UTF_8)) {
				documents = lines.filter(line -> line.startsWith("+DOC")).count();
			}
		}
		result.put("expectedDocumentCount", documents);
		result.put("expectedJsonStatus", documents < 0 || !Files.exists(expectedJson)
				? "unavailable" : "available-not-compared");
		List<String> expectedEvents = null, referenceEvents = null;
		if (!invalid && Files.exists(events)) {
			try {
				if (Files.size(events) > YAML_LIMITS.maximumInputBytes())
					throw new IllegalArgumentException("Expected event stream exceeds the input bound.");
				expectedEvents = SkillYamlEvents.fromCorpus(Files.readString(events, StandardCharsets.UTF_8));
				result.put("expectedEventsSha256", hashEvents(expectedEvents));
			} catch (Throwable error) { return crash(result, error); }
		}
		if (reference != null) {
			try {
				referenceEvents = reference.parse(new String(bytes, StandardCharsets.UTF_8));
				result.put("referenceStatus", "accepted");
				result.put("referenceEventsSha256", hashEvents(referenceEvents));
				if (expectedEvents != null) compareEvents(result, "referenceCorpusEvent", expectedEvents, referenceEvents);
			}
			catch (Throwable error) {
				Throwable cause = unwrap(error);
				boolean rejected = cause.getClass().getName().startsWith("org.snakeyaml.engine.v2.exceptions.");
				result.put("referenceStatus", rejected ? "rejected" : "crash");
				result.put("referenceException", cause.getClass().getName());
			}
		}
		SkillYamlBudget budget = new SkillYamlBudget(YAML_LIMITS);
		List<SkillYamlNode> nodes;
		try {
			budget.work(3L * bytes.length, new SkillYamlNode.Position(1, 1));
			SkillSource source = SkillSource.fromBytes(bytes, YAML_LIMITS.maximumInputBytes());
			nodes = SkillYamlParser.parseStream(source.text(), budget, 1);
			result.put("syntaxStatus", "accepted");
			result.put("actualDocumentCount", nodes.size());
			result.put("documentCountStatus", documents < 0 ? "unavailable"
					: documents == nodes.size() ? "match" : "mismatch");
		} catch (SkillYamlException error) {
			result.put("syntaxStatus", "rejected");
			diagnostic(result, error);
			result.put("classification", invalid ? "invalid-rejected" : "valid-syntax-rejected");
			return result;
		} catch (Throwable error) { return crash(result, error); }
		try {
			List<String> actualEvents = SkillYamlEvents.fromNodes(nodes);
			result.put("actualEventsSha256", hashEvents(actualEvents));
			if (expectedEvents != null) compareEvents(result, "event", expectedEvents, actualEvents);
			else if (!invalid) result.put("eventStatus", "unavailable");
			if (referenceEvents != null) compareEvents(result, "referenceEvent", referenceEvents, actualEvents);
		} catch (Throwable error) { return crash(result, error); }
		if (!invalid && documents >= 0 && documents != nodes.size()) {
			result.put("classification", "valid-document-count-mismatch");
			return result;
		}
		List<McpJsonValue> actual = new ArrayList<>();
		try {
			// Anchor scope is a document, while resource consumption is a stream.
			for (SkillYamlNode node : nodes)
				actual.add(new SkillYamlResolver(budget, JSON_LIMITS).resolve(node));
			result.put("modelStatus", "accepted");
		} catch (SkillYamlException error) {
			result.put("modelStatus", "rejected");
			result.put("modelFailureDocument", actual.size() + 1);
			diagnostic(result, error);
			result.put("classification", invalid ? "invalid-syntax-accepted-model-rejected" : "valid-model-policy-rejected");
			return result;
		} catch (Throwable error) { return crash(result, error); }
		if (invalid) {
			result.put("classification", "invalid-accepted");
			return result;
		}
		if (documents < 0 || !Files.exists(expectedJson)) {
			result.put("expectedJsonStatus", "unavailable");
			result.put("classification", "valid-accepted-json-unavailable");
			return result;
		}
		List<McpJsonValue> expected;
		try {
			if (Files.size(expectedJson) > JSON_LIMITS.maximumInputBytes())
				throw new IllegalArgumentException("Expected JSON stream exceeds the input bound.");
			expected = jsonStream(Files.readString(expectedJson, StandardCharsets.UTF_8));
		}
		catch (IllegalArgumentException error) {
			result.put("expectedJsonStatus", "not-compatible-with-strict-json-profile");
			result.put("classification", "valid-accepted-json-unavailable");
			return result;
		} catch (Throwable error) { return crash(result, error); }
		if (expected.size() != documents) {
			result.put("expectedJsonStatus", "document-count-mismatch");
			result.put("expectedJsonDocumentCount", expected.size());
			result.put("classification", "valid-json-document-count-mismatch");
			return result;
		}
		result.put("expectedJsonStatus", "comparable");
		boolean equal = equalDocuments(expected, actual);
		result.put("classification", equal ? "valid-json-match" : "valid-json-mismatch");
		if (!equal) {
			if (documents == 1) {
				result.put("expectedJson", expected.get(0));
				result.put("actualJson", actual.get(0));
			} else {
				result.put("expectedJsonDocuments", new McpJsonArray(expected));
				result.put("actualJsonDocuments", new McpJsonArray(actual));
			}
		}
		return result;
	}

	private static String hashEvents(List<String> events) throws Exception {
		MessageDigest digest = digest();
		for (String event : events) { digest.update(event.getBytes(StandardCharsets.UTF_8)); digest.update((byte) '\n'); }
		return hex(digest.digest());
	}

	private static void compareEvents(Map<String, Object> result, String prefix, List<String> expected, List<String> actual) {
		boolean equal = expected.equals(actual);
		result.put(prefix + "Status", equal ? "match" : "mismatch");
		if (equal) return;
		int index = 0;
		while (index < expected.size() && index < actual.size() && expected.get(index).equals(actual.get(index))) ++index;
		result.put(prefix + "Difference", Map.of("eventIndex", index + 1,
				"expectedCount", expected.size(), "actualCount", actual.size(),
				"expected", eventPreview(expected, index), "actual", eventPreview(actual, index)));
	}

	private static String eventPreview(List<String> events, int index) {
		if (index >= events.size()) return "<end of events>";
		String value = events.get(index);
		if (value.length() <= 512) return value;
		int end = Character.isHighSurrogate(value.charAt(511)) && Character.isLowSurrogate(value.charAt(512)) ? 511 : 512;
		return value.substring(0, end) + " [truncated; full stream hashed]";
	}

	/** Split the corpus's whitespace-separated JSON values; validate every value with the strict codec. */
	private static List<McpJsonValue> jsonStream(String source) {
		if (source.length() > JSON_LIMITS.maximumInputBytes())
			throw new IllegalArgumentException("Expected JSON stream exceeds the input bound.");
		List<McpJsonValue> values = new ArrayList<>();
		int offset = 0;
		while (offset < source.length()) {
			while (offset < source.length() && jsonWhitespace(source.charAt(offset))) ++offset;
			if (offset == source.length()) break;
			if (values.size() >= JSON_LIMITS.maximumNodeCount())
				throw new IllegalArgumentException("Expected JSON stream exceeds the document bound.");
			int start = offset, depth = 0;
			boolean quoted = false, escaped = false;
			boolean structured = source.charAt(start) == '{' || source.charAt(start) == '[';
			boolean string = source.charAt(start) == '"';
			while (offset < source.length()) {
				char character = source.charAt(offset);
				if (!structured && !string && jsonWhitespace(character)) break;
				++offset;
				if (quoted) {
					if (escaped) escaped = false;
					else if (character == '\\') escaped = true;
					else if (character == '"') {
						quoted = false;
						if (string) break;
					}
				} else if (character == '"') quoted = true;
				else if (character == '{' || character == '[') {
					if (++depth > JSON_LIMITS.maximumNestingDepth())
						throw new IllegalArgumentException("Expected JSON stream exceeds the depth bound.");
				} else if (character == '}' || character == ']') {
					if (--depth <= 0) break;
				}
			}
			values.add(JSON.parse(source.substring(start, offset)));
			if (offset < source.length() && !jsonWhitespace(source.charAt(offset)))
				throw new IllegalArgumentException("Expected JSON documents require whitespace separation.");
		}
		return List.copyOf(values);
	}
	private static boolean jsonWhitespace(char character) {
		return character == ' ' || character == '\t' || character == '\r' || character == '\n';
	}
	private static boolean equalDocuments(List<McpJsonValue> expected, List<McpJsonValue> actual) {
		if (expected.size() != actual.size()) return false;
		for (int index = 0; index < expected.size(); ++index)
			if (!equal(expected.get(index), actual.get(index))) return false;
		return true;
	}

	private static void diagnostic(Map<String, Object> result, SkillYamlException error) {
		result.put("reason", error.reason().name()); result.put("line", error.line()); result.put("column", error.column());
	}
	private static void count(Map<String, Integer> counts, String key) { counts.merge(key, 1, Integer::sum); }
	private static void verifyComparator() {
		if (!equal(JSON.parse("{\"a\":[1.00,-0.0,100],\"b\":true}"),
				JSON.parse("{\"b\":true,\"a\":[1,0,1e2]}"))
				|| equal(JSON.parse("[1,2]"), JSON.parse("[2,1]"))
				|| equal(JSON.parse("{\"a\":1}"), JSON.parse("{\"b\":1}"))
				|| equal(JSON.parse("1"), JSON.parse("\"1\""))
				|| equal(JSON.parse("9007199254740993"), JSON.parse("9007199254740992")))
			throw new AssertionError("Exact recursive JSON comparison self-test failed.");
	}
	private static void verifyJsonStream() {
		String object = "{\n  \"a\": [\"brace } and quote \\\"\", 1]\n}";
		String array = "[\n false, null, {\"b\": []}\n]";
		String string = "\"escaped \\\\ backslash\"";
		List<McpJsonValue> expected = List.of(JSON.parse(object), JSON.parse(array),
				JSON.parse(string), JSON.parse("1"), JSON.parse("null"), JSON.parse("true"));
		List<McpJsonValue> actual = jsonStream(" \t\r\n" + object + "\n" + array + "\n"
				+ string + "\n1.00\nnull\ntrue\n");
		if (!jsonStream("").isEmpty() || !jsonStream(" \t\r\n").isEmpty()
				|| !equalDocuments(expected, actual)
				|| equalDocuments(expected, actual.subList(0, actual.size() - 1))
				|| equalDocuments(List.of(JSON.parse("1"), JSON.parse("2")),
						List.of(JSON.parse("1"), JSON.parse("3"))))
			throw new AssertionError("JSON stream comparison self-test failed.");
		for (String invalid : List.of("1true", "1\ntruefalse", "\"a\"\"b\"", "{}[]", "[\n1\n",
				"{\"a\":1}\n0\nx", "[}\nnull", "\"unfinished", "0\n\"bad\\x\"", "null\u00a0true")) {
			try { jsonStream(invalid); }
			catch (IllegalArgumentException expectedFailure) { continue; }
			throw new AssertionError("JSON stream reader accepted an invalid self-test value.");
		}
	}
	private static Map<String, Object> crash(Map<String, Object> result, Throwable error) {
		result.put("classification", "crash"); result.put("exception", unwrap(error).getClass().getName());
		return result;
	}
	private static Throwable unwrap(Throwable error) {
		while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
		return error;
	}
	private static boolean equal(McpJsonValue left, McpJsonValue right) {
		if (left instanceof McpJsonNumber first && right instanceof McpJsonNumber second)
			return first.value().compareTo(second.value()) == 0;
		if (left instanceof McpJsonArray first && right instanceof McpJsonArray second) {
			if (first.values().size() != second.values().size()) return false;
			for (int index = 0; index < first.values().size(); ++index)
				if (!equal(first.values().get(index), second.values().get(index))) return false;
			return true;
		}
		if (left instanceof McpJsonObject first && right instanceof McpJsonObject second) {
			if (!first.members().keySet().equals(second.members().keySet())) return false;
			for (String key : first.members().keySet()) if (!equal(first.members().get(key), second.members().get(key))) return false;
			return true;
		}
		return left.equals(right);
	}
	private static McpJsonValue json(Object value) {
		if (value instanceof McpJsonValue json) return json;
		if (value instanceof String string) return new McpJsonString(string);
		if (value instanceof Boolean bool) return bool ? McpJsonBoolean.TRUE : McpJsonBoolean.FALSE;
		if (value instanceof Number number) return new McpJsonNumber(number.longValue());
		if (value instanceof Map<?, ?> map) {
			Map<String, McpJsonValue> members = new LinkedHashMap<>();
			map.forEach((key, item) -> members.put((String) key, json(item)));
			return new McpJsonObject(members);
		}
		throw new IllegalArgumentException("Unexpected report value type.");
	}
	private static String id(Path corpus, Path input) { return corpus.relativize(input.getParent()).toString().replace('\\', '/'); }
	private static MessageDigest digest() throws Exception { return MessageDigest.getInstance("SHA-256"); }
	private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
	private static void update(MessageDigest digest, String path, byte[] bytes) {
		digest.update(path.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
		digest.update(bytes); digest.update((byte) 0);
	}
	private static String hashClasses(Path root) throws Exception {
		MessageDigest digest = digest();
		List<Path> files;
		try (var paths = Files.walk(root)) {
			files = paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".class"))
					.sorted((left, right) -> root.relativize(left).toString().compareTo(root.relativize(right).toString())).toList();
		}
		for (Path path : files) update(digest, root.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
		return hex(digest.digest());
	}

}
