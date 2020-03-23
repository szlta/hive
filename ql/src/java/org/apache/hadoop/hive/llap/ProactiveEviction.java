/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.llap;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.SocketFactory;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.common.io.CacheTag;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.llap.daemon.rpc.LlapDaemonProtocolProtos;
import org.apache.hadoop.hive.llap.impl.LlapManagementProtocolClientImpl;
import org.apache.hadoop.hive.llap.registry.LlapServiceInstance;
import org.apache.hadoop.hive.llap.registry.impl.LlapRegistryService;
import org.apache.hadoop.io.retry.RetryPolicies;
import org.apache.hadoop.io.retry.RetryPolicy;
import org.apache.hadoop.net.NetUtils;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Through this class the caller (typically HS2) can request eviction of buffers from LLAP cache by specifying a DB,
 * table or partition name/(value). Request sending is implemented here.
 */
public final class ProactiveEviction {

  private static ExecutorService executor = null;

  private ProactiveEviction() {
    // Not to be used;
  }

  public static void shutdown() {
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  /**
   * Trigger LLAP cache eviction of buffers related to entities residing in request parameter.
   * @param conf
   * @param request
   */
  public static void evict(Configuration conf, Request request) {
    if (!HiveConf.getBoolVar(conf, HiveConf.ConfVars.LLAP_IO_PROACTIVE_EVICTION_ENABLED)) {
      return;
    }

    if (executor == null) {
      executor = Executors.newSingleThreadExecutor(
          new ThreadFactoryBuilder().setNameFormat("Proactive-Eviction-Requester").setDaemon(true).build());
    }

    try {
      LlapRegistryService llapRegistryService = LlapRegistryService.getClient(conf);
      Collection<LlapServiceInstance> instances = llapRegistryService.getInstances().getAll();
      if (instances.size() == 0) {
        // Not in LLAP mode.
        return;
      }
      for (LlapServiceInstance instance : instances) {
        Task task = new Task(conf, instance, request);
        executor.execute(task);
      }

    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * The executable task to carry out request sending.
   */
  public static class Task implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(Task.class);
    private final Request request;
    private Configuration conf;
    private LlapServiceInstance instance;
    private SocketFactory socketFactory;
    private RetryPolicy retryPolicy;

    Task(Configuration conf, LlapServiceInstance llapServiceInstance, Request request) {
      this.conf = conf;
      this.instance = llapServiceInstance;
      this.socketFactory = NetUtils.getDefaultSocketFactory(conf);
      //not making this configurable, best effort
      this.retryPolicy = RetryPolicies.retryUpToMaximumTimeWithFixedSleep(
          10000, 2000L, TimeUnit.MILLISECONDS);
      this.request = request;
    }

    @Override
    public void run() {
      if (request.isEmpty()) {
        throw new IllegalArgumentException("No entities set to trigger eviction on.");
      }
      try {
        LlapManagementProtocolClientImpl client = new LlapManagementProtocolClientImpl(conf, instance.getHost(),
            instance.getManagementPort(), retryPolicy, socketFactory);

        List<LlapDaemonProtocolProtos.EvictEntityRequestProto> protoRequests = request.toProtoRequests();

        for (LlapDaemonProtocolProtos.EvictEntityRequestProto protoRequest : protoRequests) {
          client.evictEntity(null, protoRequest);
        }
        LOG.info("Requested proactive eviction.");
      } catch (Exception e) {
        LOG.warn("Exception while requesting proactive eviction.", e);
      }
    }
  }

  /**
   * Holds information on entities: DB name(s), table name(s), partitions.
   */
  public static final class Request {

    private final Map<String, Map<String, Set<LinkedHashMap<String, String>>>> entities;

    private Request(Map<String, Map<String, Set<LinkedHashMap<String, String>>>> entities) {
      this.entities = entities;
    }

    public Map<String, Map<String, Set<LinkedHashMap<String, String>>>> getEntities() {
      return entities;
    }

    public boolean isEmpty() {
      return entities.isEmpty();
    }

    /**
     * Request often times only contains tables/partitions of 1 DB only.
     * @return the single DB name, null if the count of DBs present is not exactly 1.
     */
    public String getSingleDbName() {
      if (entities.size() == 1) {
        return entities.keySet().stream().findFirst().get();
      }
      return null;
    }

    /**
     * Translate to Protobuf requests.
     * @return list of request instances ready to be sent over protobuf.
     */
    public List<LlapDaemonProtocolProtos.EvictEntityRequestProto> toProtoRequests() {

      List<LlapDaemonProtocolProtos.EvictEntityRequestProto> protoRequests = new LinkedList<>();

      for (Map.Entry<String, Map<String, Set<LinkedHashMap<String, String>>>> dbEntry : entities.entrySet()) {
        String dbName = dbEntry.getKey();
        Map<String, Set<LinkedHashMap<String, String>>> tables = dbEntry.getValue();

        LlapDaemonProtocolProtos.EvictEntityRequestProto.Builder requestBuilder =
            LlapDaemonProtocolProtos.EvictEntityRequestProto.newBuilder();
        LlapDaemonProtocolProtos.TableProto.Builder tableBuilder = null;

        requestBuilder.setDbName(dbName.toLowerCase());
        for (Map.Entry<String, Set<LinkedHashMap<String, String>>> tableEntry : tables.entrySet()) {
          String tableName = tableEntry.getKey();
          tableBuilder = LlapDaemonProtocolProtos.TableProto.newBuilder();
          tableBuilder.setTableName(tableName.toLowerCase());

          Set<LinkedHashMap<String, String>> partitions = tableEntry.getValue();
          Set<String> partitionKeys = null;

          for (Map<String, String> partitionSpec : partitions) {
            if (partitionKeys == null) {
              // For a given table the set of partition columns (keys) should not change.
              partitionKeys = new LinkedHashSet<>(partitionSpec.keySet());
              tableBuilder.addAllPartKey(partitionKeys);
            }
            for (String partKey : tableBuilder.getPartKeyList()) {
              tableBuilder.addPartVal(partitionSpec.get(partKey));
            }
          }
          requestBuilder.addTable(tableBuilder.build());
        }
        protoRequests.add(requestBuilder.build());
      }
      return protoRequests;
    }

    /**
     * Match a CacheTag to this eviction request.
     * @param cacheTag
     * @return true if cacheTag matches and the related buffer is eligible for proactive eviction, false otherwise.
     */
    public boolean isTagMatch(CacheTag cacheTag) {
      // TODO: implement this
      return false;
    }

    /**
     * Lets callers specify what entities are requested to be evicted, and builds a Request instance accordingly.
     */
    public static final class Builder {

      private final Map<String, Map<String, Set<LinkedHashMap<String, String>>>> entities;

      private Builder() {
        this.entities = new HashMap<>();
      }

      public static Builder create() {
        return new Builder();
      }

      public Builder addPartitionOfATable(String db, String tableName, LinkedHashMap<String, String> partSpec) {
        ensureDb(db);
        ensureTable(db, tableName);
        entities.get(db).get(tableName).add(partSpec);
        return this;
      }

      public Builder addDb(String db) {
        ensureDb(db);
        return this;
      }

      public Builder addTable(String db, String table) {
        ensureDb(db);
        ensureTable(db, table);
        return this;
      }

      public Request build() {
        return new Request(entities);
      }

      private void ensureDb(String dbName) {
        Map<String, Set<LinkedHashMap<String, String>>> tables = entities.get(dbName);
        if (tables == null) {
          tables = new HashMap<>();
          entities.put(dbName, tables);
        }
      }

      private void ensureTable(String dbName, String tableName) {
        ensureDb(dbName);
        Map<String, Set<LinkedHashMap<String, String>>> tables = entities.get(dbName);

        Set<LinkedHashMap<String, String>> partitions = tables.get(tableName);
        if (partitions == null) {
          partitions = new HashSet<>();
          tables.put(tableName, partitions);
        }
      }

      /**
       * Translate from Protobuf request.
       * @param protoRequest
       * @return the builder itself.
       */
      public Builder fromProtoRequest(LlapDaemonProtocolProtos.EvictEntityRequestProto protoRequest) {
        entities.clear();
        String dbName = protoRequest.getDbName().toLowerCase();

        Map<String, Set<LinkedHashMap<String, String>>> entitiesInDb = new HashMap<>();
        List<LlapDaemonProtocolProtos.TableProto> tables = protoRequest.getTableList();

        if (tables != null && !tables.isEmpty()) {
          for (LlapDaemonProtocolProtos.TableProto table : tables) {
            String dbAndTableName =
                (new StringBuilder().append(dbName).append('.').append(table.getTableName())).toString().toLowerCase();

            if (table.getPartValCount() == 0) {
              entitiesInDb.put(dbAndTableName, null);
              continue;
            }
            Set<LinkedHashMap<String, String>> partitions = new HashSet<>();
            LinkedHashMap<String, String> partDesc = new LinkedHashMap<>();

            for (int valIx = 0; valIx < table.getPartValCount(); ++valIx) {
              int keyIx = valIx % table.getPartKeyCount();

              partDesc.put(table.getPartKey(keyIx).toLowerCase(), table.getPartVal(valIx));

              if (keyIx == table.getPartKeyCount() - 1) {
                partitions.add(partDesc);
                partDesc = new LinkedHashMap<>();
              }
            }

            entitiesInDb.put(dbAndTableName, partitions);
          }
        }
        entities.put(dbName, entitiesInDb);
        return this;
      }
    }
  }

}
