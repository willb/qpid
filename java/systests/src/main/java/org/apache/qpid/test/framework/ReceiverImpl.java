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
package org.apache.qpid.test.framework;

import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;

/**
 * <p/><table id="crc"><caption>CRC Card</caption>
 * <tr><th> Responsibilities <th> Collaborations
 * <tr><td>
 * </table>
 */
public class ReceiverImpl extends CircuitEndBase implements Receiver
{
    /** Holds a reference to the containing circuit. */
    private CircuitImpl circuit;

    /**
     * Creates a circuit end point on the specified producer, consumer and session.
     *
     * @param producer The message producer for the circuit end point.
     * @param consumer The message consumer for the circuit end point.
     * @param session  The session for the circuit end point.
     */
    public ReceiverImpl(MessageProducer producer, MessageConsumer consumer, Session session)
    {
        super(producer, consumer, session);
    }

    /**
     * Provides an assertion that the receiver encountered no exceptions.
     *
     * @return An assertion that the receiver encountered no exceptions.
     */
    public Assertion noExceptionsAssertion()
    {
        return null;
    }

    /**
     * Provides an assertion that the receiver got all messages that were sent to it.
     *
     * @return An assertion that the receiver got all messages that were sent to it.
     */
    public Assertion allMessagesAssertion()
    {
        return null;
    }

    /**
     * Sets the contianing circuit.
     *
     * @param circuit The containing circuit.
     */
    public void setCircuit(CircuitImpl circuit)
    {
        this.circuit = circuit;
    }
}
