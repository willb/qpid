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
package org.apache.qpid.server.store;

import org.apache.commons.configuration.Configuration;
import org.apache.log4j.Logger;

import org.apache.qpid.AMQException;
import org.apache.qpid.AMQStoreException;
import org.apache.qpid.framing.AMQShortString;
import org.apache.qpid.framing.FieldTable;
import org.apache.qpid.server.exchange.Exchange;
import org.apache.qpid.server.federation.Bridge;
import org.apache.qpid.server.federation.BrokerLink;
import org.apache.qpid.server.logging.LogSubject;
import org.apache.qpid.server.logging.actors.CurrentActor;
import org.apache.qpid.server.logging.messages.ConfigStoreMessages;
import org.apache.qpid.server.logging.messages.MessageStoreMessages;
import org.apache.qpid.server.logging.messages.TransactionLogMessages;
import org.apache.qpid.server.message.EnqueableMessage;
import org.apache.qpid.server.queue.AMQQueue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An implementation of a {@link MessageStore} that uses Apache Derby as the persistance
 * mechanism.
 * 
 * TODO extract the SQL statements into a generic JDBC store
 */
public class DerbyMessageStore implements MessageStore, DurableConfigurationStore
{

    private static final Logger _logger = Logger.getLogger(DerbyMessageStore.class);

    public static final String ENVIRONMENT_PATH_PROPERTY = "environment-path";


    private static final String SQL_DRIVER_NAME = "org.apache.derby.jdbc.EmbeddedDriver";

    private static final String DB_VERSION_TABLE_NAME = "QPID_DB_VERSION";

    private static final String EXCHANGE_TABLE_NAME = "QPID_EXCHANGE";
    private static final String QUEUE_TABLE_NAME = "QPID_QUEUE";
    private static final String BINDINGS_TABLE_NAME = "QPID_BINDINGS";
    private static final String QUEUE_ENTRY_TABLE_NAME = "QPID_QUEUE_ENTRY";

    private static final String META_DATA_TABLE_NAME = "QPID_META_DATA";
    private static final String MESSAGE_CONTENT_TABLE_NAME = "QPID_MESSAGE_CONTENT";

    private static final String LINKS_TABLE_NAME = "QPID_LINKS";
    private static final String BRIDGES_TABLE_NAME = "QPID_BRIDGES";

    private static final String XID_TABLE_NAME = "QPID_XIDS";
    private static final String XID_ACTIONS_TABLE_NAME = "QPID_XID_ACTIONS";
    
    private static final int DB_VERSION = 3;



    private static Class<Driver> DRIVER_CLASS;

    private final AtomicLong _messageId = new AtomicLong(0);
    private AtomicBoolean _closed = new AtomicBoolean(false);

    private String _connectionURL;

    private static final String TABLE_EXISTANCE_QUERY = "SELECT 1 FROM SYS.SYSTABLES WHERE TABLENAME = ?";

    private static final String CREATE_DB_VERSION_TABLE = "CREATE TABLE "+DB_VERSION_TABLE_NAME+" ( version int not null )";
    private static final String INSERT_INTO_DB_VERSION = "INSERT INTO "+DB_VERSION_TABLE_NAME+" ( version ) VALUES ( ? )";

    private static final String CREATE_EXCHANGE_TABLE = "CREATE TABLE "+EXCHANGE_TABLE_NAME+" ( name varchar(255) not null, type varchar(255) not null, autodelete SMALLINT not null, PRIMARY KEY ( name ) )";
    private static final String CREATE_QUEUE_TABLE = "CREATE TABLE "+QUEUE_TABLE_NAME+" ( name varchar(255) not null, owner varchar(255), exclusive SMALLINT not null, arguments blob, PRIMARY KEY ( name ))";
    private static final String CREATE_BINDINGS_TABLE = "CREATE TABLE "+BINDINGS_TABLE_NAME+" ( exchange_name varchar(255) not null, queue_name varchar(255) not null, binding_key varchar(255) not null, arguments blob , PRIMARY KEY ( exchange_name, queue_name, binding_key ) )";
    private static final String SELECT_FROM_QUEUE = "SELECT name, owner, exclusive, arguments FROM " + QUEUE_TABLE_NAME;
    private static final String FIND_QUEUE = "SELECT name, owner FROM " + QUEUE_TABLE_NAME + " WHERE name = ?";
    private static final String UPDATE_QUEUE_EXCLUSIVITY = "UPDATE " + QUEUE_TABLE_NAME + " SET exclusive = ? WHERE name = ?";
    private static final String SELECT_FROM_EXCHANGE = "SELECT name, type, autodelete FROM " + EXCHANGE_TABLE_NAME;
    private static final String SELECT_FROM_BINDINGS =
            "SELECT exchange_name, queue_name, binding_key, arguments FROM " + BINDINGS_TABLE_NAME + " ORDER BY exchange_name";
    private static final String FIND_BINDING =
            "SELECT * FROM " + BINDINGS_TABLE_NAME + " WHERE exchange_name = ? AND queue_name = ? AND binding_key = ? ";
    private static final String INSERT_INTO_EXCHANGE = "INSERT INTO " + EXCHANGE_TABLE_NAME + " ( name, type, autodelete ) VALUES ( ?, ?, ? )";
    private static final String DELETE_FROM_EXCHANGE = "DELETE FROM " + EXCHANGE_TABLE_NAME + " WHERE name = ?";
    private static final String FIND_EXCHANGE = "SELECT name FROM " + EXCHANGE_TABLE_NAME + " WHERE name = ?";
    private static final String INSERT_INTO_BINDINGS = "INSERT INTO " + BINDINGS_TABLE_NAME + " ( exchange_name, queue_name, binding_key, arguments ) values ( ?, ?, ?, ? )";
    private static final String DELETE_FROM_BINDINGS = "DELETE FROM " + BINDINGS_TABLE_NAME + " WHERE exchange_name = ? AND queue_name = ? AND binding_key = ?";
    private static final String INSERT_INTO_QUEUE = "INSERT INTO " + QUEUE_TABLE_NAME + " (name, owner, exclusive, arguments) VALUES (?, ?, ?, ?)";
    private static final String DELETE_FROM_QUEUE = "DELETE FROM " + QUEUE_TABLE_NAME + " WHERE name = ?";

    private static final String CREATE_QUEUE_ENTRY_TABLE = "CREATE TABLE "+QUEUE_ENTRY_TABLE_NAME+" ( queue_name varchar(255) not null, message_id bigint not null, PRIMARY KEY (queue_name, message_id) )";
    private static final String INSERT_INTO_QUEUE_ENTRY = "INSERT INTO " + QUEUE_ENTRY_TABLE_NAME + " (queue_name, message_id) values (?,?)";
    private static final String DELETE_FROM_QUEUE_ENTRY = "DELETE FROM " + QUEUE_ENTRY_TABLE_NAME + " WHERE queue_name = ? AND message_id =?";
    private static final String SELECT_FROM_QUEUE_ENTRY = "SELECT queue_name, message_id FROM " + QUEUE_ENTRY_TABLE_NAME + " ORDER BY queue_name, message_id";


    private static final String CREATE_META_DATA_TABLE = "CREATE TABLE "+META_DATA_TABLE_NAME+" ( message_id bigint not null, meta_data blob, PRIMARY KEY ( message_id ) )";
    private static final String CREATE_MESSAGE_CONTENT_TABLE = "CREATE TABLE "+MESSAGE_CONTENT_TABLE_NAME+" ( message_id bigint not null, offset int not null, last_byte int not null, content blob , PRIMARY KEY (message_id, offset) )";

    private static final String INSERT_INTO_MESSAGE_CONTENT = "INSERT INTO " + MESSAGE_CONTENT_TABLE_NAME + "( message_id, offset, last_byte, content ) values (?, ?, ?, ?)";
    private static final String SELECT_FROM_MESSAGE_CONTENT =
            "SELECT offset, content FROM " + MESSAGE_CONTENT_TABLE_NAME + " WHERE message_id = ? AND last_byte > ? AND offset < ? ORDER BY message_id, offset";
    private static final String DELETE_FROM_MESSAGE_CONTENT = "DELETE FROM " + MESSAGE_CONTENT_TABLE_NAME + " WHERE message_id = ?";

    private static final String INSERT_INTO_META_DATA = "INSERT INTO " + META_DATA_TABLE_NAME + "( message_id , meta_data ) values (?, ?)";;
    private static final String SELECT_FROM_META_DATA =
            "SELECT meta_data FROM " + META_DATA_TABLE_NAME + " WHERE message_id = ?";
    private static final String DELETE_FROM_META_DATA = "DELETE FROM " + META_DATA_TABLE_NAME + " WHERE message_id = ?";
    private static final String SELECT_ALL_FROM_META_DATA = "SELECT message_id, meta_data FROM " + META_DATA_TABLE_NAME;

