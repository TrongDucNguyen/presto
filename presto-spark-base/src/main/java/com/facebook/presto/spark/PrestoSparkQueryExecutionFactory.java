/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.spark;

import com.facebook.airlift.json.JsonCodec;
import com.facebook.airlift.log.Logger;
import com.facebook.presto.Session;
import com.facebook.presto.block.BlockEncodingManager;
import com.facebook.presto.event.QueryMonitor;
import com.facebook.presto.execution.QueryIdGenerator;
import com.facebook.presto.execution.QueryInfo;
import com.facebook.presto.execution.QueryPreparer;
import com.facebook.presto.execution.QueryPreparer.PreparedQuery;
import com.facebook.presto.execution.buffer.PagesSerde;
import com.facebook.presto.execution.buffer.PagesSerdeFactory;
import com.facebook.presto.execution.warnings.WarningCollector;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.operator.TaskStats;
import com.facebook.presto.security.AccessControl;
import com.facebook.presto.server.QuerySessionSupplier;
import com.facebook.presto.server.SessionContext;
import com.facebook.presto.spark.classloader_interface.IPrestoSparkQueryExecution;
import com.facebook.presto.spark.classloader_interface.IPrestoSparkQueryExecutionFactory;
import com.facebook.presto.spark.classloader_interface.PrestoSparkSession;
import com.facebook.presto.spark.classloader_interface.PrestoSparkTaskExecutorFactoryProvider;
import com.facebook.presto.spark.classloader_interface.PrestoSparkTaskInputs;
import com.facebook.presto.spark.classloader_interface.SerializedPrestoSparkPage;
import com.facebook.presto.spark.classloader_interface.SerializedPrestoSparkTaskDescriptor;
import com.facebook.presto.spark.classloader_interface.SerializedTaskStats;
import com.facebook.presto.spark.planner.PrestoSparkPlan;
import com.facebook.presto.spark.planner.PrestoSparkPlanFragmenter;
import com.facebook.presto.spark.planner.PrestoSparkQueryPlanner;
import com.facebook.presto.spark.planner.PrestoSparkQueryPlanner.PlanAndUpdateType;
import com.facebook.presto.spark.planner.PrestoSparkRddFactory;
import com.facebook.presto.spark.planner.PrestoSparkSplitEnumerator;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.QueryId;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.planner.PlanFragment;
import com.facebook.presto.sql.planner.SubPlan;
import com.facebook.presto.sql.planner.plan.RemoteSourceNode;
import com.facebook.presto.transaction.TransactionId;
import com.facebook.presto.transaction.TransactionInfo;
import com.facebook.presto.transaction.TransactionManager;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.slice.Slices;
import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.util.CollectionAccumulator;
import scala.Some;
import scala.Tuple2;

import javax.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.facebook.airlift.concurrent.MoreFutures.getFutureValue;
import static com.facebook.presto.SystemSessionProperties.isExchangeCompressionEnabled;
import static com.facebook.presto.execution.buffer.PagesSerdeUtil.readSerializedPages;
import static com.facebook.presto.sql.planner.planPrinter.PlanPrinter.textDistributedPlan;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getOnlyElement;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

