package com.hedera.services.queries.answering;

/*-
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2020 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.hedera.services.queries.AnswerFlow;
import com.hedera.services.queries.AnswerService;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.Response;
import com.hedera.services.legacy.services.stats.HederaNodeStats;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import static com.hedera.services.context.primitives.StateView.EMPTY_VIEW;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FAIL_INVALID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

public class QueryResponseHelper {
	private static final Logger log = LogManager.getLogger(QueryResponseHelper.class);
	private static final Marker ALL_QUERIES_MARKER = MarkerManager.getMarker("ALL_QUERIES");

	private final AnswerFlow answerFlow;
	private final HederaNodeStats stats;

	public QueryResponseHelper(AnswerFlow answerFlow, HederaNodeStats stats) {
		this.answerFlow = answerFlow;
		this.stats = stats;
	}

	public void respondToNetwork(
			Query query,
			StreamObserver<Response> observer,
			AnswerService answer,
			String metric
	) {
		respondWithMetrics(
				query,
				observer,
				answer,
				() -> stats.networkQueryReceived(metric),
				() -> stats.networkQueryAnswered(metric));
	}

	public void respondToHcs(
			Query query,
			StreamObserver<Response> observer,
			AnswerService answer,
			String metric
	) {
		respondWithMetrics(
				query,
				observer,
				answer,
				() -> stats.hcsQueryReceived(metric),
				() -> stats.hcsQueryAnswered(metric));
	}

	public void respondToCrypto(
			Query query,
			StreamObserver<Response> observer,
			AnswerService answer,
			String metric
	) {
		respondWithMetrics(
				query,
				observer,
				answer,
				() -> stats.cryptoQueryReceived(metric),
				() -> stats.cryptoQuerySubmitted(metric));
	}

	public void respondToFile(
			Query query,
			StreamObserver<Response> observer,
			AnswerService answer,
			String metric
	) {
		respondWithMetrics(
				query,
				observer,
				answer,
				() -> stats.fileQueryReceived(metric),
				() -> stats.fileQuerySubmitted(metric));
	}

	public void respondToContract(
			Query query,
			StreamObserver<Response> observer,
			AnswerService answer,
			String metric
	) {
		respondWithMetrics(
				query,
				observer,
				answer,
				() -> stats.smartContractQueryReceived(metric),
				() -> stats.smartContractQuerySubmitted(metric));
	}

	private void respondWithMetrics(
			Query query,
			StreamObserver<Response> observer,
			AnswerService answer,
			Runnable incReceivedCount,
			Runnable incAnsweredCount
	) {
		if (log.isDebugEnabled()) {
			log.debug(ALL_QUERIES_MARKER, "Received query: {}", query);
		}
		Response response;
		incReceivedCount.run();

		try {
			response = answerFlow.satisfyUsing(answer, query);
		} catch (Exception surprising) {
			log.warn("Query flow unable to satisfy query {}!", query, surprising);
			response = answer.responseGiven(query, EMPTY_VIEW, FAIL_INVALID, 0L);
		}

		observer.onNext(response);
		observer.onCompleted();

		if (answer.extractValidityFrom(response) == OK) {
			incAnsweredCount.run();
		}
	}
}
