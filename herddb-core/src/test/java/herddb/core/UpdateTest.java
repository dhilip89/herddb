/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */
package herddb.core;

import static herddb.core.TestUtils.execute;
import static herddb.core.TestUtils.executeUpdate;
import static herddb.model.TransactionContext.NO_TRANSACTION;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import herddb.mem.MemoryCommitLogManager;
import herddb.mem.MemoryDataStorageManager;
import herddb.mem.MemoryMetadataStorageManager;
import herddb.model.StatementEvaluationContext;
import herddb.model.commands.CreateTableSpaceStatement;

/**
 *
 *
 * @author diego.salvi
 */
public class UpdateTest {

    @Test
    public void updateWithJDBC() throws Exception {
        String nodeId = "localhost";
        try (DBManager manager = new DBManager("localhost", new MemoryMetadataStorageManager(), new MemoryDataStorageManager(), new MemoryCommitLogManager(), null, null);) {
            manager.start();
            CreateTableSpaceStatement st1 = new CreateTableSpaceStatement("tblspace1", Collections.singleton(nodeId), nodeId, 1, 0, 0);
            manager.executeStatement(st1, StatementEvaluationContext.DEFAULT_EVALUATION_CONTEXT(), NO_TRANSACTION);
            manager.waitForTablespace("tblspace1", 10000);

            execute(manager, "CREATE TABLE tblspace1.tsql (K1 string ,s1 string,n1 int, primary key(k1,s1))", Collections.emptyList());

            assertEquals(1, executeUpdate(manager, "INSERT INTO tblspace1.tsql(k1,s1,n1) values(?,?,?)", Arrays.asList("mykey", "a", Integer.valueOf(1234))).getUpdateCount());
            assertEquals(1, executeUpdate(manager, "INSERT INTO tblspace1.tsql(k1,s1,n1) values(?,?,?)", Arrays.asList("mykey2", "a", Integer.valueOf(1234))).getUpdateCount());
            assertEquals(1, executeUpdate(manager, "INSERT INTO tblspace1.tsql(k1,s1,n1) values(?,?,?)", Arrays.asList("mykey3", "a", Integer.valueOf(1234))).getUpdateCount());
            assertEquals(1, executeUpdate(manager, "INSERT INTO tblspace1.tsql(k1,s1,n1) values(?,?,?)", Arrays.asList("mykey4", "a", Integer.valueOf(1234))).getUpdateCount());

            assertEquals(1, executeUpdate(manager, "UPDATE tblspace1.tsql SET n1 = n1 + ? WHERE k1 = ?", Arrays.asList(Integer.valueOf(1234), "mykey")).getUpdateCount());
            assertEquals(1, executeUpdate(manager, "UPDATE tblspace1.tsql SET n1 = n1 - ? WHERE k1 = ?", Arrays.asList(Integer.valueOf(1234), "mykey")).getUpdateCount());
            assertEquals(1, executeUpdate(manager, "UPDATE tblspace1.tsql SET n1 = ? + n1 WHERE k1 = ?", Arrays.asList(Integer.valueOf(1234), "mykey")).getUpdateCount());
            assertEquals(1, executeUpdate(manager, "UPDATE tblspace1.tsql SET n1 = ? + n1 WHERE k1 = ?", Arrays.asList(Integer.valueOf(1234), "mykey")).getUpdateCount());
            
            assertEquals(1, executeUpdate(manager, "UPDATE tblspace1.tsql SET n1 = ? + n1 WHERE k1 = ? AND s1 = ?", Arrays.asList(Integer.valueOf(1234), "mykey", "a")).getUpdateCount());
        }
    }

}
