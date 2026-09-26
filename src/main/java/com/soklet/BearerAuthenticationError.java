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

/**
 * Standard Bearer challenge errors defined by RFC 6750.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public enum BearerAuthenticationError {
	/** The request is malformed. RFC 6750 recommends HTTP 400. */
	INVALID_REQUEST,
	/** The token is expired, revoked, malformed, or otherwise invalid. */
	INVALID_TOKEN,
	/** A valid token lacks a grantable required scope. */
	INSUFFICIENT_SCOPE
}
