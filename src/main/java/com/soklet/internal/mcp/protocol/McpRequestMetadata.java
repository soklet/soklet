/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet.internal.mcp.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

record McpRequestMetadata(String protocolVersion, McpClientCapabilities clientCapabilities,
		Optional<McpImplementationMetadata> clientInformation,
		Optional<McpRequestLogLevel> deprecatedLogLevel,
		Optional<McpProgressToken> progressToken, McpJsonObject extensionFields) {
	static final String PROTOCOL_VERSION_KEY = "io.modelcontextprotocol/protocolVersion";
	static final String CLIENT_CAPABILITIES_KEY = "io.modelcontextprotocol/clientCapabilities";
	static final String CLIENT_INFORMATION_KEY = "io.modelcontextprotocol/clientInfo";
	static final String LOG_LEVEL_KEY = "io.modelcontextprotocol/logLevel";
	static final String PROGRESS_TOKEN_KEY = "progressToken";

	McpRequestMetadata {
		requireNonNull(protocolVersion);
		requireNonNull(clientCapabilities);
		requireNonNull(clientInformation);
		requireNonNull(deprecatedLogLevel);
		requireNonNull(progressToken);
		extensionFields = McpProtocolSupport.requireInboundMetadataFields(extensionFields,
				Set.of(PROTOCOL_VERSION_KEY, CLIENT_CAPABILITIES_KEY, CLIENT_INFORMATION_KEY,
						LOG_LEVEL_KEY, PROGRESS_TOKEN_KEY));
	}

	static McpRequestMetadata fromClientCapabilities(McpClientCapabilities clientCapabilities) {
		return new McpRequestMetadata(McpProtocolVersion.CURRENT, clientCapabilities,
				Optional.empty(), Optional.empty(), Optional.empty(), McpJsonObject.empty());
	}

	McpJsonObject toJsonObject() {
		Map<String, McpJsonValue> fields = new LinkedHashMap<>(extensionFields.members());
		fields.put(PROTOCOL_VERSION_KEY, new McpJsonString(protocolVersion));
		fields.put(CLIENT_CAPABILITIES_KEY, clientCapabilities.toJsonObject());
		clientInformation.ifPresent(value -> fields.put(CLIENT_INFORMATION_KEY, value.toJsonObject()));
		deprecatedLogLevel.ifPresent(value -> fields.put(LOG_LEVEL_KEY,
				new McpJsonString(value.wireValue())));
		progressToken.ifPresent(value -> fields.put(PROGRESS_TOKEN_KEY, progressTokenValue(value)));
		return new McpJsonObject(fields);
	}

	private static McpJsonValue progressTokenValue(McpProgressToken token) {
		if (token instanceof McpProgressToken.StringToken stringToken)
			return new McpJsonString(stringToken.value());

		if (token instanceof McpProgressToken.IntegerToken integerToken)
			return new McpJsonNumber(new java.math.BigDecimal(integerToken.value()));

		throw new IllegalArgumentException("Unsupported progress token: " + token);
	}
}

record McpRequestParameters(McpRequestMetadata metadata, McpJsonObject fields) {
	McpRequestParameters {
		requireNonNull(metadata);
		fields = McpProtocolSupport.requireExtensionFields(fields, Set.of("_meta"));
	}

	McpJsonObject toJsonObject() {
		Map<String, McpJsonValue> values = new LinkedHashMap<>(fields.members());
		values.put("_meta", metadata.toJsonObject());
		return new McpJsonObject(values);
	}
}

enum McpRequestLogLevel {
	ALERT("alert"),
	CRITICAL("critical"),
	DEBUG("debug"),
	EMERGENCY("emergency"),
	ERROR("error"),
	INFO("info"),
	NOTICE("notice"),
	WARNING("warning");

	private final String wireValue;

	McpRequestLogLevel(String wireValue) {
		this.wireValue = wireValue;
	}

	String wireValue() {
		return wireValue;
	}
}

final class McpProtocolVersion {
	static final String CURRENT = "2026-07-28";
	static final java.util.List<String> SUPPORTED = java.util.List.of(CURRENT);

	private McpProtocolVersion() {
	}
}
