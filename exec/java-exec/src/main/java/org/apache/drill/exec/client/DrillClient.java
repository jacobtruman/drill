/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.client;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.apache.drill.exec.proto.UserProtos.QueryResultsMode.STREAM_FULL;
import static org.apache.drill.exec.proto.UserProtos.RunQuery.newBuilder;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.drill.common.DrillAutoCloseables;
import org.apache.drill.common.config.DrillConfig;
import org.apache.drill.common.exceptions.UserException;
import org.apache.drill.exec.ExecConstants;
import org.apache.drill.exec.coord.ClusterCoordinator;
import org.apache.drill.exec.coord.zk.ZKClusterCoordinator;
import org.apache.drill.exec.exception.OutOfMemoryException;
import org.apache.drill.exec.memory.BufferAllocator;
import org.apache.drill.exec.memory.RootAllocatorFactory;
import org.apache.drill.exec.proto.BitControl.PlanFragment;
import org.apache.drill.exec.proto.CoordinationProtos.DrillbitEndpoint;
import org.apache.drill.exec.proto.GeneralRPCProtos.Ack;
import org.apache.drill.exec.proto.UserBitShared;
import org.apache.drill.exec.proto.UserBitShared.QueryId;
import org.apache.drill.exec.proto.UserBitShared.QueryResult.QueryState;
import org.apache.drill.exec.proto.UserBitShared.QueryType;
import org.apache.drill.exec.proto.UserProtos;
import org.apache.drill.exec.proto.UserProtos.CreatePreparedStatementReq;
import org.apache.drill.exec.proto.UserProtos.CreatePreparedStatementResp;
import org.apache.drill.exec.proto.UserProtos.GetCatalogsReq;
import org.apache.drill.exec.proto.UserProtos.GetCatalogsResp;
import org.apache.drill.exec.proto.UserProtos.GetColumnsReq;
import org.apache.drill.exec.proto.UserProtos.GetColumnsResp;
import org.apache.drill.exec.proto.UserProtos.GetQueryPlanFragments;
import org.apache.drill.exec.proto.UserProtos.GetSchemasReq;
import org.apache.drill.exec.proto.UserProtos.GetSchemasResp;
import org.apache.drill.exec.proto.UserProtos.GetTablesReq;
import org.apache.drill.exec.proto.UserProtos.GetTablesResp;
import org.apache.drill.exec.proto.UserProtos.LikeFilter;
import org.apache.drill.exec.proto.UserProtos.PreparedStatementHandle;
import org.apache.drill.exec.proto.UserProtos.Property;
import org.apache.drill.exec.proto.UserProtos.QueryPlanFragments;
import org.apache.drill.exec.proto.UserProtos.RpcEndpointInfos;
import org.apache.drill.exec.proto.UserProtos.RpcType;
import org.apache.drill.exec.proto.UserProtos.RunQuery;
import org.apache.drill.exec.proto.UserProtos.UserProperties;
import org.apache.drill.exec.proto.helper.QueryIdHelper;
import org.apache.drill.exec.rpc.BasicClientWithConnection.ServerConnection;
import org.apache.drill.exec.rpc.ChannelClosedException;
import org.apache.drill.exec.rpc.ConnectionThrottle;
import org.apache.drill.exec.rpc.DrillRpcFuture;
import org.apache.drill.exec.rpc.NamedThreadFactory;
import org.apache.drill.exec.rpc.RpcConnectionHandler;
import org.apache.drill.exec.rpc.RpcException;
import org.apache.drill.exec.rpc.InvalidConnectionInfoException;
import org.apache.drill.exec.rpc.TransportCheck;
import org.apache.drill.exec.rpc.user.QueryDataBatch;
import org.apache.drill.exec.rpc.user.UserClient;
import org.apache.drill.exec.rpc.user.UserResultsListener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.util.concurrent.AbstractCheckedFuture;
import com.google.common.util.concurrent.SettableFuture;

import io.netty.buffer.DrillBuf;
import io.netty.channel.EventLoopGroup;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;

/**
 * Thin wrapper around a UserClient that handles connect/close and transforms
 * String into ByteBuf.
 */
