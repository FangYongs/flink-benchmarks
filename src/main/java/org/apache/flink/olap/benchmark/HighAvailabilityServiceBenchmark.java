/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.olap.benchmark;

import org.apache.curator.test.TestingServer;
import org.apache.flink.api.common.JobID;
import org.apache.flink.benchmark.BenchmarkBase;
import org.apache.flink.benchmark.FlinkEnvironmentContext;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.HighAvailabilityOptions;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.JobVertex;
import org.apache.flink.runtime.jobgraph.tasks.CheckpointCoordinatorConfiguration;
import org.apache.flink.runtime.jobgraph.tasks.JobCheckpointingSettings;
import org.apache.flink.runtime.jobmanager.HighAvailabilityMode;
import org.apache.flink.runtime.state.hashmap.HashMapStateBackend;
import org.apache.flink.runtime.testtasks.NoOpInvokable;
import org.apache.flink.util.FileUtils;
import org.apache.flink.util.SerializedValue;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.VerboseMode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.openjdk.jmh.annotations.Scope.Thread;

/** The benchmark for submitting short-lived jobs with and without high availability service. */
@OutputTimeUnit(SECONDS)
public class HighAvailabilityServiceBenchmark extends BenchmarkBase {
	public static void main(String[] args) throws RunnerException {
		Options options =
				new OptionsBuilder()
						.verbosity(VerboseMode.NORMAL)
						.include(".*" + HighAvailabilityServiceBenchmark.class.getCanonicalName() + ".*")
						.build();

		new Runner(options).run();
	}

	@Benchmark
	public void submitJobThroughput(HighAvailabilityContext context) throws Exception {
		context.miniCluster.executeJobBlocking(buildNoOpJob());
	}

	private JobGraph buildNoOpJob() throws Exception {
		JobGraph jobGraph = new JobGraph(JobID.generate(), UUID.randomUUID().toString());
		jobGraph.addVertex(createNoOpVertex());
		jobGraph.setSnapshotSettings(new JobCheckpointingSettings(
				CheckpointCoordinatorConfiguration
						.builder()
						.build(),
				new SerializedValue<>(new HashMapStateBackend())));
		return jobGraph;
	}

	private JobVertex createNoOpVertex() {
		JobVertex vertex = new JobVertex("v");
		vertex.setInvokableClass(NoOpInvokable.class);
		vertex.setParallelism(1);
		vertex.setMaxParallelism(1);
		return vertex;
	}

	@State(Thread)
	public static class HighAvailabilityContext extends FlinkEnvironmentContext {
		private TestingServer testingServer;
		public final File haDir;

		@Param({"ZOOKEEPER", "NONE"})
		public HighAvailabilityMode highAvailabilityMode;

		public HighAvailabilityContext() {
			try {
				haDir = Files.createTempDirectory("bench-ha-").toFile();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public void setUp() throws Exception {
			if (highAvailabilityMode == HighAvailabilityMode.ZOOKEEPER) {
				testingServer = new TestingServer();
				testingServer.start();
			}

			super.setUp();
		}

		@Override
		protected Configuration createConfiguration() {
			Configuration configuration = super.createConfiguration();
			configuration.set(HighAvailabilityOptions.HA_MODE, highAvailabilityMode.name());
			configuration.set(HighAvailabilityOptions.HA_ZOOKEEPER_QUORUM, testingServer.getConnectString());
			configuration.set(HighAvailabilityOptions.HA_STORAGE_PATH, haDir.toURI().toString());
			return configuration;
		}

		@Override
		public void tearDown() throws Exception {
			super.tearDown();
			if (highAvailabilityMode == HighAvailabilityMode.ZOOKEEPER) {
				testingServer.stop();
				testingServer.close();
			}
			FileUtils.deleteDirectory(haDir);
		}
	}
}
