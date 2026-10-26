/*
 * Copyright 2023-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.chat.evaluation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.ai.evaluation.Evaluator;
import org.springframework.util.Assert;

/**
 * Base implementation for G-Eval-style evaluators that first generate evaluation steps
 * and then score a response against those steps.
 */
public abstract class XevalEvaluator implements Evaluator {

	private final ChatClient.Builder chatClientBuilder;

	private final String criteria;

	private final int maxScore;

	private final PromptTemplate stepsPromptTemplate;

	private final PromptTemplate evaluationPromptTemplate;

	private volatile @Nullable List<String> cachedSteps;

	public abstract static class Builder<T extends XevalEvaluator, B extends Builder<T, B>> {

		protected @Nullable ChatClient.Builder chatClientBuilder;

		protected @Nullable String criteria;

		protected @Nullable PromptTemplate stepsPromptTemplate;

		protected @Nullable PromptTemplate evaluationPromptTemplate;

		protected Builder() {
		}

		public B chatClientBuilder(ChatClient.Builder chatClientBuilder) {
			this.chatClientBuilder = chatClientBuilder;
			return self();
		}

		public B criteria(String criteria) {
			this.criteria = criteria;
			return self();
		}

		public B stepsPromptTemplate(PromptTemplate stepsPromptTemplate) {
			this.stepsPromptTemplate = stepsPromptTemplate;
			return self();
		}

		public B evaluationPromptTemplate(PromptTemplate evaluationPromptTemplate) {
			this.evaluationPromptTemplate = evaluationPromptTemplate;
			return self();
		}

		public T build() {
			Assert.state(this.chatClientBuilder != null, "chatClientBuilder cannot be null");
			Assert.state(this.criteria != null && !this.criteria.isBlank(), "criteria cannot be null or empty");
			return createEvaluator(this.chatClientBuilder, this.criteria, this.stepsPromptTemplate,
					this.evaluationPromptTemplate);
		}

		protected abstract B self();

		protected abstract T createEvaluator(ChatClient.Builder chatClientBuilder, String criteria,
				@Nullable PromptTemplate stepsPromptTemplate, @Nullable PromptTemplate evaluationPromptTemplate);

	}

	protected XevalEvaluator(ChatClient.Builder chatClientBuilder, String criteria, int maxScore,
			@Nullable PromptTemplate stepsPromptTemplate, @Nullable PromptTemplate evaluationPromptTemplate) {
		Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
		Assert.hasText(criteria, "criteria cannot be null or empty");
		Assert.isTrue(maxScore > 0, "maxScore must be greater than zero");
		this.chatClientBuilder = chatClientBuilder;
		this.criteria = criteria;
		this.maxScore = maxScore;
		this.stepsPromptTemplate = stepsPromptTemplate != null ? stepsPromptTemplate
				: EvaluationPrompts.DEFAULT_STEPS_PROMPT_TEMPLATE;
		this.evaluationPromptTemplate = evaluationPromptTemplate != null ? evaluationPromptTemplate
				: EvaluationPrompts.DEFAULT_EVALUATION_PROMPT_TEMPLATE;
	}

	@Override
	public EvaluationResponse evaluate(EvaluationRequest evaluationRequest) {
		List<String> steps = getSteps();
		String formattedSteps = steps.stream()
			.map(step -> "- " + step)
			.collect(Collectors.joining(System.lineSeparator()));

		String evaluationPrompt = this.evaluationPromptTemplate.render(Map.of("criteria", this.criteria,
				"steps", formattedSteps, "maxScore", this.maxScore, "input", evaluationRequest.getUserText(), "output",
				evaluationRequest.getResponseContent()));
		String evaluationResponse = this.chatClientBuilder.build().prompt().user(evaluationPrompt).call().content();
		JsonNode result = parseJsonObject(evaluationResponse, "Evaluation response");

		JsonNode score = result.get("score");
		JsonNode reason = result.get("reason");
		Assert.state(score != null && score.isIntegralNumber(), "Evaluation response must contain an integer 'score'");
		Assert.state(reason != null && reason.isTextual(), "Evaluation response must contain a string 'reason'");
		int numericScore = score.intValue();
		Assert.state(numericScore >= 0 && numericScore <= this.maxScore,
				"Evaluation response score must be between 0 and " + this.maxScore);

		return new EvaluationResponse(numericScore == this.maxScore, numericScore, reason.textValue(),
				Collections.emptyMap());
	}

	private List<String> getSteps() {
		List<String> steps = this.cachedSteps;
		if (steps == null) {
			synchronized (this) {
				steps = this.cachedSteps;
				if (steps == null) {
					String stepsPrompt = this.stepsPromptTemplate.render(Map.of("criteria", this.criteria));
					String stepsResponse = this.chatClientBuilder.build().prompt().user(stepsPrompt).call().content();
					steps = List.copyOf(parseSteps(stepsResponse));
					this.cachedSteps = steps;
				}
			}
		}
		return steps;
	}

	private List<String> parseSteps(@Nullable String stepsResponse) {
		JsonNode result = parseJsonObject(stepsResponse, "Evaluation steps response");
		JsonNode steps = result.get("steps");
		Assert.state(steps != null && steps.isArray() && steps.size() > 0,
				"Evaluation steps response must contain a non-empty 'steps' array");
		List<String> parsedSteps = new ArrayList<>();
		for (JsonNode step : steps) {
			Assert.state(step.isTextual() && !step.textValue().isBlank(),
					"Evaluation steps response must contain only non-blank strings");
			parsedSteps.add(step.textValue());
		}
		return parsedSteps;
	}

	private JsonNode parseJsonObject(@Nullable String response, String responseDescription) {
		Assert.state(response != null && !response.isBlank(), responseDescription + " must not be null or blank");
		try {
			JsonNode result = JsonMapper.shared().readTree(response);
			Assert.state(result != null && result.isObject(), responseDescription + " must be a JSON object");
			return result;
		}
		catch (JacksonException ex) {
			throw new IllegalStateException(responseDescription + " must be valid JSON", ex);
		}
	}

}