public class DrillClient implements Closeable, ConnectionThrottle {
    public static final String DEFAULT_CLIENT_NAME = "Apache Drill Java client";

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(DrillClient.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final DrillConfig config;
    private UserClient client;
    private UserProperties props = null;
    private volatile ClusterCoordinator clusterCoordinator;
    private volatile boolean connected = false;
    private final BufferAllocator allocator;
    private int reconnectTimes;
    private int reconnectDelay;
    private boolean supportComplexTypes;
    private final boolean ownsZkConnection;
    private final boolean ownsAllocator;
    private final boolean isDirectConnection; // true if the connection bypasses zookeeper and connects directly to a drillbit
    private EventLoopGroup eventLoopGroup;
    private ExecutorService executor;
    private String clientName = DEFAULT_CLIENT_NAME;

    public DrillClient() throws OutOfMemoryException {
        this(DrillConfig.create(), false);
    }

    public DrillClient(boolean isDirect) throws OutOfMemoryException {
        this(DrillConfig.create(), isDirect);
    }

    public DrillClient(String fileName) throws OutOfMemoryException {
        this(DrillConfig.create(fileName), false);
    }

    public DrillClient(DrillConfig config) throws OutOfMemoryException {
        this(config, null, false);
    }

    public DrillClient(DrillConfig config, boolean isDirect)
            throws OutOfMemoryException {
        this(config, null, isDirect);
    }

    public DrillClient(DrillConfig config, ClusterCoordinator coordinator)
            throws OutOfMemoryException {
        this(config, coordinator, null, false);
    }

    public DrillClient(DrillConfig config, ClusterCoordinator coordinator, boolean isDirect)
            throws OutOfMemoryException {
        this(config, coordinator, null, isDirect);
    }

    public DrillClient(DrillConfig config, ClusterCoordinator coordinator, BufferAllocator allocator)
            throws OutOfMemoryException {
        this(config, coordinator, allocator, false);
    }

    public DrillClient(DrillConfig config, ClusterCoordinator coordinator, BufferAllocator allocator, boolean isDirect) {
        // if isDirect is true, the client will connect directly to the drillbit instead of
        // going thru the zookeeper
        this.isDirectConnection = isDirect;
        this.ownsZkConnection = coordinator == null && !isDirect;
        this.ownsAllocator = allocator == null;
        this.allocator = ownsAllocator ? RootAllocatorFactory.newRoot(config) : allocator;
        this.config = config;
        this.clusterCoordinator = coordinator;
        this.reconnectTimes = config.getInt(ExecConstants.BIT_RETRY_TIMES);
        this.reconnectDelay = config.getInt(ExecConstants.BIT_RETRY_DELAY);
        this.supportComplexTypes = config.getBoolean(ExecConstants.CLIENT_SUPPORT_COMPLEX_TYPES);
    }

    public DrillConfig getConfig() {
        return config;
    }

    @Override
    public void setAutoRead(boolean enableAutoRead) {
        client.setAutoRead(enableAutoRead);
    }

    /**
     * Sets the client name.
     *
     * If not set, default is {@code DrillClient#DEFAULT_CLIENT_NAME}.
     *
     * @param name the client name
     *
     * @throws IllegalStateException if called after a connection has been established.
     * @throws NullPointerException if client name is null
     */
    public void setClientName(String name) {
        if (connected) {
            throw new IllegalStateException("Attempted to modify client connection property after connection has been established.");
        }
        this.clientName = checkNotNull(name, "client name should not be null");
    }

    /**
     * Sets whether the application is willing to accept complex types (Map, Arrays) in the returned result set.
     * Default is {@code true}. If set to {@code false}, the complex types are returned as JSON encoded VARCHAR type.
     *
     * @throws IllegalStateException if called after a connection has been established.
     */
    public void setSupportComplexTypes(boolean supportComplexTypes) {
        if (connected) {
            throw new IllegalStateException("Attempted to modify client connection property after connection has been established.");
        }
        this.supportComplexTypes = supportComplexTypes;
    }

    /**
     * Connects the client to a Drillbit server
     *
     * @throws RpcException
     */
    public void connect() throws RpcException {
        connect(null, null);
    }

    public void connect(Properties props) throws RpcException {
        connect(null, props);
    }

    /**
     * Populates the endpointlist with drillbits information provided in the connection string by client.
     * For direct connection we can have connection string with drillbit property as below:
     * <dl>
     *   <dt>drillbit=ip</dt>
     *   <dd>use the ip specified as the Foreman ip with default port in config file</dd>
     *   <dt>drillbit=ip:port</dt>
     *   <dd>use the ip and port specified as the Foreman ip and port</dd>
     *   <dt>drillbit=ip1:port1,ip2:port2,...</dt>
     *   <dd>randomly select the ip and port pair from the specified list as the Foreman ip and port.</dd>
     * </dl>
     *
     * @param drillbits string with drillbit value provided in connection string
     * @param defaultUserPort string with default userport of drillbit specified in config file
     * @return list of drillbit endpoints parsed from connection string
     * @throws InvalidConnectionInfoException if the connection string has invalid or no drillbit information
     */
    static List<DrillbitEndpoint> parseAndVerifyEndpoints(String drillbits, String defaultUserPort)
            throws InvalidConnectionInfoException {
        // If no drillbits is provided then throw exception
        drillbits = drillbits.trim();
        if (drillbits.isEmpty()) {
            throw new InvalidConnectionInfoException("No drillbit information specified in the connection string");
        }

        final List<DrillbitEndpoint> endpointList = new ArrayList<>();
        final String[] connectInfo = drillbits.split(",");

        // Fetch ip address and port information for each drillbit and populate the list
        for (String drillbit : connectInfo) {

            // Trim all the empty spaces and check if the entry is empty string.
            // Ignore the empty ones.
            drillbit = drillbit.trim();

            if (!drillbit.isEmpty()) {
                // Verify if we have only ":" or only ":port" pattern
                if (drillbit.charAt(0) == ':') {
                    // Invalid drillbit information
                    throw new InvalidConnectionInfoException("Malformed connection string with drillbit hostname or " +
                            "hostaddress missing for an entry: " + drillbit);
                }

                // We are now sure that each ip:port entry will have both the values atleast once.
                // Split each drillbit connection string to get ip address and port value
                final String[] drillbitInfo = drillbit.split(":");

                // Check if we have more than one port
                if (drillbitInfo.length > 2) {
                    throw new InvalidConnectionInfoException("Malformed connection string with more than one port in a " +
                            "drillbit entry: " + drillbit);
                }

                // At this point we are sure that drillbitInfo has atleast hostname or host address
                // trim all the empty spaces which might be present in front of hostname or
                // host address information
                final String ipAddress = drillbitInfo[0].trim();
                String port = defaultUserPort;

                if (drillbitInfo.length == 2) {
                    // We have a port value also given by user. trim all the empty spaces between : and port value before
                    // validating the correctness of value.
                    port = drillbitInfo[1].trim();
                }

                try {
                    final DrillbitEndpoint endpoint = DrillbitEndpoint.newBuilder()
                            .setAddress(ipAddress)
                            .setUserPort(Integer.parseInt(port))
                            .build();

                    endpointList.add(endpoint);
                } catch (NumberFormatException e) {
                    throw new InvalidConnectionInfoException("Malformed port value in entry: " + ipAddress + ":" + port + " " +
                            "passed in connection string");
                }
            }
        }
        if (endpointList.size() == 0) {
            throw new InvalidConnectionInfoException("No valid drillbit information specified in the connection string");
        }
        return endpointList;
    }

    public synchronized void connect(String connect, Properties props) throws RpcException {
        if (connected) {
            return;
        }

        final List<DrillbitEndpoint> endpoints = new ArrayList<>();
        if (isDirectConnection) {
            // Populate the endpoints list with all the drillbit information provided in the connection string
            endpoints.addAll(parseAndVerifyEndpoints(props.getProperty("drillbit"),
                    config.getString(ExecConstants.INITIAL_USER_PORT)));
        } else {
            if (ownsZkConnection) {
                try {
                    this.clusterCoordinator = new ZKClusterCoordinator(this.config, connect);
                    this.clusterCoordinator.start(10000);
                } catch (Exception e) {
                    throw new RpcException("Failure setting up ZK for client.", e);
                }
            }
            endpoints.addAll(clusterCoordinator.getAvailableEndpoints());
            // Make sure we have at least one endpoint in the list
            checkState(!endpoints.isEmpty(), "No active Drillbit endpoint found from ZooKeeper");
        }

        // shuffle the collection then get the first endpoint
        Collections.shuffle(endpoints);
        final DrillbitEndpoint endpoint = endpoints.get(0);

        if (props != null) {
            final UserProperties.Builder upBuilder = UserProperties.newBuilder();
            for (final String key : props.stringPropertyNames()) {
                upBuilder.addProperties(Property.newBuilder().setKey(key).setValue(props.getProperty(key)));
            }

            this.props = upBuilder.build();
        }

        eventLoopGroup = createEventLoop(config.getInt(ExecConstants.CLIENT_RPC_THREADS), "Client-");
        executor = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(),
                new NamedThreadFactory("drill-client-executor-")) {
            @Override
            protected void afterExecute(final Runnable r, final Throwable t) {
                if (t != null) {
                    logger.error("{}.run() leaked an exception.", r.getClass().getName(), t);
                }
                super.afterExecute(r, t);
            }
        };
        client = new UserClient(clientName, config, supportComplexTypes, allocator, eventLoopGroup, executor);
        logger.debug("Connecting to server {}:{}", endpoint.getAddress(), endpoint.getUserPort());
        connect(endpoint);
        connected = true;
    }

