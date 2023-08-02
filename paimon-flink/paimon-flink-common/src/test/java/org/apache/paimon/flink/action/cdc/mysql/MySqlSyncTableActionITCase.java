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

package org.apache.paimon.flink.action.cdc.mysql;

import org.apache.paimon.options.CatalogOptions;
import org.apache.paimon.schema.SchemaChange;
import org.apache.paimon.schema.SchemaManager;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.DataTypes;
import org.apache.paimon.types.RowType;
import org.apache.paimon.utils.JsonSerdeUtil;

import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** IT cases for {@link MySqlSyncTableAction}. */
public class MySqlSyncTableActionITCase extends MySqlActionITCaseBase {

    private static final String DATABASE_NAME = "paimon_sync_table";

    @BeforeAll
    public static void startContainers() {
        MYSQL_CONTAINER.withSetupSQL("mysql/sync_table_setup.sql");
        start();
    }

    @Test
    @Timeout(60)
    public void testSchemaEvolution() throws Exception {
        Map<String, String> mySqlConfig = getBasicMySqlConfig();
        mySqlConfig.put("database-name", DATABASE_NAME);
        mySqlConfig.put("table-name", "schema_evolution_\\d+");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(2);
        env.enableCheckpointing(1000);
        env.setRestartStrategy(RestartStrategies.noRestart());

        Map<String, String> tableConfig = getBasicTableConfig();
        MySqlSyncTableAction action =
                new MySqlSyncTableAction(
                        mySqlConfig,
                        warehouse,
                        database,
                        tableName,
                        Collections.singletonList("pt"),
                        Arrays.asList("pt", "_id"),
                        Collections.singletonMap(
                                CatalogOptions.METASTORE.key(), "test-alter-table"),
                        tableConfig);
        action.build(env);
        JobClient client = env.executeAsync();
        waitJobRunning(client);

        checkTableSchema(
                "[{\"id\":0,\"name\":\"pt\",\"type\":\"INT NOT NULL\",\"description\":\"primary\"},{\"id\":1,\"name\":\"_id\",\"type\":\"INT NOT NULL\",\"description\":\"_id\"},{\"id\":2,\"name\":\"v1\",\"type\":\"VARCHAR(10)\",\"description\":\"v1\"}]");

        try (Statement statement = getStatement()) {
            testSchemaEvolutionImpl(statement);
        }
    }

    private void checkTableSchema(String excepted) throws Exception {

        FileStoreTable table = getFileStoreTable();

        assertEquals(excepted, JsonSerdeUtil.toFlatJson(table.schema().fields()));
    }

