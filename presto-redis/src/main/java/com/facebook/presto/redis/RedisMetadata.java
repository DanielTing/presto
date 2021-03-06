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
package com.facebook.presto.redis;

import com.facebook.presto.decoder.dummy.DummyRowDecoder;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.ColumnMetadata;
import com.facebook.presto.spi.ConnectorMetadata;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.ConnectorTableHandle;
import com.facebook.presto.spi.ConnectorTableLayout;
import com.facebook.presto.spi.ConnectorTableLayoutHandle;
import com.facebook.presto.spi.ConnectorTableLayoutResult;
import com.facebook.presto.spi.ConnectorTableMetadata;
import com.facebook.presto.spi.Constraint;
import com.facebook.presto.spi.LocalProperty;
import com.facebook.presto.spi.SchemaTableName;
import com.facebook.presto.spi.SchemaTablePrefix;
import com.facebook.presto.spi.TableNotFoundException;
import com.facebook.presto.spi.TupleDomain;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.log.Logger;

import javax.inject.Inject;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Manages the Redis connector specific metadata information. The Connector provides an additional set of columns
 * for each table that are created as hidden columns. See {@link RedisInternalFieldDescription} for a list
 * of additional columns.
 */
public class RedisMetadata
        implements ConnectorMetadata
{
    private static final Logger log = Logger.get(RedisMetadata.class);

    private final String connectorId;
    private final RedisConnectorConfig redisConnectorConfig;
    private final RedisHandleResolver handleResolver;

    private final Supplier<Map<SchemaTableName, RedisTableDescription>> redisTableDescriptionSupplier;
    private final Set<RedisInternalFieldDescription> internalFieldDescriptions;

    @Inject
    RedisMetadata(
            RedisConnectorId connectorId,
            RedisConnectorConfig redisConnectorConfig,
            RedisHandleResolver handleResolver,
            Supplier<Map<SchemaTableName, RedisTableDescription>> redisTableDescriptionSupplier,
            Set<RedisInternalFieldDescription> internalFieldDescriptions)
    {
        this.connectorId = requireNonNull(connectorId, "connectorId is null").toString();
        this.redisConnectorConfig = requireNonNull(redisConnectorConfig, "redisConfig is null");
        this.handleResolver = requireNonNull(handleResolver, "handleResolver is null");

        log.debug("Loading redis table definitions from %s", redisConnectorConfig.getTableDescriptionDir().getAbsolutePath());

        this.redisTableDescriptionSupplier = Suppliers.memoize(redisTableDescriptionSupplier);
        this.internalFieldDescriptions = requireNonNull(internalFieldDescriptions, "internalFieldDescriptions is null");
    }

    @Override
    public List<String> listSchemaNames(ConnectorSession session)
    {
        Set<String> schemas = getDefinedTables().keySet().stream()
                .map(SchemaTableName::getSchemaName)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return ImmutableList.copyOf(schemas);
    }

    @Override
    public RedisTableHandle getTableHandle(ConnectorSession session, SchemaTableName schemaTableName)
    {
        RedisTableDescription table = getDefinedTables().get(schemaTableName);
        if (table == null) {
            return null;
        }

        // check if keys are supplied in a zset
        // via the table description doc
        String keyName = null;
        if (table.getKey() != null) {
            keyName = table.getKey().getName();
        }

        return new RedisTableHandle(
                connectorId,
                schemaTableName.getSchemaName(),
                schemaTableName.getTableName(),
                getDataFormat(table.getKey()),
                getDataFormat(table.getValue()),
                keyName);
    }

    private static String getDataFormat(RedisTableFieldGroup fieldGroup)
    {
        return (fieldGroup == null) ? DummyRowDecoder.NAME : fieldGroup.getDataFormat();
    }

    @Override
    public ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        RedisTableHandle redisTableHandle = handleResolver.convertTableHandle(tableHandle);
        return getTableMetadata(redisTableHandle.toSchemaTableName());
    }

    @Override
    public List<ConnectorTableLayoutResult> getTableLayouts(
            ConnectorSession session,
            ConnectorTableHandle table,
            Constraint<ColumnHandle> constraint,
            Optional<Set<ColumnHandle>> desiredColumns)
    {
        RedisTableHandle tableHandle = handleResolver.convertTableHandle(table);

        Optional<Set<ColumnHandle>> partitioningColumns = Optional.empty();
        List<LocalProperty<ColumnHandle>> localProperties = ImmutableList.of();

        ConnectorTableLayout layout = new ConnectorTableLayout(
                new RedisTableLayoutHandle(tableHandle),
                Optional.<List<ColumnHandle>>empty(),
                TupleDomain.<ColumnHandle>all(),
                partitioningColumns,
                Optional.empty(),
                localProperties);

        return ImmutableList.of(new ConnectorTableLayoutResult(layout, constraint.getSummary()));
    }

    @Override
    public ConnectorTableLayout getTableLayout(ConnectorSession session, ConnectorTableLayoutHandle handle)
    {
        RedisTableLayoutHandle layout = handleResolver.convertLayout(handle);

        // tables in this connector have a single layout
        return getTableLayouts(session, layout.getTable(), Constraint.<ColumnHandle>alwaysTrue(), Optional.empty())
                .get(0)
                .getTableLayout();
    }

    @Override
    public List<SchemaTableName> listTables(ConnectorSession session, String schemaNameOrNull)
    {
        ImmutableList.Builder<SchemaTableName> builder = ImmutableList.builder();
        for (SchemaTableName tableName : getDefinedTables().keySet()) {
            if (schemaNameOrNull == null || tableName.getSchemaName().equals(schemaNameOrNull)) {
                builder.add(tableName);
            }
        }

        return builder.build();
    }

    @SuppressWarnings("ValueOfIncrementOrDecrementUsed")
    @Override
    public Map<String, ColumnHandle> getColumnHandles(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        RedisTableHandle redisTableHandle = handleResolver.convertTableHandle(tableHandle);

        RedisTableDescription redisTableDescription = getDefinedTables().get(redisTableHandle.toSchemaTableName());
        if (redisTableDescription == null) {
            throw new TableNotFoundException(redisTableHandle.toSchemaTableName());
        }

        ImmutableMap.Builder<String, ColumnHandle> columnHandles = ImmutableMap.builder();

        int index = 0;
        RedisTableFieldGroup key = redisTableDescription.getKey();
        if (key != null) {
            List<RedisTableFieldDescription> fields = key.getFields();
            if (fields != null) {
                for (RedisTableFieldDescription redisTableFieldDescription : fields) {
                    columnHandles.put(redisTableFieldDescription.getName(), redisTableFieldDescription.getColumnHandle(connectorId, true, index++));
                }
            }
        }

        RedisTableFieldGroup value = redisTableDescription.getValue();
        if (value != null) {
            List<RedisTableFieldDescription> fields = value.getFields();
            if (fields != null) {
                for (RedisTableFieldDescription redisTableFieldDescription : fields) {
                    columnHandles.put(redisTableFieldDescription.getName(), redisTableFieldDescription.getColumnHandle(connectorId, false, index++));
                }
            }
        }

        for (RedisInternalFieldDescription redisInternalFieldDescription : internalFieldDescriptions) {
            RedisColumnHandle columnHandle = redisInternalFieldDescription.getColumnHandle(connectorId, index++, redisConnectorConfig.isHideInternalColumns());
            columnHandles.put(redisInternalFieldDescription.getName(), columnHandle);
        }

        return columnHandles.build();
    }

    @Override
    public Map<SchemaTableName, List<ColumnMetadata>> listTableColumns(ConnectorSession session, SchemaTablePrefix prefix)
    {
        requireNonNull(prefix, "prefix is null");

        ImmutableMap.Builder<SchemaTableName, List<ColumnMetadata>> columns = ImmutableMap.builder();

        List<SchemaTableName> tableNames;
        if (prefix.getSchemaName() == null) {
            tableNames = listTables(session, null);
        }
        else {
            tableNames = ImmutableList.of(new SchemaTableName(prefix.getSchemaName(), prefix.getTableName()));
        }

        for (SchemaTableName tableName : tableNames) {
            ConnectorTableMetadata tableMetadata = getTableMetadata(tableName);
            // table can disappear during listing operation
            if (tableMetadata != null) {
                columns.put(tableName, tableMetadata.getColumns());
            }
        }
        return columns.build();
    }

    @Override
    public ColumnMetadata getColumnMetadata(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle columnHandle)
    {
        handleResolver.convertTableHandle(tableHandle);
        RedisColumnHandle redisColumnHandle = handleResolver.convertColumnHandle(columnHandle);

        return redisColumnHandle.getColumnMetadata();
    }

    @VisibleForTesting
    Map<SchemaTableName, RedisTableDescription> getDefinedTables()
    {
        return redisTableDescriptionSupplier.get();
    }

    @SuppressWarnings("ValueOfIncrementOrDecrementUsed")
    private ConnectorTableMetadata getTableMetadata(SchemaTableName schemaTableName)
    {
        RedisTableDescription table = getDefinedTables().get(schemaTableName);
        if (table == null) {
            throw new TableNotFoundException(schemaTableName);
        }

        ImmutableList.Builder<ColumnMetadata> builder = ImmutableList.builder();
        int index = 0;

        RedisTableFieldGroup key = table.getKey();
        if (key != null) {
            List<RedisTableFieldDescription> fields = key.getFields();
            if (fields != null) {
                for (RedisTableFieldDescription fieldDescription : fields) {
                    builder.add(fieldDescription.getColumnMetadata(index++));
                }
            }
        }

        RedisTableFieldGroup value = table.getValue();
        if (value != null) {
            List<RedisTableFieldDescription> fields = value.getFields();
            if (fields != null) {
                for (RedisTableFieldDescription fieldDescription : fields) {
                    builder.add(fieldDescription.getColumnMetadata(index++));
                }
            }
        }

        for (RedisInternalFieldDescription fieldDescription : internalFieldDescriptions) {
            builder.add(fieldDescription.getColumnMetadata(index++, redisConnectorConfig.isHideInternalColumns()));
        }

        return new ConnectorTableMetadata(schemaTableName, builder.build());
    }
}
