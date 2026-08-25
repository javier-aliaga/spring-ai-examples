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

import java.util.Map;
import java.util.UUID;

import io.diagrid.springai.durable.boot.DurableAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.util.Assert;

/**
 * Implements the Routing workflow pattern that classifies input and directs it
 * to specialized
 * followup tasks. This workflow enables separation of concerns by routing
 * different types
 * of inputs to specialized prompts and processes optimized for specific
 * categories.
 * 
 * <p>
 * The routing workflow is particularly effective for complex tasks where:
 * <ul>
 * <li>There are distinct categories of input that are better handled
 * separately</li>
 * <li>Classification can be handled accurately by an LLM or traditional
 * classification model</li>
 * <li>Different types of input require different specialized processing or
 * expertise</li>
 * </ul>
 * 
 * <p>
 * Common use cases include:
 * <ul>
 * <li>Customer support systems routing different types of queries (billing,
 * technical, etc.)</li>
 * <li>Content moderation systems routing content to appropriate review
 * processes</li>
 * <li>Query optimization by routing simple/complex questions to different model
 * capabilities</li>
 * </ul>
 * 
 * <p>
 * This implementation allows for dynamic routing based on content
 * classification,
 * with each route having its own specialized prompt optimized for specific
 * types of input.
 * 
 * <p/>
 * Implementation uses the <a href=
 * "https://docs.spring.io/spring-ai/reference/1.0/api/structured-output-converter.html">Spring
 * AI Structure Output</a> to convert the chat client response into a structured
 * {@link RoutingResponse} object.
 * 
 * @author Christian Tzolov
 * @see org.springframework.ai.chat.client.ChatClient
 * @see <a href=
 *      "https://docs.spring.io/spring-ai/reference/1.0/api/chatclient.html">Spring
 *      AI ChatClient</a>
 * @see <a href=
 *      "https://www.anthropic.com/research/building-effective-agents">Building
 *      Effective Agents</a>
 * @see <a href=
 *      "https://docs.spring.io/spring-ai/reference/1.0/api/structured-output-converter.html">Spring
 *      AI Structure Output</a>
 * 
 */
public class RoutingWorkflow {

    private final ChatClient classifier;

    private final ChatClient specialist;

    /**
     * Uses one client for both the classification and the specialist reply.
     */
    public RoutingWorkflow(ChatClient chatClient) {
        this(chatClient, chatClient);
    }

    /**
     * Uses a distinct client for each role, so the two show up as separate agents on Catalyst
     * (each with its own workflow name and agent-registry entry) rather than as one.
     *
     * @param classifier the client that picks the route
     * @param specialist the client that answers with the selected route's prompt
     */
    public RoutingWorkflow(ChatClient classifier, ChatClient specialist) {
        this.classifier = classifier;
        this.specialist = specialist;
    }

    /**
     * Routes input to a specialized prompt based on content classification. This
     * method
     * first analyzes the input to determine the most appropriate route, then
     * processes
     * the input using the specialized prompt for that route.
     * 
     * <p>
     * The routing process involves:
     * <ol>
     * <li>Content analysis to determine the appropriate category</li>
     * <li>Selection of a specialized prompt optimized for that category</li>
     * <li>Processing the input with the selected prompt</li>
     * </ol>
     *
     * <p>
     * This approach allows for:
     * <ul>
     * <li>Better handling of diverse input types</li>
     * <li>Optimization of prompts for specific categories</li>
     * <li>Improved accuracy through specialized processing</li>
     * </ul>
     *
     * @param input  The input text to be routed and processed
     * @param routes Map of route names to their corresponding specialized prompts
     * @return Processed response from the selected specialized route
     */
    public String route(String input, Map<String, String> routes) {
        return route(input, routes, UUID.randomUUID().toString());
    }

    /**
     * As {@link #route(String, Map)}, but scheduling the classification and the specialist reply
     * under durable instance ids derived from {@code runId} ({@code <runId>-classify} and
     * {@code <runId>-handle}). Re-running with the same run id replays a completed
     * classification from its record — so the same route is chosen and the specialist reply,
     * the expensive half, is the only work left to do.
     *
     * @param runId identifies this ticket's run; see {@link #route(String, Map)} for the rest
     */
    public String route(String input, Map<String, String> routes, String runId) {
        Assert.notNull(input, "Input text cannot be null");
        Assert.notEmpty(routes, "Routes map cannot be null or empty");

        // Determine the appropriate route for the input
        String routeKey = determineRoute(input, routes.keySet(), runId + "-classify");

        // Get the selected prompt from the routes map
        String selectedPrompt = routes.get(routeKey);

        if (selectedPrompt == null) {
            throw new IllegalArgumentException("Selected route '" + routeKey + "' not found in routes map");
        }

        // Process the input with the selected prompt
        return specialist.prompt(selectedPrompt + "\nInput: " + input)
                .advisors(a -> a.param(DurableAdvisor.INSTANCE_ID_KEY, runId + "-handle"))
                .call()
                .content();
    }

    /**
     * Analyzes the input content and determines the most appropriate route based on
     * content classification. The classification process considers key terms,
     * context,
     * and patterns in the input to select the optimal route.
     * 
     * <p>
     * The method uses an LLM to:
     * <ul>
     * <li>Analyze the input content and context</li>
     * <li>Consider the available routing options</li>
     * <li>Provide reasoning for the routing decision</li>
     * <li>Select the most appropriate route</li>
     * </ul>
     *
     * @param input           The input text to analyze for routing
     * @param availableRoutes The set of available routing options
     * @param instanceId      The durable instance id this classification runs under
     * @return The selected route key based on content analysis
     */
    @SuppressWarnings("null")
    private String determineRoute(String input, Iterable<String> availableRoutes, String instanceId) {
        System.out.println("\nAvailable routes: " + availableRoutes);

        String selectorPrompt = String.format("""
                Analyze the input and select the most appropriate support team from these options: %s
                First explain your reasoning, then provide your selection in this JSON format:

                \\{
                    "reasoning": "Brief explanation of why this ticket should be routed to a specific team.
                                Consider key terms, user intent, and urgency level.",
                    "selection": "The chosen team name"
                \\}

                Input: %s""", availableRoutes, input);

        RoutingResponse routingResponse = classifier.prompt(selectorPrompt)
                .advisors(a -> a.param(DurableAdvisor.INSTANCE_ID_KEY, instanceId))
                .call()
                .entity(RoutingResponse.class);

        System.out.println(String.format("Routing Analysis:%s\nSelected route: %s",
                routingResponse.reasoning(), routingResponse.selection()));

        return routingResponse.selection();
    }
}