    private void testSchemaEvolutionImpl(Statement statement) throws Exception {
        FileStoreTable table = getFileStoreTable();
        statement.executeUpdate("USE paimon_sync_table");

        statement.executeUpdate("INSERT INTO schema_evolution_1 VALUES (1, 1, 'one')");
        statement.executeUpdate(
                "INSERT INTO schema_evolution_2 VALUES (1, 2, 'two'), (2, 4, 'four')");
        RowType rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.INT().notNull(),
                            DataTypes.INT().notNull(),
                            DataTypes.VARCHAR(10)
                        },
                        new String[] {"pt", "_id", "v1"});
        List<String> primaryKeys = Arrays.asList("pt", "_id");
        List<String> expected = Arrays.asList("+I[1, 1, one]", "+I[1, 2, two]", "+I[2, 4, four]");
        waitForResult(expected, table, rowType, primaryKeys);

        statement.executeUpdate("ALTER TABLE schema_evolution_1 ADD COLUMN v2 INT");
        statement.executeUpdate(
                "INSERT INTO schema_evolution_1 VALUES (2, 3, 'three', 30), (1, 5, 'five', 50)");
        statement.executeUpdate("ALTER TABLE schema_evolution_2 ADD COLUMN v2 INT");
        statement.executeUpdate("INSERT INTO schema_evolution_2 VALUES (1, 6, 'six', 60)");
        statement.executeUpdate("UPDATE schema_evolution_2 SET v1 = 'second' WHERE _id = 2");
        rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.INT().notNull(),
                            DataTypes.INT().notNull(),
                            DataTypes.VARCHAR(10),
                            DataTypes.INT()
                        },
                        new String[] {"pt", "_id", "v1", "v2"});
        expected =
                Arrays.asList(
                        "+I[1, 1, one, NULL]",
                        "+I[1, 2, second, NULL]",
                        "+I[2, 3, three, 30]",
                        "+I[2, 4, four, NULL]",
                        "+I[1, 5, five, 50]",
                        "+I[1, 6, six, 60]");
        waitForResult(expected, table, rowType, primaryKeys);

        statement.executeUpdate("ALTER TABLE schema_evolution_1 MODIFY COLUMN v2 BIGINT");
        statement.executeUpdate(
                "INSERT INTO schema_evolution_1 VALUES (2, 7, 'seven', 70000000000)");
        statement.executeUpdate("DELETE FROM schema_evolution_1 WHERE _id = 5");
        statement.executeUpdate("UPDATE schema_evolution_1 SET v2 = 30000000000 WHERE _id = 3");
        statement.executeUpdate("ALTER TABLE schema_evolution_2 MODIFY COLUMN v2 BIGINT");
        statement.executeUpdate(
                "INSERT INTO schema_evolution_2 VALUES (2, 8, 'eight', 80000000000)");
        rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.INT().notNull(),
                            DataTypes.INT().notNull(),
                            DataTypes.VARCHAR(10),
                            DataTypes.BIGINT()
                        },
                        new String[] {"pt", "_id", "v1", "v2"});
        expected =
                Arrays.asList(
                        "+I[1, 1, one, NULL]",
                        "+I[1, 2, second, NULL]",
                        "+I[2, 3, three, 30000000000]",
                        "+I[2, 4, four, NULL]",
                        "+I[1, 6, six, 60]",
                        "+I[2, 7, seven, 70000000000]",
                        "+I[2, 8, eight, 80000000000]");
        waitForResult(expected, table, rowType, primaryKeys);

        statement.executeUpdate("ALTER TABLE schema_evolution_1 ADD COLUMN v3 NUMERIC(8, 3)");
        statement.executeUpdate("ALTER TABLE schema_evolution_1 ADD COLUMN v4 VARBINARY(10)");
        statement.executeUpdate("ALTER TABLE schema_evolution_1 ADD COLUMN v5 FLOAT");
        statement.executeUpdate("ALTER TABLE schema_evolution_1 MODIFY COLUMN v1 VARCHAR(20)");
        statement.executeUpdate(
                "INSERT INTO schema_evolution_1 VALUES (1, 9, 'nine', 90000000000, 99999.999, 'nine.bin', 9.9)");
        statement.executeUpdate("ALTER TABLE schema_evolution_2 ADD COLUMN v3 NUMERIC(8, 3)");
        statement.executeUpdate("ALTER TABLE schema_evolution_2 ADD COLUMN v4 VARBINARY(10)");
        statement.executeUpdate("ALTER TABLE schema_evolution_2 ADD COLUMN v5 FLOAT");
        statement.executeUpdate("ALTER TABLE schema_evolution_2 MODIFY COLUMN v1 VARCHAR(20)");
        statement.executeUpdate(
                "UPDATE schema_evolution_2 SET v1 = 'very long string' WHERE _id = 8");
        rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.INT().notNull(),
                            DataTypes.INT().notNull(),
                            DataTypes.VARCHAR(20),
                            DataTypes.BIGINT(),
                            DataTypes.DECIMAL(8, 3),
                            DataTypes.VARBINARY(10),
                            DataTypes.FLOAT()
                        },
                        new String[] {"pt", "_id", "v1", "v2", "v3", "v4", "v5"});
        expected =
                Arrays.asList(
                        "+I[1, 1, one, NULL, NULL, NULL, NULL]",
                        "+I[1, 2, second, NULL, NULL, NULL, NULL]",
                        "+I[2, 3, three, 30000000000, NULL, NULL, NULL]",
                        "+I[2, 4, four, NULL, NULL, NULL, NULL]",
                        "+I[1, 6, six, 60, NULL, NULL, NULL]",
                        "+I[2, 7, seven, 70000000000, NULL, NULL, NULL]",
                        "+I[2, 8, very long string, 80000000000, NULL, NULL, NULL]",
                        "+I[1, 9, nine, 90000000000, 99999.999, [110, 105, 110, 101, 46, 98, 105, 110], 9.9]");
        waitForResult(expected, table, rowType, primaryKeys);

        statement.executeUpdate("ALTER TABLE schema_evolution_1 MODIFY COLUMN v4 VARBINARY(20)");
        statement.executeUpdate("ALTER TABLE schema_evolution_1 MODIFY COLUMN v5 DOUBLE");
        statement.executeUpdate(
                "UPDATE schema_evolution_1 SET v4 = 'nine.bin.long', v5 = 9.00000000009 WHERE _id = 9");
        statement.executeUpdate("ALTER TABLE schema_evolution_2 MODIFY COLUMN v4 VARBINARY(20)");
        statement.executeUpdate("ALTER TABLE schema_evolution_2 MODIFY COLUMN v5 DOUBLE");
        statement.executeUpdate(
                "UPDATE schema_evolution_2 SET v4 = 'four.bin.long', v5 = 4.00000000004 WHERE _id = 4");
        rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.INT().notNull(),
                            DataTypes.INT().notNull(),
                            DataTypes.VARCHAR(20),
                            DataTypes.BIGINT(),
                            DataTypes.DECIMAL(8, 3),
                            DataTypes.VARBINARY(20),
                            DataTypes.DOUBLE()
                        },
                        new String[] {"pt", "_id", "v1", "v2", "v3", "v4", "v5"});
        expected =
                Arrays.asList(
                        "+I[1, 1, one, NULL, NULL, NULL, NULL]",
                        "+I[1, 2, second, NULL, NULL, NULL, NULL]",
                        "+I[2, 3, three, 30000000000, NULL, NULL, NULL]",
                        "+I[2, 4, four, NULL, NULL, [102, 111, 117, 114, 46, 98, 105, 110, 46, 108, 111, 110, 103], 4.00000000004]",
                        "+I[1, 6, six, 60, NULL, NULL, NULL]",
                        "+I[2, 7, seven, 70000000000, NULL, NULL, NULL]",
                        "+I[2, 8, very long string, 80000000000, NULL, NULL, NULL]",
                        "+I[1, 9, nine, 90000000000, 99999.999, [110, 105, 110, 101, 46, 98, 105, 110, 46, 108, 111, 110, 103], 9.00000000009]");
        waitForResult(expected, table, rowType, primaryKeys);

        // test that catalog loader works
        assertThat(getFileStoreTable().options()).containsEntry("alter-table-test", "true");
    }

    @Test
    @Timeout(60)
    public void testMultipleSchemaEvolutions() throws Exception {
        Map<String, String> mySqlConfig = getBasicMySqlConfig();
        mySqlConfig.put("database-name", DATABASE_NAME);
        mySqlConfig.put("table-name", "schema_evolution_multiple");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        env.enableCheckpointing(1000);
        env.setRestartStrategy(RestartStrategies.noRestart());

        MySqlSyncTableAction action =
                new MySqlSyncTableAction(
                        mySqlConfig,
                        warehouse,
                        database,
                        tableName,
                        Collections.emptyList(),
                        Collections.singletonList("_id"),
                        Collections.emptyMap(),
                        Collections.emptyMap());
        action.build(env);
        JobClient client = env.executeAsync();
        waitJobRunning(client);

        checkTableSchema(
                "[{\"id\":0,\"name\":\"_id\",\"type\":\"INT NOT NULL\",\"description\":\"primary\"},{\"id\":1,\"name\":\"v1\",\"type\":\"VARCHAR(10)\",\"description\":\"v1\"},{\"id\":2,\"name\":\"v2\",\"type\":\"INT\",\"description\":\"v2\"},{\"id\":3,\"name\":\"v3\",\"type\":\"VARCHAR(10)\",\"description\":\"v3\"}]");

        try (Statement statement = getStatement()) {
            testSchemaEvolutionMultipleImpl(statement);
        }
    }

    private void testSchemaEvolutionMultipleImpl(Statement statement) throws Exception {
        FileStoreTable table = getFileStoreTable();
        statement.executeUpdate("USE paimon_sync_table");

        statement.executeUpdate(
                "INSERT INTO schema_evolution_multiple VALUES (1, 'one', 10, 'string_1')");
        RowType rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.INT().notNull(),
                            DataTypes.VARCHAR(10),
                            DataTypes.INT(),
                            DataTypes.VARCHAR(10)
                        },
                        new String[] {"_id", "v1", "v2", "v3"});
        List<String> primaryKeys = Collections.singletonList("_id");
        List<String> expected = Collections.singletonList("+I[1, one, 10, string_1]");
        waitForResult(expected, table, rowType, primaryKeys);

        statement.executeUpdate(
                "ALTER TABLE schema_evolution_multiple "
                        + "ADD v4 INT, "
                        + "MODIFY COLUMN v1 VARCHAR(20), "
                        // I'd love to change COMMENT to DEFAULT
                        // however debezium parser seems to have a bug here
                        + "ADD COLUMN (v5 DOUBLE, v6 DECIMAL(5, 3), `$% ^,& *(` VARCHAR(10) COMMENT 'Hi, v700 DOUBLE \\', v701 INT a test'), "
                        + "MODIFY v2 BIGINT");
        statement.executeUpdate(
                "INSERT INTO schema_evolution_multiple VALUES "
                        + "(2, 'long_string_two', 2000000000000, 'string_2', 20, 20.5, 20.002, 'test_2')");
        rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.INT().notNull(),
                            DataTypes.VARCHAR(20),
                            DataTypes.BIGINT(),
                            DataTypes.VARCHAR(10),
                            DataTypes.INT(),
                            DataTypes.DOUBLE(),
                            DataTypes.DECIMAL(5, 3),
                            DataTypes.VARCHAR(10)
                        },
                        new String[] {"_id", "v1", "v2", "v3", "v4", "v5", "v6", "$% ^,& *("});
        expected =
                Arrays.asList(
                        "+I[1, one, 10, string_1, NULL, NULL, NULL, NULL]",
                        "+I[2, long_string_two, 2000000000000, string_2, 20, 20.5, 20.002, test_2]");
        waitForResult(expected, table, rowType, primaryKeys);
    }

    @Test
    @Timeout(90)
    public void testAllTypes() throws Exception {
        // the first round checks for table creation
        // the second round checks for running the action on an existing table
        for (int i = 0; i < 2; i++) {
            testAllTypesOnce();
        }
    }

    private void testAllTypesOnce() throws Exception {
        Map<String, String> mySqlConfig = getBasicMySqlConfig();
        mySqlConfig.put("database-name", DATABASE_NAME);
        mySqlConfig.put("table-name", "all_types_table");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        env.enableCheckpointing(1000);
        env.setRestartStrategy(RestartStrategies.noRestart());

        MySqlSyncTableAction action =
                new MySqlSyncTableAction(
                        mySqlConfig,
                        warehouse,
                        database,
                        tableName,
                        Collections.singletonList("pt"),
                        Arrays.asList("pt", "_id"),
                        Collections.emptyMap(),
                        Collections.emptyMap());
        action.build(env);
        JobClient client = env.executeAsync();
        waitJobRunning(client);

        try (Statement statement = getStatement()) {
            testAllTypesImpl(statement);
        }

        client.cancel().get();
    }

    private void testAllTypesImpl(Statement statement) throws Exception {
        RowType rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.INT().notNull(), // _id
                            DataTypes.DECIMAL(2, 1).notNull(), // pt
                            DataTypes.BOOLEAN(), // _tinyint1
                            DataTypes.BOOLEAN(), // _boolean
                            DataTypes.BOOLEAN(), // _bool
                            DataTypes.TINYINT(), // _tinyint
                            DataTypes.SMALLINT(), // _tinyint_unsigned
                            DataTypes.SMALLINT(), // _tinyint_unsigned_zerofill
                            DataTypes.SMALLINT(), // _smallint
                            DataTypes.INT(), // _smallint_unsigned
                            DataTypes.INT(), // _smallint_unsigned_zerofill
                            DataTypes.INT(), // _mediumint
                            DataTypes.BIGINT(), // _mediumint_unsigned
                            DataTypes.BIGINT(), // _mediumint_unsigned_zerofill
                            DataTypes.INT(), // _int
                            DataTypes.BIGINT(), // _int_unsigned
                            DataTypes.BIGINT(), // _int_unsigned_zerofill
                            DataTypes.BIGINT(), // _bigint
                            DataTypes.DECIMAL(20, 0), // _bigint_unsigned
                            DataTypes.DECIMAL(20, 0), // _bigint_unsigned_zerofill
                            DataTypes.DECIMAL(20, 0), // _serial
                            DataTypes.FLOAT(), // _float
                            DataTypes.FLOAT(), // _float_unsigned
                            DataTypes.FLOAT(), // _float_unsigned_zerofill
                            DataTypes.DOUBLE(), // _real
                            DataTypes.DOUBLE(), // _real_unsigned
                            DataTypes.DOUBLE(), // _real_unsigned_zerofill
                            DataTypes.DOUBLE(), // _double
                            DataTypes.DOUBLE(), // _double_unsigned
                            DataTypes.DOUBLE(), // _double_unsigned_zerofill
                            DataTypes.DOUBLE(), // _double_precision
                            DataTypes.DOUBLE(), // _double_precision_unsigned
                            DataTypes.DOUBLE(), // _double_precision_unsigned_zerofill
                            DataTypes.DECIMAL(8, 3), // _numeric
                            DataTypes.DECIMAL(8, 3), // _numeric_unsigned
                            DataTypes.DECIMAL(8, 3), // _numeric_unsigned_zerofill
                            DataTypes.STRING(), // _fixed
                            DataTypes.STRING(), // _fixed_unsigned
                            DataTypes.STRING(), // _fixed_unsigned_zerofill
                            DataTypes.DECIMAL(8, 0), // _decimal
                            DataTypes.DECIMAL(8, 0), // _decimal_unsigned
                            DataTypes.DECIMAL(8, 0), // _decimal_unsigned_zerofill
                            DataTypes.DATE(), // _date
                            DataTypes.TIMESTAMP(0), // _datetime
                            DataTypes.TIMESTAMP(3), // _datetime3
                            DataTypes.TIMESTAMP(6), // _datetime6
                            DataTypes.TIMESTAMP(0), // _datetime_p
                            DataTypes.TIMESTAMP(2), // _datetime_p2
                            DataTypes.TIMESTAMP(6), // _timestamp
                            DataTypes.TIMESTAMP(0), // _timestamp0
                            DataTypes.CHAR(10), // _char
                            DataTypes.VARCHAR(20), // _varchar
                            DataTypes.STRING(), // _tinytext
                            DataTypes.STRING(), // _text
                            DataTypes.STRING(), // _mediumtext
                            DataTypes.STRING(), // _longtext
                            DataTypes.BINARY(10), // _bin
                            DataTypes.VARBINARY(20), // _varbin
                            DataTypes.BYTES(), // _tinyblob
                            DataTypes.BYTES(), // _blob
                            DataTypes.BYTES(), // _mediumblob
                            DataTypes.BYTES(), // _longblob
                            DataTypes.STRING(), // _json
                            DataTypes.STRING(), // _enum
                            DataTypes.INT(), // _year
                            DataTypes.TIME(), // _time
                            DataTypes.STRING(), // _point
                            DataTypes.STRING(), // _geometry
                            DataTypes.STRING(), // _linestring
                            DataTypes.STRING(), // _polygon
                            DataTypes.STRING(), // _multipoint
                            DataTypes.STRING(), // _multiline
                            DataTypes.STRING(), // _multipolygon
                            DataTypes.STRING(), // _geometrycollection
                            DataTypes.ARRAY(DataTypes.STRING()) // _set
                        },
                        new String[] {
                            "_id",
                            "pt",
                            "_tinyint1",
                            "_boolean",
                            "_bool",
                            "_tinyint",
                            "_tinyint_unsigned",
                            "_tinyint_unsigned_zerofill",
                            "_smallint",
                            "_smallint_unsigned",
                            "_smallint_unsigned_zerofill",
                            "_mediumint",
                            "_mediumint_unsigned",
                            "_mediumint_unsigned_zerofill",
                            "_int",
                            "_int_unsigned",
                            "_int_unsigned_zerofill",
                            "_bigint",
                            "_bigint_unsigned",
                            "_bigint_unsigned_zerofill",
                            "_serial",
                            "_float",
                            "_float_unsigned",
                            "_float_unsigned_zerofill",
                            "_real",
                            "_real_unsigned",
                            "_real_unsigned_zerofill",
                            "_double",
                            "_double_unsigned",
                            "_double_unsigned_zerofill",
                            "_double_precision",
                            "_double_precision_unsigned",
                            "_double_precision_unsigned_zerofill",
                            "_numeric",
                            "_numeric_unsigned",
                            "_numeric_unsigned_zerofill",
                            "_fixed",
                            "_fixed_unsigned",
                            "_fixed_unsigned_zerofill",
                            "_decimal",
                            "_decimal_unsigned",
                            "_decimal_unsigned_zerofill",
                            "_date",
                            "_datetime",
                            "_datetime3",
                            "_datetime6",
                            "_datetime_p",
                            "_datetime_p2",
                            "_timestamp",
                            "_timestamp0",
                            "_char",
                            "_varchar",
                            "_tinytext",
                            "_text",
                            "_mediumtext",
                            "_longtext",
                            "_bin",
                            "_varbin",
                            "_tinyblob",
                            "_blob",
                            "_mediumblob",
                            "_longblob",
                            "_json",
                            "_enum",
                            "_year",
                            "_time",
                            "_point",
                            "_geometry",
                            "_linestring",
                            "_polygon",
                            "_multipoint",
                            "_multiline",
                            "_multipolygon",
                            "_geometrycollection",
                            "_set",
                        });
        FileStoreTable table = getFileStoreTable();
        List<String> expected =
                Arrays.asList(
                        "+I["
                                + "1, 1.1, "
                                + "true, true, false, 1, 2, 3, "
                                + "1000, 2000, 3000, "
                                + "100000, 200000, 300000, "
                                + "1000000, 2000000, 3000000, "
                                + "10000000000, 20000000000, 30000000000, 40000000000, "
                                + "1.5, 2.5, 3.5, "
                                + "1.000001, 2.000002, 3.000003, "
                                + "1.000011, 2.000022, 3.000033, "
                                + "1.000111, 2.000222, 3.000333, "
                                + "12345.110, 12345.220, 12345.330, "
                                + "1.2345678987654322E32, 1.2345678987654322E32, 1.2345678987654322E32, "
                                + "11111, 22222, 33333, "
                                + "19439, "
                                // display value of datetime is not affected by timezone
                                + "2023-03-23T14:30:05, 2023-03-23T14:30:05.123, 2023-03-23T14:30:05.123456, "
                                + "2023-03-24T14:30, 2023-03-24T14:30:05.120, "
                                // display value of timestamp is affected by timezone
                                // we store 2023-03-23T15:00:10.123456 in UTC-8 system timezone
                                // and query this timestamp in UTC-5 MySQL server timezone
                                // so the display value should increase by 3 hour
                                + "2023-03-23T18:00:10.123456, 2023-03-23T03:10, "
                                + "Paimon, Apache Paimon, Apache Paimon MySQL TINYTEXT Test Data, Apache Paimon MySQL Test Data, Apache Paimon MySQL MEDIUMTEXT Test Data, Apache Paimon MySQL Long Test Data, "
                                + "[98, 121, 116, 101, 115, 0, 0, 0, 0, 0], "
                                + "[109, 111, 114, 101, 32, 98, 121, 116, 101, 115], "
                                + "[84, 73, 78, 89, 66, 76, 79, 66, 32, 116, 121, 112, 101, 32, 116, 101, 115, 116, 32, 100, 97, 116, 97], "
                                + "[66, 76, 79, 66, 32, 116, 121, 112, 101, 32, 116, 101, 115, 116, 32, 100, 97, 116, 97], "
                                + "[77, 69, 68, 73, 85, 77, 66, 76, 79, 66, 32, 116, 121, 112, 101, 32, 116, 101, 115, 116, 32, 100, 97, 116, 97], "
                                + "[76, 79, 78, 71, 66, 76, 79, 66, 32, 32, 98, 121, 116, 101, 115, 32, 116, 101, 115, 116, 32, 100, 97, 116, 97], "
                                + "{\"a\": \"b\"}, "
                                + "value1, "
                                + "2023, "
                                + "36803000, "
                                + "{\"coordinates\":[1,1],\"type\":\"Point\",\"srid\":0}, "
                                + "{\"coordinates\":[[[1,1],[2,1],[2,2],[1,2],[1,1]]],\"type\":\"Polygon\",\"srid\":0}, "
                                + "{\"coordinates\":[[3,0],[3,3],[3,5]],\"type\":\"LineString\",\"srid\":0}, "
                                + "{\"coordinates\":[[[1,1],[2,1],[2,2],[1,2],[1,1]]],\"type\":\"Polygon\",\"srid\":0}, "
                                + "{\"coordinates\":[[1,1],[2,2]],\"type\":\"MultiPoint\",\"srid\":0}, "
                                + "{\"coordinates\":[[[1,1],[2,2],[3,3]],[[4,4],[5,5]]],\"type\":\"MultiLineString\",\"srid\":0}, "
                                + "{\"coordinates\":[[[[0,0],[10,0],[10,10],[0,10],[0,0]]],[[[5,5],[7,5],[7,7],[5,7],[5,5]]]],\"type\":\"MultiPolygon\",\"srid\":0}, "
                                + "{\"geometries\":[{\"type\":\"Point\",\"coordinates\":[10,10]},{\"type\":\"Point\",\"coordinates\":[30,30]},{\"type\":\"LineString\",\"coordinates\":[[15,15],[20,20]]}],\"type\":\"GeometryCollection\",\"srid\":0}, "
                                + "[a, b]"
                                + "]",
                        "+I["
                                + "2, 2.2, "
                                + "NULL, NULL, NULL, NULL, NULL, NULL, "
                                + "NULL, NULL, NULL, "
                                + "NULL, NULL, NULL, "
                                + "NULL, NULL, NULL, "
                                + "NULL, NULL, NULL, 50000000000, "
                                + "NULL, NULL, NULL, "
                                + "NULL, NULL, NULL, "
                                + "NULL, NULL, NULL, "
                                + "NULL, NULL, NULL, "
                                + "NULL, NULL, NULL, "
                                + "NULL, NULL, NULL, "
                                + "NULL, NULL, NULL, "
                                + "NULL, "
                                + "NULL, NULL, NULL, "
                                + "NULL, NULL, "
                                + "NULL, NULL, "
                                + "NULL, NULL, NULL, NULL, NULL, NULL, "
                                + "NULL, NULL, NULL, NULL, NULL, NULL, "
                                + "NULL, "
                                + "NULL, "
                                + "NULL, "
                                + "NULL, "
                                + "NULL, "
                                + "NULL, "
                                + "NULL, "
                                + "NULL, "
                                + "NULL, "
                                + "NULL, "
                                + "NULL, "
                                + "NULL, "
                                + "NULL"
                                + "]");
        waitForResult(expected, table, rowType, Arrays.asList("pt", "_id"));

        // test all types during schema evolution
        try {
            statement.executeUpdate("ALTER TABLE all_types_table ADD COLUMN v INT");
            List<DataField> newFields = new ArrayList<>(rowType.getFields());
            newFields.add(new DataField(rowType.getFieldCount(), "v", DataTypes.INT()));
            RowType newRowType = new RowType(newFields);
            List<String> newExpected =
                    expected.stream()
                            .map(s -> s.substring(0, s.length() - 1) + ", NULL]")
                            .collect(Collectors.toList());
            waitForResult(newExpected, table, newRowType, Arrays.asList("pt", "_id"));
        } finally {
            statement.executeUpdate("ALTER TABLE all_types_table DROP COLUMN v");
            SchemaManager schemaManager = new SchemaManager(table.fileIO(), table.location());
            schemaManager.commitChanges(SchemaChange.dropColumn("v"));
        }
    }

    @Test
    public void testIncompatibleMySqlTable() {
        Map<String, String> mySqlConfig = getBasicMySqlConfig();
        mySqlConfig.put("database-name", DATABASE_NAME);
        mySqlConfig.put("table-name", "incompatible_field_\\d+");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        MySqlSyncTableAction action =
                new MySqlSyncTableAction(
                        mySqlConfig,
                        warehouse,
                        database,
                        tableName,
                        Collections.emptyList(),
                        Collections.singletonList("_id"),
                        Collections.emptyMap(),
                        Collections.emptyMap());

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> action.build(env),
                        "Expecting IllegalArgumentException");
        assertThat(e)
                .hasMessage(
                        "Column v1 have different types in table "
                                + DATABASE_NAME
                                + ".incompatible_field_1 and table "
                                + DATABASE_NAME
                                + ".incompatible_field_2");
    }

    @Test
    public void testIncompatiblePaimonTable() throws Exception {
        Map<String, String> mySqlConfig = getBasicMySqlConfig();
        mySqlConfig.put("database-name", DATABASE_NAME);
        mySqlConfig.put("table-name", "incompatible_pk_\\d+");

        createFileStoreTable(
                RowType.of(
                        new DataType[] {DataTypes.INT(), DataTypes.BIGINT(), DataTypes.DOUBLE()},
                        new String[] {"a", "b", "c"}),
                Collections.emptyList(),
                Collections.singletonList("a"),
                new HashMap<>());

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        MySqlSyncTableAction action =
                new MySqlSyncTableAction(
                        mySqlConfig,
                        warehouse,
                        database,
                        tableName,
                        Collections.emptyList(),
                        Collections.singletonList("a"),
                        Collections.emptyMap(),
                        Collections.emptyMap());

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> action.build(env),
                        "Expecting IllegalArgumentException");
        assertThat(e).hasMessageContaining("Paimon schema and MySQL schema are not compatible.");
    }

    @Test
    public void testInvalidPrimaryKey() {
        Map<String, String> mySqlConfig = getBasicMySqlConfig();
        mySqlConfig.put("database-name", DATABASE_NAME);
        mySqlConfig.put("table-name", "schema_evolution_\\d+");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        MySqlSyncTableAction action =
                new MySqlSyncTableAction(
                        mySqlConfig,
                        warehouse,
                        database,
                        tableName,
                        Collections.emptyList(),
                        Collections.singletonList("pk"),
                        Collections.emptyMap(),
                        Collections.emptyMap());

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> action.build(env),
                        "Expecting IllegalArgumentException");
        assertThat(e)
                .hasMessage(
                        "Specified primary key pk does not exist in MySQL tables or computed columns.");
    }

    @Test
    public void testNoPrimaryKey() {
        Map<String, String> mySqlConfig = getBasicMySqlConfig();
        mySqlConfig.put("database-name", DATABASE_NAME);
        mySqlConfig.put("table-name", "incompatible_pk_\\d+");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        MySqlSyncTableAction action =
                new MySqlSyncTableAction(
                        mySqlConfig,
                        warehouse,
                        database,
                        tableName,
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyMap(),
                        Collections.emptyMap());

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> action.build(env),
                        "Expecting IllegalArgumentException");
        assertThat(e)
                .hasMessage(
                        "Primary keys are not specified. "
                                + "Also, can't infer primary keys from MySQL table schemas because "
                                + "MySQL tables have no primary keys or have different primary keys.");
    }

    @Test
    @Timeout(60)
    public void testComputedColumn() throws Exception {
        // the first round checks for table creation
        // the second round checks for running the action on an existing table
        for (int i = 0; i < 2; i++) {
            innerTestComputedColumn(i == 0);
        }
    }

    private void innerTestComputedColumn(boolean executeMysql) throws Exception {
        Map<String, String> mySqlConfig = getBasicMySqlConfig();
        mySqlConfig.put("database-name", DATABASE_NAME);
        mySqlConfig.put("table-name", "test_computed_column");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(2);
        env.enableCheckpointing(1000);
        env.setRestartStrategy(RestartStrategies.noRestart());

        List<String> computedColumnDefs =
                Arrays.asList(
                        "_year_date=year(_date)",
                        "_year_datetime=year(_datetime)",
                        "_year_timestamp=year(_timestamp)",
                        "_month_date=month(_date)",
                        "_month_datetime=month(_datetime)",
                        "_month_timestamp=month(_timestamp)",
                        "_day_date=day(_date)",
                        "_day_datetime=day(_datetime)",
                        "_day_timestamp=day(_timestamp)",
                        "_hour_date=hour(_date)",
                        "_hour_datetime=hour(_datetime)",
                        "_hour_timestamp=hour(_timestamp)",
                        "_substring_date1=substring(_date,2)",
                        "_substring_date2=substring(_timestamp,5,10)",
                        "_truncate_date=truncate(pk,2)");

        MySqlSyncTableAction action =
                new MySqlSyncTableAction(
                        mySqlConfig,
                        warehouse,
                        database,
                        tableName,
                        Collections.singletonList("_year_date"),
                        Arrays.asList("pk", "_year_date"),
                        computedColumnDefs,
                        Collections.emptyMap(),
                        Collections.emptyMap());
        action.build(env);
        JobClient client = env.executeAsync();
        waitJobRunning(client);

        if (executeMysql) {
            try (Statement statement = getStatement()) {
                statement.execute("USE paimon_sync_table");
                statement.executeUpdate(
                        "INSERT INTO test_computed_column VALUES (1, '2023-03-23', '2022-01-01 14:30', '2021-09-15 15:00:10')");
                statement.executeUpdate(
                        "INSERT INTO test_computed_column VALUES (2, '2023-03-23', null, null)");
            }
        }

        FileStoreTable table = getFileStoreTable();
        RowType rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.INT().notNull(),
                            DataTypes.DATE(),
                            DataTypes.TIMESTAMP(0),
                            DataTypes.TIMESTAMP(0),
                            DataTypes.INT().notNull(),
                            DataTypes.INT(),
                            DataTypes.INT(),
                            DataTypes.INT(),
                            DataTypes.INT(),
                            DataTypes.INT(),
                            DataTypes.INT(),
                            DataTypes.INT(),
                            DataTypes.INT(),
                            DataTypes.INT(),
                            DataTypes.INT(),
                            DataTypes.INT(),
                            DataTypes.STRING(),
                            DataTypes.STRING(),
                            DataTypes.INT()
                        },
                        new String[] {
                            "pk",
                            "_date",
                            "_datetime",
                            "_timestamp",
                            "_year_date",
                            "_year_datetime",
                            "_year_timestamp",
                            "_month_date",
                            "_month_datetime",
                            "_month_timestamp",
                            "_day_date",
                            "_day_datetime",
                            "_day_timestamp",
                            "_hour_date",
                            "_hour_datetime",
                            "_hour_timestamp",
                            "_substring_date1",
                            "_substring_date2",
                            "_truncate_date"
                        });
        List<String> expected =
                Arrays.asList(
                        "+I[1, 19439, 2022-01-01T14:30, 2021-09-15T15:00:10, 2023, 2022, 2021, 3, 1, 9, 23, 1, 15, 0, 14, 15, 23-03-23, 09-15, 0]",
                        "+I[2, 19439, NULL, NULL, 2023, NULL, NULL, 3, NULL, NULL, 23, NULL, NULL, 0, NULL, NULL, 23-03-23, NULL, 2]");
        waitForResult(expected, table, rowType, Arrays.asList("pk", "_year_date"));
    }

    @Test
    @Timeout(60)
    public void testTinyInt1Convert() throws Exception {
        Map<String, String> mySqlConfig = getBasicMySqlConfig();
        mySqlConfig.put("database-name", DATABASE_NAME);
        mySqlConfig.put("table-name", "test_tinyint1_convert");
        mySqlConfig.put("mysql.converter.tinyint1-to-bool", "false");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(2);
        env.enableCheckpointing(1000);
        env.setRestartStrategy(RestartStrategies.noRestart());

        MySqlSyncTableAction action =
                new MySqlSyncTableAction(
                        mySqlConfig,
                        warehouse,
                        database,
                        tableName,
                        Collections.emptyList(),
                        Collections.singletonList("pk"),
                        Collections.emptyList(),
                        Collections.emptyMap(),
                        Collections.emptyMap());
        action.build(env);
        JobClient client = env.executeAsync();
        waitJobRunning(client);

        try (Statement statement = getStatement()) {
            statement.execute("USE paimon_sync_table");
            statement.executeUpdate(
                    "INSERT INTO test_tinyint1_convert VALUES (1, '2021-09-15 15:00:10', 21)");
            statement.executeUpdate(
                    "INSERT INTO test_tinyint1_convert VALUES (2, '2023-03-23 16:00:20', 42)");

            FileStoreTable table = getFileStoreTable();
            RowType rowType =
                    RowType.of(
                            new DataType[] {
                                DataTypes.INT().notNull(),
                                DataTypes.TIMESTAMP(0),
                                DataTypes.TINYINT()
                            },
                            new String[] {"pk", "_datetime", "_tinyint1"});
            List<String> expected =
                    Arrays.asList(
                            "+I[1, 2021-09-15T15:00:10, 21]", "+I[2, 2023-03-23T16:00:20, 42]");
            waitForResult(expected, table, rowType, Collections.singletonList("pk"));
        }
    }

    @Test
    @Timeout(60)
    public void testSchemaEvolutionWithTinyint1Convert() throws Exception {
        Map<String, String> mySqlConfig = getBasicMySqlConfig();
        mySqlConfig.put("database-name", "paimon_sync_table_tinyint");
        mySqlConfig.put("table-name", "schema_evolution_3");
        mySqlConfig.put("mysql.converter.tinyint1-to-bool", "false");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(2);
        env.enableCheckpointing(1000);
        env.setRestartStrategy(RestartStrategies.noRestart());

        Map<String, String> tableConfig = getBasicTableConfig();
        MySqlSyncTableAction action =
                new MySqlSyncTableAction(
                        mySqlConfig,
                        warehouse,
                        database,
                        tableName,
                        Collections.singletonList("pt"),
                        Arrays.asList("pt", "_id"),
                        Collections.singletonMap(
                                CatalogOptions.METASTORE.key(), "test-alter-table"),
                        tableConfig);
        action.build(env);
        JobClient client = env.executeAsync();
        waitJobRunning(client);

        checkTableSchema(
                "[{\"id\":0,\"name\":\"pt\",\"type\":\"INT NOT NULL\",\"description\":\"primary\"},{\"id\":1,\"name\":\"_id\",\"type\":\"INT NOT NULL\",\"description\":\"_id\"},{\"id\":2,\"name\":\"v1\",\"type\":\"VARCHAR(10)\",\"description\":\"v1\"}]");

        try (Statement statement = getStatement()) {
            testSchemaEvolutionImplWithTinyIntConvert(statement);
        }
    }

    private void testSchemaEvolutionImplWithTinyIntConvert(Statement statement) throws Exception {
        FileStoreTable table = getFileStoreTable();
        statement.executeUpdate("USE paimon_sync_table_tinyint");

        statement.executeUpdate("INSERT INTO schema_evolution_3 VALUES (1, 1, 'one')");
        statement.executeUpdate(
                "INSERT INTO schema_evolution_3 VALUES (1, 2, 'two'), (2, 4, 'four')");
        RowType rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.INT().notNull(),
                            DataTypes.INT().notNull(),
                            DataTypes.VARCHAR(10)
                        },
                        new String[] {"pt", "_id", "v1"});
        List<String> primaryKeys = Arrays.asList("pt", "_id");
        List<String> expected = Arrays.asList("+I[1, 1, one]", "+I[1, 2, two]", "+I[2, 4, four]");
        waitForResult(expected, table, rowType, primaryKeys);

        statement.executeUpdate("ALTER TABLE schema_evolution_3 ADD COLUMN v2 TINYINT(1)");
        statement.executeUpdate(
                "INSERT INTO schema_evolution_3 VALUES (2, 3, 'three', 30), (1, 5, 'five', 50)");
        rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.INT().notNull(),
                            DataTypes.INT().notNull(),
                            DataTypes.VARCHAR(10),
                            DataTypes.TINYINT()
                        },
                        new String[] {"pt", "_id", "v1", "v2"});
        expected =
                Arrays.asList(
                        "+I[1, 1, one, NULL]",
                        "+I[1, 2, two, NULL]",
                        "+I[2, 3, three, 30]",
                        "+I[2, 4, four, NULL]",
                        "+I[1, 5, five, 50]");
        waitForResult(expected, table, rowType, primaryKeys);
    }

    @Test
    public void testSyncShards() throws Exception {
        Map<String, String> mySqlConfig = getBasicMySqlConfig();
        mySqlConfig.put("database-name", "shard_.+");
        mySqlConfig.put("table-name", "t.+");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(2);
        env.enableCheckpointing(1000);
        env.setRestartStrategy(RestartStrategies.noRestart());

        MySqlSyncTableAction action =
                new MySqlSyncTableAction(
                        mySqlConfig,
                        warehouse,
                        database,
                        tableName,
                        Collections.singletonList("pt"),
                        Arrays.asList("pk", "pt"),
                        Collections.singletonList("pt=substring(_date,5)"),
                        Collections.emptyMap(),
                        Collections.emptyMap());
        action.build(env);
        JobClient client = env.executeAsync();
        waitJobRunning(client);

        try (Statement statement = getStatement()) {
            statement.execute("USE shard_1");
            statement.executeUpdate("INSERT INTO t1 VALUES (1, '2023-07-30'), (2, '2023-07-30')");
            statement.execute("USE shard_2");
            statement.executeUpdate("INSERT INTO t1 VALUES (3, '2023-07-31'), (4, '2023-07-31')");
        }

        FileStoreTable table = getFileStoreTable();
        RowType rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.INT().notNull(),
                            DataTypes.VARCHAR(10),
                            DataTypes.STRING().notNull()
                        },
                        new String[] {"pk", "_date", "pt"});
        waitForResult(
                Arrays.asList(
                        "+I[1, 2023-07-30, 07-30]",
                        "+I[2, 2023-07-30, 07-30]",
                        "+I[3, 2023-07-31, 07-31]",
                        "+I[4, 2023-07-31, 07-31]"),
                table,
                rowType,
                Arrays.asList("pk", "pt"));
    }

    private FileStoreTable getFileStoreTable() throws Exception {
        return getFileStoreTable(tableName);
    }
}