    private static final String CREATE_LINKS_TABLE =
            "CREATE TABLE "+LINKS_TABLE_NAME+" ( id_lsb bigint not null,"
                                            + " id_msb bigint not null,"
                                             + " create_time bigint not null,"
                                             + " arguments blob,  PRIMARY KEY ( id_lsb, id_msb ))";
    private static final String SELECT_FROM_LINKS =
            "SELECT create_time, arguments FROM " + LINKS_TABLE_NAME + " WHERE id_lsb = ? and id_msb";
    private static final String DELETE_FROM_LINKS = "DELETE FROM " + LINKS_TABLE_NAME 
                                                    + " WHERE id_lsb = ? and id_msb = ?";
    private static final String SELECT_ALL_FROM_LINKS = "SELECT id_lsb, id_msb, create_time, "
                                                        + "arguments FROM " + LINKS_TABLE_NAME;
    private static final String FIND_LINK = "SELECT id_lsb, id_msb FROM " + LINKS_TABLE_NAME + " WHERE id_lsb = ? and"
                                            + " id_msb = ?";
    private static final String INSERT_INTO_LINKS = "INSERT INTO " + LINKS_TABLE_NAME + "( id_lsb, "
                                                  + "id_msb, create_time, arguments ) values (?, ?, ?, ?)";


    private static final String CREATE_BRIDGES_TABLE =
            "CREATE TABLE "+BRIDGES_TABLE_NAME+" ( id_lsb bigint not null,"
            + " id_msb bigint not null,"
            + " create_time bigint not null,"
            + " link_id_lsb bigint not null,"
            + " link_id_msb bigint not null,"
            + " arguments blob,  PRIMARY KEY ( id_lsb, id_msb ))";
    private static final String SELECT_FROM_BRIDGES =
            "SELECT create_time, link_id_lsb, link_id_msb, arguments FROM " 
            + BRIDGES_TABLE_NAME + " WHERE id_lsb = ? and id_msb = ?";
    private static final String DELETE_FROM_BRIDGES = "DELETE FROM " + BRIDGES_TABLE_NAME 
                                                      + " WHERE id_lsb = ? and id_msb = ?";
    private static final String SELECT_ALL_FROM_BRIDGES = "SELECT id_lsb, id_msb, " 
                                                          + " create_time," 
                                                          + " link_id_lsb, link_id_msb, "
                                                        + "arguments FROM " + BRIDGES_TABLE_NAME
                                                        + " WHERE link_id_lsb = ? and link_id_msb = ?";
    private static final String FIND_BRIDGE = "SELECT id_lsb, id_msb FROM " + BRIDGES_TABLE_NAME +
                                              " WHERE id_lsb = ? and id_msb = ?";
    private static final String INSERT_INTO_BRIDGES = "INSERT INTO " + BRIDGES_TABLE_NAME + "( id_lsb, id_msb, "
                                                    + "create_time, "
                                                    + "link_id_lsb, link_id_msb, "
                                                    + "arguments )"
                                                    + " values (?, ?, ?, ?, ?, ?)";

    private static final String CREATE_XIDS_TABLE =
            "CREATE TABLE "+XID_TABLE_NAME+" ( format bigint not null,"
            + " global_id varchar(64) for bit data, branch_id varchar(64) for bit data,  PRIMARY KEY ( format, " +
            "global_id, branch_id ))";
    private static final String INSERT_INTO_XIDS = 
            "INSERT INTO "+XID_TABLE_NAME+" ( format, global_id, branch_id ) values (?, ?, ?)";
    private static final String DELETE_FROM_XIDS = "DELETE FROM " + XID_TABLE_NAME
                                                      + " WHERE format = ? and global_id = ? and branch_id = ?";
    private static final String SELECT_ALL_FROM_XIDS = "SELECT format, global_id, branch_id FROM " + XID_TABLE_NAME;


    private static final String CREATE_XID_ACTIONS_TABLE =
            "CREATE TABLE "+XID_ACTIONS_TABLE_NAME+" ( format bigint not null,"
            + " global_id varchar(64) for bit data not null, branch_id varchar(64) for bit data not null, " +
            "action_type char not null, queue_name varchar(255) not null, message_id bigint not null" +
            ",  PRIMARY KEY ( " +
            "format, global_id, branch_id, action_type, queue_name, message_id))";
    private static final String INSERT_INTO_XID_ACTIONS =
            "INSERT INTO "+XID_ACTIONS_TABLE_NAME+" ( format, global_id, branch_id, action_type, " +
            "queue_name, message_id ) values (?,?,?,?,?,?) ";
    private static final String DELETE_FROM_XID_ACTIONS = "DELETE FROM " + XID_ACTIONS_TABLE_NAME
                                                   + " WHERE format = ? and global_id = ? and branch_id = ?";
    private static final String SELECT_ALL_FROM_XID_ACTIONS = 
            "SELECT action_type, queue_name, message_id FROM " + XID_ACTIONS_TABLE_NAME + 
            " WHERE format = ? and global_id = ? and branch_id = ?";

    private static final String DERBY_SINGLE_DB_SHUTDOWN_CODE = "08006";


    private LogSubject _logSubject;
    private boolean _configured;


    private static final class CommitStoreFuture implements StoreFuture
    {
        public boolean isComplete()
        {
            return true;
        }

        public void waitForCompletion()
        {

        }
    }

    private enum State
    {
        INITIAL,
        CONFIGURING,
        RECOVERING,
        STARTED,
        CLOSING,
        CLOSED
    }

    private State _state = State.INITIAL;


    public void configureConfigStore(String name,
                          ConfigurationRecoveryHandler recoveryHandler,
                          Configuration storeConfiguration,
                          LogSubject logSubject) throws Exception
    {
        stateTransition(State.INITIAL, State.CONFIGURING);
        _logSubject = logSubject;
        CurrentActor.get().message(_logSubject, ConfigStoreMessages.CREATED(this.getClass().getName()));

        if(!_configured)
        {
            commonConfiguration(name, storeConfiguration, logSubject);
            _configured = true;
        }

        // this recovers durable exchanges, queues, and bindings
        recover(recoveryHandler);


        stateTransition(State.RECOVERING, State.STARTED);

    }


    public void configureMessageStore(String name,
                          MessageStoreRecoveryHandler recoveryHandler,
                          Configuration storeConfiguration,
                          LogSubject logSubject) throws Exception
    {
        if(!_configured)
        {

            _logSubject = logSubject;
        }

        CurrentActor.get().message(_logSubject, MessageStoreMessages.CREATED(this.getClass().getName()));

        if(!_configured)
        {

            commonConfiguration(name, storeConfiguration, logSubject);
            _configured = true;
        }

        recoverMessages(recoveryHandler);

    }



    public void configureTransactionLog(String name,
                          TransactionLogRecoveryHandler recoveryHandler,
                          Configuration storeConfiguration,
                          LogSubject logSubject) throws Exception
    {

        if(!_configured)
        {
            _logSubject = logSubject;
        }
        CurrentActor.get().message(_logSubject, TransactionLogMessages.CREATED(this.getClass().getName()));

        if(!_configured)
        {

            _logSubject = logSubject;

            commonConfiguration(name, storeConfiguration, logSubject);
            _configured = true;
        }

        TransactionLogRecoveryHandler.DtxRecordRecoveryHandler dtxrh = recoverQueueEntries(recoveryHandler);
        recoverXids(dtxrh);

    }



    private void commonConfiguration(String name, Configuration storeConfiguration, LogSubject logSubject)
            throws ClassNotFoundException, SQLException
    {
        initialiseDriver();

        //Update to pick up QPID_WORK and use that as the default location not just derbyDB

        final String databasePath = storeConfiguration.getString(ENVIRONMENT_PATH_PROPERTY, System.getProperty("QPID_WORK")
                + File.separator + "derbyDB");

        File environmentPath = new File(databasePath);
        if (!environmentPath.exists())
        {
            if (!environmentPath.mkdirs())
            {
                throw new IllegalArgumentException("Environment path " + environmentPath + " could not be read or created. "
                    + "Ensure the path is correct and that the permissions are correct.");
            }
        }

        CurrentActor.get().message(_logSubject, MessageStoreMessages.STORE_LOCATION(environmentPath.getAbsolutePath()));

        createOrOpenDatabase(name, databasePath);
    }

    private static synchronized void initialiseDriver() throws ClassNotFoundException
    {
        if(DRIVER_CLASS == null)
        {
            DRIVER_CLASS = (Class<Driver>) Class.forName(SQL_DRIVER_NAME);
        }
    }

    private void createOrOpenDatabase(String name, final String environmentPath) throws SQLException
    {
        //FIXME this the _vhost name should not be added here, but derby wont use an empty directory as was possibly just created.
        _connectionURL = "jdbc:derby:" + environmentPath + "/" + name + ";create=true";

        Connection conn = newAutoCommitConnection();

        createVersionTable(conn);
        createExchangeTable(conn);
        createQueueTable(conn);
        createBindingsTable(conn);
        createQueueEntryTable(conn);
        createMetaDataTable(conn);
        createMessageContentTable(conn);
        createLinkTable(conn);
        createBridgeTable(conn);
        createXidTable(conn);
        createXidActionTable(conn);
        conn.close();
    }



