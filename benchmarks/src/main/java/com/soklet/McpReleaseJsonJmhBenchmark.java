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

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Release-bound MCP JSON and Tool Schema Profile 1 benchmarks.
 *
 * <p>Warmup, measurement, fork, thread, JVM, and artifact ordering policy is
 * supplied by the candidate-tracked producer. Keeping those values out of the
 * annotations makes command-line drift visible in the retained JMH JSON.</p>
 */
public class McpReleaseJsonJmhBenchmark {
	@Benchmark
	public Object jsonParse(JsonState state) {
		return state.runtime.parse(McpReleaseBenchmarkRuntime.JSON_PAYLOAD);
	}

	@Benchmark
	public byte[] jsonWrite(JsonState state) {
		return state.runtime.write(state.parsed);
	}

	@Benchmark
	public Object profile1SchemaCompile(ProfileState state) {
		return state.runtime.compile(state.schema);
	}

	@Benchmark
	public Object profile1SchemaEvaluate(ProfileState state) {
		return state.runtime.evaluate(state.program, state.instance);
	}

	@State(Scope.Thread)
	public static class JsonState {
		@Param({ McpReleaseBenchmarkRuntime.BASELINE_ARTIFACT,
				McpReleaseBenchmarkRuntime.CANDIDATE_ARTIFACT })
		public String artifact;

		private McpReleaseBenchmarkRuntime runtime;
		private Object parsed;

		@Setup(Level.Trial)
		public void setUp() {
			runtime = McpReleaseBenchmarkRuntime.open(artifact);
			parsed = runtime.parse(McpReleaseBenchmarkRuntime.JSON_PAYLOAD);
		}

		@TearDown(Level.Trial)
		public void tearDown() {
			runtime.close();
		}
	}

	@State(Scope.Thread)
	public static class ProfileState {
		@Param({ McpReleaseBenchmarkRuntime.CANDIDATE_ARTIFACT })
		public String artifact;

		private McpReleaseBenchmarkRuntime runtime;
		private Object schema;
		private Object instance;
		private Object program;

		@Setup(Level.Trial)
		public void setUp() {
			runtime = McpReleaseBenchmarkRuntime.open(artifact);
			schema = runtime.parse(McpReleaseBenchmarkRuntime.PROFILE_SCHEMA);
			instance = runtime.parse(McpReleaseBenchmarkRuntime.PROFILE_INSTANCE);
			program = runtime.compile(schema);
			runtime.evaluate(program, instance);
		}

		@TearDown(Level.Trial)
		public void tearDown() {
			runtime.close();
		}
	}
}
