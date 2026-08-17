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

package com.soklet;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Validated, bounded carrier for the trace fields of one structured MCP log
 * record. Sensitive field values are available only through the deliberate
 * {@link #toLogMessage()} emission boundary and are redacted from diagnostic
 * rendering.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpTraceLogRecord {
	@NonNull
	static final String TOKEN_FORMAT = "soklet-mcp-trace-correlation-v1";
	static final int MAXIMUM_LOG_MESSAGE_CHARACTERS = 184;
	private static final int TOKEN_CHARACTERS = 22;
	private static final int TRACE_ID_CHARACTERS = 32;
	private final DefaultMcpSecurityControls.@Nullable TraceCorrelationToken
			correlationToken;
	private final @Nullable String rawValidatedTraceId;

	private McpTraceLogRecord(
			DefaultMcpSecurityControls.@Nullable TraceCorrelationToken
					correlationToken,
			@Nullable String rawValidatedTraceId) {
		if (correlationToken == null && rawValidatedTraceId == null)
			throw new IllegalArgumentException(
					"An MCP trace log record requires at least one trace field.");
		if (correlationToken != null)
			validateCorrelationToken(correlationToken);
		if (rawValidatedTraceId != null)
			validateRawTraceId(rawValidatedTraceId);
		this.correlationToken = correlationToken;
		this.rawValidatedTraceId = rawValidatedTraceId;
	}

	@NonNull
	static Optional<@NonNull McpTraceLogRecord> capture(
			@NonNull Optional<DefaultMcpSecurityControls.@NonNull TraceCorrelationToken>
					correlationToken,
			@NonNull Optional<@NonNull String> rawValidatedTraceId) {
		requireNonNull(correlationToken);
		requireNonNull(rawValidatedTraceId);
		if (correlationToken.isEmpty() && rawValidatedTraceId.isEmpty())
			return Optional.empty();
		return Optional.of(new McpTraceLogRecord(
				correlationToken.orElse(null), rawValidatedTraceId.orElse(null)));
	}

	@NonNull
	String toLogMessage() {
		StringBuilder message = new StringBuilder(
				MAXIMUM_LOG_MESSAGE_CHARACTERS);
		if (this.correlationToken != null) {
			message.append("tokenFormat=").append(TOKEN_FORMAT)
					.append(";keyId=").append(this.correlationToken.keyId())
					.append(";token=").append(this.correlationToken.token());
		}
		if (this.rawValidatedTraceId != null) {
			if (!message.isEmpty())
				message.append(';');
			message.append("traceId=").append(this.rawValidatedTraceId);
		}
		if (message.length() > MAXIMUM_LOG_MESSAGE_CHARACTERS)
			throw new IllegalStateException(
					"MCP trace log message exceeded its validated bound.");
		return message.toString();
	}

	private static void validateCorrelationToken(
			DefaultMcpSecurityControls.@NonNull TraceCorrelationToken token) {
		requireNonNull(token);
		McpKeyIdValidator.validate(token.keyId(),
				"MCP trace-correlation log key ID");
		String value = token.token();
		if (value.length() != TOKEN_CHARACTERS)
			throw new IllegalArgumentException(
					"MCP trace-correlation log token must contain exactly 22 Base64URL characters.");
		for (int index = 0; index < value.length(); ++index) {
			char character = value.charAt(index);
			if (!isBase64UrlCharacter(character))
				throw new IllegalArgumentException(
						"MCP trace-correlation log token must contain only unpadded Base64URL characters.");
		}
	}

	private static void validateRawTraceId(@NonNull String traceId) {
		requireNonNull(traceId);
		if (traceId.length() != TRACE_ID_CHARACTERS)
			throw new IllegalArgumentException(
					"Raw validated MCP trace ID must contain exactly 32 lowercase hexadecimal characters.");
		boolean anyNonzero = false;
		for (int index = 0; index < traceId.length(); ++index) {
			char character = traceId.charAt(index);
			if (!((character >= '0' && character <= '9')
					|| (character >= 'a' && character <= 'f')))
				throw new IllegalArgumentException(
						"Raw validated MCP trace ID must contain exactly 32 lowercase hexadecimal characters.");
			anyNonzero |= character != '0';
		}
		if (!anyNonzero)
			throw new IllegalArgumentException(
					"Raw validated MCP trace ID must not be all zero.");
	}

	private static boolean isBase64UrlCharacter(char character) {
		return (character >= '0' && character <= '9')
				|| (character >= 'A' && character <= 'Z')
				|| (character >= 'a' && character <= 'z')
				|| character == '-' || character == '_';
	}

	@Override
	@NonNull
	public String toString() {
		return "%s{tokenFormat='%s', keyId=%s, token=%s, traceId=%s}"
				.formatted(getClass().getSimpleName(), TOKEN_FORMAT,
						this.correlationToken == null ? "<absent>"
								: "'%s'".formatted(
										this.correlationToken.keyId()),
						this.correlationToken == null ? "<absent>"
								: "<redacted>",
						this.rawValidatedTraceId == null ? "<absent>"
								: "<redacted>");
	}
}
