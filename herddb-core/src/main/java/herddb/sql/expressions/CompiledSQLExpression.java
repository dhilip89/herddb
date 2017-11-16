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
package herddb.sql.expressions;

import herddb.model.StatementEvaluationContext;
import herddb.model.StatementExecutionException;
import java.util.Collections;
import java.util.List;

/**
 * A specific implementation of a predicate
 *
 * @author enrico.olivelli
 */
public interface CompiledSQLExpression {

    public interface BinaryExpressionBuilder {

        public CompiledSQLExpression build(boolean not, CompiledSQLExpression left, CompiledSQLExpression right);
    }

    /**
     * Evaluates the expression
     *
     * @param bean
     * @param context
     * @return
     * @throws StatementExecutionException
     */
    public Object evaluate(herddb.utils.DataAccessor bean, StatementEvaluationContext context) throws StatementExecutionException;

    /**
     * Validates the expression without actually doing complex operation
     *
     * @param context
     * @throws StatementExecutionException
     */
    public default void validate(StatementEvaluationContext context) throws StatementExecutionException {
    }

    public default List<CompiledSQLExpression> scanForConstraintedValueOnColumnWithOperator(
            String column, String operator, BindableTableScanColumnNameResolver columnNameResolver
    ) {
        return Collections.emptyList();
    }
    
    public default List<CompiledSQLExpression> scanForConstraintsOnColumn(
            String column, BindableTableScanColumnNameResolver columnNameResolver
    ) {
        return Collections.emptyList();
    }

    public default CompiledSQLExpression cast(int type) {
        return new CastExpression(this, type);
    }
}
