/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package org.apache.qpid.server.store.berkeleydb.entry;

import org.apache.qpid.server.store.MessageStore;

public class PreparedTransaction
{
    private final MessageStore.Transaction.Record[] _enqueues;
    private final MessageStore.Transaction.Record[] _dequeues;

    public PreparedTransaction(MessageStore.Transaction.Record[] enqueues, MessageStore.Transaction.Record[] dequeues)
    {
        _enqueues = enqueues;
        _dequeues = dequeues;
    }

    public MessageStore.Transaction.Record[] getEnqueues()
    {
        return _enqueues;
    }

    public MessageStore.Transaction.Record[] getDequeues()
    {
        return _dequeues;
    }
}
