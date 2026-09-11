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

package com.soklet.internal.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.Optional;

public class HostHeaderValidatorTests {
	@Test
	public void ipv6LiteralParserAcceptsOnlyCompleteAddressLiterals() {
		for (String literal : List.of(
				"::1",
				"0:0:0:0:0:0:0:1",
				"2001:db8::1",
				"::ffff:192.0.2.128",
				"2001:db8:0:0:0:ffff:192.0.2.128")) {
			Optional<InetAddress> parsed =
					HostHeaderValidator.parseIpv6AddressLiteral(literal);
			Assertions.assertTrue(parsed.isPresent(), literal);
		}

		Assertions.assertTrue(
				HostHeaderValidator.parseIpv6AddressLiteral(null).isEmpty());
		for (String nonLiteral : List.of(
				"",
				"example.test",
				"deadbeef.attacker.test",
				"v1.example",
				".::1",
				"2001:db8::g",
				"::1%lo0",
				"127.0.0.1")) {
			Assertions.assertTrue(
					HostHeaderValidator.parseIpv6AddressLiteral(nonLiteral).isEmpty(),
					nonLiteral);
		}
	}

	@Test
	public void ipvFutureRemainsAValidHostLiteralButIsNotResolvedAsIpv6() {
		Assertions.assertTrue(
				HostHeaderValidator.isValidHostHeaderValue("[v1.example]:443"));
		Assertions.assertTrue(
				HostHeaderValidator.parseIpv6AddressLiteral("v1.example").isEmpty());
	}
}
