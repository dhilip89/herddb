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
package herddb.client.impl;

import herddb.client.HDBException;
import herddb.client.ScanResultSet;
import herddb.client.ScanResultSetMetadata;
import java.util.Collections;
import java.util.Map;

/**
 * Empty result set
 *
 * @author enrico.olivelli
 */
public class EmptyScanResultSet extends ScanResultSet {

    public EmptyScanResultSet(long transactionId) {
        super(transactionId);
    }

    private static final ScanResultSetMetadata EMPTY = new ScanResultSetMetadata(new String[0]);

    @Override
    public ScanResultSetMetadata getMetadata() {
        return EMPTY;
    }

    @Override
    public boolean hasNext() throws HDBException {
        return false;
    }

    @Override
    public Map<String, Object> next() throws HDBException {
        throw new HDBException("this is an empty result set");
    }

}