    protected static EventLoopGroup createEventLoop(int size, String prefix) {
        return TransportCheck.createEventLoopGroup(size, prefix);
    }

    public synchronized boolean reconnect() {
        if (client.isActive()) {
            return true;
        }
        int retry = reconnectTimes;
        while (retry > 0) {
            retry--;
            try {
                Thread.sleep(this.reconnectDelay);
                final ArrayList<DrillbitEndpoint> endpoints = new ArrayList<>(clusterCoordinator.getAvailableEndpoints());
                if (endpoints.isEmpty()) {
                    continue;
                }
                client.close();
                Collections.shuffle(endpoints);
                connect(endpoints.iterator().next());
                return true;
            } catch (Exception e) {
            }
        }
        return false;
    }

    private void connect(DrillbitEndpoint endpoint) throws RpcException {
        final FutureHandler f = new FutureHandler();
        client.connect(f, endpoint, props, getUserCredentials());
        f.checkedGet();
    }

    public BufferAllocator getAllocator() {
        return allocator;
    }

    /**
     * Closes this client's connection to the server
     */
    @Override
    public void close() {
        if (this.client != null) {
            this.client.close();
        }
        if (this.ownsAllocator && allocator != null) {
            DrillAutoCloseables.closeNoChecked(allocator);
        }
        if (ownsZkConnection) {
            if (clusterCoordinator != null) {
                try {
                    clusterCoordinator.close();
                    clusterCoordinator = null;
                } catch (Exception e) {
                    logger.warn("Error while closing Cluster Coordinator.", e);
                }
            }
        }
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
        }

