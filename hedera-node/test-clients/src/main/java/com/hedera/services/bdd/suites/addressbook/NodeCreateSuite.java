/*
 * Copyright (C) 2020-2024 Hedera Hashgraph, LLC
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

package com.hedera.services.bdd.suites.addressbook;

import static com.hedera.services.bdd.junit.TestTags.EMBEDDED;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewNode;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(TOKEN)
public class NodeCreateSuite {
    @HapiTest
    @Tag(EMBEDDED)
    final Stream<DynamicTest> createNodeWorks() {
        final String description = "His vorpal blade went snicker-snack!";

        return hapiTest(
                nodeCreate("ntb").description(description),
                viewNode(
                        "ntb", node -> assertEquals(description, node.description(), "Node was created successfully")));
    }
}