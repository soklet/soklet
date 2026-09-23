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

import javax.annotation.concurrent.ThreadSafe;

/**
 * Independent removable callback registration. Close is idempotent, non-waiting,
 * and suppresses invocation only if it wins before the callback is claimed.
 * A claimed callback may still run after close returns. No checked close failure
 * or retry-until-success contract is introduced.
 */
@ThreadSafe
public interface CallbackRegistration extends AutoCloseable {
	@Override void close();
}
