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

/**
 * Checked, bounded connection setup/catch-up. Queued events are delivered only
 * after this callback returns successfully. This is not a post-activation
 * indefinite producer. Owned subscriptions survive initializer return.
 * One initializer instance may be reused concurrently for different clients;
 * captured application state must support that reuse.
 */
@FunctionalInterface
public interface SseClientInitializer {
	void initialize(@NonNull SseUnicaster sseUnicaster) throws Exception;
}