    private void createVersionTable(final Connection conn) throws SQLException
    {
        if(!tableExists(DB_VERSION_TABLE_NAME, conn))
        {
            Statement stmt = conn.createStatement();
            try
            {
                stmt.execute(CREATE_DB_VERSION_TABLE);
            }
            finally
            {
                stmt.close();
            }

            PreparedStatement pstmt = conn.prepareStatement(INSERT_INTO_DB_VERSION);
            try
            {
                pstmt.setInt(1, DB_VERSION);
                pstmt.execute();
            }
            finally
            {
                pstmt.close();
            }
        }

    }


    private void createExchangeTable(final Connection conn) throws SQLException
    {
        if(!tableExists(EXCHANGE_TABLE_NAME, conn))
        {
            Statement stmt = conn.createStatement();
            try
            {
                stmt.execute(CREATE_EXCHANGE_TABLE);
            }
            finally
            {
                stmt.close();
            }
        }
    }

    private void createQueueTable(final Connection conn) throws SQLException
    {
        if(!tableExists(QUEUE_TABLE_NAME, conn))
        {
            Statement stmt = conn.createStatement();
            try
            {
                stmt.execute(CREATE_QUEUE_TABLE);
            }
            finally
            {
                stmt.close();
            }
        }
    }

    private void createBindingsTable(final Connection conn) throws SQLException
    {
        if(!tableExists(BINDINGS_TABLE_NAME, conn))
        {
            Statement stmt = conn.createStatement();
            try
            {
                stmt.execute(CREATE_BINDINGS_TABLE);
            }
            finally
            {
                stmt.close();
            }
        }

    }

    private void createQueueEntryTable(final Connection conn) throws SQLException
    {
        if(!tableExists(QUEUE_ENTRY_TABLE_NAME, conn))
        {
            Statement stmt = conn.createStatement();
            try
            {
                stmt.execute(CREATE_QUEUE_ENTRY_TABLE);
            }
            finally
            {
                stmt.close();
            }
        }

    }

        private void createMetaDataTable(final Connection conn) throws SQLException
    {
        if(!tableExists(META_DATA_TABLE_NAME, conn))
        {
            Statement stmt = conn.createStatement();
            try
            {
                stmt.execute(CREATE_META_DATA_TABLE);
            }
            finally
            {
                stmt.close();
            }
        }

    }


    private void createMessageContentTable(final Connection conn) throws SQLException
    {
        if(!tableExists(MESSAGE_CONTENT_TABLE_NAME, conn))
        {
            Statement stmt = conn.createStatement();
            try
            {
            stmt.execute(CREATE_MESSAGE_CONTENT_TABLE);
            }
            finally
            {
                stmt.close();
            }
        }

    }

    private void createLinkTable(final Connection conn) throws SQLException
    {
        if(!tableExists(LINKS_TABLE_NAME, conn))
        {
            Statement stmt = conn.createStatement();
            try
            {
                stmt.execute(CREATE_LINKS_TABLE);
            }
            finally
            {
                stmt.close();
            }
        }
    }


    private void createBridgeTable(final Connection conn) throws SQLException
    {
        if(!tableExists(BRIDGES_TABLE_NAME, conn))
        {
            Statement stmt = conn.createStatement();
            try
            {
                stmt.execute(CREATE_BRIDGES_TABLE);
            }
            finally
            {
                stmt.close();
            }
        }
    }

    private void createXidTable(final Connection conn) throws SQLException
    {
        if(!tableExists(XID_TABLE_NAME, conn))
        {
            Statement stmt = conn.createStatement();
            try
            {
                stmt.execute(CREATE_XIDS_TABLE);
            }
            finally
            {
                stmt.close();
            }
        }
    }


    private void createXidActionTable(final Connection conn) throws SQLException
    {
        if(!tableExists(XID_ACTIONS_TABLE_NAME, conn))
        {
            Statement stmt = conn.createStatement();
            try
            {
                stmt.execute(CREATE_XID_ACTIONS_TABLE);
            }
            finally
            {
                stmt.close();
            }
        }
    }

    private boolean tableExists(final String tableName, final Connection conn) throws SQLException
    {
        PreparedStatement stmt = conn.prepareStatement(TABLE_EXISTANCE_QUERY);
        try
        {
            stmt.setString(1, tableName);
            ResultSet rs = stmt.executeQuery();
            try
            {
                return rs.next();
            }
            finally
            {
                rs.close();
            }
        }
        finally
        {
            stmt.close();
        }

    }

    public void recover(ConfigurationRecoveryHandler recoveryHandler) throws AMQException
    {
        stateTransition(State.CONFIGURING, State.RECOVERING);

        CurrentActor.get().message(_logSubject,MessageStoreMessages.RECOVERY_START());

        try
        {
            ConfigurationRecoveryHandler.QueueRecoveryHandler qrh = recoveryHandler.begin(this);
            loadQueues(qrh);

            ConfigurationRecoveryHandler.ExchangeRecoveryHandler erh = qrh.completeQueueRecovery();
            List<String> exchanges = loadExchanges(erh);
            ConfigurationRecoveryHandler.BindingRecoveryHandler brh = erh.completeExchangeRecovery();
            recoverBindings(brh, exchanges);
            ConfigurationRecoveryHandler.BrokerLinkRecoveryHandler lrh = brh.completeBindingRecovery();
            recoverBrokerLinks(lrh);
        }
        catch (SQLException e)
        {

            throw new AMQStoreException("Error recovering persistent state: " + e.getMessage(), e);
        }


    }

