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
import herddb.model.AutoIncrementPrimaryKeyRecordFunction;
import herddb.model.Column;
import herddb.model.DMLStatement;
import herddb.model.DMLStatementExecutionResult;
import herddb.model.DataScanner;
import herddb.model.DataScannerException;
import herddb.model.RecordFunction;
import herddb.model.ScanResult;
import herddb.model.StatementEvaluationContext;
import herddb.model.StatementExecutionException;
import herddb.model.StatementExecutionResult;
import herddb.model.Table;
import herddb.model.TableAwareStatement;
import herddb.model.TransactionContext;
import herddb.model.commands.InsertStatement;
import herddb.sql.SQLRecordFunction;
import herddb.sql.SQLRecordKeyFunction;
import herddb.sql.expressions.CompiledSQLExpression;
import herddb.sql.expressions.ConstantExpression;
import herddb.utils.Bytes;
import herddb.utils.DataAccessor;
import herddb.utils.Wrapper;
import java.util.ArrayList;
import java.util.List;

public class InsertOp implements PlannerOp {

    private final String tableSpace;
    private final String tableName;
    private final PlannerOp input;
    private final boolean returnValues;

    public InsertOp(String tableSpace, String tableName, PlannerOp input, boolean returnValues) {
        this.tableSpace = tableSpace;
        this.tableName = tableName;
        this.input = input.optimize();
        this.returnValues = returnValues;
    }

    @Override
    public String getTablespace() {
        return tableSpace;
    }

    @Override
    public StatementExecutionResult execute(TableSpaceManager tableSpaceManager,
            TransactionContext transactionContext, StatementEvaluationContext context,
            boolean lockRequired, boolean forWrite) {
        StatementExecutionResult input = this.input.execute(tableSpaceManager,
                transactionContext, context, true, true);
        ScanResult downstreamScanResult = (ScanResult) input;
        final Table table = tableSpaceManager.getTableManager(tableName).getTable();
        long transactionId = transactionContext.transactionId;
        int updateCount = 0;
        Bytes key = null;
        Bytes newValue = null;
        try (DataScanner inputScanner = downstreamScanResult.dataScanner;) {
            while (inputScanner.hasNext()) {

                DataAccessor row = inputScanner.next();
                long transactionIdFromScanner = inputScanner.getTransactionId();
                if (transactionIdFromScanner > 0 && transactionIdFromScanner != transactionId) {
                    transactionId = transactionIdFromScanner;
                    transactionContext = new TransactionContext(transactionId);
                }
                int index = 0;
                List<CompiledSQLExpression> keyValueExpression = new ArrayList<>();
                List<String> keyExpressionToColumn = new ArrayList<>();

                List<CompiledSQLExpression> valuesExpressions = new ArrayList<>();
                List<String> valuesColumns = new ArrayList<>();
                for (Column column : table.getColumns()) {
                    Object value = row.get(index++);
                    if (value != null) {
                        ConstantExpression exp = new ConstantExpression(value);
                        if (table.isPrimaryKeyColumn(column.name)) {
                            keyExpressionToColumn.add(column.name);
                            keyValueExpression.add(exp);
                        }
                        valuesColumns.add(column.name);
                        valuesExpressions.add(exp);
                    }
                }

                RecordFunction keyfunction;
                if (keyValueExpression.isEmpty()
                        && table.auto_increment) {
                    keyfunction = new AutoIncrementPrimaryKeyRecordFunction();
                } else {
                    if (keyValueExpression.size() != table.primaryKey.length) {
                        throw new StatementExecutionException("you must set a value for the primary key (expressions=" + keyValueExpression.size() + ")");
                    }
                    keyfunction = new SQLRecordKeyFunction(keyExpressionToColumn, keyValueExpression, table);
                }
                RecordFunction valuesfunction = new SQLRecordFunction(valuesColumns, table, valuesExpressions);

                DMLStatement insertStatement = new InsertStatement(tableSpace, tableName, keyfunction, valuesfunction).setReturnValues(returnValues);

                DMLStatementExecutionResult _result = (DMLStatementExecutionResult) tableSpaceManager.executeStatement(insertStatement, context, transactionContext);
                updateCount += _result.getUpdateCount();
                if (_result.transactionId > 0 && _result.transactionId != transactionId) {
                    transactionId = _result.transactionId;
                    transactionContext = new TransactionContext(transactionId);
                }
                key = _result.getKey();
                newValue = _result.getNewvalue();
            }
            if (updateCount > 1 && returnValues) {
                if (transactionId > 0) {
                    // usually the first record will be rolledback with transaction failure
                    throw new StatementExecutionException("cannot 'return values' on multi-values insert");
                } else {
                    throw new StatementExecutionException("cannot 'return values' on multi-values insert, at least record could have been written because autocommit=true");
                }
            }
            return new DMLStatementExecutionResult(transactionId, updateCount, key, newValue);
        } catch (DataScannerException err) {
            throw new StatementExecutionException(err);
        }

    }

    @Override
    public <T> T unwrap(Class<T> clazz) {
        if (clazz.isAssignableFrom(TableAwareStatement.class)) {
            return (T) new TableAwareStatement(tableName, tableSpace) {
            };
        }
        return Wrapper.unwrap(this, clazz);
    }
}
