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
package org.apache.qpid.server.store.berkeleydb;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.Destination;
import javax.jms.ExceptionListener;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.Topic;
import javax.jms.TopicConnection;
import javax.jms.TopicPublisher;
import javax.jms.TopicSession;
import javax.jms.TopicSubscriber;

import org.apache.qpid.client.AMQConnectionFactory;
import org.apache.qpid.client.AMQDestination;
import org.apache.qpid.client.AMQSession;
import org.apache.qpid.framing.AMQShortString;
import org.apache.qpid.url.URLSyntaxException;

/**
 * Prepares an older version brokers BDB store with the required
 * contents for use in the BDBStoreUpgradeTest.
 *
 * NOTE: Must be used with the equivalent older version client!
 *
 * The store will then be used to verify that the upgraded is
 * completed properly and that once upgraded it functions as
 * expected with the new broker.
 *
 */
public class BDBStoreUpgradeTestPreparer
{
    public static final String TOPIC_NAME="myUpgradeTopic";
    public static final String SUB_NAME="myDurSubName";
    public static final String SELECTOR_SUB_NAME="mySelectorDurSubName";
    public static final String SELECTOR_TOPIC_NAME="mySelectorUpgradeTopic";
    public static final String QUEUE_NAME="myUpgradeQueue";
    public static final String NON_DURABLE_QUEUE_NAME="queue-non-durable";

    private static AMQConnectionFactory _connFac;
    private static final String CONN_URL =
        "amqp://guest:guest@clientid/test?brokerlist='tcp://localhost:5672'";

    /**
     * Create a BDBStoreUpgradeTestPreparer instance
     */
    public BDBStoreUpgradeTestPreparer () throws URLSyntaxException
    {
        _connFac = new AMQConnectionFactory(CONN_URL);
    }

    private void prepareBroker() throws Exception
    {
        prepareQueues();
        prepareNonDurableQueue();
        prepareDurableSubscriptionWithSelector();
        prepareDurableSubscriptionWithoutSelector();
    }

    private void prepareNonDurableQueue() throws Exception
    {
        Connection connection = _connFac.createConnection();
        AMQSession<?, ?> session = (AMQSession<?,?>)connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        AMQShortString queueName = AMQShortString.valueOf(NON_DURABLE_QUEUE_NAME);
        AMQDestination destination = (AMQDestination) session.createQueue(NON_DURABLE_QUEUE_NAME);
        session.sendCreateQueue(queueName, false, false, false, null);
        session.bindQueue(queueName, queueName, null, AMQShortString.valueOf("amq.direct"), destination);
        MessageProducer messageProducer = session.createProducer(destination);
        sendMessages(session, messageProducer, destination, DeliveryMode.PERSISTENT, 1024, 3);
        connection.close();
    }

    /**
     * Prepare a queue for use in testing message and binding recovery
     * after the upgrade is performed.
     *
     * - Create a transacted session on the connection.
     * - Use a consumer to create the (durable by default) queue.
     * - Send 5 large messages to test (multi-frame) content recovery.
     * - Send 1 small message to test (single-frame) content recovery.
     * - Commit the session.
     * - Send 5 small messages to test that uncommitted messages are not recovered.
     *   following the upgrade.
     * - Close the session.
     */
    private void prepareQueues() throws Exception
    {
        // Create a connection
        Connection connection = _connFac.createConnection();
        connection.start();
        connection.setExceptionListener(new ExceptionListener()
        {
            public void onException(JMSException e)
            {
                e.printStackTrace();
            }
        });
        // Create a session on the connection, transacted to confirm delivery
        Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
        Queue queue = session.createQueue(QUEUE_NAME);
        // Create a consumer to ensure the queue gets created
        // (and enter it into the store, as queues are made durable by default)
        MessageConsumer messageConsumer = session.createConsumer(queue);
        messageConsumer.close();

        // Create a Message producer
        MessageProducer messageProducer = session.createProducer(queue);

        // Publish 5 persistent messages, 256k chars to ensure they are multi-frame
        sendMessages(session, messageProducer, queue, DeliveryMode.PERSISTENT, 256*1024, 5);
        // Publish 5 persistent messages, 1k chars to ensure they are single-frame
        sendMessages(session, messageProducer, queue, DeliveryMode.PERSISTENT, 1*1024, 5);

        session.commit();

        // Publish 5 persistent messages which will NOT be committed and so should be 'lost'
        sendMessages(session, messageProducer, queue, DeliveryMode.PERSISTENT, 1*1024, 5);

        session.close();
        connection.close();
    }

