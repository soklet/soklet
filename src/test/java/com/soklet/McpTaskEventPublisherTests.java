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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Focused contract tests for the public MCP task-event boundary.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public class McpTaskEventPublisherTests {
	@Test
	public void inMemoryPublishersBroadcastIndependentlyAndRegistrationsClose()
			throws Exception {
		McpTaskEventPublisher first =
				McpTaskEventPublisher.fromInMemoryDefaults();
		McpTaskEventPublisher second =
				McpTaskEventPublisher.fromInMemoryDefaults();
		List<String> firstEvents = new ArrayList<>();
		List<String> peerEvents = new ArrayList<>();
		List<String> secondEvents = new ArrayList<>();
		McpSubscriptionEventRegistration firstRegistration =
				first.subscribe(firstEvents::add);
		McpSubscriptionEventRegistration peerRegistration =
				first.subscribe(peerEvents::add);
		McpSubscriptionEventRegistration secondRegistration =
				second.subscribe(secondEvents::add);

		first.publishTaskChanged("task-one");
		Assertions.assertEquals(List.of("task-one"), firstEvents);
		Assertions.assertEquals(List.of("task-one"), peerEvents);
		Assertions.assertTrue(secondEvents.isEmpty());

		firstRegistration.close();
		firstRegistration.close();
		first.publishTaskChanged("task-two");
		Assertions.assertEquals(List.of("task-one"), firstEvents);
		Assertions.assertEquals(List.of("task-one", "task-two"), peerEvents);

		peerRegistration.close();
		secondRegistration.close();
	}

	@Test
	public void inMemoryPublisherAttemptsEveryListenerBeforeRethrowingFailure() {
		McpTaskEventPublisher publisher =
				McpTaskEventPublisher.fromInMemoryDefaults();
		RuntimeException firstFailure = new IllegalStateException("first");
		RuntimeException secondFailure = new IllegalArgumentException("second");
		List<String> delivered = new ArrayList<>();
		publisher.subscribe(taskId -> {
			throw firstFailure;
		});
		publisher.subscribe(delivered::add);
		publisher.subscribe(taskId -> {
			throw secondFailure;
		});

		RuntimeException thrown = Assertions.assertThrows(RuntimeException.class,
				() -> publisher.publishTaskChanged("task-one"));
		Assertions.assertSame(firstFailure, thrown);
		Assertions.assertArrayEquals(new Throwable[]{secondFailure},
				thrown.getSuppressed());
		Assertions.assertEquals(List.of("task-one"), delivered);
	}

	@Test
	public void publicBoundaryValidatesTaskIdsAndDefaultsToPollingOnly()
			throws Exception {
		McpTaskEventPublisher publisher =
				McpTaskEventPublisher.fromInMemoryDefaults();
		Assertions.assertThrows(NullPointerException.class,
				() -> publisher.subscribe(null));
		Assertions.assertThrows(NullPointerException.class,
				() -> publisher.publishTaskChanged(null));
		for (String invalid : List.of("", "   ", "task\ridentifier",
				"task\nidentifier"))
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> publisher.publishTaskChanged(invalid));

		McpTaskManager manager = new McpTaskManager() {
			@Override
			public Optional<McpTask> findTask(McpTaskRequestContext context) {
				return Optional.empty();
			}

			@Override
			public void updateTask(McpTaskUpdateContext context) {
			}

			@Override
			public void requestTaskCancelation(McpTaskRequestContext context) {
			}
		};
		Assertions.assertTrue(manager.getTaskEventPublisher().isEmpty());
	}
}
