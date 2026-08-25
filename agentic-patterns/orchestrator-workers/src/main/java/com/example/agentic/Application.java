
/* 
* Copyright 2024 - 2024 the original author or authors.
* 
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
* 
* https://www.apache.org/licenses/LICENSE-2.0
* 
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.example.agentic;

import java.util.UUID;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

// ------------------------------------------------------------
// ORCHESTRATOR WORKERS
// ------------------------------------------------------------

@SpringBootApplication
public class Application {

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

	/**
	 * The two roles are separate {@code ChatClient} beans so each is its own agent on Catalyst,
	 * with its own workflow name ({@code spring-ai.taskOrchestrator.workflow} /
	 * {@code spring-ai.contentWorker.workflow}) and agent-registry entry. Each is built from the
	 * injected, Spring-managed builder — {@code clone()} keeps the durable advisor on both.
	 */
	@Bean
	public ChatClient taskOrchestrator(ChatClient.Builder chatClientBuilder) {
		return chatClientBuilder.clone().build();
	}

	@Bean
	public ChatClient contentWorker(ChatClient.Builder chatClientBuilder) {
		return chatClientBuilder.clone().build();
	}

	@Bean
	public CommandLineRunner commandLineRunner(ChatClient taskOrchestrator, ChatClient contentWorker,
			@Value("${orchestrator.run-id:}") String configuredRunId) {
		return args -> {

			String runId = StringUtils.hasText(configuredRunId)
					? configuredRunId
					: UUID.randomUUID().toString();

			System.out.println("\nRun id: " + runId);
			System.out.println("Re-run with --orchestrator.run-id=" + runId
					+ " to resume: the decomposition and any finished workers replay from their records.\n");

			new OrchestratorWorkers(taskOrchestrator, contentWorker)
					.process("Write a product description for a new eco-friendly water bottle", runId);

		};
	}
}
