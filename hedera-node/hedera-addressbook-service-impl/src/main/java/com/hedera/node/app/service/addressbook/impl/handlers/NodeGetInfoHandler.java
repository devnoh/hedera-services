/*
 * Copyright (C) 2024 Hedera Hashgraph, LLC
 *
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
 */

package com.hedera.node.app.service.addressbook.impl.handlers;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.addressbook.NodeGetInfoQuery;
import com.hedera.hapi.node.addressbook.NodeGetInfoResponse;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.PaidQueryHandler;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.data.LedgerConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#NODE_GET_INFO}.
 */
@Singleton
public class NodeGetInfoHandler extends PaidQueryHandler {

    @Inject
    public NodeGetInfoHandler() {
        // Dagger 2
    }

    @Override
    public QueryHeader extractHeader(@NonNull final Query query) {
        requireNonNull(query);
        return query.nodeGetInfoOrThrow().header();
    }

    @Override
    public Response createEmptyResponse(@NonNull final ResponseHeader header) {
        requireNonNull(header);
        final var response = NodeGetInfoResponse.newBuilder().header(header);
        return Response.newBuilder().nodeGetInfo(response).build();
    }

    @Override
    public void validate(@NonNull final QueryContext context) throws PreCheckException {
        requireNonNull(context);
        final var query = context.query();
        final var store = context.createStore(ReadableNodeStore.class);
        final NodeGetInfoQuery op = query.nodeGetInfoOrThrow();
        throw new UnsupportedOperationException("need implementation");
    }

    @Override
    public Response findResponse(@NonNull final QueryContext context, @NonNull final ResponseHeader header) {
        requireNonNull(context);
        requireNonNull(header);
        final var query = context.query();
        final var config = context.configuration().getConfigData(LedgerConfig.class);
        throw new UnsupportedOperationException("need implementation");
    }

    @NonNull
    @Override
    public Fees computeFees(@NonNull QueryContext queryContext) {
        final var query = queryContext.query();
        final var nodeStore = queryContext.createStore(ReadableNodeStore.class);
        final var op = query.nodeGetInfoOrThrow();
        throw new UnsupportedOperationException("need implementation");
    }
}