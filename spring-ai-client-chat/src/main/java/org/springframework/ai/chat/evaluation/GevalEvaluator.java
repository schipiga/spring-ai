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

import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;

/**
 * Evaluates a response using the two-stage G-Eval approach on a 0-to-10 scale.
 */
public class GevalEvaluator extends XevalEvaluator {

	private static final int MAX_SCORE = 10;

	private GevalEvaluator(ChatClient.Builder chatClientBuilder, String criteria,
			@Nullable PromptTemplate stepsPromptTemplate, @Nullable PromptTemplate evaluationPromptTemplate) {
		super(chatClientBuilder, criteria, MAX_SCORE, stepsPromptTemplate, evaluationPromptTemplate);
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder extends XevalEvaluator.Builder<GevalEvaluator, Builder> {

		private Builder() {
		}

		@Override
		protected Builder self() {
			return this;
		}

		@Override
		protected GevalEvaluator createEvaluator(ChatClient.Builder chatClientBuilder, String criteria,
				@Nullable PromptTemplate stepsPromptTemplate, @Nullable PromptTemplate evaluationPromptTemplate) {
			return new GevalEvaluator(chatClientBuilder, criteria, stepsPromptTemplate, evaluationPromptTemplate);
		}

	}

}