    /**
     * Prepare a DurableSubscription backing queue for use in testing selector
     * recovery and queue exclusivity marking during the upgrade process.
     *
     * - Create a transacted session on the connection.
     * - Open and close a DurableSubscription with selector to create the backing queue.
     * - Send a message which matches the selector.
     * - Send a message which does not match the selector.
     * - Send a message which matches the selector but will remain uncommitted.
     * - Close the session.
     */
    private void prepareDurableSubscriptionWithSelector() throws Exception
    {

        // Create a connection
        TopicConnection connection = _connFac.createTopicConnection();
        connection.start();
        connection.setExceptionListener(new ExceptionListener()
        {
            public void onException(JMSException e)
            {
                e.printStackTrace();
            }
        });
        // Create a session on the connection, transacted to confirm delivery
        Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
        Topic topic = session.createTopic(SELECTOR_TOPIC_NAME);

        // Create and register a durable subscriber with selector and then close it
        TopicSubscriber durSub1 = session.createDurableSubscriber(topic, SELECTOR_SUB_NAME,"testprop='true'", false);
        durSub1.close();

        // Create a publisher and send a persistent message which matches the selector
        // followed by one that does not match, and another which matches but is not
        // committed and so should be 'lost'
        TopicSession pubSession = connection.createTopicSession(true, Session.SESSION_TRANSACTED);
        TopicPublisher publisher = pubSession.createPublisher(topic);

        publishMessages(pubSession, publisher, topic, DeliveryMode.PERSISTENT, 1*1024, 1, "true");
        publishMessages(pubSession, publisher, topic, DeliveryMode.PERSISTENT, 1*1024, 1, "false");
        pubSession.commit();
        publishMessages(pubSession, publisher, topic, DeliveryMode.PERSISTENT, 1*1024, 1, "true");

        publisher.close();
        pubSession.close();
        connection.close();
    }

    /**
     * Prepare a DurableSubscription backing queue for use in testing use of
     * DurableSubscriptions without selectors following the upgrade process.
     *
     * - Create a transacted session on the connection.
     * - Open and close a DurableSubscription without selector to create the backing queue.
     * - Send a message which matches the subscription and commit session.
     * - Close the session.
     */
    private void prepareDurableSubscriptionWithoutSelector() throws Exception
    {
        // Create a connection
        TopicConnection connection = _connFac.createTopicConnection();
        connection.start();
        connection.setExceptionListener(new ExceptionListener()
        {
            public void onException(JMSException e)
            {
                e.printStackTrace();
            }
        });
        // Create a session on the connection, transacted to confirm delivery
        Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
        Topic topic = session.createTopic(TOPIC_NAME);

        // Create and register a durable subscriber without selector and then close it
        TopicSubscriber durSub1 = session.createDurableSubscriber(topic, SUB_NAME);
        durSub1.close();

        // Create a publisher and send a persistent message which matches the subscription
        TopicSession pubSession = connection.createTopicSession(true, Session.SESSION_TRANSACTED);
        TopicPublisher publisher = pubSession.createPublisher(topic);

        publishMessages(pubSession, publisher, topic, DeliveryMode.PERSISTENT, 1*1024, 1, "indifferent");
        pubSession.commit();

        publisher.close();
        pubSession.close();
        connection.close();
    }

    public static void sendMessages(Session session, MessageProducer messageProducer,
            Destination dest, int deliveryMode, int length, int numMesages) throws JMSException
    {
        for (int i = 1; i <= numMesages; i++)
        {
            Message message = session.createTextMessage(generateString(length));
            message.setIntProperty("ID", i);
            messageProducer.send(message, deliveryMode, Message.DEFAULT_PRIORITY, Message.DEFAULT_TIME_TO_LIVE);
        }
    }

    public static void publishMessages(Session session, TopicPublisher publisher,
            Destination dest, int deliveryMode, int length, int numMesages, String selectorProperty) throws JMSException
    {
        for (int i = 1; i <= numMesages; i++)
        {
            Message message = session.createTextMessage(generateString(length));
            message.setIntProperty("ID", i);
            message.setStringProperty("testprop", selectorProperty);
            publisher.publish(message, deliveryMode, Message.DEFAULT_PRIORITY, Message.DEFAULT_TIME_TO_LIVE);
        }
    }

    /**
     * Generates a string of a given length consisting of the sequence 0,1,2,..,9,0,1,2.
     *
     * @param length number of characters in the string
     * @return string sequence of the given length
     */
    public static String generateString(int length)
    {
        char[] base_chars = new char[]{'0','1','2','3','4','5','6','7','8','9'};
        char[] chars = new char[length];
        for (int i = 0; i < (length); i++)
        {
            chars[i] = base_chars[i % 10];
        }
        return new String(chars);
    }

    /**
     * Run the preparation tool.
     * @param args Command line arguments.
     */
    public static void main(String[] args) throws Exception
    {
        System.setProperty("qpid.dest_syntax", "BURL");
        BDBStoreUpgradeTestPreparer producer = new BDBStoreUpgradeTestPreparer();
        producer.prepareBroker();
    }
}