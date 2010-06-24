package org.apache.qpid.test.client.destination;
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


import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;

import org.apache.qpid.client.AMQAnyDestination;
import org.apache.qpid.client.AMQDestination;
import org.apache.qpid.client.AMQSession_0_10;
import org.apache.qpid.client.messaging.address.Node.ExchangeNode;
import org.apache.qpid.client.messaging.address.Node.QueueNode;
import org.apache.qpid.messaging.Address;
import org.apache.qpid.test.utils.QpidBrokerTestCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AddressBasedDestinationTest extends QpidBrokerTestCase
{
    private static final Logger _logger = LoggerFactory.getLogger(AddressBasedDestinationTest.class);
    private Connection _connection;
    
    @Override
    public void setUp() throws Exception
    {
        super.setUp();
        _connection = getConnection() ;
        _connection.start();
    }
    
    @Override
    public void tearDown() throws Exception
    {
        _connection.close();
        super.tearDown();
    }
    
    public void testCreateOptions() throws Exception
    {
        if (!isCppBroker())
        {
            _logger.info("Not C++ broker, exiting test");
            return;
        }
        
        Session jmsSession = _connection.createSession(false,Session.AUTO_ACKNOWLEDGE);
        MessageProducer prod;
        MessageConsumer cons;
        
        // default (create never, assert never) -------------------
        // create never --------------------------------------------
        String addr1 = "ADDR:testQueue1";
        AMQDestination  dest = new AMQAnyDestination(addr1);
        try
        {
            cons = jmsSession.createConsumer(dest); 
        }
        catch(JMSException e)
        {
            assertTrue(e.getMessage().contains("The name 'testQueue1' supplied in the address " +
                    "doesn't resolve to an exchange or a queue"));
        }
        
        try
        {
            prod = jmsSession.createProducer(dest);
        }
        catch(JMSException e)
        {
            assertTrue(e.getMessage().contains("The name supplied in the address " +
                    "doesn't resolve to an exchange or a queue"));
        }
            
        assertFalse("Queue should not be created",(
                (AMQSession_0_10)jmsSession).isQueueExist(dest, (QueueNode)dest.getSourceNode() ,true));
        
        
        // create always -------------------------------------------
        addr1 = "ADDR:testQueue1; { create: always }";
        dest = new AMQAnyDestination(addr1);
        cons = jmsSession.createConsumer(dest); 
        
        assertTrue("Queue not created as expected",(
                (AMQSession_0_10)jmsSession).isQueueExist(dest,(QueueNode)dest.getSourceNode(), true));              
        assertTrue("Queue not bound as expected",(
                (AMQSession_0_10)jmsSession).isQueueBound("", 
                    dest.getAddressName(),dest.getAddressName(), dest.getSourceNode().getDeclareArgs()));
        
        // create receiver -----------------------------------------
        addr1 = "ADDR:testQueue2; { create: receiver }";
        dest = new AMQAnyDestination(addr1);
        try
        {
            prod = jmsSession.createProducer(dest);
        }
        catch(JMSException e)
        {
            assertTrue(e.getMessage().contains("The name supplied in the address " +
                    "doesn't resolve to an exchange or a queue"));
        }
            
        assertFalse("Queue should not be created",(
                (AMQSession_0_10)jmsSession).isQueueExist(dest,(QueueNode)dest.getSourceNode(), true));
        
        
        cons = jmsSession.createConsumer(dest); 
        
        assertTrue("Queue not created as expected",(
                (AMQSession_0_10)jmsSession).isQueueExist(dest,(QueueNode)dest.getSourceNode(), true));              
        assertTrue("Queue not bound as expected",(
                (AMQSession_0_10)jmsSession).isQueueBound("", 
                    dest.getAddressName(),dest.getAddressName(), dest.getSourceNode().getDeclareArgs()));
        
        // create never --------------------------------------------
        addr1 = "ADDR:testQueue3; { create: never }";
        dest = new AMQAnyDestination(addr1);
        try
        {
            cons = jmsSession.createConsumer(dest); 
        }
        catch(JMSException e)
        {
            assertTrue(e.getMessage().contains("The name 'testQueue3' supplied in the address " +
                    "doesn't resolve to an exchange or a queue"));
        }
        
        try
        {
            prod = jmsSession.createProducer(dest);
        }
        catch(JMSException e)
        {
            assertTrue(e.getMessage().contains("The name 'testQueue3' supplied in the address " +
                    "doesn't resolve to an exchange or a queue"));
        }
            
        assertFalse("Queue should not be created",(
                (AMQSession_0_10)jmsSession).isQueueExist(dest,(QueueNode)dest.getSourceNode(), true));
        
        // create sender ------------------------------------------
        addr1 = "ADDR:testQueue3; { create: sender }";
        dest = new AMQAnyDestination(addr1);
                
        try
        {
            cons = jmsSession.createConsumer(dest); 
        }
        catch(JMSException e)
        {
            assertTrue(e.getMessage().contains("The name 'testQueue3' supplied in the address " +
                    "doesn't resolve to an exchange or a queue"));
        }
        assertFalse("Queue should not be created",(
                (AMQSession_0_10)jmsSession).isQueueExist(dest,(QueueNode)dest.getSourceNode(), true));
        
        prod = jmsSession.createProducer(dest);
        assertTrue("Queue not created as expected",(
                (AMQSession_0_10)jmsSession).isQueueExist(dest,(QueueNode)dest.getSourceNode(), true));              
        assertTrue("Queue not bound as expected",(
                (AMQSession_0_10)jmsSession).isQueueBound("", 
                    dest.getAddressName(),dest.getAddressName(), dest.getSourceNode().getDeclareArgs()));
        
    }
    
    // todo add tests for delete options
    
    public void testCreateQueue() throws Exception
    {
        if (!isCppBroker())
        {
            _logger.info("Not C++ broker, exiting test");
            return;
        }
        Session jmsSession = _connection.createSession(false,Session.AUTO_ACKNOWLEDGE);
        
        String addr = "ADDR:my-queue/hello; " +
                      "{" + 
                            "create: always, " +
                            "node: " + 
                            "{" + 
                                 "durable: true ," +
                                 "x-declare: " +
                                 "{" + 
                                     "auto-delete: true," +
                                     "'qpid.max_size': 1000," +
                                     "'qpid.max_count': 100" +
                                  "}, " +   
                                  "x-bindings: [{exchange : 'amq.direct', key : test}, " + 
                                               "{exchange : 'amq.fanout'}," +
                                               "{exchange: 'amq.match', arguments: {x-match: any, dep: sales, loc: CA}}," +
                                               "{exchange : 'amq.topic', key : 'a.#'}" +
                                              "]," + 
                                     
                            "}" +
                      "}";
        AMQDestination dest = new AMQAnyDestination(addr);
        MessageConsumer cons = jmsSession.createConsumer(dest); 
        
        assertTrue("Queue not created as expected",(
                (AMQSession_0_10)jmsSession).isQueueExist(dest,(QueueNode)dest.getSourceNode(), true));              
        
        assertTrue("Queue not bound as expected",(
                (AMQSession_0_10)jmsSession).isQueueBound("", 
                    dest.getAddressName(),dest.getAddressName(), null));
        
        assertTrue("Queue not bound as expected",(
                (AMQSession_0_10)jmsSession).isQueueBound("amq.direct", 
                    dest.getAddressName(),"test", null));
        
        assertTrue("Queue not bound as expected",(
                (AMQSession_0_10)jmsSession).isQueueBound("amq.fanout", 
                    dest.getAddressName(),null, null));
        
        assertTrue("Queue not bound as expected",(
                (AMQSession_0_10)jmsSession).isQueueBound("amq.topic", 
                    dest.getAddressName(),"a.#", null));   
        
        Map<String,Object> args = new HashMap<String,Object>();
        args.put("x-match","any");
        args.put("dep","sales");
        args.put("loc","CA");
        assertTrue("Queue not bound as expected",(
                (AMQSession_0_10)jmsSession).isQueueBound("amq.match", 
                    dest.getAddressName(),null, args));
        
    }
    
    public void testCreateExchange() throws Exception
    {
        if (!isCppBroker())
        {
            _logger.info("Not C++ broker, exiting test");
            return;
        }
        Session jmsSession = _connection.createSession(false,Session.AUTO_ACKNOWLEDGE);
        
        String addr = "ADDR:my-exchange/hello; " + 
                      "{ " + 
                        "create: always, " +                        
                        "node: " + 
                        "{" +
                             "type: topic, " +
                             "x-declare: " +
                             "{ " + 
                                 "type:direct, " + 
                                 "auto-delete: true, " +
                                 "'qpid.msg_sequence': 1, " +
                                 "'qpid.ive': 1" + 
                             "}" +
                        "}" +
                      "}";
        
        AMQDestination dest = new AMQAnyDestination(addr);
        MessageConsumer cons = jmsSession.createConsumer(dest); 
        
        assertTrue("Exchange not created as expected",(
                (AMQSession_0_10)jmsSession).isExchangeExist(dest, (ExchangeNode)dest.getTargetNode() , true));
       
        // The existence of the queue is implicitly tested here
        assertTrue("Queue not bound as expected",(
                (AMQSession_0_10)jmsSession).isQueueBound("my-exchange", 
                    dest.getQueueName(),"hello", Collections.<String, Object>emptyMap()));
    }
    
    public void testBindQueueWithArgs() throws Exception
    {
        if (!isCppBroker())
        {
            _logger.info("Not C++ broker, exiting test");
            return;
        }
        
        Session jmsSession = _connection.createSession(false,Session.AUTO_ACKNOWLEDGE);
        
        String headersBinding = "{exchange: 'amq.match', arguments: {x-match: any, dep: sales, loc: CA}}";
        
        String addr = "ADDR:my-queue/hello; " + 
                      "{ " + 
                           "create: always, " +
                           "node: "  + 
                           "{" + 
                               "durable: true ," +
                               "x-declare: " + 
                               "{ " + 
                                     "auto-delete: true," +
                                     "'qpid.max_count': 100" +
                               "}, " +
                               "x-bindings: [{exchange : 'amq.direct', key : test}, " +
                                            "{exchange : 'amq.topic', key : 'a.#'}," + 
                                             headersBinding + 
                                           "]" +
                           "}" +
                      "}";

        AMQDestination dest = new AMQAnyDestination(addr);
        MessageConsumer cons = jmsSession.createConsumer(dest); 
        
        assertTrue("Queue not created as expected",(
                (AMQSession_0_10)jmsSession).isQueueExist(dest,(QueueNode)dest.getSourceNode(), true));              
        
        assertTrue("Queue not bound as expected",(
                (AMQSession_0_10)jmsSession).isQueueBound("", 
                    dest.getAddressName(),dest.getAddressName(), null));
        
        assertTrue("Queue not bound as expected",(
                (AMQSession_0_10)jmsSession).isQueueBound("amq.direct", 
                    dest.getAddressName(),"test", null));  
      
        assertTrue("Queue not bound as expected",(
                (AMQSession_0_10)jmsSession).isQueueBound("amq.topic", 
                    dest.getAddressName(),"a.#", null));
        
        Address a = Address.parse(headersBinding);
        assertTrue("Queue not bound as expected",(
                (AMQSession_0_10)jmsSession).isQueueBound("amq.match", 
                    dest.getAddressName(),null, a.getOptions()));
    }
    
    /**
     * Test goal: Verifies the capacity property in address string is handled properly.
     * Test strategy:
     * Creates a destination with capacity 10.
     * Creates consumer with client ack.
     * Sends 15 messages to the queue, tries to receive 10.
     * Tries to receive the 11th message and checks if its null.
     * 
     * Since capacity is 10 and we haven't acked any messages, 
     * we should not have received the 11th.
     * 
     * Acks the 10th message and verifies we receive the rest of the msgs.
     */
    public void testCapacity() throws Exception
    {
        verifyCapacity("ADDR:my-queue; {create: always, link:{capacity: 10}}");
    }
    
    public void testSourceAndTargetCapacity() throws Exception
    {
        verifyCapacity("ADDR:my-queue; {create: always, link:{capacity: {source:10, target:15} }}");
    }
    
    private void verifyCapacity(String address) throws Exception
    {
        if (!isCppBroker())
        {
            _logger.info("Not C++ broker, exiting test");
            return;
        }
        
        Session jmsSession = _connection.createSession(false,Session.CLIENT_ACKNOWLEDGE);
        
        AMQDestination dest = new AMQAnyDestination(address);
        MessageConsumer cons = jmsSession.createConsumer(dest); 
        MessageProducer prod = jmsSession.createProducer(dest);
        
        for (int i=0; i< 15; i++)
        {
            prod.send(jmsSession.createTextMessage("msg" + i) );
        }
        
        for (int i=0; i< 9; i++)
        {
            cons.receive();
        }
        Message msg = cons.receive(RECEIVE_TIMEOUT);
        assertNotNull("Should have received the 10th message",msg);        
        assertNull("Shouldn't have received the 11th message as capacity is 10",cons.receive(RECEIVE_TIMEOUT));
        msg.acknowledge();
        for (int i=11; i<16; i++)
        {
            assertNotNull("Should have received the " + i + "th message as we acked the last 10",cons.receive(RECEIVE_TIMEOUT));
        }
    }
    
    /*public void testBindQueueForXMLExchange() throws Exception
    {
        if (!isCppBroker())
        {
            return;
        }
        
        Session jmsSession = _connection.createSession(false,Session.AUTO_ACKNOWLEDGE);
        ((AMQSession_0_10)jmsSession).sendExchangeDeclare("xml", "xml",null,null,false);
        
        String xQuery = "let $w := ./weather \n" +
                        "return $w/station = \'Raleigh-Durham International Airport (KRDU)\' \n" +
                            "and $w/temperature_f > 50 \n" + 
                            "and $w/temperature_f - $w/dewpoint > 5 \n" + 
                            "and $w/wind_speed_mph > 7 \n" +
                            "and $w/wind_speed_mph < 20";
        
        String xmlExchangeBinding = "'xml; {xquery: " + xQuery + "} '";
        
        String addr = "ADDR:my-queue/hello; { " + 
                        "create: always, " +
                        "node-properties: {" + 
                             "durable: true ," +
                             "x-properties: { " + 
                                 "auto-delete: true," +
                                 "'qpid.max_count': 100," + 
                                 " bindings: ['amq.direct/test', 'amq.topic/a.#'," + xmlExchangeBinding + "]" +
                                 
                             "}" +
                        "}" +
                      "}";
        
        AMQDestination dest = new AMQAnyDestination(addr);
        MessageConsumer cons = jmsSession.createConsumer(dest); 
        
        assertTrue("Queue not created as expected",(
                (AMQSession_0_10)jmsSession).isQueueExist(dest, true));              
        
        assertTrue("Queue not bound as expected",(
                (AMQSession_0_10)jmsSession).isQueueBound("", 
                    dest.getName(),dest.getName(), null));
        
        assertTrue("Queue not bound as expected",(
                (AMQSession_0_10)jmsSession).isQueueBound("amq.direct", 
                    dest.getName(),"test", null));  
      
        assertTrue("Queue not bound as expected",(
                (AMQSession_0_10)jmsSession).isQueueBound("amq.topic", 
                    dest.getName(),"a.#", null));
        
        Address a = Address.parse(xmlExchangeBinding);
        assertTrue("Queue not bound as expected",(
                (AMQSession_0_10)jmsSession).isQueueBound("xml", 
                    dest.getName(),null, a.getOptions()));
        
    }*/
}