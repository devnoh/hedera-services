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

package com.hedera.node.app.workflows.handle.flow;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.REVERTED_SUCCESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNRESOLVABLE_REQUIRED_SIGNERS;
import static com.hedera.hapi.node.base.SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES;
import static com.hedera.hapi.util.HapiUtils.functionOf;
import static com.hedera.node.app.spi.authorization.SystemPrivilege.IMPERMISSIBLE;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.CHILD;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.SCHEDULED;
import static com.hedera.node.app.spi.workflows.record.ExternalizedRecordCustomizer.NOOP_EXTERNALIZED_RECORD_CUSTOMIZER;
import static com.hedera.node.app.workflows.handle.flow.dispatch.child.logic.ChildRecordBuilderFactoryTest.asTxn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FeeData;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.consensus.ConsensusSubmitMessageTransactionBody;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.util.UnknownHederaFunctionality;
import com.hedera.node.app.fees.ChildFeeContextImpl;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.ids.WritableEntityIdStore;
import com.hedera.node.app.records.BlockRecordManager;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.records.CryptoCreateRecordBuilder;
import com.hedera.node.app.services.ServiceScopeLookup;
import com.hedera.node.app.signature.KeyVerifier;
import com.hedera.node.app.signature.impl.SignatureVerificationImpl;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.fees.ExchangeRateInfo;
import com.hedera.node.app.spi.fees.FeeAccumulator;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.fixtures.Scenarios;
import com.hedera.node.app.spi.fixtures.state.MapWritableStates;
import com.hedera.node.app.spi.metrics.StoreMetricsService;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.node.app.spi.signatures.VerificationAssistant;
import com.hedera.node.app.spi.workflows.ComputeDispatchFeesAsTopLevel;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.record.RecordListCheckPoint;
import com.hedera.node.app.spi.workflows.record.SingleTransactionRecordBuilder;
import com.hedera.node.app.state.HederaRecordCache;
import com.hedera.node.app.state.WrappedHederaState;
import com.hedera.node.app.throttle.NetworkUtilizationManager;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.dispatcher.ReadableStoreFactory;
import com.hedera.node.app.workflows.dispatcher.ServiceApiFactory;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.app.workflows.dispatcher.WritableStoreFactory;
import com.hedera.node.app.workflows.handle.flow.dispatch.Dispatch;
import com.hedera.node.app.workflows.handle.flow.dispatch.child.ChildDispatchComponent;
import com.hedera.node.app.workflows.handle.flow.dispatch.child.logic.ChildDispatchFactory;
import com.hedera.node.app.workflows.handle.flow.dispatch.logic.DispatchProcessor;
import com.hedera.node.app.workflows.handle.record.RecordListBuilder;
import com.hedera.node.app.workflows.handle.record.SingleTransactionRecordBuilderImpl;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.app.workflows.handle.validation.AttributeValidatorImpl;
import com.hedera.node.app.workflows.handle.validation.ExpiryValidatorImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.test.fixtures.state.MapReadableKVState;
import com.swirlds.platform.test.fixtures.state.MapReadableStates;
import com.swirlds.platform.test.fixtures.state.MapWritableKVState;
import com.swirlds.platform.test.fixtures.state.StateTestBase;
import com.swirlds.state.HederaState;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.spi.info.NetworkInfo;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.inject.Provider;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class DispatchHandleContextTest extends StateTestBase implements Scenarios {
    private static final long GAS_LIMIT = 456L;
    private static final Fees FEES = new Fees(1L, 2L, 3L);
    public static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(1_234_567L, 890);
    private static final AccountID PAYER_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(1_234).build();
    private static final AccountID NODE_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(3).build();
    private static final TransactionBody CONTRACT_CALL_TXN_BODY = TransactionBody.newBuilder()
            .transactionID(
                    TransactionID.newBuilder().accountID(PAYER_ACCOUNT_ID).build())
            .contractCall(
                    ContractCallTransactionBody.newBuilder().gas(GAS_LIMIT).build())
            .build();
    private static final SignatureVerification FAILED_VERIFICATION =
            new SignatureVerificationImpl(Key.DEFAULT, Bytes.EMPTY, false);
    private static final TransactionBody MISSING_FUNCTION_TXN_BODY = TransactionBody.newBuilder()
            .transactionID(
                    TransactionID.newBuilder().accountID(PAYER_ACCOUNT_ID).build())
            .build();
    private static final TransactionBody CRYPTO_TRANSFER_TXN_BODY = TransactionBody.newBuilder()
            .transactionID(
                    TransactionID.newBuilder().accountID(PAYER_ACCOUNT_ID).build())
            .cryptoTransfer(CryptoTransferTransactionBody.DEFAULT)
            .build();
    private static final TransactionInfo CRYPTO_TRANSFER_TXN_INFO = new TransactionInfo(
            Transaction.DEFAULT, CRYPTO_TRANSFER_TXN_BODY, SignatureMap.DEFAULT, Bytes.EMPTY, CRYPTO_TRANSFER);
    private static final TransactionInfo CONTRACT_CALL_TXN_INFO = new TransactionInfo(
            Transaction.DEFAULT, CONTRACT_CALL_TXN_BODY, SignatureMap.DEFAULT, Bytes.EMPTY, CONTRACT_CALL);

    @Mock
    private FeeAccumulator accumulator;

    @Mock
    private KeyVerifier verifier;

    @Mock
    private NetworkInfo networkInfo;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private TransactionDispatcher dispatcher;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private ServiceScopeLookup serviceScopeLookup;

    @Mock
    private BlockRecordManager blockRecordManager;

    @Mock
    private HederaRecordCache recordCache;

    @Mock
    private FeeManager feeManager;

    @Mock
    private FeeCalculator feeCalculator;

    @Mock
    private ExchangeRateManager exchangeRateManager;

    @Mock
    private Authorizer authorizer;

    @Mock
    private SignatureVerification verification;

    @Mock
    private NetworkUtilizationManager networkUtilizationManager;

    @Mock
    private StoreMetricsService storeMetricsService;

    @Mock
    private WritableEntityIdStore entityIdStore;

    @Mock
    private SingleTransactionRecordBuilderImpl oneChildBuilder;

    @Mock
    private SingleTransactionRecordBuilderImpl twoChildBuilder;

    @Mock
    private Provider<ChildDispatchComponent.Factory> childDispatchProvider;

    @Mock
    private ChildDispatchFactory childDispatchFactory;

    @Mock
    private Dispatch parentDispatch;

    @Mock
    private Dispatch childDispatch;

    @Mock
    private DispatchProcessor dispatchProcessor;

    @Mock
    private RecordListBuilder recordListBuilder;

    @Mock(strictness = LENIENT)
    private HederaState baseState;

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableSingletonState<EntityNumber> entityNumberState;

    @Mock
    private ExchangeRateInfo exchangeRateInfo;

    @Mock
    private SavepointStackImpl stack;

    @Mock
    private WrappedHederaState wrappedHederaState;

    @Mock
    private WritableStoreFactory writableStoreFactory;

    @Mock
    private WritableAccountStore writableAccountStore;

    @Mock
    private VerificationAssistant assistant;

    private ServiceApiFactory apiFactory;
    private ReadableStoreFactory readableStoreFactory;
    private DispatchHandleContext subject;

    private static final AccountID payerId = ALICE.accountID();
    private static final CryptoTransferTransactionBody transferBody = CryptoTransferTransactionBody.newBuilder()
            .tokenTransfers(TokenTransferList.newBuilder()
                    .token(TokenID.DEFAULT)
                    .nftTransfers(NftTransfer.newBuilder()
                            .receiverAccountID(AccountID.DEFAULT)
                            .senderAccountID(AccountID.DEFAULT)
                            .serialNumber(1)
                            .build())
                    .build())
            .build();
    private static final TransactionBody txBody = asTxn(transferBody, payerId, CONSENSUS_NOW);
    private final Configuration configuration = HederaTestConfigBuilder.createConfig();
    private SingleTransactionRecordBuilderImpl parentRecordBuilder =
            new SingleTransactionRecordBuilderImpl(CONSENSUS_NOW);
    private SingleTransactionRecordBuilderImpl childRecordBuilder =
            new SingleTransactionRecordBuilderImpl(CONSENSUS_NOW);
    private final TransactionBody txnBodyWithoutId = TransactionBody.newBuilder()
            .consensusSubmitMessage(ConsensusSubmitMessageTransactionBody.DEFAULT)
            .build();
    private static final TransactionInfo txnInfo = new TransactionInfo(
            Transaction.newBuilder().body(txBody).build(), txBody, SignatureMap.DEFAULT, Bytes.EMPTY, CRYPTO_TRANSFER);

    @BeforeEach
    void setup() {
        when(serviceScopeLookup.getServiceName(any())).thenReturn(TokenService.NAME);
        readableStoreFactory = new ReadableStoreFactory(baseState);
        apiFactory = new ServiceApiFactory(stack, configuration, storeMetricsService);
        subject = createContext(txBody);

        mockNeeded();
    }

    @Test
    void numTxnSignaturesConsultsVerifier() {
        given(verifier.numSignaturesVerified()).willReturn(2);
        assertThat(subject.numTxnSignatures()).isEqualTo(2);
    }

    @Test
    void dispatchComputeFeesDelegatesWithBodyAndNotFree() {
        given(dispatcher.dispatchComputeFees(any())).willReturn(FEES);
        assertThat(subject.dispatchComputeFees(CRYPTO_TRANSFER_TXN_BODY, PAYER_ACCOUNT_ID))
                .isSameAs(FEES);
    }

    @Test
    void dispatchComputeThrowsWithMissingBody() {
        Assertions.assertThatThrownBy(() -> subject.dispatchComputeFees(MISSING_FUNCTION_TXN_BODY, PAYER_ACCOUNT_ID))
                .isInstanceOf(HandleException.class);
    }

    @Test
    void dispatchComputeFeesDelegatesWithFree() {
        given(authorizer.hasWaivedFees(PAYER_ACCOUNT_ID, CRYPTO_TRANSFER, CRYPTO_TRANSFER_TXN_BODY))
                .willReturn(true);
        assertThat(subject.dispatchComputeFees(CRYPTO_TRANSFER_TXN_BODY, PAYER_ACCOUNT_ID))
                .isSameAs(Fees.FREE);
        verifyNoInteractions(dispatcher);
    }

    @Test
    void usesBlockRecordManagerForInfo() {
        assertThat(subject.blockRecordInfo()).isSameAs(blockRecordManager);
    }

    @Test
    void getsResourcePrices() {
        given(feeManager.getFeeData(CRYPTO_TRANSFER, CONSENSUS_NOW, TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES))
                .willReturn(FeeData.DEFAULT);
        assertThat(subject.resourcePricesFor(CRYPTO_TRANSFER, TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES))
                .isNotNull();
    }

    @Test
    void getsFeeCalculator() {
        given(verifier.numSignaturesVerified()).willReturn(2);
        given(feeManager.createFeeCalculator(
                        any(),
                        eq(Key.DEFAULT),
                        eq(CRYPTO_TRANSFER_TXN_INFO.functionality()),
                        eq(2),
                        eq(0),
                        eq(CONSENSUS_NOW),
                        eq(TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES),
                        eq(false),
                        eq(readableStoreFactory)))
                .willReturn(feeCalculator);
        assertThat(subject.feeCalculator(TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES))
                .isSameAs(feeCalculator);
    }

    @Test
    void getsFeeAccumulator() {
        assertThat(subject.feeAccumulator()).isSameAs(accumulator);
    }

    @Test
    void getsAttributeValidator() {
        assertThat(subject.attributeValidator()).isInstanceOf(AttributeValidatorImpl.class);
    }

    @Test
    void getsExpiryValidator() {
        assertThat(subject.expiryValidator()).isInstanceOf(ExpiryValidatorImpl.class);
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void testConstructorWithInvalidArguments() {
        final var allArgs = new Object[] {
            CONSENSUS_NOW,
            txnInfo,
            configuration,
            authorizer,
            blockRecordManager,
            feeManager,
            readableStoreFactory,
            payerId,
            verifier,
            Key.newBuilder().build(),
            accumulator,
            exchangeRateManager,
            stack,
            entityIdStore,
            dispatcher,
            recordCache,
            writableStoreFactory,
            apiFactory,
            networkInfo,
            parentRecordBuilder,
            childDispatchProvider,
            childDispatchFactory,
            parentDispatch,
            dispatchProcessor,
            networkUtilizationManager
        };

        final var constructor = DispatchHandleContext.class.getConstructors()[0];
        for (int i = 0; i < allArgs.length; i++) {
            final var index = i;
            // Skip signatureMapSize and payerKey
            if (index == 2 || index == 4) {
                continue;
            }
            assertThatThrownBy(() -> {
                        final var argsWithNull = Arrays.copyOf(allArgs, allArgs.length);
                        argsWithNull[index] = null;
                        constructor.newInstance(argsWithNull);
                    })
                    .isInstanceOf(InvocationTargetException.class)
                    .hasCauseInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Handling of record list checkpoint creation")
    final class RevertRecordFromCheckPointTest {
        @Test
        void successCreateRecordListCheckPoint() {
            var precedingRecord = createRecordBuilder();
            var childRecord = createRecordBuilder();
            given(recordListBuilder.precedingRecordBuilders()).willReturn(List.of(precedingRecord));
            given(recordListBuilder.childRecordBuilders()).willReturn(List.of(childRecord));

            final var actual = subject.createRecordListCheckPoint();

            assertThat(actual).isEqualTo(new RecordListCheckPoint(precedingRecord, childRecord));
        }

        @Test
        void successCreateRecordListCheckPoint_MultipleRecords() {
            var precedingRecord = createRecordBuilder();
            var precedingRecord1 = createRecordBuilder();
            var childRecord = createRecordBuilder();
            var childRecord1 = createRecordBuilder();

            given(recordListBuilder.precedingRecordBuilders()).willReturn(List.of(precedingRecord, precedingRecord1));
            given(recordListBuilder.childRecordBuilders()).willReturn(List.of(childRecord, childRecord1));

            final var actual = subject.createRecordListCheckPoint();

            assertThat(actual).isEqualTo(new RecordListCheckPoint(precedingRecord1, childRecord1));
        }

        @Test
        void success_createRecordListCheckPoint_null_values() {
            final var actual = subject.createRecordListCheckPoint();
            assertThat(actual).isEqualTo(new RecordListCheckPoint(null, null));
        }

        private static SingleTransactionRecordBuilderImpl createRecordBuilder() {
            return new SingleTransactionRecordBuilderImpl(Instant.EPOCH);
        }
    }

    @Nested
    @DisplayName("Handling new EntityNumber")
    final class EntityIdNumTest {
        @Test
        void testNewEntityNumWithInitialState() {
            when(entityIdStore.incrementAndGet()).thenReturn(1L);
            final var actual = subject.newEntityNum();

            assertThat(actual).isEqualTo(1L);
            verify(entityIdStore).incrementAndGet();
        }

        @Test
        void testPeekingAtNewEntityNumWithInitialState() {
            when(entityIdStore.peekAtNextNumber()).thenReturn(1L);
            final var actual = subject.peekAtNewEntityNum();

            assertThat(actual).isEqualTo(1L);

            verify(entityIdStore).peekAtNextNumber();
        }

        @Test
        void testNewEntityNum() {
            when(entityIdStore.incrementAndGet()).thenReturn(43L);

            final var actual = subject.newEntityNum();

            assertThat(actual).isEqualTo(43L);
            verify(entityIdStore).incrementAndGet();
            verify(entityIdStore, never()).peekAtNextNumber();
        }

        @Test
        void testPeekingAtNewEntityNum() {
            when(entityIdStore.peekAtNextNumber()).thenReturn(43L);

            final var actual = subject.peekAtNewEntityNum();

            assertThat(actual).isEqualTo(43L);
            verify(entityIdStore).peekAtNextNumber();
            verify(entityIdStore, never()).incrementAndGet();
        }
    }

    @Test
    void getsExpectedValues() {
        assertThat(subject.body()).isSameAs(txBody);
        assertThat(subject.networkInfo()).isSameAs(networkInfo);
        assertThat(subject.payer()).isEqualTo(payerId);
        assertThat(subject.networkInfo()).isEqualTo(networkInfo);
        assertThat(subject.recordCache()).isEqualTo(recordCache);
        assertThat(subject.savepointStack()).isEqualTo(stack);
        assertThat(subject.configuration()).isEqualTo(configuration);
        assertThat(subject.authorizer()).isEqualTo(authorizer);
    }

    @Nested
    @DisplayName("Handling of stack data")
    final class StackDataTest {
        @Test
        void testGetStack() {
            final var context = createContext(txBody);
            final var actual = context.savepointStack();
            assertThat(actual).isEqualTo(stack);
        }

        @Test
        void testCreateReadableStore() {
            final var context = createContext(txBody);

            final var store = context.readableStore(ReadableAccountStore.class);
            assertThat(store).isNotNull();
        }

        @Test
        void testCreateWritableStore() {
            final var context = createContext(txBody);
            given(writableStoreFactory.getStore(WritableAccountStore.class)).willReturn(writableAccountStore);
            assertThat(context.writableStore(WritableAccountStore.class)).isSameAs(writableAccountStore);
        }

        @SuppressWarnings("ConstantConditions")
        @Test
        void testCreateStoreWithInvalidParameters() {
            final var context = createContext(txBody);

            assertThatThrownBy(() -> context.readableStore(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> context.readableStore(List.class)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> context.writableStore(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Handling of verification data")
    final class VerificationDataTest {
        @SuppressWarnings("ConstantConditions")
        @Test
        void testVerificationForWithInvalidParameters() {
            final var context = createContext(txBody);

            assertThatThrownBy(() -> context.verificationFor((Key) null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> context.verificationFor((Bytes) null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void testVerificationForKey() {
            when(verifier.verificationFor(Key.DEFAULT)).thenReturn(verification);
            final var context = createContext(txBody);

            final var actual = context.verificationFor(Key.DEFAULT);

            assertThat(actual).isEqualTo(verification);
        }

        @Test
        void testVerificationForAlias() {
            when(verifier.verificationFor(ERIN.account().alias())).thenReturn(verification);
            final var context = createContext(txBody);
            final var actual = context.verificationFor(ERIN.account().alias());

            assertThat(actual).isEqualTo(verification);
        }
    }

    @Nested
    @DisplayName("Requesting keys of child transactions")
    final class KeyRequestTest {
        @SuppressWarnings("ConstantConditions")
        @Test
        void testAllKeysForTransactionWithInvalidParameters() {
            final var bob = BOB.accountID();
            assertThatThrownBy(() -> subject.allKeysForTransaction(null, bob)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> subject.allKeysForTransaction(txBody, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void testAllKeysForTransactionSuccess() throws PreCheckException {
            doAnswer(invocation -> {
                        final var innerContext = invocation.getArgument(0, PreHandleContext.class);
                        innerContext.requireKey(BOB.account().key());
                        innerContext.optionalKey(CAROL.account().key());
                        return null;
                    })
                    .when(dispatcher)
                    .dispatchPreHandle(any());

            final var keys = subject.allKeysForTransaction(txBody, ERIN.accountID());
            assertThat(keys.payerKey()).isEqualTo(ERIN.account().key());
            assertThat(keys.requiredNonPayerKeys())
                    .containsExactly(BOB.account().key());
            assertThat(keys.optionalNonPayerKeys())
                    .containsExactly(CAROL.account().key());
        }

        @Test
        void testAllKeysForTransactionWithFailingPureCheck() throws PreCheckException {
            doThrow(new PreCheckException(INVALID_TRANSACTION_BODY))
                    .when(dispatcher)
                    .dispatchPureChecks(any());
            assertThatThrownBy(() -> subject.allKeysForTransaction(txBody, ERIN.accountID()))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_TRANSACTION_BODY));
        }

        @Test
        void testAllKeysForTransactionWithFailingPreHandle() throws PreCheckException {
            doThrow(new PreCheckException(INSUFFICIENT_ACCOUNT_BALANCE))
                    .when(dispatcher)
                    .dispatchPreHandle(any());

            // gathering keys should not throw exceptions except for inability to read a key.
            assertThatThrownBy(() -> subject.allKeysForTransaction(txBody, ERIN.accountID()))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(UNRESOLVABLE_REQUIRED_SIGNERS));
        }
    }

    @Nested
    @DisplayName("Dispatching fee computation")
    final class FeeDispatchTest {
        @SuppressWarnings("ConstantConditions")
        @Test
        void invokesComputeFeesDispatchWithChildFeeContextImpl() {
            final var fees = new Fees(1L, 2L, 3L);
            given(dispatcher.dispatchComputeFees(any())).willReturn(fees);
            final var captor = ArgumentCaptor.forClass(FeeContext.class);
            final var result = subject.dispatchComputeFees(txBody, account1002, ComputeDispatchFeesAsTopLevel.NO);
            verify(dispatcher).dispatchComputeFees(captor.capture());
            final var feeContext = captor.getValue();
            assertInstanceOf(ChildFeeContextImpl.class, feeContext);
            assertSame(fees, result);
        }

        @SuppressWarnings("ConstantConditions")
        @Test
        void invokesComputeFeesDispatchWithNoTransactionId() {
            final var fees = new Fees(1L, 2L, 3L);
            given(dispatcher.dispatchComputeFees(any())).willReturn(fees);
            final var captor = ArgumentCaptor.forClass(FeeContext.class);
            final var result =
                    subject.dispatchComputeFees(txnBodyWithoutId, account1002, ComputeDispatchFeesAsTopLevel.NO);
            verify(dispatcher).dispatchComputeFees(captor.capture());
            final var feeContext = captor.getValue();
            assertInstanceOf(ChildFeeContextImpl.class, feeContext);
            assertSame(fees, result);
        }
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void failsAsExpectedWithoutAvailableApi() {
        assertThrows(IllegalArgumentException.class, () -> subject.serviceApi(Object.class));
    }

    @Nested
    @DisplayName("Handling of record builder")
    final class RecordBuilderTest {
        @SuppressWarnings("ConstantConditions")
        @Test
        void testMethodsWithInvalidParameters() {
            final var context = createContext(txBody);

            assertThatThrownBy(() -> context.recordBuilder(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> context.recordBuilder(List.class)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> context.addChildRecordBuilder(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> context.addChildRecordBuilder(List.class))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> context.addRemovableChildRecordBuilder(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> context.addRemovableChildRecordBuilder(List.class))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void testGetRecordBuilder() {
            final var context = createContext(txBody);
            final var actual = context.recordBuilder(CryptoCreateRecordBuilder.class);
            assertThat(actual).isEqualTo(parentRecordBuilder);
        }

        @Test
        void testAddChildRecordBuilder(@Mock final SingleTransactionRecordBuilderImpl childRecordBuilder) {
            when(recordListBuilder.addChild(any(), any())).thenReturn(childRecordBuilder);
            final var context = createContext(txBody);
            final var actual = context.addChildRecordBuilder(CryptoCreateRecordBuilder.class);
            assertThat(actual).isEqualTo(childRecordBuilder);
        }

        @Test
        void testAddRemovableChildRecordBuilder(@Mock final SingleTransactionRecordBuilderImpl childRecordBuilder) {
            when(recordListBuilder.addRemovableChild(any())).thenReturn(childRecordBuilder);
            final var context = createContext(txBody);

            final var actual = context.addRemovableChildRecordBuilder(CryptoCreateRecordBuilder.class);

            assertThat(actual).isEqualTo(childRecordBuilder);
        }
    }

    @Nested
    @DisplayName("Handling of dispatcher")
    final class DispatcherTest {
        private static final Predicate<Key> VERIFIER_CALLBACK = key -> true;
        private static final String FOOD_SERVICE = "FOOD_SERVICE";
        private static final Map<String, String> BASE_DATA = Map.of(
                A_KEY, APPLE,
                B_KEY, BANANA,
                C_KEY, CHERRY,
                D_KEY, DATE,
                E_KEY, EGGPLANT,
                F_KEY, FIG,
                G_KEY, GRAPE);

        @Mock(strictness = LENIENT)
        private HederaState baseState;

        @Mock(strictness = LENIENT, answer = Answers.RETURNS_SELF)
        private SingleTransactionRecordBuilderImpl childRecordBuilder;

        @BeforeEach
        void setup() {
            final var baseKVState = new MapWritableKVState<>(FRUIT_STATE_KEY, new HashMap<>(BASE_DATA));
            final var writableStates =
                    MapWritableStates.builder().state(baseKVState).build();
            final var readableStates = MapReadableStates.builder()
                    .state(new MapReadableKVState(FRUIT_STATE_KEY, new HashMap<>(BASE_DATA)))
                    .build();
            when(baseState.getReadableStates(FOOD_SERVICE)).thenReturn(readableStates);
            when(baseState.getWritableStates(FOOD_SERVICE)).thenReturn(writableStates);
            final var accountsState = new MapWritableKVState<AccountID, Account>("ACCOUNTS");
            accountsState.put(ALICE.accountID(), ALICE.account());
            when(baseState.getWritableStates(TokenService.NAME))
                    .thenReturn(MapWritableStates.builder().state(accountsState).build());

            doAnswer(invocation -> {
                        final var childContext = invocation.getArgument(0, HandleContext.class);
                        final var childStack = (SavepointStackImpl) childContext.savepointStack();
                        childStack
                                .peek()
                                .getWritableStates(FOOD_SERVICE)
                                .get(FRUIT_STATE_KEY)
                                .put(A_KEY, ACAI);
                        return null;
                    })
                    .when(dispatcher)
                    .dispatchHandle(any());
        }

        @SuppressWarnings("ConstantConditions")
        @Test
        void testDispatchWithInvalidArguments() {
            assertThatThrownBy(() -> subject.dispatchPrecedingTransaction(
                            null, SingleTransactionRecordBuilder.class, VERIFIER_CALLBACK, AccountID.DEFAULT))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() ->
                            subject.dispatchPrecedingTransaction(txBody, null, VERIFIER_CALLBACK, AccountID.DEFAULT))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> subject.dispatchChildTransaction(
                            null, SingleTransactionRecordBuilder.class, VERIFIER_CALLBACK, AccountID.DEFAULT, CHILD))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() ->
                            subject.dispatchChildTransaction(txBody, null, VERIFIER_CALLBACK, AccountID.DEFAULT, CHILD))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> subject.dispatchRemovableChildTransaction(
                            null,
                            SingleTransactionRecordBuilder.class,
                            VERIFIER_CALLBACK,
                            AccountID.DEFAULT,
                            NOOP_EXTERNALIZED_RECORD_CUSTOMIZER))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> subject.dispatchRemovableChildTransaction(
                            txBody, null, VERIFIER_CALLBACK, AccountID.DEFAULT, NOOP_EXTERNALIZED_RECORD_CUSTOMIZER))
                    .isInstanceOf(NullPointerException.class);
        }

        private static Stream<Arguments> createContextDispatchers() {
            return Stream.of(
                    Arguments.of((Consumer<HandleContext>) context -> context.dispatchPrecedingTransaction(
                            txBody, SingleTransactionRecordBuilder.class, VERIFIER_CALLBACK, ALICE.accountID())),
                    Arguments.of((Consumer<HandleContext>) context -> context.dispatchChildTransaction(
                            txBody, SingleTransactionRecordBuilder.class, VERIFIER_CALLBACK, ALICE.accountID(), CHILD)),
                    Arguments.of((Consumer<HandleContext>) context -> context.dispatchRemovableChildTransaction(
                            txBody,
                            SingleTransactionRecordBuilder.class,
                            VERIFIER_CALLBACK,
                            ALICE.accountID(),
                            (ignore) -> Transaction.DEFAULT)));
        }

        @ParameterizedTest
        @MethodSource("createContextDispatchers")
        void testDispatchPreHandleFails(final Consumer<HandleContext> contextDispatcher) throws PreCheckException {
            final var txBody = TransactionBody.newBuilder()
                    .transactionID(TransactionID.newBuilder().accountID(ALICE.accountID()))
                    .consensusSubmitMessage(ConsensusSubmitMessageTransactionBody.DEFAULT)
                    .build();
            doThrow(new PreCheckException(ResponseCodeEnum.INVALID_TOPIC_ID))
                    .when(dispatcher)
                    .dispatchPureChecks(txBody);
            final var context = createContext(txBody, HandleContext.TransactionCategory.USER);

            contextDispatcher.accept(context);

            verify(dispatcher, never()).dispatchHandle(any());
        }

        @Test
        void testDispatchPrecedingWithNonEmptyStackDoesntFail() {
            final var context = createContext(txBody, HandleContext.TransactionCategory.USER);
            stack.createSavepoint();

            assertThatNoException()
                    .isThrownBy(() -> context.dispatchPrecedingTransaction(
                            txBody, SingleTransactionRecordBuilder.class, VERIFIER_CALLBACK, AccountID.DEFAULT));
            verify(recordListBuilder, never()).addRemovablePreceding(any());
            verify(dispatcher, never()).dispatchHandle(any());
            verify(stack).commitFullStack();
        }

        @Test
        void testDispatchPrecedingWithChangedDataDoesntFail() {
            final var context = createContext(txBody, HandleContext.TransactionCategory.USER);
            when(stack.peek()).thenReturn(new WrappedHederaState(baseState));
            when(stack.peek().getWritableStates(FOOD_SERVICE)).thenReturn(writableStates);
            final Map<String, String> newData = new HashMap<>(BASE_DATA);
            newData.put(B_KEY, BLUEBERRY);

            assertThatNoException()
                    .isThrownBy(() -> context.dispatchPrecedingTransaction(
                            txBody, SingleTransactionRecordBuilder.class, VERIFIER_CALLBACK, ALICE.accountID()));
            assertThatNoException()
                    .isThrownBy((() -> context.dispatchPrecedingTransaction(
                            txBody, SingleTransactionRecordBuilder.class, VERIFIER_CALLBACK, ALICE.accountID())));
            verify(dispatchProcessor, times(2)).processDispatch(any());
        }

        @Test
        void testDispatchPrecedingIsCommitted() {
            final var context = createContext(txBody, HandleContext.TransactionCategory.USER);

            Mockito.lenient().when(verifier.verificationFor((Key) any())).thenReturn(verification);

            context.dispatchPrecedingTransaction(
                    txBody, SingleTransactionRecordBuilder.class, VERIFIER_CALLBACK, ALICE.accountID());

            verify(dispatchProcessor).processDispatch(childDispatch);
            verify(stack).commitFullStack();
        }

        @Test
        void testRemovableDispatchPrecedingIsNotCommitted() {
            final var context = createContext(txBody, HandleContext.TransactionCategory.USER);

            Mockito.lenient().when(verifier.verificationFor((Key) any())).thenReturn(verification);

            context.dispatchRemovablePrecedingTransaction(
                    txBody, SingleTransactionRecordBuilder.class, VERIFIER_CALLBACK, ALICE.accountID());

            verify(dispatchProcessor).processDispatch(childDispatch);
            verify(stack, never()).commitFullStack();
        }

        @Test
        void testChildWithPaidRewardsUpdatedPaidRewards() {
            final var context = createContext(txBody, HandleContext.TransactionCategory.USER);

            Mockito.lenient().when(verifier.verificationFor((Key) any())).thenReturn(verification);
            given(childDispatch.recordBuilder()).willReturn(childRecordBuilder);
            given(childRecordBuilder.getPaidStakingRewards())
                    .willReturn(List.of(
                            AccountAmount.newBuilder()
                                    .accountID(PAYER_ACCOUNT_ID)
                                    .amount(+1)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(NODE_ACCOUNT_ID)
                                    .amount(+2)
                                    .build()));
            assertThat(context.dispatchPaidRewards()).isSameAs(Collections.emptyMap());

            context.dispatchChildTransaction(
                    txBody, SingleTransactionRecordBuilder.class, VERIFIER_CALLBACK, ALICE.accountID(), SCHEDULED);

            verify(dispatchProcessor).processDispatch(childDispatch);
            verify(stack, never()).commitFullStack();
            assertThat(context.dispatchPaidRewards())
                    .containsExactly(Map.entry(PAYER_ACCOUNT_ID, +1L), Map.entry(NODE_ACCOUNT_ID, +2L));
        }
    }

    @Test
    void testExchangeRateInfo() {
        assertSame(exchangeRateInfo, subject.exchangeRateInfo());
    }

    @Test
    void usesAssistantInVerification() {
        given(verifier.verificationFor(Key.DEFAULT, assistant)).willReturn(FAILED_VERIFICATION);
        assertThat(subject.verificationFor(Key.DEFAULT, assistant)).isSameAs(FAILED_VERIFICATION);
    }

    @Test
    void getsPrivilegedAuthorization() {
        given(authorizer.hasPrivilegedAuthorization(payerId, txnInfo.functionality(), txnInfo.txBody()))
                .willReturn(IMPERMISSIBLE);
        assertThat(subject.hasPrivilegedAuthorization()).isSameAs(IMPERMISSIBLE);
    }

    @Test
    void revertsAsExpected() {
        final var checkpoint = new RecordListCheckPoint(null, null);
        given(parentDispatch.recordListBuilder()).willReturn(recordListBuilder);
        subject.revertRecordsFrom(checkpoint);
        verify(recordListBuilder).revertChildrenFrom(checkpoint);
    }

    @Test
    void dispatchesThrottlingN() {
        subject.shouldThrottleNOfUnscaled(2, CRYPTO_TRANSFER);
        verify(networkUtilizationManager).shouldThrottleNOfUnscaled(2, CRYPTO_TRANSFER, CONSENSUS_NOW);
    }

    @Test
    void allowsThrottleCapacityForChildrenIfNoneShouldThrottle() {
        given(parentDispatch.recordListBuilder()).willReturn(recordListBuilder);
        given(recordListBuilder.childRecordBuilders()).willReturn(List.of(oneChildBuilder, twoChildBuilder));
        given(oneChildBuilder.status()).willReturn(SUCCESS);
        given(oneChildBuilder.transaction()).willReturn(CRYPTO_TRANSFER_TXN_INFO.transaction());
        given(oneChildBuilder.transactionBody()).willReturn(CRYPTO_TRANSFER_TXN_INFO.txBody());
        given(twoChildBuilder.status()).willReturn(REVERTED_SUCCESS);
        given(parentDispatch.stack()).willReturn(stack);
        given(stack.peek()).willReturn(wrappedHederaState);

        assertThat(subject.hasThrottleCapacityForChildTransactions()).isTrue();
    }

    @Test
    void doesntAllowThrottleCapacityForChildrenIfOneShouldThrottle() {
        given(parentDispatch.recordListBuilder()).willReturn(recordListBuilder);
        given(recordListBuilder.childRecordBuilders()).willReturn(List.of(oneChildBuilder, twoChildBuilder));
        given(oneChildBuilder.status()).willReturn(SUCCESS);
        given(oneChildBuilder.transaction()).willReturn(CONTRACT_CALL_TXN_INFO.transaction());
        given(oneChildBuilder.transactionBody()).willReturn(CONTRACT_CALL_TXN_INFO.txBody());
        given(twoChildBuilder.status()).willReturn(SUCCESS);
        given(twoChildBuilder.transaction()).willReturn(CRYPTO_TRANSFER_TXN_INFO.transaction());
        given(twoChildBuilder.transactionBody()).willReturn(CRYPTO_TRANSFER_TXN_INFO.txBody());
        given(networkUtilizationManager.shouldThrottle(any(), eq(wrappedHederaState), eq(CONSENSUS_NOW)))
                .willReturn(true);
        given(parentDispatch.stack()).willReturn(stack);
        given(stack.peek()).willReturn(wrappedHederaState);
        assertThat(subject.hasThrottleCapacityForChildTransactions()).isFalse();
    }

    private DispatchHandleContext createContext(final TransactionBody txBody) {
        return createContext(txBody, HandleContext.TransactionCategory.USER);
    }

    private DispatchHandleContext createContext(
            final TransactionBody txBody, final HandleContext.TransactionCategory category) {
        final HederaFunctionality function;
        try {
            function = functionOf(txBody);
        } catch (final UnknownHederaFunctionality e) {
            throw new RuntimeException(e);
        }

        final TransactionInfo txnInfo = new TransactionInfo(
                Transaction.newBuilder().body(txBody).build(), txBody, SignatureMap.DEFAULT, Bytes.EMPTY, function);
        lenient().when(parentDispatch.txnCategory()).thenReturn(category);
        return new DispatchHandleContext(
                CONSENSUS_NOW,
                txnInfo,
                configuration,
                authorizer,
                blockRecordManager,
                feeManager,
                readableStoreFactory,
                payerId,
                verifier,
                Key.DEFAULT,
                accumulator,
                exchangeRateManager,
                stack,
                entityIdStore,
                dispatcher,
                recordCache,
                writableStoreFactory,
                apiFactory,
                networkInfo,
                parentRecordBuilder,
                childDispatchProvider,
                childDispatchFactory,
                parentDispatch,
                dispatchProcessor,
                networkUtilizationManager);
    }

    private void mockNeeded() {
        lenient().when(parentDispatch.recordListBuilder()).thenReturn(recordListBuilder);
        lenient().when(parentDispatch.recordBuilder()).thenReturn(parentRecordBuilder);
        lenient()
                .when(childDispatchFactory.createChildDispatch(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(childDispatch);
        lenient().when(childDispatch.recordListBuilder()).thenReturn(recordListBuilder);
        lenient().when(childDispatch.recordBuilder()).thenReturn(childRecordBuilder);
        lenient()
                .when(stack.getWritableStates(TokenService.NAME))
                .thenReturn(MapWritableStates.builder()
                        .state(MapWritableKVState.builder("ACCOUNTS").build())
                        .state(MapWritableKVState.builder("ALIASES").build())
                        .build());
        lenient().when(writableStates.<EntityNumber>getSingleton(anyString())).thenReturn(entityNumberState);
        lenient().when(stack.getWritableStates(EntityIdService.NAME)).thenReturn(writableStates);
        lenient().when(stack.getReadableStates(TokenService.NAME)).thenReturn(defaultTokenReadableStates());
        lenient().when(exchangeRateManager.exchangeRateInfo(any())).thenReturn(exchangeRateInfo);
        given(baseState.getWritableStates(TokenService.NAME)).willReturn(writableStates);
        given(baseState.getReadableStates(TokenService.NAME)).willReturn(defaultTokenReadableStates());
    }
}