public class PrestoSparkQueryExecutionFactory
        implements IPrestoSparkQueryExecutionFactory
{
    private static final Logger log = Logger.get(PrestoSparkQueryExecutionFactory.class);

    private final QueryIdGenerator queryIdGenerator;
    private final QuerySessionSupplier sessionSupplier;
    private final QueryPreparer queryPreparer;
    private final PrestoSparkQueryPlanner queryPlanner;
    private final PrestoSparkPlanFragmenter planFragmenter;
    private final PrestoSparkSplitEnumerator splitEnumerator;
    private final PrestoSparkRddFactory rddFactory;
    private final QueryMonitor queryMonitor;
    private final BlockEncodingManager blockEncodingManager;
    private final JsonCodec<TaskStats> taskStatsJsonCodec;
    private final JsonCodec<PrestoSparkTaskDescriptor> sparkTaskDescriptorJsonCodec;
    private final TransactionManager transactionManager;
    private final AccessControl accessControl;
    private final Metadata metadata;
    private final Set<PrestoSparkCredentialsProvider> credentialsProviders;

    @Inject
    public PrestoSparkQueryExecutionFactory(
            QueryIdGenerator queryIdGenerator,
            QuerySessionSupplier sessionSupplier,
            QueryPreparer queryPreparer,
            PrestoSparkQueryPlanner queryPlanner,
            PrestoSparkPlanFragmenter planFragmenter,
            PrestoSparkSplitEnumerator splitEnumerator,
            PrestoSparkRddFactory rddFactory,
            QueryMonitor queryMonitor,
            BlockEncodingManager blockEncodingManager,
            JsonCodec<TaskStats> taskStatsJsonCodec,
            JsonCodec<PrestoSparkTaskDescriptor> sparkTaskDescriptorJsonCodec,
            TransactionManager transactionManager,
            AccessControl accessControl,
            Metadata metadata,
            Set<PrestoSparkCredentialsProvider> credentialsProviders)
    {
        this.queryIdGenerator = requireNonNull(queryIdGenerator, "queryIdGenerator is null");
        this.sessionSupplier = requireNonNull(sessionSupplier, "sessionSupplier is null");
        this.queryPreparer = requireNonNull(queryPreparer, "queryPreparer is null");
        this.queryPlanner = requireNonNull(queryPlanner, "queryPlanner is null");
        this.planFragmenter = requireNonNull(planFragmenter, "planFragmenter is null");
        this.splitEnumerator = requireNonNull(splitEnumerator, "splitEnumerator is null");
        this.rddFactory = requireNonNull(rddFactory, "rddFactory is null");
        this.queryMonitor = requireNonNull(queryMonitor, "queryMonitor is null");
        this.blockEncodingManager = requireNonNull(blockEncodingManager, "pagesSerde is null");
        this.taskStatsJsonCodec = requireNonNull(taskStatsJsonCodec, "taskStatsJsonCodec is null");
        this.sparkTaskDescriptorJsonCodec = requireNonNull(sparkTaskDescriptorJsonCodec, "sparkTaskDescriptorJsonCodec is null");
        this.transactionManager = requireNonNull(transactionManager, "transactionManager is null");
        this.accessControl = requireNonNull(accessControl, "accessControl is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.credentialsProviders = ImmutableSet.copyOf(requireNonNull(credentialsProviders, "credentialsProviders is null"));
    }

    @Override
    public IPrestoSparkQueryExecution create(
            SparkContext sparkContext,
            PrestoSparkSession prestoSparkSession,
            String sql,
            PrestoSparkTaskExecutorFactoryProvider executorFactoryProvider)
    {
        QueryId queryId = queryIdGenerator.createNextQueryId();
        SessionContext sessionContext = PrestoSparkSessionContext.createFromSessionInfo(prestoSparkSession, credentialsProviders);
        TransactionId transactionId = transactionManager.beginTransaction(true);
        Session session = sessionSupplier.createSession(queryId, sessionContext)
                .beginTransactionId(transactionId, transactionManager, accessControl);

        // TODO: implement query monitor
        // queryMonitor.queryCreatedEvent();

        // TODO: implement warning collection
        WarningCollector warningCollector = WarningCollector.NOOP;

        PreparedQuery preparedQuery = queryPreparer.prepareQuery(session, sql, warningCollector);
        PlanAndUpdateType planAndUpdateType = queryPlanner.createQueryPlan(session, preparedQuery, warningCollector);
        SubPlan fragmentedPlan = planFragmenter.fragmentQueryPlan(session, planAndUpdateType.getPlan(), warningCollector);
        log.debug(textDistributedPlan(fragmentedPlan, metadata.getFunctionManager(), session, true));
        PrestoSparkPlan prestoSparkPlan = splitEnumerator.preparePlan(session, fragmentedPlan);

        JavaSparkContext javaSparkContext = new JavaSparkContext(sparkContext);
        CollectionAccumulator<SerializedTaskStats> taskStatsCollector = new CollectionAccumulator<>();
        taskStatsCollector.register(sparkContext, new Some<>("taskStatsCollector"), false);
        JavaPairRDD<Integer, SerializedPrestoSparkPage> rdd = rddFactory.createSparkRdd(
                javaSparkContext,
                session,
                prestoSparkPlan,
                executorFactoryProvider,
                taskStatsCollector);

        return new PrestoSparkQueryExecution(
                session,
                queryMonitor,
                taskStatsCollector,
                rdd,
                executorFactoryProvider,
                prestoSparkPlan,
                planAndUpdateType.getUpdateType(),
                new PagesSerdeFactory(blockEncodingManager, isExchangeCompressionEnabled(session)).createPagesSerde(),
                taskStatsJsonCodec,
                sparkTaskDescriptorJsonCodec,
                transactionManager);
    }

    public static class PrestoSparkQueryExecution
            implements IPrestoSparkQueryExecution
    {
        private final Session session;
        private final QueryMonitor queryMonitor;
        private final CollectionAccumulator<SerializedTaskStats> taskStatsCollector;
        private final JavaPairRDD<Integer, SerializedPrestoSparkPage> rdd;
        private final PrestoSparkTaskExecutorFactoryProvider prestoSparkTaskExecutorFactoryProvider;
        private final PrestoSparkPlan prestoSparkPlan;
        private final Optional<String> updateType;
        private final PagesSerde pagesSerde;
        private final JsonCodec<TaskStats> taskStatsJsonCodec;
        private final JsonCodec<PrestoSparkTaskDescriptor> sparkTaskDescriptorJsonCodec;

        private final TransactionManager transactionManager;

        private PrestoSparkQueryExecution(
                Session session,
                QueryMonitor queryMonitor,
                CollectionAccumulator<SerializedTaskStats> taskStatsCollector,
                JavaPairRDD<Integer, SerializedPrestoSparkPage> rdd,
                PrestoSparkTaskExecutorFactoryProvider prestoSparkTaskExecutorFactoryProvider,
                PrestoSparkPlan prestoSparkPlan,
                Optional<String> updateType,
                PagesSerde pagesSerde,
                JsonCodec<TaskStats> taskStatsJsonCodec,
                JsonCodec<PrestoSparkTaskDescriptor> sparkTaskDescriptorJsonCodec,
                TransactionManager transactionManager)
        {
            this.session = requireNonNull(session, "session is null");
            this.queryMonitor = requireNonNull(queryMonitor, "queryMonitor is null");
            this.taskStatsCollector = requireNonNull(taskStatsCollector, "taskStatsCollector is null");
            this.rdd = requireNonNull(rdd, "rdd is null");
            this.prestoSparkTaskExecutorFactoryProvider = requireNonNull(prestoSparkTaskExecutorFactoryProvider, "prestoSparkExecutorFactoryProvider is null");
            this.prestoSparkPlan = requireNonNull(prestoSparkPlan, "prestoSparkPlan is null");
            this.updateType = updateType;
            this.pagesSerde = requireNonNull(pagesSerde, "pagesSerde is null");
            this.taskStatsJsonCodec = requireNonNull(taskStatsJsonCodec, "taskStatsJsonCodec is null");
            this.sparkTaskDescriptorJsonCodec = requireNonNull(sparkTaskDescriptorJsonCodec, "sparkTaskDescriptorJsonCodec is null");
            this.transactionManager = requireNonNull(transactionManager, "transactionManager is null");
        }

        @Override
        public List<List<Object>> execute()
        {
            PlanFragment rootFragment = prestoSparkPlan.getPlan().getFragment();
            RemoteSourceNode remoteSource = getOnlyElement(rootFragment.getRemoteSourceNodes());
            PrestoSparkTaskDescriptor taskDescriptor = new PrestoSparkTaskDescriptor(
                    session.toSessionRepresentation(),
                    session.getIdentity().getExtraCredentials(),
                    rootFragment,
                    ImmutableList.of(),
                    prestoSparkPlan.getTableWriteInfo());
            SerializedPrestoSparkTaskDescriptor serializedTaskDescriptor = new SerializedPrestoSparkTaskDescriptor(sparkTaskDescriptorJsonCodec.toJsonBytes(taskDescriptor));

            List<Tuple2<Integer, SerializedPrestoSparkPage>> resultRdd;
            try {
                List<Tuple2<Integer, SerializedPrestoSparkPage>> sparkDriverInput = rdd.collect();
                resultRdd = ImmutableList.copyOf(prestoSparkTaskExecutorFactoryProvider.get().create(
                        0,
                        0,
                        serializedTaskDescriptor,
                        new PrestoSparkTaskInputs(ImmutableMap.of(remoteSource.getId().toString(), sparkDriverInput.iterator())),
                        taskStatsCollector));
                commit();
            }
            catch (RuntimeException executionFailure) {
                try {
                    rollback();
                }
                catch (RuntimeException rollbackFailure) {
                    if (executionFailure != rollbackFailure) {
                        executionFailure.addSuppressed(rollbackFailure);
                    }
                }
                queryCompletedEvent(Optional.of(executionFailure));
                throw executionFailure;
            }

            // successfully finished
            queryCompletedEvent(Optional.empty());

            ConnectorSession connectorSession = session.toConnectorSession();
            return resultRdd.stream()
                    .map(Tuple2::_2)
                    .map(this::deserializePage)
                    .flatMap(page -> getPageValues(connectorSession, page, rootFragment.getTypes()).stream())
                    .collect(toList());
        }

        public List<Type> getOutputTypes()
        {
            return prestoSparkPlan.getPlan().getFragment().getTypes();
        }

        public Optional<String> getUpdateType()
        {
            return updateType;
        }

        private Page deserializePage(SerializedPrestoSparkPage page)
        {
            return pagesSerde.deserialize(readSerializedPages(Slices.wrappedBuffer(page.getBytes()).getInput()).next());
        }

        private static List<List<Object>> getPageValues(ConnectorSession connectorSession, Page page, List<Type> types)
        {
            ImmutableList.Builder<List<Object>> rows = ImmutableList.builder();
            for (int position = 0; position < page.getPositionCount(); position++) {
                ImmutableList.Builder<Object> columns = ImmutableList.builder();
                for (int channel = 0; channel < page.getChannelCount(); channel++) {
                    Type type = types.get(channel);
                    Block block = page.getBlock(channel);
                    columns.add(type.getObjectValue(connectorSession, block, position));
                }
                rows.add(columns.build());
            }
            return rows.build();
        }

        private void commit()
        {
            getFutureValue(transactionManager.asyncCommit(getTransactionInfo().getTransactionId()));
        }

        private void rollback()
        {
            getFutureValue(transactionManager.asyncAbort(getTransactionInfo().getTransactionId()));
        }

        private TransactionInfo getTransactionInfo()
        {
            Optional<TransactionInfo> transaction = session.getTransactionId()
                    .flatMap(transactionManager::getOptionalTransactionInfo);
            checkState(transaction.isPresent(), "transaction is not present");
            checkState(transaction.get().isAutoCommitContext(), "transaction doesn't have auto commit context enabled");
            return transaction.get();
        }

        private void queryCompletedEvent(Optional<Throwable> failure)
        {
            QueryInfo queryInfo = createQueryInfo(failure);
            // TODO: implement query monitor
            // queryMonitor.queryCompletedEvent(queryInfo);
        }

        private QueryInfo createQueryInfo(Optional<Throwable> failure)
        {
            List<SerializedTaskStats> serializedTaskStats = taskStatsCollector.value();
            List<TaskStats> taskStats = serializedTaskStats.stream()
                    .map(SerializedTaskStats::getBytes)
                    .map(taskStatsJsonCodec::fromJson)
                    .collect(toImmutableList());
            // TODO: create query info
            return null;
        }
    }
}