        if (executor != null) {
            executor.shutdownNow();
        }

        // TODO:  Did DRILL-1735 changes cover this TODO?:
        // TODO: fix tests that fail when this is called.
        //allocator.close();
        connected = false;
    }


    /**
     * Return the server infos. Only available after connecting
     *
     * The result might be null if the server doesn't provide the informations.
     *
     * @return the server informations, or null if not connected or if the server
     *         doesn't provide the information
     */
    public RpcEndpointInfos getServerInfos() {
        return client != null ? client.getServerInfos() : null;
    }

    /**
     * Submits a string based query plan for execution and returns the result batches. Supported query types are:
     * <p><ul>
     *  <li>{@link QueryType#LOGICAL}
     *  <li>{@link QueryType#PHYSICAL}
     *  <li>{@link QueryType#SQL}
     * </ul>
     *
     * @param type Query type
     * @param plan Query to execute
     * @return a handle for the query result
     * @throws RpcException
     */
    public List<QueryDataBatch> runQuery(QueryType type, String plan) throws RpcException {
        checkArgument(type == QueryType.LOGICAL || type == QueryType.PHYSICAL || type == QueryType.SQL,
                String.format("Only query types %s, %s and %s are supported in this API",
                        QueryType.LOGICAL, QueryType.PHYSICAL, QueryType.SQL));
        final UserProtos.RunQuery query = newBuilder().setResultsMode(STREAM_FULL).setType(type).setPlan(plan).build();
        final ListHoldingResultsListener listener = new ListHoldingResultsListener(query);
        client.submitQuery(listener, query);
        return listener.getResults();
    }

    /**
     * API to just plan a query without execution
     * @param type
     * @param query
     * @param isSplitPlan - option to tell whether to return single or split plans for a query
     * @return list of PlanFragments that can be used later on in {@link #runQuery(QueryType, List, UserResultsListener)}
     * to run a query without additional planning
     */
    public DrillRpcFuture<QueryPlanFragments> planQuery(QueryType type, String query, boolean isSplitPlan) {
        GetQueryPlanFragments runQuery = GetQueryPlanFragments.newBuilder().setQuery(query).setType(type).setSplitPlan(isSplitPlan).build();
        return client.planQuery(runQuery);
    }

    /**
     * Run query based on list of fragments that were supposedly produced during query planning phase. Supported
     * query type is {@link QueryType#EXECUTION}
     * @param type
     * @param planFragments
     * @param resultsListener
     * @throws RpcException
     */
    public void runQuery(QueryType type, List<PlanFragment> planFragments, UserResultsListener resultsListener)
            throws RpcException {
        // QueryType can be only executional
        checkArgument((QueryType.EXECUTION == type), "Only EXECUTION type query is supported with PlanFragments");
        // setting Plan on RunQuery will be used for logging purposes and therefore can not be null
        // since there is no Plan string provided we will create a JsonArray out of individual fragment Plans
        ArrayNode jsonArray = objectMapper.createArrayNode();
        for (PlanFragment fragment : planFragments) {
            try {
                jsonArray.add(objectMapper.readTree(fragment.getFragmentJson()));
            } catch (IOException e) {
                logger.error("Exception while trying to read PlanFragment JSON for %s", fragment.getHandle().getQueryId(), e);
                throw new RpcException(e);
            }
        }
        final String fragmentsToJsonString;
        try {
            fragmentsToJsonString = objectMapper.writeValueAsString(jsonArray);
        } catch (JsonProcessingException e) {
            logger.error("Exception while trying to get JSONString from Array of individual Fragments Json for %s", e);
            throw new RpcException(e);
        }
        final UserProtos.RunQuery query = newBuilder().setType(type).addAllFragments(planFragments)
                .setPlan(fragmentsToJsonString)
                .setResultsMode(STREAM_FULL).build();
        client.submitQuery(resultsListener, query);
    }

    /*
     * Helper method to generate the UserCredentials message from the properties.
     */
    private UserBitShared.UserCredentials getUserCredentials() {
        // If username is not propagated as one of the properties
        String userName = "anonymous";

        if (props != null) {
            for (Property property : props.getPropertiesList()) {
                if (property.getKey().equalsIgnoreCase("user") && !Strings.isNullOrEmpty(property.getValue())) {
                    userName = property.getValue();
                    break;
                }
            }
        }

        return UserBitShared.UserCredentials.newBuilder().setUserName(userName).build();
    }

    public DrillRpcFuture<Ack> cancelQuery(QueryId id) {
        if (logger.isDebugEnabled()) {
            logger.debug("Cancelling query {}", QueryIdHelper.getQueryId(id));
        }
        return client.send(RpcType.CANCEL_QUERY, id, Ack.class);
    }

    public DrillRpcFuture<Ack> resumeQuery(final QueryId queryId) {
        if (logger.isDebugEnabled()) {
            logger.debug("Resuming query {}", QueryIdHelper.getQueryId(queryId));
        }
        return client.send(RpcType.RESUME_PAUSED_QUERY, queryId, Ack.class);
    }

    /**
     * Get the list of catalogs in <code>INFORMATION_SCHEMA.CATALOGS</code> table satisfying the given filters.
     *
     * @param catalogNameFilter Filter on <code>catalog name</code>. Pass null to apply no filter.
     * @return
     */
    public DrillRpcFuture<GetCatalogsResp> getCatalogs(LikeFilter catalogNameFilter) {
        final GetCatalogsReq.Builder reqBuilder = GetCatalogsReq.newBuilder();
        if (catalogNameFilter != null) {
            reqBuilder.setCatalogNameFilter(catalogNameFilter);
        }

        return client.send(RpcType.GET_CATALOGS, reqBuilder.build(), GetCatalogsResp.class);
    }

    /**
     * Get the list of schemas in <code>INFORMATION_SCHEMA.SCHEMATA</code> table satisfying the given filters.
     *
     * @param catalogNameFilter Filter on <code>catalog name</code>. Pass null to apply no filter.
     * @param schemaNameFilter Filter on <code>schema name</code>. Pass null to apply no filter.
     * @return
     */
    public DrillRpcFuture<GetSchemasResp> getSchemas(LikeFilter catalogNameFilter, LikeFilter schemaNameFilter) {
        final GetSchemasReq.Builder reqBuilder = GetSchemasReq.newBuilder();
        if (catalogNameFilter != null) {
            reqBuilder.setCatalogNameFilter(catalogNameFilter);
        }

        if (schemaNameFilter != null) {
            reqBuilder.setSchemaNameFilter(schemaNameFilter);
        }

        return client.send(RpcType.GET_SCHEMAS, reqBuilder.build(), GetSchemasResp.class);
    }

    /**
     * Get the list of tables in <code>INFORMATION_SCHEMA.TABLES</code> table satisfying the given filters.
     *
     * @param catalogNameFilter Filter on <code>catalog name</code>. Pass null to apply no filter.
     * @param schemaNameFilter Filter on <code>schema name</code>. Pass null to apply no filter.
     * @param tableNameFilter Filter in <code>table name</code>. Pass null to apply no filter.
     * @param tableTypeFilter Filter in <code>table type</code>. Pass null to apply no filter
     * @return
     */
    public DrillRpcFuture<GetTablesResp> getTables(LikeFilter catalogNameFilter, LikeFilter schemaNameFilter,
                                                   LikeFilter tableNameFilter, List<String> tableTypeFilter) {
        final GetTablesReq.Builder reqBuilder = GetTablesReq.newBuilder();
        if (catalogNameFilter != null) {
            reqBuilder.setCatalogNameFilter(catalogNameFilter);
        }

        if (schemaNameFilter != null) {
            reqBuilder.setSchemaNameFilter(schemaNameFilter);
        }

        if (tableNameFilter != null) {
            reqBuilder.setTableNameFilter(tableNameFilter);
        }

        if (tableTypeFilter != null) {
            reqBuilder.addAllTableTypeFilter(tableTypeFilter);
        }

        return client.send(RpcType.GET_TABLES, reqBuilder.build(), GetTablesResp.class);
    }

    /**
     * Get the list of columns in <code>INFORMATION_SCHEMA.COLUMNS</code> table satisfying the given filters.
     *
     * @param catalogNameFilter Filter on <code>catalog name</code>. Pass null to apply no filter.
     * @param schemaNameFilter Filter on <code>schema name</code>. Pass null to apply no filter.
     * @param tableNameFilter Filter in <code>table name</code>. Pass null to apply no filter.
     * @param columnNameFilter Filter in <code>column name</code>. Pass null to apply no filter.
     * @return
     */
    public DrillRpcFuture<GetColumnsResp> getColumns(LikeFilter catalogNameFilter, LikeFilter schemaNameFilter,
                                                     LikeFilter tableNameFilter, LikeFilter columnNameFilter) {
        final GetColumnsReq.Builder reqBuilder = GetColumnsReq.newBuilder();
        if (catalogNameFilter != null) {
            reqBuilder.setCatalogNameFilter(catalogNameFilter);
        }

        if (schemaNameFilter != null) {
            reqBuilder.setSchemaNameFilter(schemaNameFilter);
        }

        if (tableNameFilter != null) {
            reqBuilder.setTableNameFilter(tableNameFilter);
        }

        if (columnNameFilter != null) {
            reqBuilder.setColumnNameFilter(columnNameFilter);
        }

        return client.send(RpcType.GET_COLUMNS, reqBuilder.build(), GetColumnsResp.class);
    }

    /**
     * Create a prepared statement for given <code>query</code>.
     *
     * @param query
     * @return
     */
    public DrillRpcFuture<CreatePreparedStatementResp> createPreparedStatement(final String query) {
        final CreatePreparedStatementReq req =
                CreatePreparedStatementReq.newBuilder()
                        .setSqlQuery(query)
                        .build();

        return client.send(RpcType.CREATE_PREPARED_STATEMENT, req, CreatePreparedStatementResp.class);
    }

    /**
     * Execute the given prepared statement.
     *
     * @param preparedStatementHandle Prepared statement handle returned in response to
     *                                {@link #createPreparedStatement(String)}.
     * @param resultsListener {@link UserResultsListener} instance for listening for query results.
     */
    public void executePreparedStatement(final PreparedStatementHandle preparedStatementHandle,
                                         final UserResultsListener resultsListener) {
        final RunQuery runQuery = newBuilder()
                .setResultsMode(STREAM_FULL)
                .setType(QueryType.PREPARED_STATEMENT)
                .setPreparedStatementHandle(preparedStatementHandle)
                .build();
        client.submitQuery(resultsListener, runQuery);
    }

    /**
     * Execute the given prepared statement and return the results.
     *
     * @param preparedStatementHandle Prepared statement handle returned in response to
     *                                {@link #createPreparedStatement(String)}.
     * @return List of {@link QueryDataBatch}s. It is responsibility of the caller to release query data batches.
     * @throws RpcException
     */
    @VisibleForTesting
    public List<QueryDataBatch> executePreparedStatement(final PreparedStatementHandle preparedStatementHandle)
            throws RpcException {
        final RunQuery runQuery = newBuilder()
                .setResultsMode(STREAM_FULL)
                .setType(QueryType.PREPARED_STATEMENT)
                .setPreparedStatementHandle(preparedStatementHandle)
                .build();

        final ListHoldingResultsListener resultsListener = new ListHoldingResultsListener(runQuery);

        client.submitQuery(resultsListener, runQuery);

        return resultsListener.getResults();
    }

    /**
     * Submits a Logical plan for direct execution (bypasses parsing)
     *
     * @param  plan  the plan to execute
     */
    public void runQuery(QueryType type, String plan, UserResultsListener resultsListener) {
        String zdw_prefix = "zdw.`";
        String zdw_suffix = ".zdw";
        int start = plan.indexOf(zdw_prefix);
        if (start > 0) {
            int end = plan.indexOf("`", start + zdw_prefix.length());
            // get the beginning of the query
            String query_prefix = plan.substring(0, start);
            // get the path in the middle of the query
            String query_path = plan.substring(start + zdw_prefix.length(), end);
            // get the end of the query
            String query_suffix = plan.substring(end);

            //System.out.println("QEURY PATH: " + query_path);

            if (query_path.indexOf(zdw_suffix) > 0) {
                //System.out.println("single file specified");
                File data_file = new File(query_path);
                if (!data_file.exists()) {
                    File compressed_file = new File(query_path + ".xz");
                    ProcessZDWXZFile(compressed_file);
                }
            } else {
                String query_path_tmp = query_path + "/tmp/";
               // System.out.println("process all zdw.xz files in \"" + query_path + "\" into \"" + query_path_tmp + "\" dir, change plan to point to that dir, then run query");
                File file_dir = new File(query_path);
                File new_file_dir = new File(query_path_tmp);
                query_path = query_path_tmp;

                for (File file : file_dir.listFiles()) {
                    if (file.getName().toLowerCase().endsWith(".zdw.xz")) {
                        ProcessZDWXZFile(file, new_file_dir);
                    }
                }
            }
            plan = query_prefix + "dfs.`" + query_path + query_suffix;
            //System.out.println("NEW PLAN: " + plan);
        }

        client.submitQuery(resultsListener, newBuilder().setResultsMode(STREAM_FULL).setType(type).setPlan(plan).build());
    }

    /**
     * Process a zdw.xz compressed file
     *
     * @param zdwxz_file
     */
    public void ProcessZDWXZFile(File zdwxz_file) {
        String path = zdwxz_file.getPath();
        path = path.substring(0, path.lastIndexOf("/"));
        File dest_dir = new File(path);
        ProcessZDWXZFile(zdwxz_file, dest_dir);
    }

    /**
     * Process a zdw.xz compressed file into a specific directory
     *
     * @param zdwxz_file
     * @param dest_dir
     */
    public void ProcessZDWXZFile(File zdwxz_file, File dest_dir) {
        if (!dest_dir.exists()) {
            dest_dir.mkdir();
        }

        String file_name = zdwxz_file.getName();
        String extless_name = file_name.substring(0, file_name.indexOf(".zdw.xz"));

        String dest_path = dest_dir.getPath() + "/";
        File data_file = new File(dest_path + extless_name + ".zdw");
        File sql_data_file = new File(dest_path + extless_name + ".sql");
        File sql_desc_file = new File(dest_path + extless_name + ".desc.sql");

        if (!data_file.exists()) {
            //System.out.println("File does not exist: " + data_file.getPath());
            if (zdwxz_file.exists()) {
                //System.out.println("Compressed file does exist: " + compressed_file.getPath());
                runScript("unconvertDWfile " + zdwxz_file.getPath() + " -d " + dest_dir.getPath());

                if (sql_desc_file.exists()) {
                    if (sql_data_file.exists()) {
                        BuildDataFile(sql_desc_file, sql_data_file, data_file);
                        sql_desc_file.delete();
                        sql_data_file.delete();
                    } else {
                        System.out.println("data file does not exist: " + sql_data_file.getPath());
                    }
                } else {
                    System.out.println("Desc file does not exist: " + sql_desc_file.getPath());
                }
            } else {
                System.out.println("Compressed file does not exist: " + zdwxz_file.getPath());
                System.out.println("TODO: Try to get file");
            }
        }
    }

    /**
     * Build data file from files extracted from zdw.xz file
     *
     * @param desc_file
     * @param data_file
     * @param out_file
     */
    public void BuildDataFile(File desc_file, File data_file, File out_file) {
        try {
            FileReader fr = new FileReader(desc_file.getPath());
            BufferedReader br = new BufferedReader(fr);

            FileWriter fw = new FileWriter(out_file.getPath());
            PrintWriter pw = new PrintWriter(fw);

            String ln;
            String header = "";
            boolean first = true;

            do {
                ln = br.readLine();

                if (ln != null) {
                    ln = ln.substring(0, ln.indexOf('\t'));
                    if (first) {
                        first = false;
                    } else {
                        ln = '\t' + ln;
                    }
                    header += ln;
                }
            }
            while (ln != null);
            pw.println(header);

            br.close();

            FileReader dfr = new FileReader(data_file.getPath());
            BufferedReader dbr = new BufferedReader(dfr);

            do {
                ln = dbr.readLine();
                if (ln != null) {
                    pw.println(ln);
                }
            }
            while (ln != null);

            dbr.close();
            pw.close();

        } catch (FileNotFoundException ex) {
            System.out.println(ex);
        } catch (IOException ex) {
            System.out.println(ex);
        }
    }

    /**
     * Run external script
     *
     * @param command
     */
    public void runScript(String command) {
        //System.out.println("RUNNING COMMAND: " + command);
        String sCommandString = command;
        CommandLine oCmdLine = CommandLine.parse(sCommandString);
        DefaultExecutor oDefaultExecutor = new DefaultExecutor();
        oDefaultExecutor.setExitValue(0);
        try {
            int iExitValue = oDefaultExecutor.execute(oCmdLine);
        } catch (ExecuteException e) {
            System.err.println("Execution failed.");
            e.printStackTrace();
        } catch (IOException e) {
            System.err.println("permission denied.");
            e.printStackTrace();
        }
    }

    private class ListHoldingResultsListener implements UserResultsListener {
        private final Vector<QueryDataBatch> results = new Vector<>();
        private final SettableFuture<List<QueryDataBatch>> future = SettableFuture.create();
        private final UserProtos.RunQuery query;

        public ListHoldingResultsListener(UserProtos.RunQuery query) {
            logger.debug("Listener created for query \"\"\"{}\"\"\"", query);
            this.query = query;
        }

        @Override
        public void submissionFailed(UserException ex) {
            // or  !client.isActive()
            if (ex.getCause() instanceof ChannelClosedException) {
                if (reconnect()) {
                    try {
                        client.submitQuery(this, query);
                    } catch (Exception e) {
                        fail(e);
                    }
                } else {
                    fail(ex);
                }
            } else {
                fail(ex);
            }
        }

        @Override
        public void queryCompleted(QueryState state) {
            future.set(results);
        }

        private void fail(Exception ex) {
            logger.debug("Submission failed.", ex);
            future.setException(ex);
            future.set(results);
        }

        @Override
        public void dataArrived(QueryDataBatch result, ConnectionThrottle throttle) {
            logger.debug("Result arrived:  Result: {}", result);
            results.add(result);
        }

        public List<QueryDataBatch> getResults() throws RpcException {
            try {
                return future.get();
            } catch (Throwable t) {
        /*
         * Since we're not going to return the result to the caller
         * to clean up, we have to do it.
         */
                for (final QueryDataBatch queryDataBatch : results) {
                    queryDataBatch.release();
                }

                throw RpcException.mapException(t);
            }
        }

        @Override
        public void queryIdArrived(QueryId queryId) {
            if (logger.isDebugEnabled()) {
                logger.debug("Query ID arrived: {}", QueryIdHelper.getQueryId(queryId));
            }
        }
    }

    private class FutureHandler extends AbstractCheckedFuture<Void, RpcException> implements RpcConnectionHandler<ServerConnection>, DrillRpcFuture<Void> {
        protected FutureHandler() {
            super(SettableFuture.<Void>create());
        }

        @Override
        public void connectionSucceeded(ServerConnection connection) {
            getInner().set(null);
        }

        @Override
        public void connectionFailed(FailureType type, Throwable t) {
            getInner().setException(new RpcException(String.format("%s : %s", type.name(), t.getMessage()), t));
        }

        private SettableFuture<Void> getInner() {
            return (SettableFuture<Void>) delegate();
        }

        @Override
        protected RpcException mapException(Exception e) {
            return RpcException.mapException(e);
        }

        @Override
        public DrillBuf getBuffer() {
            return null;
        }
    }
}
