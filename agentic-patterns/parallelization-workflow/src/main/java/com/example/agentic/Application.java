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

import java.util.List;
import java.util.UUID;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

@SpringBootApplication
public class Application {

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

	/**
	 * The agent each parallel worker calls, exposed as a bean so Catalyst can identify it: the
	 * bean name becomes the agent name in the agent registry and its workflow name
	 * ({@code spring-ai.stakeholderAnalyst.workflow}). Building from the injected,
	 * Spring-managed {@code ChatClient.Builder} is what carries the durable advisor.
	 */
	@Bean
	public ChatClient stakeholderAnalyst(ChatClient.Builder chatClientBuilder) {
		return chatClientBuilder.build();
	}

	@Bean
	public CommandLineRunner commandLineRunner(ChatClient stakeholderAnalyst,
			@Value("${parallel.run-id:}") String configuredRunId) {

		return args -> {
			// ------------------------------------------------------------
			// PARALLEL WORKFLOW
			// ------------------------------------------------------------

			String runId = StringUtils.hasText(configuredRunId)
					? configuredRunId
					: UUID.randomUUID().toString();

			System.out.println("\nRun id: " + runId);
			System.out.println("Re-run with --parallel.run-id=" + runId
					+ " to resume: inputs that already completed return their recorded results.\n");

			List<String> parallelResponse = new ParallelizationlWorkflow(stakeholderAnalyst)
					.parallel("""
							Analyze how market changes will impact this stakeholder group.
							Provide specific impacts and recommended actions.
							Format with clear sections and priorities.
							""",
							List.of(
									"""
											Customers:
											- Price sensitive
											- Want better tech
											- Environmental concerns
											""",

									"""
											Employees:
											- Job security worries
											- Need new skills
											- Want clear direction
											""",

									"""
											Investors:
											- Expect growth
											- Want cost control
											- Risk concerns
											""",

									"""
											Suppliers:
											- Capacity constraints
											- Price pressures
											- Tech transitions
											"""),
							4, runId);

			System.out.println(parallelResponse);

		};
	}
}