    private void recoverBrokerLinks(final ConfigurationRecoveryHandler.BrokerLinkRecoveryHandler lrh)
            throws SQLException
    {
        _logger.info("Recovering broker links...");

        Connection conn = null;
        try
        {
            conn = newAutoCommitConnection();

            PreparedStatement stmt = conn.prepareStatement(SELECT_ALL_FROM_LINKS);

            try
            {
                ResultSet rs = stmt.executeQuery();

                try
                {

                    while(rs.next())
                    {
                        UUID id  = new UUID(rs.getLong(2), rs.getLong(1));
                        long createTime = rs.getLong(3);
                        Blob argumentsAsBlob = rs.getBlob(4);

                        byte[] dataAsBytes = argumentsAsBlob.getBytes(1,(int) argumentsAsBlob.length());
                        
                        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(dataAsBytes));
                        int size = dis.readInt();
                        
                        Map<String,String> arguments = new HashMap<String, String>();
                        
                        for(int i = 0; i < size; i++)
                        {
                            arguments.put(dis.readUTF(), dis.readUTF());
                        }

                        ConfigurationRecoveryHandler.BridgeRecoveryHandler brh = lrh.brokerLink(id, createTime, arguments);

                        recoverBridges(brh, id);

                    }
                }
                catch (IOException e)
                {
                    throw new SQLException(e.getMessage(), e);
                }
                finally
                {
                    rs.close();
                }
            }
            finally
            {
                stmt.close();
            }

        }
        finally
        {
            if(conn != null)
            {
                conn.close();
            }
        }

    }

    private void recoverBridges(final ConfigurationRecoveryHandler.BridgeRecoveryHandler brh, final UUID linkId)
            throws SQLException
    {
        _logger.info("Recovering bridges for link " + linkId + "...");

        Connection conn = null;
        try
        {
            conn = newAutoCommitConnection();

            PreparedStatement stmt = conn.prepareStatement(SELECT_ALL_FROM_BRIDGES);

            try
            {
                stmt.setLong(1, linkId.getLeastSignificantBits());
                stmt.setLong(2, linkId.getMostSignificantBits());

                ResultSet rs = stmt.executeQuery();

                try
                {

                    while(rs.next())
                    {
                        UUID id  = new UUID(rs.getLong(2), rs.getLong(1));
                        long createTime = rs.getLong(3);
                        Blob argumentsAsBlob = rs.getBlob(6);

                        byte[] dataAsBytes = argumentsAsBlob.getBytes(1,(int) argumentsAsBlob.length());

                        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(dataAsBytes));
                        int size = dis.readInt();

                        Map<String,String> arguments = new HashMap<String, String>();

                        for(int i = 0; i < size; i++)
                        {
                            arguments.put(dis.readUTF(), dis.readUTF());
                        }

                        brh.bridge(id, createTime, arguments);

                    }
                    brh.completeBridgeRecoveryForLink();
                }
                catch (IOException e)
                {
                    throw new SQLException(e.getMessage(), e);
                }
                finally
                {
                    rs.close();
                }
            }
            finally
            {
                stmt.close();
            }

        }
        finally
        {
            if(conn != null)
            {
                conn.close();
            }
        }

    }

    private void loadQueues(ConfigurationRecoveryHandler.QueueRecoveryHandler qrh) throws SQLException
    {
        Connection conn = newAutoCommitConnection();
        try
        {
            Statement stmt = conn.createStatement();
            try
            {
                ResultSet rs = stmt.executeQuery(SELECT_FROM_QUEUE);
                try
                {

                    while(rs.next())
                    {
                        String queueName = rs.getString(1);
                        String owner = rs.getString(2);
                        boolean exclusive = rs.getBoolean(3);
                        Blob argumentsAsBlob = rs.getBlob(4);

                        byte[] dataAsBytes = argumentsAsBlob.getBytes(1,(int) argumentsAsBlob.length());
                        FieldTable arguments;
                        if(dataAsBytes.length > 0)
                        {

                            try
                            {
                                arguments = new FieldTable(new DataInputStream(new ByteArrayInputStream(dataAsBytes)),dataAsBytes.length);
                            }
                            catch (IOException e)
                            {
                                throw new RuntimeException("IO Exception should not be thrown",e);
                            }
                        }
                        else
                        {
                            arguments = null;
                        }

                        qrh.queue(queueName, owner, exclusive, arguments);

                    }

                }
                finally
                {
                    rs.close();
                }
            }
            finally
            {
                stmt.close();
            }
        }
        finally
        {
            conn.close();
        }
    }


    private List<String> loadExchanges(ConfigurationRecoveryHandler.ExchangeRecoveryHandler erh) throws SQLException
    {

        List<String> exchanges = new ArrayList<String>();
        Connection conn = null;
        try
        {
            conn = newAutoCommitConnection();

            Statement stmt = conn.createStatement();
            try
            {
                ResultSet rs = stmt.executeQuery(SELECT_FROM_EXCHANGE);
                try
                {
                    while(rs.next())
                    {
                        String exchangeName = rs.getString(1);
                        String type = rs.getString(2);
                        boolean autoDelete = rs.getShort(3) != 0;

                        exchanges.add(exchangeName);

                        erh.exchange(exchangeName, type, autoDelete);

                    }
                    return exchanges;
                }
                finally
                {
                    rs.close();
                }
            }
            finally
            {
                stmt.close();
            }
        }
        finally
        {
            if(conn != null)
            {
                conn.close();
            }
        }

    }

    private void recoverBindings(ConfigurationRecoveryHandler.BindingRecoveryHandler brh, List<String> exchanges) throws SQLException
    {
        _logger.info("Recovering bindings...");

        Connection conn = null;
        try
        {
            conn = newAutoCommitConnection();

            PreparedStatement stmt = conn.prepareStatement(SELECT_FROM_BINDINGS);

            try
            {
                ResultSet rs = stmt.executeQuery();

                try
                {

                    while(rs.next())
                    {
                        String exchangeName = rs.getString(1);
                        String queueName = rs.getString(2);
                        String bindingKey = rs.getString(3);
                        Blob arguments = rs.getBlob(4);
                        java.nio.ByteBuffer buf;

                        if(arguments != null  && arguments.length() != 0)
                        {
                            byte[] argumentBytes = arguments.getBytes(1, (int) arguments.length());
                            buf = java.nio.ByteBuffer.wrap(argumentBytes);
                        }
                        else
                        {
                            buf = null;
                        }

                        brh.binding(exchangeName, queueName, bindingKey, buf);
                    }
                }
                finally
                {
                    rs.close();
                }
            }
            finally
            {
                stmt.close();
            }

        }
        finally
        {
            if(conn != null)
            {
                conn.close();
            }
        }
    }



    public void close() throws Exception
    {
        CurrentActor.get().message(_logSubject,MessageStoreMessages.CLOSED());
        _closed.getAndSet(true);

        try
        {
            Connection conn = DriverManager.getConnection(_connectionURL + ";shutdown=true");
            // Shouldn't reach this point - shutdown=true should throw SQLException
            conn.close();
            _logger.error("Unable to shut down the store");
        }
        catch (SQLException e)
        { 
            if (e.getSQLState().equalsIgnoreCase(DERBY_SINGLE_DB_SHUTDOWN_CODE)) 
            {     
                //expected and represents a clean shutdown of this database only, do nothing.
            }
            else
            {
                _logger.error("Exception whilst shutting down the store: " + e);
            }
        }
    }

    public StoredMessage addMessage(StorableMessageMetaData metaData)
    {
        if(metaData.isPersistent())
        {
            return new StoredDerbyMessage(_messageId.incrementAndGet(), metaData);
        }
        else
        {
            return new StoredMemoryMessage(_messageId.incrementAndGet(), metaData);
        }
    }

    public StoredMessage getMessage(long messageNumber)
    {
        return null;
    }

    public void removeMessage(long messageId)
    {
        try
        {
            Connection conn = newConnection();
            try
            {
                PreparedStatement stmt = conn.prepareStatement(DELETE_FROM_META_DATA);
                try
                {
                    stmt.setLong(1,messageId);
                    int results = stmt.executeUpdate();
                    stmt.close();

                    if (results == 0)
                    {
                        _logger.warn("Message metadata not found for message id " + messageId);
                    }

                    if (_logger.isDebugEnabled())
                    {
                        _logger.debug("Deleted metadata for message " + messageId);
                    }

                    stmt = conn.prepareStatement(DELETE_FROM_MESSAGE_CONTENT);
                    stmt.setLong(1,messageId);
                    results = stmt.executeUpdate();
                }
                finally
                {
                    stmt.close();
                }
                conn.commit();
            }
            catch(SQLException e)
            {
                try
                {
                    conn.rollback();
                }
                catch(SQLException t)
                {
                    // ignore - we are re-throwing underlying exception
                }

                throw e;

            }
            finally
            {
                conn.close();
            }
        }
        catch (SQLException e)
        {
            throw new RuntimeException("Error removing message with id " + messageId + " from database: " + e.getMessage(), e);
        }

    }

    public void createExchange(Exchange exchange) throws AMQStoreException
    {
        if (_state != State.RECOVERING)
        {
            try
            {
                Connection conn = newAutoCommitConnection();

                try
                {


                    PreparedStatement stmt = conn.prepareStatement(FIND_EXCHANGE);
                    try
                    {
                        stmt.setString(1, exchange.getNameShortString().toString());
                        ResultSet rs = stmt.executeQuery();
                        try
                        {

                            // If we don't have any data in the result set then we can add this exchange
                            if (!rs.next())
                            {

                                PreparedStatement insertStmt = conn.prepareStatement(INSERT_INTO_EXCHANGE);
                                try
                                {
                                    insertStmt.setString(1, exchange.getName().toString());
                                    insertStmt.setString(2, exchange.getTypeShortString().asString());
                                    insertStmt.setShort(3, exchange.isAutoDelete() ? (short) 1 : (short) 0);
                                    insertStmt.execute();
                                }
                                finally
                                {
                                    insertStmt.close();
                                }
                            }
                        }
                        finally
                        {
                            rs.close();
                        }
                    }
                    finally
                    {
                        stmt.close();
                    }

                }
                finally
                {
                    conn.close();
                }
            }
            catch (SQLException e)
            {
                throw new AMQStoreException("Error writing Exchange with name " + exchange.getNameShortString() + " to database: " + e.getMessage(), e);
            }
        }

    }

    public void removeExchange(Exchange exchange) throws AMQStoreException
    {

        try
        {
            Connection conn = newAutoCommitConnection();
            try
            {
                PreparedStatement stmt = conn.prepareStatement(DELETE_FROM_EXCHANGE);
                try
                {
                    stmt.setString(1, exchange.getNameShortString().toString());
                    int results = stmt.executeUpdate();
                    stmt.close();
                    if(results == 0)
                    {
                        throw new AMQStoreException("Exchange " + exchange.getNameShortString() + " not found");
                    }
                }
                finally
                {
                    stmt.close();
                }
            }
            finally
            {
                conn.close();
            }
        }
        catch (SQLException e)
        {
            throw new AMQStoreException("Error deleting Exchange with name " + exchange.getNameShortString() + " from database: " + e.getMessage(), e);
        }
    }

    public void bindQueue(Exchange exchange, AMQShortString routingKey, AMQQueue queue, FieldTable args)
            throws AMQStoreException
    {
        if (_state != State.RECOVERING)
        {
            try
            {
                Connection conn = newAutoCommitConnection();

                try
                {

                    PreparedStatement stmt = conn.prepareStatement(FIND_BINDING);
                    try
                    {
                        stmt.setString(1, exchange.getNameShortString().toString() );
                        stmt.setString(2, queue.getNameShortString().toString());
                        stmt.setString(3, routingKey == null ? null : routingKey.toString());

                        ResultSet rs = stmt.executeQuery();
                        try
                        {
                            // If this binding is not already in the store then create it.
                            if (!rs.next())
                            {
                                PreparedStatement insertStmt = conn.prepareStatement(INSERT_INTO_BINDINGS);
                                try
                                {
                                    insertStmt.setString(1, exchange.getNameShortString().toString() );
                                    insertStmt.setString(2, queue.getNameShortString().toString());
                                    insertStmt.setString(3, routingKey == null ? null : routingKey.toString());
                                    if(args != null)
                                    {
                                        // TODO - In Java 6 we could use create/set Blob
                                        byte[] bytes = args.getDataAsBytes();
                                        ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
                                        insertStmt.setBinaryStream(4, bis, bytes.length);
                                    }
                                    else
                                    {
                                        insertStmt.setNull(4, Types.BLOB);
                                    }

                                    insertStmt.executeUpdate();
                                }
                                finally
                                {
                                    insertStmt.close();
                                }
                            }
                        }
                        finally
                        {
                            rs.close();
                        }
                    }
                    finally
                    {
                        stmt.close();
                    }
                }
                finally
                {
                    conn.close();
                }
            }
            catch (SQLException e)
            {
                throw new AMQStoreException("Error writing binding for AMQQueue with name " + queue.getNameShortString() + " to exchange "
                    + exchange.getNameShortString() + " to database: " + e.getMessage(), e);
            }

        }


    }

    public void unbindQueue(Exchange exchange, AMQShortString routingKey, AMQQueue queue, FieldTable args)
            throws AMQStoreException
    {
        Connection conn = null;
        PreparedStatement stmt = null;

        try
        {
            conn = newAutoCommitConnection();
            // exchange_name varchar(255) not null, queue_name varchar(255) not null, binding_key varchar(255), arguments blob
            stmt = conn.prepareStatement(DELETE_FROM_BINDINGS);
            stmt.setString(1, exchange.getNameShortString().toString() );
            stmt.setString(2, queue.getNameShortString().toString());
            stmt.setString(3, routingKey == null ? null : routingKey.toString());

            int result = stmt.executeUpdate();

            if(result != 1)
            {
                 throw new AMQStoreException("Queue binding for queue with name " + queue.getNameShortString() + " to exchange "
                + exchange.getNameShortString() + "  not found");
            }
        }
        catch (SQLException e)
        {
            throw new AMQStoreException("Error removing binding for AMQQueue with name " + queue.getNameShortString() + " to exchange "
                + exchange.getNameShortString() + " in database: " + e.getMessage(), e);
        }
        finally
        {
            closePreparedStatement(stmt);
            closeConnection(conn);
        }
    }

    public void createQueue(AMQQueue queue) throws AMQStoreException
    {
        createQueue(queue, null);
    }

    public void createQueue(AMQQueue queue, FieldTable arguments) throws AMQStoreException
    {
        _logger.debug("public void createQueue(AMQQueue queue = " + queue + "): called");

        if (_state != State.RECOVERING)
        {
            try
            {
                Connection conn = newAutoCommitConnection();

                PreparedStatement stmt = conn.prepareStatement(FIND_QUEUE);
                try
                {
                    stmt.setString(1, queue.getNameShortString().toString());
                    ResultSet rs = stmt.executeQuery();
                    try
                    {

                        // If we don't have any data in the result set then we can add this queue
                        if (!rs.next())
                        {
                            PreparedStatement insertStmt = conn.prepareStatement(INSERT_INTO_QUEUE);

                            try
                            {
                                String owner = queue.getOwner() == null ? null : queue.getOwner().toString();

                                insertStmt.setString(1, queue.getNameShortString().toString());
                                insertStmt.setString(2, owner);
                                insertStmt.setBoolean(3,queue.isExclusive());

                                final byte[] underlying;
                                if(arguments != null)
                                {
                                    underlying = arguments.getDataAsBytes();
                                }
                                else
                                {
                                    underlying = new byte[0];
                                }

                                ByteArrayInputStream bis = new ByteArrayInputStream(underlying);
                                insertStmt.setBinaryStream(4,bis,underlying.length);

                                insertStmt.execute();
                            }
                            finally
                            {
                                insertStmt.close();
                            }
                        }
                    }
                    finally
                    {
                        rs.close();
                    }
                }
                finally
                {
                    stmt.close();
                }
                conn.close();

            }
            catch (SQLException e)
            {
                throw new AMQStoreException("Error writing AMQQueue with name " + queue.getNameShortString() + " to database: " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * Updates the specified queue in the persistent store, IF it is already present. If the queue
     * is not present in the store, it will not be added.
     *
     * NOTE: Currently only updates the exclusivity.
     *
     * @param queue The queue to update the entry for.
     * @throws AMQStoreException If the operation fails for any reason.
     */
    public void updateQueue(final AMQQueue queue) throws AMQStoreException
    {
        if (_state != State.RECOVERING)
        {
            try
            {
                Connection conn = newAutoCommitConnection();

                try
                {
                    PreparedStatement stmt = conn.prepareStatement(FIND_QUEUE);
                    try
                    {
                        stmt.setString(1, queue.getNameShortString().toString());

                        ResultSet rs = stmt.executeQuery();
                        try
                        {
                            if (rs.next())
                            {
                                PreparedStatement stmt2 = conn.prepareStatement(UPDATE_QUEUE_EXCLUSIVITY);
                                try
                                {
                                    stmt2.setBoolean(1,queue.isExclusive());
                                    stmt2.setString(2, queue.getNameShortString().toString());

                                    stmt2.execute();
                                }
                                finally
                                {
                                    stmt2.close();
                                }
                            }
                        }
                        finally
                        {
                            rs.close();
                        }
                    }
                    finally
                    {
                        stmt.close();
                    }
                }
                finally
                {
                    conn.close();
                }
            }
            catch (SQLException e)
            {
                throw new AMQStoreException("Error updating AMQQueue with name " + queue.getNameShortString() + " to database: " + e.getMessage(), e);
            }
        }
        
    }

    /**
     * Convenience method to create a new Connection configured for TRANSACTION_READ_COMMITED
     * isolation and with auto-commit transactions enabled.
     */
    private Connection newAutoCommitConnection() throws SQLException
    {
        final Connection connection = newConnection();
        try
        {
            connection.setAutoCommit(true);
        }
        catch (SQLException sqlEx)
        {

            try
            {
                connection.close();
            }
            finally
            {
                throw sqlEx;
            }
        }
        
        return connection;
    }

    /**
     * Convenience method to create a new Connection configured for TRANSACTION_READ_COMMITED
     * isolation and with auto-commit transactions disabled.
     */
    private Connection newConnection() throws SQLException
    {
        final Connection connection = DriverManager.getConnection(_connectionURL);
        try
        {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        }
        catch (SQLException sqlEx)
        {
            try
            {
                connection.close();
            }
            finally
            {
                throw sqlEx;
            }
        }
        return connection;
    }

    public void removeQueue(final AMQQueue queue) throws AMQStoreException
    {
        AMQShortString name = queue.getNameShortString();
        _logger.debug("public void removeQueue(AMQShortString name = " + name + "): called");
        Connection conn = null;
        PreparedStatement stmt = null;
        try
        {
            conn = newAutoCommitConnection();
            stmt = conn.prepareStatement(DELETE_FROM_QUEUE);
            stmt.setString(1, name.toString());
            int results = stmt.executeUpdate();

            if (results == 0)
            {
                throw new AMQStoreException("Queue " + name + " not found");
            }
        }
        catch (SQLException e)
        {
            throw new AMQStoreException("Error deleting AMQQueue with name " + name + " from database: " + e.getMessage(), e);
        }
        finally
        {
            closePreparedStatement(stmt);
            closeConnection(conn);
        }


    }

    public void createBrokerLink(final BrokerLink link) throws AMQStoreException
    {
        _logger.debug("public void createBrokerLink(BrokerLink = " + link + "): called");

        if (_state != State.RECOVERING)
        {
            try
            {
                Connection conn = newAutoCommitConnection();

                PreparedStatement stmt = conn.prepareStatement(FIND_LINK);
                try
                {
                    
                    stmt.setLong(1, link.getId().getLeastSignificantBits());
                    stmt.setLong(2, link.getId().getMostSignificantBits());
                    ResultSet rs = stmt.executeQuery();
                    try
                    {

                        // If we don't have any data in the result set then we can add this queue
                        if (!rs.next())
                        {
                            PreparedStatement insertStmt = conn.prepareStatement(INSERT_INTO_LINKS);

                            try
                            {
                                
                                insertStmt.setLong(1, link.getId().getLeastSignificantBits());
                                insertStmt.setLong(2, link.getId().getMostSignificantBits());
                                insertStmt.setLong(3, link.getCreateTime());

                                byte[] argumentBytes = convertStringMapToBytes(link.getArguments());
                                ByteArrayInputStream bis = new ByteArrayInputStream(argumentBytes);

                                insertStmt.setBinaryStream(4,bis,argumentBytes.length);

                                insertStmt.execute();
                            }
                            finally
                            {
                                insertStmt.close();
                            }
                        }
                    }
                    finally
                    {
                        rs.close();
                    }
                }
                finally
                {
                    stmt.close();
                }
                conn.close();

            }
            catch (SQLException e)
            {
                throw new AMQStoreException("Error writing " + link + " to database: " + e.getMessage(), e);
            }
        }
    }

    private byte[] convertStringMapToBytes(final Map<String, String> arguments) throws AMQStoreException
    {
        byte[] argumentBytes;
        if(arguments == null)
        {
            argumentBytes = new byte[0];
        }
        else
        {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos);


            try
            {
                dos.writeInt(arguments.size());
                for(Map.Entry<String,String> arg : arguments.entrySet())
                {
                    dos.writeUTF(arg.getKey());
                    dos.writeUTF(arg.getValue());
                }
            }
            catch (IOException e)
            {
                // This should never happen
                throw new AMQStoreException(e.getMessage(), e);
            }
            argumentBytes = bos.toByteArray();
        }
        return argumentBytes;
    }

    public void deleteBrokerLink(final BrokerLink link) throws AMQStoreException
    {
        _logger.debug("public void deleteBrokerLink( " + link + "): called");
        Connection conn = null;
        PreparedStatement stmt = null;
        try
        {
            conn = newAutoCommitConnection();
            stmt = conn.prepareStatement(DELETE_FROM_LINKS);
            stmt.setLong(1, link.getId().getLeastSignificantBits());
            stmt.setLong(2, link.getId().getMostSignificantBits());
            int results = stmt.executeUpdate();

            if (results == 0)
            {
                throw new AMQStoreException("Link " + link + " not found");
            }
        }
        catch (SQLException e)
        {
            throw new AMQStoreException("Error deleting Link " + link + " from database: " + e.getMessage(), e);
        }
        finally
        {
            closePreparedStatement(stmt);
            closeConnection(conn);
        }


    }

    public void createBridge(final Bridge bridge) throws AMQStoreException
    {
        _logger.debug("public void createBridge(BrokerLink = " + bridge + "): called");

        if (_state != State.RECOVERING)
        {
            try
            {
                Connection conn = newAutoCommitConnection();

                PreparedStatement stmt = conn.prepareStatement(FIND_BRIDGE);
                try
                {

                    UUID id = bridge.getId();
                    stmt.setLong(1, id.getLeastSignificantBits());
                    stmt.setLong(2, id.getMostSignificantBits());
                    ResultSet rs = stmt.executeQuery();
                    try
                    {

                        // If we don't have any data in the result set then we can add this queue
                        if (!rs.next())
                        {
                            PreparedStatement insertStmt = conn.prepareStatement(INSERT_INTO_BRIDGES);

                            try
                            {

                                insertStmt.setLong(1, id.getLeastSignificantBits());
                                insertStmt.setLong(2, id.getMostSignificantBits());

                                insertStmt.setLong(3, bridge.getCreateTime());

                                UUID linkId = bridge.getLink().getId();
                                insertStmt.setLong(4, linkId.getLeastSignificantBits());
                                insertStmt.setLong(5, linkId.getMostSignificantBits());

                                byte[] argumentBytes = convertStringMapToBytes(bridge.getArguments());
                                ByteArrayInputStream bis = new ByteArrayInputStream(argumentBytes);

                                insertStmt.setBinaryStream(6,bis,argumentBytes.length);

                                insertStmt.execute();
                            }
                            finally
                            {
                                insertStmt.close();
                            }
                        }
                    }
                    finally
                    {
                        rs.close();
                    }
                }
                finally
                {
                    stmt.close();
                }
                conn.close();

            }
            catch (SQLException e)
            {
                throw new AMQStoreException("Error writing " + bridge + " to database: " + e.getMessage(), e);
            }
        }
    }

    public void deleteBridge(final Bridge bridge) throws AMQStoreException
    {
        _logger.debug("public void deleteBridge( " + bridge + "): called");
        Connection conn = null;
        PreparedStatement stmt = null;
        try
        {
            conn = newAutoCommitConnection();
            stmt = conn.prepareStatement(DELETE_FROM_BRIDGES);
            stmt.setLong(1, bridge.getId().getLeastSignificantBits());
            stmt.setLong(2, bridge.getId().getMostSignificantBits());
            int results = stmt.executeUpdate();

            if (results == 0)
            {
                throw new AMQStoreException("Bridge " + bridge + " not found");
            }
        }
        catch (SQLException e)
        {
            throw new AMQStoreException("Error deleting bridge " + bridge + " from database: " + e.getMessage(), e);
        }
        finally
        {
            closePreparedStatement(stmt);
            closeConnection(conn);
        }

    }

    public Transaction newTransaction()
    {
        return new DerbyTransaction();
    }

    public void enqueueMessage(ConnectionWrapper connWrapper, final TransactionLogResource queue, Long messageId) throws AMQStoreException
    {
        String name = queue.getResourceName();

        Connection conn = connWrapper.getConnection();


        try
        {
            if (_logger.isDebugEnabled())
            {
                _logger.debug("Enqueuing message " + messageId + " on queue " + name + "[Connection" + conn + "]");
            }
            
            PreparedStatement stmt = conn.prepareStatement(INSERT_INTO_QUEUE_ENTRY);
            try
            {
                stmt.setString(1,name);
                stmt.setLong(2,messageId);
                stmt.executeUpdate();
            }
            finally
            {
                stmt.close();
            }
        }
        catch (SQLException e)
        {
            _logger.error("Failed to enqueue: " + e.getMessage(), e);
            throw new AMQStoreException("Error writing enqueued message with id " + messageId + " for queue " + name
                + " to database", e);
        }

    }

    public void dequeueMessage(ConnectionWrapper connWrapper, final TransactionLogResource  queue, Long messageId) throws AMQStoreException
    {
        String name = queue.getResourceName();


        Connection conn = connWrapper.getConnection();


        try
        {
            PreparedStatement stmt = conn.prepareStatement(DELETE_FROM_QUEUE_ENTRY);
            try
            {
                stmt.setString(1,name);
                stmt.setLong(2,messageId);
                int results = stmt.executeUpdate();



                if(results != 1)
                {
                    throw new AMQStoreException("Unable to find message with id " + messageId + " on queue " + name);
                }

                if (_logger.isDebugEnabled())
                {
                    _logger.debug("Dequeuing message " + messageId + " on queue " + name );
                }
            }
            finally
            {
                stmt.close();
            }
        }
        catch (SQLException e)
        {
            _logger.error("Failed to dequeue: " + e.getMessage(), e);
            throw new AMQStoreException("Error deleting enqueued message with id " + messageId + " for queue " + name
                + " from database", e);
        }

    }


    private void removeXid(ConnectionWrapper connWrapper, long format, byte[] globalId, byte[] branchId)
            throws AMQStoreException
    {
        Connection conn = connWrapper.getConnection();


        try
        {
            PreparedStatement stmt = conn.prepareStatement(DELETE_FROM_XIDS);
            try
            {
                stmt.setLong(1,format);
                stmt.setBytes(2,globalId);
                stmt.setBytes(3,branchId);
                int results = stmt.executeUpdate();



                if(results != 1)
                {
                    throw new AMQStoreException("Unable to find message with xid");
                }
            }
            finally
            {
                stmt.close();
            }

            stmt = conn.prepareStatement(DELETE_FROM_XID_ACTIONS);
            try
            {
                stmt.setLong(1,format);
                stmt.setBytes(2,globalId);
                stmt.setBytes(3,branchId);
                int results = stmt.executeUpdate();

            }
            finally
            {
                stmt.close();
            }

        }
        catch (SQLException e)
        {
            _logger.error("Failed to dequeue: " + e.getMessage(), e);
            throw new AMQStoreException("Error deleting enqueued message with xid", e);
        }

    }


    private void recordXid(ConnectionWrapper connWrapper, long format, byte[] globalId, byte[] branchId,
                           Transaction.Record[] enqueues, Transaction.Record[] dequeues) throws AMQStoreException
    {
        Connection conn = connWrapper.getConnection();


        try
        {

            PreparedStatement stmt = conn.prepareStatement(INSERT_INTO_XIDS);
            try
            {
                stmt.setLong(1,format);
                stmt.setBytes(2, globalId);
                stmt.setBytes(3, branchId);
                stmt.executeUpdate();
            }
            finally
            {
                stmt.close();
            }
            
            stmt = conn.prepareStatement(INSERT_INTO_XID_ACTIONS);

            try
            {
                stmt.setLong(1,format);
                stmt.setBytes(2, globalId);
                stmt.setBytes(3, branchId);

                if(enqueues != null)
                {
                    stmt.setString(4, "E");
                    for(Transaction.Record record : enqueues)
                    {
                        stmt.setString(5, record.getQueue().getResourceName());
                        stmt.setLong(6, record.getMessage().getMessageNumber());
                        stmt.executeUpdate();
                    }
                }

                if(dequeues != null)
                {
                    stmt.setString(4, "D");
                    for(Transaction.Record record : dequeues)
                    {
                        stmt.setString(5, record.getQueue().getResourceName());
                        stmt.setLong(6, record.getMessage().getMessageNumber());
                        stmt.executeUpdate();
                    }
                }

            }
            finally
            {
                stmt.close();
            }

        }
        catch (SQLException e)
        {
            _logger.error("Failed to enqueue: " + e.getMessage(), e);
            throw new AMQStoreException("Error writing xid ", e);
        }

    }
    
    private static final class ConnectionWrapper
    {
        private final Connection _connection;

        public ConnectionWrapper(Connection conn)
        {
            _connection = conn;
        }

        public Connection getConnection()
        {
            return _connection;
        }
    }


    public void commitTran(ConnectionWrapper connWrapper) throws AMQStoreException
    {

        try
        {
            Connection conn = connWrapper.getConnection();
            conn.commit();

            if (_logger.isDebugEnabled())
            {
                _logger.debug("commit tran completed");
            }

            conn.close();
        }
        catch (SQLException e)
        {
            throw new AMQStoreException("Error commit tx: " + e.getMessage(), e);
        }
        finally
        {

        }
    }

    public StoreFuture commitTranAsync(ConnectionWrapper connWrapper) throws AMQStoreException
    {
        commitTran(connWrapper);
        return new CommitStoreFuture();
    }

    public void abortTran(ConnectionWrapper connWrapper) throws AMQStoreException
    {
        if (connWrapper == null)
        {
            throw new AMQStoreException("Fatal internal error: transactional context is empty at abortTran");
        }

        if (_logger.isDebugEnabled())
        {
            _logger.debug("abort tran called: " + connWrapper.getConnection());
        }

        try
        {
            Connection conn = connWrapper.getConnection();
            conn.rollback();
            conn.close();
        }
        catch (SQLException e)
        {
            throw new AMQStoreException("Error aborting transaction: " + e.getMessage(), e);
        }

    }

    public Long getNewMessageId()
    {
        return _messageId.incrementAndGet();
    }


    private void storeMetaData(Connection conn, long messageId, StorableMessageMetaData metaData)
        throws SQLException
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("Adding metadata for message " +messageId);
        }
        
        PreparedStatement stmt = conn.prepareStatement(INSERT_INTO_META_DATA);
        try
        {
            stmt.setLong(1,messageId);

            final int bodySize = 1 + metaData.getStorableSize();
            byte[] underlying = new byte[bodySize];
            underlying[0] = (byte) metaData.getType().ordinal();
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(underlying);
            buf.position(1);
            buf = buf.slice();

            metaData.writeToBuffer(0, buf);
            ByteArrayInputStream bis = new ByteArrayInputStream(underlying);
            try
            {
                stmt.setBinaryStream(2,bis,underlying.length);
                int result = stmt.executeUpdate();

                if(result == 0)
                {
                    throw new RuntimeException("Unable to add meta data for message " +messageId);
                }
            }
            finally
            {
                try
                {
                    bis.close();
                }
                catch (IOException e)
                {

                    throw new SQLException(e);
                }
            }

        }
        finally
        {
            stmt.close();
        }
        
    }




    private void recoverMessages(MessageStoreRecoveryHandler recoveryHandler) throws SQLException
    {
        Connection conn = newAutoCommitConnection();
        try
        {
            MessageStoreRecoveryHandler.StoredMessageRecoveryHandler messageHandler = recoveryHandler.begin();

            Statement stmt = conn.createStatement();
            try
            {
                ResultSet rs = stmt.executeQuery(SELECT_ALL_FROM_META_DATA);
                try
                {

                    long maxId = 0;

                    while(rs.next())
                    {

                        long messageId = rs.getLong(1);
                        Blob dataAsBlob = rs.getBlob(2);

                        if(messageId > maxId)
                        {
                            maxId = messageId;
                        }

                        byte[] dataAsBytes = dataAsBlob.getBytes(1,(int) dataAsBlob.length());
                        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(dataAsBytes);
                        buf.position(1);
                        buf = buf.slice();
                        MessageMetaDataType type = MessageMetaDataType.values()[dataAsBytes[0]];
                        StorableMessageMetaData metaData = type.getFactory().createMetaData(buf);
                        StoredDerbyMessage message = new StoredDerbyMessage(messageId, metaData, false);
                        messageHandler.message(message);
                    }

                    _messageId.set(maxId);

                    messageHandler.completeMessageRecovery();
                }
                finally
                {
                    rs.close();
                }
            }
            finally
            {
                stmt.close();
            }
        }
        finally
        {
            conn.close();
        }
    }



    private TransactionLogRecoveryHandler.DtxRecordRecoveryHandler recoverQueueEntries(TransactionLogRecoveryHandler recoveryHandler) throws SQLException
    {
        Connection conn = newAutoCommitConnection();
        try
        {
            TransactionLogRecoveryHandler.QueueEntryRecoveryHandler queueEntryHandler = recoveryHandler.begin(this);

            Statement stmt = conn.createStatement();
            try
            {
                ResultSet rs = stmt.executeQuery(SELECT_FROM_QUEUE_ENTRY);
                try
                {
                    while(rs.next())
                    {

                        String queueName = rs.getString(1);
                        long messageId = rs.getLong(2);
                        queueEntryHandler.queueEntry(queueName,messageId);
                    }
                }
                finally
                {
                    rs.close();
                }
            }
            finally
            {
                stmt.close();
            }

            return queueEntryHandler.completeQueueEntryRecovery();
        }
        finally
        {
            conn.close();
        }
    }

    private static final class Xid
    {

        private final long _format;
        private final byte[] _globalId;
        private final byte[] _branchId;

        public Xid(long format, byte[] globalId, byte[] branchId)
        {
            _format = format;
            _globalId = globalId;
            _branchId = branchId;
        }

        public long getFormat()
        {
            return _format;
        }

        public byte[] getGlobalId()
        {
            return _globalId;
        }

        public byte[] getBranchId()
        {
            return _branchId;
        }
    }

    private static class RecordImpl implements MessageStore.Transaction.Record, TransactionLogResource, EnqueableMessage
    {

        private final String _queueName;
        private long _messageNumber;

        public RecordImpl(String queueName, long messageNumber)
        {
            _queueName = queueName;
            _messageNumber = messageNumber;
        }

        public TransactionLogResource getQueue()
        {
            return this;
        }

        public EnqueableMessage getMessage()
        {
            return this;
        }

        public long getMessageNumber()
        {
            return _messageNumber;
        }

        public boolean isPersistent()
        {
            return true;
        }

        public StoredMessage getStoredMessage()
        {
            throw new UnsupportedOperationException();
        }

        public String getResourceName()
        {
            return _queueName;
        }
    }

    private void recoverXids(TransactionLogRecoveryHandler.DtxRecordRecoveryHandler dtxrh) throws SQLException
    {
        Connection conn = newAutoCommitConnection();
        try
        {
            List<Xid> xids = new ArrayList<Xid>();
            
            Statement stmt = conn.createStatement();
            try
            {
                ResultSet rs = stmt.executeQuery(SELECT_ALL_FROM_XIDS);
                try
                {
                    while(rs.next())
                    {

                        long format = rs.getLong(1);
                        byte[] globalId = rs.getBytes(2);
                        byte[] branchId = rs.getBytes(3);
                        xids.add(new Xid(format, globalId, branchId));
                    }
                }
                finally
                {
                    rs.close();
                }
            }
            finally
            {
                stmt.close();
            }

            
            
            for(Xid xid : xids)
            {
                List<RecordImpl> enqueues = new ArrayList<RecordImpl>();
                List<RecordImpl> dequeues = new ArrayList<RecordImpl>();
                
                PreparedStatement pstmt = conn.prepareStatement(SELECT_ALL_FROM_XID_ACTIONS);
            
                try
                {
                    pstmt.setLong(1, xid.getFormat());
                    pstmt.setBytes(2, xid.getGlobalId());
                    pstmt.setBytes(3, xid.getBranchId());

                    ResultSet rs = pstmt.executeQuery();
                    try
                    {
                        while(rs.next())
                        {

                            String actionType = rs.getString(1);
                            String queueName = rs.getString(2);
                            long messageId = rs.getLong(3);

                            RecordImpl record = new RecordImpl(queueName, messageId);
                            List<RecordImpl> records = "E".equals(actionType) ? enqueues : dequeues;
                            records.add(record);
                        }
                    }
                    finally
                    {
                        rs.close();
                    }
                }
                finally
                {
                    pstmt.close();
                }
                
                dtxrh.dtxRecord(xid.getFormat(), xid.getGlobalId(), xid.getBranchId(), 
                                enqueues.toArray(new RecordImpl[enqueues.size()]), 
                                dequeues.toArray(new RecordImpl[dequeues.size()]));
            }
            
            
            dtxrh.completeDtxRecordRecovery();
        }
        finally
        {
            conn.close();
        }

    }
    
    StorableMessageMetaData getMetaData(long messageId) throws SQLException
    {

        Connection conn = newAutoCommitConnection();
        try
        {
            PreparedStatement stmt = conn.prepareStatement(SELECT_FROM_META_DATA);
            try
            {
                stmt.setLong(1,messageId);
                ResultSet rs = stmt.executeQuery();
                try
                {

                    if(rs.next())
                    {
                        Blob dataAsBlob = rs.getBlob(1);

                        byte[] dataAsBytes = dataAsBlob.getBytes(1,(int) dataAsBlob.length());
                        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(dataAsBytes);
                        buf.position(1);
                        buf = buf.slice();
                        MessageMetaDataType type = MessageMetaDataType.values()[dataAsBytes[0]];
                        StorableMessageMetaData metaData = type.getFactory().createMetaData(buf);

                        return metaData;
                    }
                    else
                    {
                        throw new RuntimeException("Meta data not found for message with id " + messageId);
                    }
                }
                finally
                {
                    rs.close();
                }
            }
            finally
            {
                stmt.close();
            }
        }
        finally
        {
            conn.close();
        }
    }


    private void addContent(Connection conn, long messageId, int offset, ByteBuffer src)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("Adding content chunk offset " + offset + " for message " +messageId);
        }
        PreparedStatement stmt = null;

        try
        {
            src = src.slice();

            byte[] chunkData = new byte[src.limit()];
            src.duplicate().get(chunkData);

            stmt = conn.prepareStatement(INSERT_INTO_MESSAGE_CONTENT);
            stmt.setLong(1,messageId);
            stmt.setInt(2, offset);
            stmt.setInt(3, offset+chunkData.length);


            // TODO in Java 6 we could just use blobs

            ByteArrayInputStream bis = new ByteArrayInputStream(chunkData);
            stmt.setBinaryStream(4, bis, chunkData.length);
            stmt.executeUpdate();
        }
        catch (SQLException e)
        {
            closeConnection(conn);
            throw new RuntimeException("Error adding content chunk offset " + offset + " for message " + messageId + ": " + e.getMessage(), e);
        }
        finally
        {
            closePreparedStatement(stmt);
        }

    }


    public int getContent(long messageId, int offset, ByteBuffer dst)
    {
        Connection conn = null;
        PreparedStatement stmt = null;

        try
        {
            conn = newAutoCommitConnection();

            stmt = conn.prepareStatement(SELECT_FROM_MESSAGE_CONTENT);
            stmt.setLong(1,messageId);
            stmt.setInt(2, offset);
            stmt.setInt(3, offset+dst.remaining());
            ResultSet rs = stmt.executeQuery();

            int written = 0;

            while(rs.next())
            {
                int offsetInMessage = rs.getInt(1);
                Blob dataAsBlob = rs.getBlob(2);

                final int size = (int) dataAsBlob.length();
                byte[] dataAsBytes = dataAsBlob.getBytes(1, size);

                int posInArray = offset + written - offsetInMessage;
                int count = size - posInArray;
                if(count > dst.remaining())
                {
                    count = dst.remaining();
                }
                dst.put(dataAsBytes,posInArray,count);
                written+=count;

                if(dst.remaining() == 0)
                {
                    break;
                }
            }

            return written;

        }
        catch (SQLException e)
        {
            throw new RuntimeException("Error retrieving content from offset " + offset + " for message " + messageId + ": " + e.getMessage(), e);
        }
        finally
        {
            closePreparedStatement(stmt);
            closeConnection(conn);
        }


    }

    public boolean isPersistent()
    {
        return true;
    }


    private synchronized void stateTransition(State requiredState, State newState) throws AMQStoreException
    {
        if (_state != requiredState)
        {
            throw new AMQStoreException("Cannot transition to the state: " + newState + "; need to be in state: " + requiredState
                + "; currently in state: " + _state);
        }

        _state = newState;
    }


    private class DerbyTransaction implements Transaction
    {
        private final ConnectionWrapper _connWrapper;


        private DerbyTransaction()
        {
            try
            {
                _connWrapper = new ConnectionWrapper(newConnection());
            }
            catch (SQLException e)
            {
                throw new RuntimeException(e);
            }
        }

        public void enqueueMessage(TransactionLogResource queue, EnqueableMessage message) throws AMQStoreException
        {
            if(message.getStoredMessage() instanceof StoredDerbyMessage)
            {
                try
                {
                    ((StoredDerbyMessage)message.getStoredMessage()).store(_connWrapper.getConnection());
                }
                catch (SQLException e)
                {
                    throw new AMQStoreException("Exception on enqueuing message " + _messageId, e);
                }
            }

            DerbyMessageStore.this.enqueueMessage(_connWrapper, queue, message.getMessageNumber());
        }

        public void dequeueMessage(TransactionLogResource queue, EnqueableMessage message) throws AMQStoreException
        {
            DerbyMessageStore.this.dequeueMessage(_connWrapper, queue, message.getMessageNumber());

        }

        public void commitTran() throws AMQStoreException
        {
            DerbyMessageStore.this.commitTran(_connWrapper);
        }

        public StoreFuture commitTranAsync() throws AMQStoreException
        {
            return DerbyMessageStore.this.commitTranAsync(_connWrapper);
        }

        public void abortTran() throws AMQStoreException
        {
            DerbyMessageStore.this.abortTran(_connWrapper);
        }

        public void removeXid(long format, byte[] globalId, byte[] branchId) throws AMQStoreException
        {
            DerbyMessageStore.this.removeXid(_connWrapper, format, globalId, branchId);
        }

        public void recordXid(long format, byte[] globalId, byte[] branchId, Record[] enqueues, Record[] dequeues)
                throws AMQStoreException
        {
            DerbyMessageStore.this.recordXid(_connWrapper, format, globalId, branchId, enqueues, dequeues);
        }
    }



    private class StoredDerbyMessage implements StoredMessage
    {

        private final long _messageId;
        private StorableMessageMetaData _metaData;
        private volatile SoftReference<StorableMessageMetaData> _metaDataRef;
        private byte[] _data;
        private volatile SoftReference<byte[]> _dataRef;
        

        StoredDerbyMessage(long messageId, StorableMessageMetaData metaData)
        {
            this(messageId, metaData, true);
        }


        StoredDerbyMessage(long messageId,
                           StorableMessageMetaData metaData, boolean persist)
        {
            _messageId = messageId;
            

            _metaDataRef = new SoftReference<StorableMessageMetaData>(metaData);
            if(persist)
            {
                _metaData = metaData;    
            }
        }

        public StorableMessageMetaData getMetaData()
        {
            StorableMessageMetaData metaData = _metaData == null ? _metaDataRef.get() : _metaData;
            if(metaData == null)
            {
                try
                {
                    metaData = DerbyMessageStore.this.getMetaData(_messageId);
                }
                catch (SQLException e)
                {
                    throw new RuntimeException(e);
                }
                _metaDataRef = new SoftReference<StorableMessageMetaData>(metaData);
            }

            return metaData;
        }

        public long getMessageNumber()
        {
            return _messageId;
        }

        public void addContent(int offsetInMessage, java.nio.ByteBuffer src)
        {
            src = src.slice();

            if(_data == null)
            {
                _data = new byte[src.remaining()];
                _dataRef = new SoftReference<byte[]>(_data);
                src.duplicate().get(_data);
            }
            else
            {
                byte[] oldData = _data;
                _data = new byte[oldData.length + src.remaining()];
                _dataRef = new SoftReference<byte[]>(_data);

                System.arraycopy(oldData,0,_data,0,oldData.length);
                src.duplicate().get(_data, oldData.length, src.remaining());
            }
            
        }

        public int getContent(int offsetInMessage, java.nio.ByteBuffer dst)
        {
            byte[] data = _dataRef == null ? null : _dataRef.get();
            if(data != null)
            {
                int length = Math.min(dst.remaining(), data.length - offsetInMessage);
                dst.put(data, offsetInMessage, length);
                return length;
            }
            else
            {
                return DerbyMessageStore.this.getContent(_messageId, offsetInMessage, dst);
            }
        }


        public ByteBuffer getContent(int offsetInMessage, int size)
        {
            ByteBuffer buf = ByteBuffer.allocate(size);
            getContent(offsetInMessage, buf);
            buf.position(0);
            return  buf;
        }

        public synchronized StoreFuture flushToStore()
        {
            try
            {
                if(_metaData != null)
                {
                    Connection conn = newConnection();

                    store(conn);
                    
                    conn.commit();
                    conn.close();
                }
            }
            catch (SQLException e)
            {
                if(_logger.isDebugEnabled())
                {
                    _logger.debug("Error when trying to flush message " + _messageId + " to store: " + e);
                }
                throw new RuntimeException(e);
            }
            return IMMEDIATE_FUTURE;
        }

        private synchronized void store(final Connection conn) throws SQLException
        {
            if(_metaData != null)
            {
                try
                {
                    storeMetaData(conn, _messageId, _metaData);
                    DerbyMessageStore.this.addContent(conn, _messageId, 0,
                                                      _data == null ? ByteBuffer.allocate(0) : ByteBuffer.wrap(_data));
                }
                finally
                {
                    _metaData = null;
                    _data = null;
                }
            }

            if(_logger.isDebugEnabled())
            {
                _logger.debug("Storing message " + _messageId + " to store");
            }
        }

        public void remove()
        {
            DerbyMessageStore.this.removeMessage(_messageId);
        }
    }

    private void closeConnection(final Connection conn)
    {
        if(conn != null)
        {
           try
           {
               conn.close();
           }
           catch (SQLException e)
           {
               _logger.error("Problem closing connection", e);
           }
        }
    }

    private void closePreparedStatement(final PreparedStatement stmt)
    {
        if (stmt != null)
        {
            try
            {
                stmt.close();
            }
            catch(SQLException e)
            {
                _logger.error("Problem closing prepared statement", e);
            }
        }
    }

}