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
package herddb.model.planner;

import herddb.core.TableSpaceManager;
import herddb.model.DataScanner;
import herddb.model.ScanResult;
import herddb.model.StatementEvaluationContext;
import herddb.model.StatementExecutionException;
import herddb.model.StatementExecutionResult;
import herddb.model.TransactionContext;
import herddb.model.commands.ScanStatement;

/**
 * Full table scan
 *
 * @author eolivelli
 */
public class TableScanOp implements PlannerOp {

    final ScanStatement statement;

    public TableScanOp(ScanStatement statement) {
        this.statement = statement;
    }

    @Override
    public String getTablespace() {
        return statement.getTableSpace();
    }

    @Override
    public StatementExecutionResult execute(TableSpaceManager tableSpaceManager, TransactionContext transactionContext, StatementEvaluationContext context) throws StatementExecutionException {
        DataScanner scan = tableSpaceManager.scan(statement, context, transactionContext);
        return new ScanResult(transactionContext.transactionId, scan);
    }

}
