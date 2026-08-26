
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

import com.example.agentic.EvaluatorOptimizer.RefinedResponse;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

// ------------------------------------------------------------
// EVALUATOR-OPTIMIZER
// ------------------------------------------------------------

@SpringBootApplication
public class Application {

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

	/**
	 * The two roles are separate {@code ChatClient} beans so each is its own agent on Catalyst,
	 * with its own workflow name ({@code spring-ai.solutionGenerator.workflow} /
	 * {@code spring-ai.solutionEvaluator.workflow}) and agent-registry entry. Each is built from
	 * the injected, Spring-managed builder — {@code clone()} keeps the durable advisor on both.
	 */
	@Bean
	public ChatClient solutionGenerator(ChatClient.Builder chatClientBuilder) {
		return chatClientBuilder.clone().build();
	}

	@Bean
	public ChatClient solutionEvaluator(ChatClient.Builder chatClientBuilder) {
		return chatClientBuilder.clone().build();
	}

	@Bean
	public CommandLineRunner commandLineRunner(ChatClient solutionGenerator, ChatClient solutionEvaluator,
			@Value("${evaluator.run-id:}") String configuredRunId) {
		return args -> {
			String runId = StringUtils.hasText(configuredRunId)
					? configuredRunId
					: UUID.randomUUID().toString();

			System.out.println("\nRun id: " + runId);
			System.out.println("Re-run with --evaluator.run-id=" + runId
					+ " to resume: completed refinement rounds replay from their records.\n");

			RefinedResponse refinedResponse = new EvaluatorOptimizer(solutionGenerator, solutionEvaluator)
					.loop("""
					<user input>
					Implement a Stack in Java with:
					1. push(x)
					2. pop()
					3. getMin()
					All operations should be O(1).
					All inner fields should be private and when used should be prefixed with 'this.'.
					</user input>
					""", runId);

			System.out.println("FINAL OUTPUT:\n : " + refinedResponse);
		};
	}
}
