/* Copyright 2026 Revetware LLC. Licensed under the Apache License, Version 2.0. */
package com.soklet.internal.mcp.skills;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Test-only event reflection; never composes YAML nodes or constructs tagged objects. */
final class SkillYamlReferenceEvents {
	private static final int MAXIMUM_INPUT_CHARACTERS = 4 * 1024 * 1024;
	private final Object parser;
	private final Method parseString;
	private final Method eventId;
	private final Method nodeAnchor;
	private final Method anchorValue;
	private final Method scalarTag;
	private final Method scalarStyle;
	private final Method scalarValue;
	private final Method collectionTag;
	private final Method aliasAnchor;

	SkillYamlReferenceEvents() throws ReflectiveOperationException {
		Class<?> settingsClass = Class.forName("org.snakeyaml.engine.v2.api.LoadSettings");
		Object builder = settingsClass.getMethod("builder").invoke(null);
		builder.getClass().getMethod("setCodePointLimit", int.class).invoke(builder, MAXIMUM_INPUT_CHARACTERS);
		builder.getClass().getMethod("setParseComments", boolean.class).invoke(builder, false);
		Object settings = builder.getClass().getMethod("build").invoke(builder);
		Class<?> parserClass = Class.forName("org.snakeyaml.engine.v2.api.lowlevel.Parse");
		this.parser = parserClass.getConstructor(settingsClass).newInstance(settings);
		this.parseString = parserClass.getMethod("parseString", String.class);
		this.eventId = type("Event").getMethod("getEventId");
		this.nodeAnchor = type("NodeEvent").getMethod("getAnchor");
		this.anchorValue = Class.forName("org.snakeyaml.engine.v2.common.Anchor").getMethod("getValue");
		this.scalarTag = type("ScalarEvent").getMethod("getTag");
		this.scalarStyle = type("ScalarEvent").getMethod("getScalarStyle");
		this.scalarValue = type("ScalarEvent").getMethod("getValue");
		this.collectionTag = type("CollectionStartEvent").getMethod("getTag");
		this.aliasAnchor = type("AliasEvent").getMethod("getAlias");
	}

	/** Engine exceptions remain distinguishable from reflection/projection failures. */
	List<String> parse(String source) throws ReflectiveOperationException {
		if (source.length() > MAXIMUM_INPUT_CHARACTERS)
			throw new IllegalArgumentException("Reference input exceeds the verification bound.");
		List<String> events = new ArrayList<>();
		char[] collections = new char[SkillYamlEvents.MAX_DEPTH];
		int depth = 0;
		long characters = 0;
		boolean started = false, ended = false, document = false, root = false;
		// Iteration deliberately forces the maintained parser's lazy validation.
		for (Object event : (Iterable<?>) this.parseString.invoke(this.parser, source)) {
			if (events.size() == SkillYamlEvents.MAX_EVENTS || ended)
				throw failure();
			String id = ((Enum<?>) this.eventId.invoke(event)).name();
			String normalized;
			switch (id) {
				case "StreamStart" -> {
					if (started) throw failure();
					started = true;
					normalized = "+STR";
				}
				case "StreamEnd" -> {
					if (!started || document || depth != 0) throw failure();
					ended = true;
					normalized = "-STR";
				}
				case "DocumentStart" -> {
					if (!started || document || depth != 0) throw failure();
					document = true;
					root = false;
					normalized = "+DOC";
				}
				case "DocumentEnd" -> {
					if (!document || !root || depth != 0) throw failure();
					document = false;
					normalized = "-DOC";
				}
				case "MappingStart", "SequenceStart", "Scalar", "Alias" -> {
					if (!document || depth == 0 && root || depth >= SkillYamlEvents.MAX_DEPTH)
						throw failure();
					if (depth == 0) root = true;
					if (id.equals("MappingStart") || id.equals("SequenceStart")) {
						boolean mapping = id.equals("MappingStart");
						collections[depth++] = mapping ? 'M' : 'S';
						normalized = SkillYamlEvents.collection(mapping, anchor(event),
								optionalString(this.collectionTag.invoke(event)));
					} else if (id.equals("Scalar")) {
						normalized = SkillYamlEvents.scalar(anchor(event), optionalString(this.scalarTag.invoke(event)),
								style(this.scalarStyle.invoke(event)), (String) this.scalarValue.invoke(event));
					} else {
						normalized = SkillYamlEvents.alias((String) this.anchorValue.invoke(this.aliasAnchor.invoke(event)));
					}
				}
				case "MappingEnd", "SequenceEnd" -> {
					char expected = id.equals("MappingEnd") ? 'M' : 'S';
					if (!document || depth == 0 || collections[depth - 1] != expected) throw failure();
					--depth;
					normalized = expected == 'M' ? "-MAP" : "-SEQ";
				}
				default -> throw failure(); // Comments were explicitly disabled; unknown events are not silently omitted.
			}
			characters += normalized.length();
			if (characters > SkillYamlEvents.MAX_OUTPUT_CHARACTERS) throw failure();
			events.add(normalized);
		}
		if (!ended) throw failure();
		return List.copyOf(events);
	}

	private String anchor(Object event) throws ReflectiveOperationException {
		Optional<?> anchor = (Optional<?>) this.nodeAnchor.invoke(event);
		return anchor.isEmpty() ? null : (String) this.anchorValue.invoke(anchor.get());
	}

	private static String optionalString(Object value) {
		return (String) ((Optional<?>) value).orElse(null);
	}

	private static SkillYamlNode.Style style(Object value) {
		return switch (((Enum<?>) value).name()) {
			case "PLAIN" -> SkillYamlNode.Style.PLAIN;
			case "SINGLE_QUOTED" -> SkillYamlNode.Style.SINGLE_QUOTED;
			case "DOUBLE_QUOTED" -> SkillYamlNode.Style.DOUBLE_QUOTED;
			case "LITERAL" -> SkillYamlNode.Style.LITERAL;
			case "FOLDED" -> SkillYamlNode.Style.FOLDED;
			default -> throw failure();
		};
	}

	private static Class<?> type(String name) throws ClassNotFoundException {
		return Class.forName("org.snakeyaml.engine.v2.events." + name);
	}

	private static IllegalStateException failure() {
		return new IllegalStateException("Reference event projection is unsupported, malformed, or exceeds its bound.");
	}
}
