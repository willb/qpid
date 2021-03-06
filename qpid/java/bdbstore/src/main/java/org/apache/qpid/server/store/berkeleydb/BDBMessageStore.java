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

import java.io.File;
import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.configuration.Configuration;
import org.apache.log4j.Logger;
import org.apache.qpid.AMQStoreException;
import org.apache.qpid.framing.AMQShortString;
import org.apache.qpid.framing.FieldTable;
import org.apache.qpid.server.exchange.Exchange;
import org.apache.qpid.server.federation.Bridge;
import org.apache.qpid.server.federation.BrokerLink;
import org.apache.qpid.server.logging.LogMessage;
import org.apache.qpid.server.logging.LogSubject;
import org.apache.qpid.server.logging.actors.CurrentActor;
import org.apache.qpid.server.logging.messages.ConfigStoreMessages;
import org.apache.qpid.server.logging.messages.MessageStoreMessages;
import org.apache.qpid.server.logging.messages.TransactionLogMessages;
import org.apache.qpid.server.message.EnqueableMessage;
import org.apache.qpid.server.queue.AMQQueue;
import org.apache.qpid.server.store.ConfigurationRecoveryHandler;
import org.apache.qpid.server.store.ConfigurationRecoveryHandler.BindingRecoveryHandler;
import org.apache.qpid.server.store.ConfigurationRecoveryHandler.ExchangeRecoveryHandler;
import org.apache.qpid.server.store.ConfigurationRecoveryHandler.QueueRecoveryHandler;
import org.apache.qpid.server.store.DurableConfigurationStore;
import org.apache.qpid.server.store.MessageStore;
import org.apache.qpid.server.store.MessageStoreRecoveryHandler;
import org.apache.qpid.server.store.MessageStoreRecoveryHandler.StoredMessageRecoveryHandler;
import org.apache.qpid.server.store.StorableMessageMetaData;
import org.apache.qpid.server.store.StoredMemoryMessage;
import org.apache.qpid.server.store.StoredMessage;
import org.apache.qpid.server.store.TransactionLogRecoveryHandler;
import org.apache.qpid.server.store.TransactionLogRecoveryHandler.QueueEntryRecoveryHandler;
import org.apache.qpid.server.store.TransactionLogResource;
import org.apache.qpid.server.store.berkeleydb.entry.BindingRecord;
import org.apache.qpid.server.store.berkeleydb.entry.ExchangeRecord;
import org.apache.qpid.server.store.berkeleydb.entry.PreparedTransaction;
import org.apache.qpid.server.store.berkeleydb.entry.QueueEntryKey;
import org.apache.qpid.server.store.berkeleydb.entry.QueueRecord;
import org.apache.qpid.server.store.berkeleydb.entry.Xid;
import org.apache.qpid.server.store.berkeleydb.tuple.AMQShortStringBinding;
import org.apache.qpid.server.store.berkeleydb.tuple.ContentBinding;
import org.apache.qpid.server.store.berkeleydb.tuple.ExchangeBinding;
import org.apache.qpid.server.store.berkeleydb.tuple.MessageMetaDataBinding;
import org.apache.qpid.server.store.berkeleydb.tuple.PreparedTransactionBinding;
import org.apache.qpid.server.store.berkeleydb.tuple.QueueBinding;
import org.apache.qpid.server.store.berkeleydb.tuple.QueueBindingTupleBinding;
import org.apache.qpid.server.store.berkeleydb.tuple.QueueEntryBinding;
import org.apache.qpid.server.store.berkeleydb.tuple.StringMapBinding;
import org.apache.qpid.server.store.berkeleydb.tuple.UUIDTupleBinding;
import org.apache.qpid.server.store.berkeleydb.tuple.XidBinding;
import org.apache.qpid.server.store.berkeleydb.upgrade.Upgrader;

import com.sleepycat.bind.tuple.ByteBinding;
import com.sleepycat.bind.tuple.LongBinding;
import com.sleepycat.je.CheckpointConfig;
import com.sleepycat.je.Cursor;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.LockConflictException;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.TransactionConfig;

/**
 * BDBMessageStore implements a persistent {@link MessageStore} using the BDB high performance log.
 *
 * <p/><table id="crc"><caption>CRC Card</caption> <tr><th> Responsibilities <th> Collaborations <tr><td> Accept
 * transaction boundary demarcations: Begin, Commit, Abort. <tr><td> Store and remove queues. <tr><td> Store and remove
 * exchanges. <tr><td> Store and remove messages. <tr><td> Bind and unbind queues to exchanges. <tr><td> Enqueue and
 * dequeue messages to queues. <tr><td> Generate message identifiers. </table>
 */
@SuppressWarnings({"unchecked"})
public class BDBMessageStore implements MessageStore, DurableConfigurationStore
{
    private static final Logger _log = Logger.getLogger(BDBMessageStore.class);

    private static final int LOCK_RETRY_ATTEMPTS = 5;

    public static final int VERSION = 6;

    public static final String ENVIRONMENT_PATH_PROPERTY = "environment-path";

    private Environment _environment;

    private String MESSAGEMETADATADB_NAME = "MESSAGE_METADATA";
    private String MESSAGECONTENTDB_NAME = "MESSAGE_CONTENT";
    private String QUEUEBINDINGSDB_NAME = "QUEUE_BINDINGS";
    private String DELIVERYDB_NAME = "DELIVERIES";
    private String EXCHANGEDB_NAME = "EXCHANGES";
    private String QUEUEDB_NAME = "QUEUES";
    private String BRIDGEDB_NAME = "BRIDGES";
    private String LINKDB_NAME = "LINKS";
    private String XIDDB_NAME = "XIDS";


    private Database _messageMetaDataDb;
    private Database _messageContentDb;
    private Database _queueBindingsDb;
    private Database _deliveryDb;
    private Database _exchangeDb;
    private Database _queueDb;
    private Database _bridgeDb;
    private Database _linkDb;
    private Database _xidDb;

    /* =======
     * Schema:
     * =======
     *
     * Queue:
     * name(AMQShortString) - name(AMQShortString), owner(AMQShortString),
     *                        arguments(FieldTable encoded as binary), exclusive (boolean)
     *
     * Exchange:
     * name(AMQShortString) - name(AMQShortString), typeName(AMQShortString), autodelete (boolean)
     *
     * Binding:
     * exchangeName(AMQShortString), queueName(AMQShortString), routingKey(AMQShortString),
     *                                            arguments (FieldTable encoded as binary) - 0 (zero)
     *
     * QueueEntry:
     * queueName(AMQShortString), messageId (long) - 0 (zero)
     *
     * Message (MetaData):
     * messageId (long) - bodySize (integer), metaData (MessageMetaData encoded as binary)
     *
     * Message (Content):
     * messageId (long), byteOffset (integer) - dataLength(integer), data(binary)
     */

    private LogSubject _logSubject;

    private final AtomicLong _messageId = new AtomicLong(0);

    private final CommitThread _commitThread = new CommitThread("Commit-Thread");

    private enum State
    {
        INITIAL,
        CONFIGURING,
        CONFIGURED,
        RECOVERING,
        STARTED,
        CLOSING,
        CLOSED
    }

    private State _state = State.INITIAL;

    private TransactionConfig _transactionConfig = new TransactionConfig();

    private boolean _readOnly = false;

    private boolean _configured;


    public BDBMessageStore()
    {
    }


    public void configureConfigStore(String name,
                                     ConfigurationRecoveryHandler recoveryHandler,
                                     Configuration storeConfiguration,
                                     LogSubject logSubject) throws Exception
    {
        CurrentActor.get().message(logSubject, ConfigStoreMessages.CREATED(this.getClass().getName()));

        if(!_configured)
        {
            _logSubject = logSubject;
            configure(name,storeConfiguration);
            _configured = true;
            stateTransition(State.CONFIGURING, State.CONFIGURED);
        }

        recover(recoveryHandler);
        stateTransition(State.RECOVERING, State.STARTED);
    }

    public void configureMessageStore(String name,
                                      MessageStoreRecoveryHandler recoveryHandler,
                                      Configuration storeConfiguration,
                                      LogSubject logSubject) throws Exception
    {
        CurrentActor.get().message(logSubject, MessageStoreMessages.CREATED(this.getClass().getName()));

        if(!_configured)
        {
            _logSubject = logSubject;
            configure(name,storeConfiguration);
            _configured = true;
            stateTransition(State.CONFIGURING, State.CONFIGURED);
        }

        recoverMessages(recoveryHandler);
    }

    public void configureTransactionLog(String name, TransactionLogRecoveryHandler recoveryHandler,
            Configuration storeConfiguration, LogSubject logSubject) throws Exception
    {
        CurrentActor.get().message(logSubject, TransactionLogMessages.CREATED(this.getClass().getName()));


        if(!_configured)
        {
            _logSubject = logSubject;
            configure(name,storeConfiguration);
            _configured = true;
            stateTransition(State.CONFIGURING, State.CONFIGURED);
        }

        recoverQueueEntries(recoveryHandler);



    }

    public org.apache.qpid.server.store.MessageStore.Transaction newTransaction()
    {
        return new BDBTransaction();
    }


    /**
     * Called after instantiation in order to configure the message store.
     *
     * @param name The name of the virtual host using this store
     * @return whether a new store environment was created or not (to indicate whether recovery is necessary)
     *
     * @throws Exception If any error occurs that means the store is unable to configure itself.
     */
    public boolean configure(String name, Configuration storeConfig) throws Exception
    {
        File environmentPath = new File(storeConfig.getString(ENVIRONMENT_PATH_PROPERTY,
                                System.getProperty("QPID_WORK") + "/bdbstore/" + name));
        if (!environmentPath.exists())
        {
            if (!environmentPath.mkdirs())
            {
                throw new IllegalArgumentException("Environment path " + environmentPath + " could not be read or created. "
                                                   + "Ensure the path is correct and that the permissions are correct.");
            }
        }

        message(MessageStoreMessages.STORE_LOCATION(environmentPath.getAbsolutePath()));

        return configure(environmentPath, false);
    }

    void message(final LogMessage message)
    {
        CurrentActor.message(_logSubject, message);
    }

    /**
     * @param environmentPath location for the store to be created in/recovered from
     * @param readonly if true then don't allow modifications to an existing store, and don't create a new store if none exists
     * @return whether or not a new store environment was created
     * @throws AMQStoreException
     * @throws DatabaseException
     */
    protected boolean configure(File environmentPath, boolean readonly) throws AMQStoreException, DatabaseException
    {
        _readOnly = readonly;
        stateTransition(State.INITIAL, State.CONFIGURING);

        _log.info("Configuring BDB message store");

        return setupStore(environmentPath, readonly);
    }

    /**
     * Move the store state from CONFIGURING to STARTED.
     *
     * This is required if you do not want to perform recovery of the store data
     *
     * @throws AMQStoreException if the store is not in the correct state
     */
    public void start() throws AMQStoreException
    {
        stateTransition(State.CONFIGURING, State.STARTED);
    }

    private boolean setupStore(File storePath, boolean readonly) throws DatabaseException, AMQStoreException
    {
        checkState(State.CONFIGURING);

        boolean newEnvironment = createEnvironment(storePath, readonly);

        new Upgrader(_environment, _logSubject).upgradeIfNecessary();

        openDatabases(readonly);

        if (!readonly)
        {
            _commitThread.start();
        }

        return newEnvironment;
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

    private void checkState(State requiredState) throws AMQStoreException
    {
        if (_state != requiredState)
        {
            throw new AMQStoreException("Unexpected state: " + _state + "; required state: " + requiredState);
        }
    }

    private boolean createEnvironment(File environmentPath, boolean readonly) throws DatabaseException
    {
        _log.info("BDB message store using environment path " + environmentPath.getAbsolutePath());
        EnvironmentConfig envConfig = new EnvironmentConfig();
        // This is what allows the creation of the store if it does not already exist.
        envConfig.setAllowCreate(true);
        envConfig.setTransactional(true);
        envConfig.setConfigParam("je.lock.nLockTables", "7");

        // Added to help diagnosis of Deadlock issue
        // http://www.oracle.com/technology/products/berkeley-db/faq/je_faq.html#23
        if (Boolean.getBoolean("qpid.bdb.lock.debug"))
        {
            envConfig.setConfigParam("je.txn.deadlockStackTrace", "true");
            envConfig.setConfigParam("je.txn.dumpLocks", "true");
        }

        // Set transaction mode
        _transactionConfig.setReadCommitted(true);

        //This prevents background threads running which will potentially update the store.
        envConfig.setReadOnly(readonly);
        try
        {
            _environment = new Environment(environmentPath, envConfig);
            return false;
        }
        catch (DatabaseException de)
        {
            if (de.getMessage().contains("Environment.setAllowCreate is false"))
            {
                //Allow the creation this time
                envConfig.setAllowCreate(true);
                if (_environment != null )
                {
                    _environment.cleanLog();
                    _environment.close();
                }
                _environment = new Environment(environmentPath, envConfig);

                return true;
            }
            else
            {
                throw de;
            }
        }
    }

    private void openDatabases(boolean readonly) throws DatabaseException
    {
        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setTransactional(true);
        dbConfig.setAllowCreate(true);

        //This is required if we are wanting read only access.
        dbConfig.setReadOnly(readonly);

        _messageMetaDataDb = openDatabase(MESSAGEMETADATADB_NAME, dbConfig);
        _queueDb = openDatabase(QUEUEDB_NAME, dbConfig);
        _exchangeDb = openDatabase(EXCHANGEDB_NAME, dbConfig);
        _queueBindingsDb = openDatabase(QUEUEBINDINGSDB_NAME, dbConfig);
        _messageContentDb = openDatabase(MESSAGECONTENTDB_NAME, dbConfig);
        _deliveryDb = openDatabase(DELIVERYDB_NAME, dbConfig);
        _linkDb = openDatabase(LINKDB_NAME, dbConfig);
        _bridgeDb = openDatabase(BRIDGEDB_NAME, dbConfig);
        _xidDb = openDatabase(XIDDB_NAME, dbConfig);


    }

    private Database openDatabase(final String dbName, final DatabaseConfig dbConfig)
    {
        // if opening read-only and the database doesn't exist, then you can't create it
        return dbConfig.getReadOnly() && !_environment.getDatabaseNames().contains(dbName)
               ? null
               : _environment.openDatabase(null, dbName, dbConfig);
    }

    /**
     * Called to close and cleanup any resources used by the message store.
     *
     * @throws Exception If the close fails.
     */
    public void close() throws Exception
    {
        if (_state != State.STARTED)
        {
            return;
        }

        _state = State.CLOSING;

        _commitThread.close();
        _commitThread.join();

        if (_messageMetaDataDb != null)
        {
            _log.info("Closing message metadata database");
            _messageMetaDataDb.close();
        }

        if (_messageContentDb != null)
        {
            _log.info("Closing message content database");
            _messageContentDb.close();
        }

        if (_exchangeDb != null)
        {
            _log.info("Closing exchange database");
            _exchangeDb.close();
        }

        if (_queueBindingsDb != null)
        {
            _log.info("Closing bindings database");
            _queueBindingsDb.close();
        }

        if (_queueDb != null)
        {
            _log.info("Closing queue database");
            _queueDb.close();
        }

        if (_deliveryDb != null)
        {
            _log.info("Close delivery database");
            _deliveryDb.close();
        }

        if (_bridgeDb != null)
        {
            _log.info("Close bridge database");
            _bridgeDb.close();
        }

        if (_linkDb != null)
        {
            _log.info("Close link database");
            _linkDb.close();
        }


        if (_xidDb != null)
        {
            _log.info("Close xid database");
            _xidDb.close();
        }

        closeEnvironment();

        _state = State.CLOSED;

        message(MessageStoreMessages.CLOSED());
    }

    private void closeEnvironment() throws DatabaseException
    {
        if (_environment != null)
        {
            if(!_readOnly)
            {
                // Clean the log before closing. This makes sure it doesn't contain
                // redundant data. Closing without doing this means the cleaner may not
                // get a chance to finish.
                _environment.cleanLog();
            }
            _environment.close();
        }
    }


    public void recover(ConfigurationRecoveryHandler recoveryHandler) throws AMQStoreException
    {
        stateTransition(State.CONFIGURED, State.RECOVERING);

        message(MessageStoreMessages.RECOVERY_START());

        try
        {
            QueueRecoveryHandler qrh = recoveryHandler.begin(this);
            loadQueues(qrh);

            ExchangeRecoveryHandler erh = qrh.completeQueueRecovery();
            loadExchanges(erh);

            BindingRecoveryHandler brh = erh.completeExchangeRecovery();
            recoverBindings(brh);

            ConfigurationRecoveryHandler.BrokerLinkRecoveryHandler lrh = brh.completeBindingRecovery();
            recoverBrokerLinks(lrh);
        }
        catch (DatabaseException e)
        {
            throw new AMQStoreException("Error recovering persistent state: " + e.getMessage(), e);
        }

    }

    private void loadQueues(QueueRecoveryHandler qrh) throws DatabaseException
    {
        Cursor cursor = null;

        try
        {
            cursor = _queueDb.openCursor(null, null);
            DatabaseEntry key = new DatabaseEntry();
            DatabaseEntry value = new DatabaseEntry();
            QueueBinding binding = QueueBinding.getInstance();
            while (cursor.getNext(key, value, LockMode.RMW) == OperationStatus.SUCCESS)
            {
                QueueRecord queueRecord = binding.entryToObject(value);

                String queueName = queueRecord.getNameShortString() == null ? null :
                                        queueRecord.getNameShortString().asString();
                String owner = queueRecord.getOwner() == null ? null :
                                        queueRecord.getOwner().asString();
                boolean exclusive = queueRecord.isExclusive();

                FieldTable arguments = queueRecord.getArguments();

                qrh.queue(queueName, owner, exclusive, arguments);
            }

        }
        finally
        {
            if (cursor != null)
            {
                cursor.close();
            }
        }
    }


    private void loadExchanges(ExchangeRecoveryHandler erh) throws DatabaseException
    {
        Cursor cursor = null;

        try
        {
            cursor = _exchangeDb.openCursor(null, null);
            DatabaseEntry key = new DatabaseEntry();
            DatabaseEntry value = new DatabaseEntry();
            ExchangeBinding binding = ExchangeBinding.getInstance();

            while (cursor.getNext(key, value, LockMode.RMW) == OperationStatus.SUCCESS)
            {
                ExchangeRecord exchangeRec = binding.entryToObject(value);

                String exchangeName = exchangeRec.getNameShortString() == null ? null :
                                      exchangeRec.getNameShortString().asString();
                String type = exchangeRec.getType() == null ? null :
                              exchangeRec.getType().asString();
                boolean autoDelete = exchangeRec.isAutoDelete();

                erh.exchange(exchangeName, type, autoDelete);
            }
        }
        finally
        {
            if (cursor != null)
            {
                cursor.close();
            }
        }

    }

    private void recoverBindings(BindingRecoveryHandler brh) throws DatabaseException
    {
        Cursor cursor = null;
        try
        {
            cursor = _queueBindingsDb.openCursor(null, null);
            DatabaseEntry key = new DatabaseEntry();
            DatabaseEntry value = new DatabaseEntry();
            QueueBindingTupleBinding binding = QueueBindingTupleBinding.getInstance();

            while (cursor.getNext(key, value, LockMode.RMW) == OperationStatus.SUCCESS)
            {
                //yes, this is retrieving all the useful information from the key only.
                //For table compatibility it shall currently be left as is
                BindingRecord bindingRecord = binding.entryToObject(key);

                String exchangeName = bindingRecord.getExchangeName() == null ? null :
                                      bindingRecord.getExchangeName().asString();
                String queueName = bindingRecord.getQueueName() == null ? null :
                                   bindingRecord.getQueueName().asString();
                String routingKey = bindingRecord.getRoutingKey() == null ? null :
                                    bindingRecord.getRoutingKey().asString();
                ByteBuffer argumentsBB = (bindingRecord.getArguments() == null ? null :
                    java.nio.ByteBuffer.wrap(bindingRecord.getArguments().getDataAsBytes()));

                brh.binding(exchangeName, queueName, routingKey, argumentsBB);
            }
        }
        finally
        {
            if (cursor != null)
            {
                cursor.close();
            }
        }

    }


    private void recoverBrokerLinks(final ConfigurationRecoveryHandler.BrokerLinkRecoveryHandler lrh)
    {
        Cursor cursor = null;

        try
        {
            cursor = _linkDb.openCursor(null, null);
            DatabaseEntry key = new DatabaseEntry();
            DatabaseEntry value = new DatabaseEntry();

            while (cursor.getNext(key, value, LockMode.RMW) == OperationStatus.SUCCESS)
            {
                UUID id = UUIDTupleBinding.getInstance().entryToObject(key);
                long createTime = LongBinding.entryToLong(value);
                Map<String,String> arguments = StringMapBinding.getInstance().entryToObject(value);

                ConfigurationRecoveryHandler.BridgeRecoveryHandler brh = lrh.brokerLink(id, createTime, arguments);

                recoverBridges(brh, id);
            }
        }
        finally
        {
            if (cursor != null)
            {
                cursor.close();
            }
        }

    }

    private void recoverBridges(final ConfigurationRecoveryHandler.BridgeRecoveryHandler brh, final UUID linkId)
    {
        Cursor cursor = null;

        try
        {
            cursor = _bridgeDb.openCursor(null, null);
            DatabaseEntry key = new DatabaseEntry();
            DatabaseEntry value = new DatabaseEntry();

            while (cursor.getNext(key, value, LockMode.RMW) == OperationStatus.SUCCESS)
            {
                UUID id = UUIDTupleBinding.getInstance().entryToObject(key);

                UUID parentId = UUIDTupleBinding.getInstance().entryToObject(value);
                if(parentId.equals(linkId))
                {

                    long createTime = LongBinding.entryToLong(value);
                    Map<String,String> arguments = StringMapBinding.getInstance().entryToObject(value);
                    brh.bridge(id,createTime,arguments);
                }
            }
            brh.completeBridgeRecoveryForLink();
        }
        finally
        {
            if (cursor != null)
            {
                cursor.close();
            }
        }

    }


    private void recoverMessages(MessageStoreRecoveryHandler msrh) throws DatabaseException
    {
        StoredMessageRecoveryHandler mrh = msrh.begin();

        Cursor cursor = null;
        try
        {
            cursor = _messageMetaDataDb.openCursor(null, null);
            DatabaseEntry key = new DatabaseEntry();
            DatabaseEntry value = new DatabaseEntry();
            MessageMetaDataBinding valueBinding = MessageMetaDataBinding.getInstance();

            long maxId = 0;

            while (cursor.getNext(key, value, LockMode.RMW) == OperationStatus.SUCCESS)
            {
                long messageId = LongBinding.entryToLong(key);
                StorableMessageMetaData metaData = valueBinding.entryToObject(value);

                StoredBDBMessage message = new StoredBDBMessage(messageId, metaData, false);
                mrh.message(message);

                maxId = Math.max(maxId, messageId);
            }

            _messageId.set(maxId);
        }
        catch (DatabaseException e)
        {
            _log.error("Database Error: " + e.getMessage(), e);
            throw e;
        }
        finally
        {
            if (cursor != null)
            {
                cursor.close();
            }
        }
    }

    private void recoverQueueEntries(TransactionLogRecoveryHandler recoveryHandler)
    throws DatabaseException
    {
        QueueEntryRecoveryHandler qerh = recoveryHandler.begin(this);

        ArrayList<QueueEntryKey> entries = new ArrayList<QueueEntryKey>();

        Cursor cursor = null;
        try
        {
            cursor = _deliveryDb.openCursor(null, null);
            DatabaseEntry key = new DatabaseEntry();
            QueueEntryBinding keyBinding = QueueEntryBinding.getInstance();

            DatabaseEntry value = new DatabaseEntry();

            while (cursor.getNext(key, value, LockMode.RMW) == OperationStatus.SUCCESS)
            {
                QueueEntryKey qek = keyBinding.entryToObject(key);

                entries.add(qek);
            }

            try
            {
                cursor.close();
            }
            finally
            {
                cursor = null;
            }

            for(QueueEntryKey entry : entries)
            {
                AMQShortString queueName = entry.getQueueName();
                long messageId = entry.getMessageId();

                qerh.queueEntry(queueName.asString(),messageId);
            }
        }
        catch (DatabaseException e)
        {
            _log.error("Database Error: " + e.getMessage(), e);
            throw e;
        }
        finally
        {
            if (cursor != null)
            {
                cursor.close();
            }
        }



        TransactionLogRecoveryHandler.DtxRecordRecoveryHandler dtxrh = qerh.completeQueueEntryRecovery();

        cursor = null;
        try
        {
            cursor = _xidDb.openCursor(null, null);
            DatabaseEntry key = new DatabaseEntry();
            XidBinding keyBinding = XidBinding.getInstance();
            PreparedTransactionBinding valueBinding = new PreparedTransactionBinding();
            DatabaseEntry value = new DatabaseEntry();

            while (cursor.getNext(key, value, LockMode.RMW) == OperationStatus.SUCCESS)
            {
                Xid xid = keyBinding.entryToObject(key);
                PreparedTransaction preparedTransaction = valueBinding.entryToObject(value);
                dtxrh.dtxRecord(xid.getFormat(),xid.getGlobalId(),xid.getBranchId(),
                                preparedTransaction.getEnqueues(),preparedTransaction.getDequeues());
            }

            try
            {
                cursor.close();
            }
            finally
            {
                cursor = null;
            }

        }
        catch (DatabaseException e)
        {
            _log.error("Database Error: " + e.getMessage(), e);
            throw e;
        }
        finally
        {
            if (cursor != null)
            {
                cursor.close();
            }
        }


        dtxrh.completeDtxRecordRecovery();
    }

    /**
     * Removes the specified message from the store.
     *
     * @param messageId Identifies the message to remove.
     *
     * @throws AMQStoreException If the operation fails for any reason.
     */
    public void removeMessage(long messageId) throws AMQStoreException
    {
        removeMessage(messageId, true);
    }
    public void removeMessage(long messageId, boolean sync) throws AMQStoreException
    {

        boolean complete = false;
        com.sleepycat.je.Transaction tx = null;

        Random rand = null;
        int attempts = 0;
        try
        {
            do
            {
                tx = null;
                try
                {
                    tx = _environment.beginTransaction(null, null);

                    //remove the message meta data from the store
                    DatabaseEntry key = new DatabaseEntry();
                    LongBinding.longToEntry(messageId, key);

                    if (_log.isDebugEnabled())
                    {
                        _log.debug("Removing message id " + messageId);
                    }


                    OperationStatus status = _messageMetaDataDb.delete(tx, key);
                    if (status == OperationStatus.NOTFOUND)
                    {
                        _log.info("Message not found (attempt to remove failed - probably application initiated rollback) " +
                        messageId);
                    }

                    if (_log.isDebugEnabled())
                    {
                        _log.debug("Deleted metadata for message " + messageId);
                    }

                    //now remove the content data from the store if there is any.
                    DatabaseEntry contentKeyEntry = new DatabaseEntry();
                    LongBinding.longToEntry(messageId, contentKeyEntry);
                    _messageContentDb.delete(tx, contentKeyEntry);

                    if (_log.isDebugEnabled())
                    {
                        _log.debug("Deleted content for message " + messageId);
                    }

                    commit(tx, sync);
                    complete = true;
                    tx = null;
                }
                catch (LockConflictException e)
                {
                    try
                    {
                        if(tx != null)
                        {
                            tx.abort();
                        }
                    }
                    catch(DatabaseException e2)
                    {
                        _log.warn("Unable to abort transaction after LockConflictExcption", e2);
                        // rethrow the original log conflict exception, the secondary exception should already have
                        // been logged.
                        throw e;
                    }


                    _log.warn("Lock timeout exception. Retrying (attempt "
                              + (attempts+1) + " of "+ LOCK_RETRY_ATTEMPTS +") " + e);

                    if(++attempts < LOCK_RETRY_ATTEMPTS)
                    {
                        if(rand == null)
                        {
                            rand = new Random();
                        }

                        try
                        {
                            Thread.sleep(500l + (long)(500l * rand.nextDouble()));
                        }
                        catch (InterruptedException e1)
                        {

                        }
                    }
                    else
                    {
                        // rethrow the lock conflict exception since we could not solve by retrying
                        throw e;
                    }
                }
            }
            while(!complete);
        }
        catch (DatabaseException e)
        {
            _log.error("Unexpected BDB exception", e);

            if (tx != null)
            {
                try
                {
                    tx.abort();
                    tx = null;
                }
                catch (DatabaseException e1)
                {
                    throw new AMQStoreException("Error aborting transaction " + e1, e1);
                }
            }

            throw new AMQStoreException("Error removing message with id " + messageId + " from database: " + e.getMessage(), e);
        }
        finally
        {
            if (tx != null)
            {
                try
                {
                    tx.abort();
                    tx = null;
                }
                catch (DatabaseException e1)
                {
                    throw new AMQStoreException("Error aborting transaction " + e1, e1);
                }
            }
        }
    }

    /**
     * @see DurableConfigurationStore#createExchange(Exchange)
     */
    public void createExchange(Exchange exchange) throws AMQStoreException
    {
        if (_state != State.RECOVERING)
        {
            ExchangeRecord exchangeRec = new ExchangeRecord(exchange.getNameShortString(),
                                             exchange.getTypeShortString(), exchange.isAutoDelete());

            DatabaseEntry key = new DatabaseEntry();
            AMQShortStringBinding keyBinding = AMQShortStringBinding.getInstance();
            keyBinding.objectToEntry(exchange.getNameShortString(), key);

            DatabaseEntry value = new DatabaseEntry();
            ExchangeBinding exchangeBinding = ExchangeBinding.getInstance();
            exchangeBinding.objectToEntry(exchangeRec, value);

            try
            {
                _exchangeDb.put(null, key, value);
            }
            catch (DatabaseException e)
            {
                throw new AMQStoreException("Error writing Exchange with name " + exchange.getName() + " to database: " + e.getMessage(), e);
            }
        }
    }

    /**
     * @see DurableConfigurationStore#removeExchange(Exchange)
     */
    public void removeExchange(Exchange exchange) throws AMQStoreException
    {
        DatabaseEntry key = new DatabaseEntry();
        AMQShortStringBinding keyBinding = AMQShortStringBinding.getInstance();
        keyBinding.objectToEntry(exchange.getNameShortString(), key);
        try
        {
            OperationStatus status = _exchangeDb.delete(null, key);
            if (status == OperationStatus.NOTFOUND)
            {
                throw new AMQStoreException("Exchange " + exchange.getName() + " not found");
            }
        }
        catch (DatabaseException e)
        {
            throw new AMQStoreException("Error writing deleting with name " + exchange.getName() + " from database: " + e.getMessage(), e);
        }
    }


    /**
     * @see DurableConfigurationStore#bindQueue(Exchange, AMQShortString, AMQQueue, FieldTable)
     */
    public void bindQueue(Exchange exchange, AMQShortString routingKey, AMQQueue queue, FieldTable args) throws AMQStoreException
    {
        bindQueue(new BindingRecord(exchange.getNameShortString(), queue.getNameShortString(), routingKey, args));
    }

    protected void bindQueue(final BindingRecord bindingRecord) throws AMQStoreException
    {
        if (_state != State.RECOVERING)
        {
            DatabaseEntry key = new DatabaseEntry();
            QueueBindingTupleBinding keyBinding = QueueBindingTupleBinding.getInstance();

            keyBinding.objectToEntry(bindingRecord, key);

            //yes, this is writing out 0 as a value and putting all the
            //useful info into the key, don't ask me why. For table
            //compatibility it shall currently be left as is
            DatabaseEntry value = new DatabaseEntry();
            ByteBinding.byteToEntry((byte) 0, value);

            try
            {
                _queueBindingsDb.put(null, key, value);
            }
            catch (DatabaseException e)
            {
                throw new AMQStoreException("Error writing binding for AMQQueue with name " + bindingRecord.getQueueName() + " to exchange "
                                       + bindingRecord.getExchangeName() + " to database: " + e.getMessage(), e);
            }
        }
    }

    /**
     * @see DurableConfigurationStore#unbindQueue(Exchange, AMQShortString, AMQQueue, FieldTable)
     */
    public void unbindQueue(Exchange exchange, AMQShortString routingKey, AMQQueue queue, FieldTable args)
            throws AMQStoreException
    {
        DatabaseEntry key = new DatabaseEntry();
        QueueBindingTupleBinding keyBinding = QueueBindingTupleBinding.getInstance();
        keyBinding.objectToEntry(new BindingRecord(exchange.getNameShortString(), queue.getNameShortString(), routingKey, args), key);

        try
        {
            OperationStatus status = _queueBindingsDb.delete(null, key);
            if (status == OperationStatus.NOTFOUND)
            {
                throw new AMQStoreException("Queue binding for queue with name " + queue.getName() + " to exchange "
                                       + exchange.getName() + "  not found");
            }
        }
        catch (DatabaseException e)
        {
            throw new AMQStoreException("Error deleting queue binding for queue with name " + queue.getName() + " to exchange "
                                   + exchange.getName() + " from database: " + e.getMessage(), e);
        }
    }

    /**
     * @see DurableConfigurationStore#createQueue(AMQQueue)
     */
    public void createQueue(AMQQueue queue) throws AMQStoreException
    {
        createQueue(queue, null);
    }

    /**
     * @see DurableConfigurationStore#createQueue(AMQQueue, FieldTable)
     */
    public void createQueue(AMQQueue queue, FieldTable arguments) throws AMQStoreException
    {
        if (_log.isDebugEnabled())
        {
            _log.debug("public void createQueue(AMQQueue queue(" + queue.getName() + ") = " + queue + "): called");
        }

        QueueRecord queueRecord= new QueueRecord(queue.getNameShortString(),
                                                queue.getOwner(), queue.isExclusive(), arguments);

        createQueue(queueRecord);
    }

    /**
     * Makes the specified queue persistent.
     *
     * Only intended for direct use during store upgrades.
     *
     * @param queueRecord     Details of the queue to store.
     *
     * @throws AMQStoreException If the operation fails for any reason.
     */
    protected void createQueue(QueueRecord queueRecord) throws AMQStoreException
    {
        if (_state != State.RECOVERING)
        {
            DatabaseEntry key = new DatabaseEntry();
            AMQShortStringBinding keyBinding = AMQShortStringBinding.getInstance();
            keyBinding.objectToEntry(queueRecord.getNameShortString(), key);

            DatabaseEntry value = new DatabaseEntry();
            QueueBinding queueBinding = QueueBinding.getInstance();

            queueBinding.objectToEntry(queueRecord, value);
            try
            {
                _queueDb.put(null, key, value);
            }
            catch (DatabaseException e)
            {
                throw new AMQStoreException("Error writing AMQQueue with name " + queueRecord.getNameShortString().asString()
                        + " to database: " + e.getMessage(), e);
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
        if (_log.isDebugEnabled())
        {
            _log.debug("Updating queue: " + queue.getName());
        }

        try
        {
            DatabaseEntry key = new DatabaseEntry();
            AMQShortStringBinding keyBinding = AMQShortStringBinding.getInstance();
            keyBinding.objectToEntry(queue.getNameShortString(), key);

            DatabaseEntry value = new DatabaseEntry();
            DatabaseEntry newValue = new DatabaseEntry();
            QueueBinding queueBinding = QueueBinding.getInstance();

            OperationStatus status = _queueDb.get(null, key, value, LockMode.DEFAULT);
            if(status == OperationStatus.SUCCESS)
            {
                //read the existing record and apply the new exclusivity setting
                QueueRecord queueRecord = queueBinding.entryToObject(value);
                queueRecord.setExclusive(queue.isExclusive());

                //write the updated entry to the store
                queueBinding.objectToEntry(queueRecord, newValue);

                _queueDb.put(null, key, newValue);
            }
            else if(status != OperationStatus.NOTFOUND)
            {
                throw new AMQStoreException("Error updating queue details within the store: " + status);
            }
        }
        catch (DatabaseException e)
        {
            throw new AMQStoreException("Error updating queue details within the store: " + e,e);
        }
    }

    /**
     * Removes the specified queue from the persistent store.
     *
     * @param queue The queue to remove.
     *
     * @throws AMQStoreException If the operation fails for any reason.
     */
    public void removeQueue(final AMQQueue queue) throws AMQStoreException
    {
        AMQShortString name = queue.getNameShortString();

        if (_log.isDebugEnabled())
        {
            _log.debug("public void removeQueue(AMQShortString name = " + name + "): called");
        }

        DatabaseEntry key = new DatabaseEntry();
        AMQShortStringBinding keyBinding = AMQShortStringBinding.getInstance();
        keyBinding.objectToEntry(name, key);
        try
        {
            OperationStatus status = _queueDb.delete(null, key);
            if (status == OperationStatus.NOTFOUND)
            {
                throw new AMQStoreException("Queue " + name + " not found");
            }
        }
        catch (DatabaseException e)
        {
            throw new AMQStoreException("Error writing deleting with name " + name + " from database: " + e.getMessage(), e);
        }
    }

    public void createBrokerLink(final BrokerLink link) throws AMQStoreException
    {
        if (_state != State.RECOVERING)
        {
            DatabaseEntry key = new DatabaseEntry();
            UUIDTupleBinding.getInstance().objectToEntry(link.getId(), key);

            DatabaseEntry value = new DatabaseEntry();
            LongBinding.longToEntry(link.getCreateTime(),value);
            StringMapBinding.getInstance().objectToEntry(link.getArguments(), value);

            try
            {
                _linkDb.put(null, key, value);
            }
            catch (DatabaseException e)
            {
                throw new AMQStoreException("Error writing Link  " + link
                                            + " to database: " + e.getMessage(), e);
            }
        }
    }

    public void deleteBrokerLink(final BrokerLink link) throws AMQStoreException
    {
        DatabaseEntry key = new DatabaseEntry();
        UUIDTupleBinding.getInstance().objectToEntry(link.getId(), key);
        try
        {
            OperationStatus status = _linkDb.delete(null, key);
            if (status == OperationStatus.NOTFOUND)
            {
                throw new AMQStoreException("Link " + link + " not found");
            }
        }
        catch (DatabaseException e)
        {
            throw new AMQStoreException("Error deleting the Link " + link + " from database: " + e.getMessage(), e);
        }
    }

    public void createBridge(final Bridge bridge) throws AMQStoreException
    {
        if (_state != State.RECOVERING)
        {
            DatabaseEntry key = new DatabaseEntry();
            UUIDTupleBinding.getInstance().objectToEntry(bridge.getId(), key);

            DatabaseEntry value = new DatabaseEntry();
            UUIDTupleBinding.getInstance().objectToEntry(bridge.getLink().getId(),value);
            LongBinding.longToEntry(bridge.getCreateTime(),value);
            StringMapBinding.getInstance().objectToEntry(bridge.getArguments(), value);

            try
            {
                _bridgeDb.put(null, key, value);
            }
            catch (DatabaseException e)
            {
                throw new AMQStoreException("Error writing Bridge  " + bridge
                                            + " to database: " + e.getMessage(), e);
            }

        }
    }

    public void deleteBridge(final Bridge bridge) throws AMQStoreException
    {
        DatabaseEntry key = new DatabaseEntry();
        UUIDTupleBinding.getInstance().objectToEntry(bridge.getId(), key);
        try
        {
            OperationStatus status = _bridgeDb.delete(null, key);
            if (status == OperationStatus.NOTFOUND)
            {
                throw new AMQStoreException("Bridge " + bridge + " not found");
            }
        }
        catch (DatabaseException e)
        {
            throw new AMQStoreException("Error deleting the Bridge " + bridge + " from database: " + e.getMessage(), e);
        }
    }

    /**
     * Places a message onto a specified queue, in a given transaction.
     *
     * @param tx   The transaction for the operation.
     * @param queue     The the queue to place the message on.
     * @param messageId The message to enqueue.
     *
     * @throws AMQStoreException If the operation fails for any reason.
     */
    public void enqueueMessage(final com.sleepycat.je.Transaction tx, final TransactionLogResource queue,
                               long messageId) throws AMQStoreException
    {
        AMQShortString name = AMQShortString.valueOf(queue.getResourceName());

        DatabaseEntry key = new DatabaseEntry();
        QueueEntryBinding keyBinding = QueueEntryBinding.getInstance();
        QueueEntryKey dd = new QueueEntryKey(name, messageId);
        keyBinding.objectToEntry(dd, key);
        DatabaseEntry value = new DatabaseEntry();
        ByteBinding.byteToEntry((byte) 0, value);

        try
        {
            if (_log.isDebugEnabled())
            {
                _log.debug("Enqueuing message " + messageId + " on queue " + name + " [Transaction" + tx + "]");
            }
            _deliveryDb.put(tx, key, value);
        }
        catch (DatabaseException e)
        {
            _log.error("Failed to enqueue: " + e.getMessage(), e);
            throw new AMQStoreException("Error writing enqueued message with id " + messageId + " for queue " + name
                                   + " to database", e);
        }
    }

    /**
     * Extracts a message from a specified queue, in a given transaction.
     *
     * @param tx   The transaction for the operation.
     * @param queue     The name queue to take the message from.
     * @param messageId The message to dequeue.
     *
     * @throws AMQStoreException If the operation fails for any reason, or if the specified message does not exist.
     */
    public void dequeueMessage(final com.sleepycat.je.Transaction tx, final TransactionLogResource queue,
                               long messageId) throws AMQStoreException
    {
        AMQShortString name = new AMQShortString(queue.getResourceName());

        DatabaseEntry key = new DatabaseEntry();
        QueueEntryBinding keyBinding = QueueEntryBinding.getInstance();
        QueueEntryKey queueEntryKey = new QueueEntryKey(name, messageId);

        keyBinding.objectToEntry(queueEntryKey, key);

        if (_log.isDebugEnabled())
        {
            _log.debug("Dequeue message id " + messageId);
        }

        try
        {

            OperationStatus status = _deliveryDb.delete(tx, key);
            if (status == OperationStatus.NOTFOUND)
            {
                throw new AMQStoreException("Unable to find message with id " + messageId + " on queue " + name);
            }
            else if (status != OperationStatus.SUCCESS)
            {
                throw new AMQStoreException("Unable to remove message with id " + messageId + " on queue " + name);
            }

            if (_log.isDebugEnabled())
            {
                _log.debug("Removed message " + messageId + ", " + name + " from delivery db");

            }
        }
        catch (DatabaseException e)
        {

            _log.error("Failed to dequeue message " + messageId + ": " + e.getMessage(), e);
            _log.error(tx);

            throw new AMQStoreException("Error accessing database while dequeuing message: " + e.getMessage(), e);
        }
    }


    private void recordXid(com.sleepycat.je.Transaction txn,
                           long format,
                           byte[] globalId,
                           byte[] branchId,
                           Transaction.Record[] enqueues,
                           Transaction.Record[] dequeues) throws AMQStoreException
    {
        DatabaseEntry key = new DatabaseEntry();
        Xid xid = new Xid(format, globalId, branchId);
        XidBinding keyBinding = XidBinding.getInstance();
        keyBinding.objectToEntry(xid,key);

        DatabaseEntry value = new DatabaseEntry();
        PreparedTransaction preparedTransaction = new PreparedTransaction(enqueues, dequeues);
        PreparedTransactionBinding valueBinding = new PreparedTransactionBinding();
        valueBinding.objectToEntry(preparedTransaction, value);

        try
        {
            _xidDb.put(txn, key, value);
        }
        catch (DatabaseException e)
        {
            _log.error("Failed to write xid: " + e.getMessage(), e);
            throw new AMQStoreException("Error writing xid to database", e);
        }
    }

    private void removeXid(com.sleepycat.je.Transaction txn, long format, byte[] globalId, byte[] branchId)
            throws AMQStoreException
    {
        DatabaseEntry key = new DatabaseEntry();
        Xid xid = new Xid(format, globalId, branchId);
        XidBinding keyBinding = XidBinding.getInstance();

        keyBinding.objectToEntry(xid, key);


        try
        {

            OperationStatus status = _xidDb.delete(txn, key);
            if (status == OperationStatus.NOTFOUND)
            {
                throw new AMQStoreException("Unable to find xid");
            }
            else if (status != OperationStatus.SUCCESS)
            {
                throw new AMQStoreException("Unable to remove xid");
            }

        }
        catch (DatabaseException e)
        {

            _log.error("Failed to remove xid ", e);
            _log.error(txn);

            throw new AMQStoreException("Error accessing database while removing xid: " + e.getMessage(), e);
        }
    }

    /**
     * Commits all operations performed within a given transaction.
     *
     * @param tx The transaction to commit all operations for.
     *
     * @throws AMQStoreException If the operation fails for any reason.
     */
    private StoreFuture commitTranImpl(final com.sleepycat.je.Transaction tx, boolean syncCommit) throws AMQStoreException
    {
        if (tx == null)
        {
            throw new AMQStoreException("Fatal internal error: transactional is null at commitTran");
        }

        StoreFuture result;
        try
        {
            result = commit(tx, syncCommit);

            if (_log.isDebugEnabled())
            {
                _log.debug("commitTranImpl completed for [Transaction:" + tx + "]");
            }
        }
        catch (DatabaseException e)
        {
            throw new AMQStoreException("Error commit tx: " + e.getMessage(), e);
        }

        return result;
    }

    /**
     * Abandons all operations performed within a given transaction.
     *
     * @param tx The transaction to abandon.
     *
     * @throws AMQStoreException If the operation fails for any reason.
     */
    public void abortTran(final com.sleepycat.je.Transaction tx) throws AMQStoreException
    {
        if (_log.isDebugEnabled())
        {
            _log.debug("abortTran called for [Transaction:" + tx + "]");
        }

        try
        {
            tx.abort();
        }
        catch (DatabaseException e)
        {
            throw new AMQStoreException("Error aborting transaction: " + e.getMessage(), e);
        }
    }

    /**
     * Primarily for testing purposes.
     *
     * @param queueName
     *
     * @return a list of message ids for messages enqueued for a particular queue
     */
    List<Long> getEnqueuedMessages(AMQShortString queueName) throws AMQStoreException
    {
        Cursor cursor = null;
        try
        {
            cursor = _deliveryDb.openCursor(null, null);

            DatabaseEntry key = new DatabaseEntry();

            QueueEntryKey dd = new QueueEntryKey(queueName, 0);

            QueueEntryBinding keyBinding = QueueEntryBinding.getInstance();
            keyBinding.objectToEntry(dd, key);

            DatabaseEntry value = new DatabaseEntry();

            LinkedList<Long> messageIds = new LinkedList<Long>();

            OperationStatus status = cursor.getSearchKeyRange(key, value, LockMode.DEFAULT);
            dd = keyBinding.entryToObject(key);

            while ((status == OperationStatus.SUCCESS) && dd.getQueueName().equals(queueName))
            {

                messageIds.add(dd.getMessageId());
                status = cursor.getNext(key, value, LockMode.DEFAULT);
                if (status == OperationStatus.SUCCESS)
                {
                    dd = keyBinding.entryToObject(key);
                }
            }

            return messageIds;
        }
        catch (DatabaseException e)
        {
            throw new AMQStoreException("Database error: " + e.getMessage(), e);
        }
        finally
        {
            if (cursor != null)
            {
                try
                {
                    cursor.close();
                }
                catch (DatabaseException e)
                {
                    throw new AMQStoreException("Error closing cursor: " + e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Return a valid, currently unused message id.
     *
     * @return A fresh message id.
     */
    public long getNewMessageId()
    {
        return _messageId.incrementAndGet();
    }

    /**
     * Stores a chunk of message data.
     *
     * @param tx         The transaction for the operation.
     * @param messageId       The message to store the data for.
     * @param contentBody     The content of the data chunk.
     *
     * @throws AMQStoreException If the operation fails for any reason, or if the specified message does not exist.
     */
    protected void addContent(final com.sleepycat.je.Transaction tx, long messageId,
                                      ByteBuffer contentBody) throws AMQStoreException
    {
        DatabaseEntry key = new DatabaseEntry();
        LongBinding.longToEntry(messageId, key);
        DatabaseEntry value = new DatabaseEntry();
        ContentBinding messageBinding = ContentBinding.getInstance();
        messageBinding.objectToEntry(contentBody.array(), value);
        try
        {
            OperationStatus status = _messageContentDb.put(tx, key, value);
            if (status != OperationStatus.SUCCESS)
            {
                throw new AMQStoreException("Error adding content for message id " + messageId + ": " + status);
            }

            if (_log.isDebugEnabled())
            {
                _log.debug("Storing content for message " + messageId + "[Transaction" + tx + "]");
            }
        }
        catch (DatabaseException e)
        {
            throw new AMQStoreException("Error writing AMQMessage with id " + messageId + " to database: " + e.getMessage(), e);
        }
    }

    /**
     * Stores message meta-data.
     *
     * @param tx         The transaction for the operation.
     * @param messageId       The message to store the data for.
     * @param messageMetaData The message meta data to store.
     *
     * @throws AMQStoreException If the operation fails for any reason, or if the specified message does not exist.
     */
    private void storeMetaData(final com.sleepycat.je.Transaction tx, long messageId,
                               StorableMessageMetaData messageMetaData)
            throws AMQStoreException
    {
        if (_log.isDebugEnabled())
        {
            _log.debug("public void storeMetaData(Txn tx = " + tx + ", Long messageId = "
                       + messageId + ", MessageMetaData messageMetaData = " + messageMetaData + "): called");
        }

        DatabaseEntry key = new DatabaseEntry();
        LongBinding.longToEntry(messageId, key);
        DatabaseEntry value = new DatabaseEntry();

        MessageMetaDataBinding messageBinding = MessageMetaDataBinding.getInstance();
        messageBinding.objectToEntry(messageMetaData, value);
        try
        {
            _messageMetaDataDb.put(tx, key, value);
            if (_log.isDebugEnabled())
            {
                _log.debug("Storing message metadata for message id " + messageId + "[Transaction" + tx + "]");
            }
        }
        catch (DatabaseException e)
        {
            throw new AMQStoreException("Error writing message metadata with id " + messageId + " to database: " + e.getMessage(), e);
        }
    }

    /**
     * Retrieves message meta-data.
     *
     * @param messageId The message to get the meta-data for.
     *
     * @return The message meta data.
     *
     * @throws AMQStoreException If the operation fails for any reason, or if the specified message does not exist.
     */
    public StorableMessageMetaData getMessageMetaData(long messageId) throws AMQStoreException
    {
        if (_log.isDebugEnabled())
        {
            _log.debug("public MessageMetaData getMessageMetaData(Long messageId = "
                       + messageId + "): called");
        }

        DatabaseEntry key = new DatabaseEntry();
        LongBinding.longToEntry(messageId, key);
        DatabaseEntry value = new DatabaseEntry();
        MessageMetaDataBinding messageBinding = MessageMetaDataBinding.getInstance();

        try
        {
            OperationStatus status = _messageMetaDataDb.get(null, key, value, LockMode.READ_UNCOMMITTED);
            if (status != OperationStatus.SUCCESS)
            {
                throw new AMQStoreException("Metadata not found for message with id " + messageId);
            }

            StorableMessageMetaData mdd = messageBinding.entryToObject(value);

            return mdd;
        }
        catch (DatabaseException e)
        {
            throw new AMQStoreException("Error reading message metadata for message with id " + messageId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Fills the provided ByteBuffer with as much content for the specified message as possible, starting
     * from the specified offset in the message.
     *
     * @param messageId The message to get the data for.
     * @param offset    The offset of the data within the message.
     * @param dst       The destination of the content read back
     *
     * @return The number of bytes inserted into the destination
     *
     * @throws AMQStoreException If the operation fails for any reason, or if the specified message does not exist.
     */
    public int getContent(long messageId, int offset, ByteBuffer dst) throws AMQStoreException
    {
        DatabaseEntry contentKeyEntry = new DatabaseEntry();

        LongBinding.longToEntry(messageId, contentKeyEntry);
        DatabaseEntry value = new DatabaseEntry();
        ContentBinding contentTupleBinding = ContentBinding.getInstance();

        if (_log.isDebugEnabled())
        {
            _log.debug("Message Id: " + messageId + " Getting content body from offset: " + offset);
        }

        try
        {
            int written = 0;
            OperationStatus status = _messageContentDb.get(null, contentKeyEntry, value, LockMode.READ_UNCOMMITTED);
            if (status == OperationStatus.SUCCESS)
            {
                byte[] dataAsBytes = contentTupleBinding.entryToObject(value);
                int size = dataAsBytes.length;
                if (offset > size)
                {
                    throw new RuntimeException("Offset " + offset + " is greater than message size " + size
                            + " for message id " + messageId + "!");
                }

                written = size - offset;
                if(written > dst.remaining())
                {
                    written = dst.remaining();
                }
                dst.put(dataAsBytes, offset, written);
            }
            return written;
        }
        catch (DatabaseException e)
        {
            throw new AMQStoreException("Error getting AMQMessage with id " + messageId + " to database: " + e.getMessage(), e);
        }
    }

    public boolean isPersistent()
    {
        return true;
    }

    public <T extends StorableMessageMetaData> StoredMessage<T> addMessage(T metaData)
    {
        if(metaData.isPersistent())
        {
            return (StoredMessage<T>) new StoredBDBMessage(getNewMessageId(), metaData);
        }
        else
        {
            return new StoredMemoryMessage(getNewMessageId(), metaData);
        }
    }


    //Package getters for the various databases used by the Store

    Database getMetaDataDb()
    {
        return _messageMetaDataDb;
    }

    Database getContentDb()
    {
        return _messageContentDb;
    }

    Database getQueuesDb()
    {
        return _queueDb;
    }

    Database getDeliveryDb()
    {
        return _deliveryDb;
    }

    Database getExchangesDb()
    {
        return _exchangeDb;
    }

    Database getBindingsDb()
    {
        return _queueBindingsDb;
    }

    Environment getEnvironment()
    {
        return _environment;
    }

    private StoreFuture commit(com.sleepycat.je.Transaction tx, boolean syncCommit) throws DatabaseException
    {
        tx.commitNoSync();

        BDBCommitFuture commitFuture = new BDBCommitFuture(_commitThread, tx, syncCommit);
        commitFuture.commit();

        return commitFuture;
    }

    public void startCommitThread()
    {
        _commitThread.start();
    }

    private static final class BDBCommitFuture implements StoreFuture
    {
        private final CommitThread _commitThread;
        private final com.sleepycat.je.Transaction _tx;
        private DatabaseException _databaseException;
        private boolean _complete;
        private boolean _syncCommit;

        public BDBCommitFuture(CommitThread commitThread, com.sleepycat.je.Transaction tx, boolean syncCommit)
        {
            _commitThread = commitThread;
            _tx = tx;
            _syncCommit = syncCommit;
        }

        public synchronized void complete()
        {
            if (_log.isDebugEnabled())
            {
                _log.debug("public synchronized void complete(): called (Transaction = " + _tx + ")");
            }
            _complete = true;

            notifyAll();
        }

        public synchronized void abort(DatabaseException databaseException)
        {
            _complete = true;
            _databaseException = databaseException;

            notifyAll();
        }

        public void commit() throws DatabaseException
        {
            _commitThread.addJob(this, _syncCommit);

            if(!_syncCommit)
            {
                _log.debug("CommitAsync was requested, returning immediately.");
                return;
            }

            waitForCompletion();

            if (_databaseException != null)
            {
                throw _databaseException;
            }

        }

        public synchronized boolean isComplete()
        {
            return _complete;
        }

        public synchronized void waitForCompletion()
        {
            while (!isComplete())
            {
                _commitThread.explicitNotify();
                try
                {
                    wait(250);
                }
                catch (InterruptedException e)
                {
                    //TODO Should we ignore, or throw a 'StoreException'?
                    throw new RuntimeException(e);
                }
            }
        }
    }

    /**
     * Implements a thread which batches and commits a queue of {@link BDBCommitFuture} operations. The commit operations
     * themselves are responsible for adding themselves to the queue and waiting for the commit to happen before
     * continuing, but it is the responsibility of this thread to tell the commit operations when they have been
     * completed by calling back on their {@link BDBCommitFuture#complete()} and {@link BDBCommitFuture#abort} methods.
     *
     * <p/><table id="crc"><caption>CRC Card</caption> <tr><th> Responsibilities <th> Collarations </table>
     */
    private class CommitThread extends Thread
    {
        private final AtomicBoolean _stopped = new AtomicBoolean(false);
        private final Queue<BDBCommitFuture> _jobQueue = new ConcurrentLinkedQueue<BDBCommitFuture>();
        private final CheckpointConfig _config = new CheckpointConfig();
        private final Object _lock = new Object();

        public CommitThread(String name)
        {
            super(name);
            _config.setForce(true);

        }

        public void explicitNotify()
        {
            synchronized (_lock)
            {
                _lock.notify();
            }
        }

        public void run()
        {
            while (!_stopped.get())
            {
                synchronized (_lock)
                {
                    while (!_stopped.get() && !hasJobs())
                    {
                        try
                        {
                            // RHM-7 Periodically wake up and check, just in case we
                            // missed a notification. Don't want to lock the broker hard.
                            _lock.wait(1000);
                        }
                        catch (InterruptedException e)
                        {
                        }
                    }
                }
                processJobs();
            }
        }

        private void processJobs()
        {
            int size = _jobQueue.size();

            try
            {
                _environment.flushLog(true);

                for(int i = 0; i < size; i++)
                {
                    BDBCommitFuture commit = _jobQueue.poll();
                    commit.complete();
                }

            }
            catch (DatabaseException e)
            {
                for(int i = 0; i < size; i++)
                {
                    BDBCommitFuture commit = _jobQueue.poll();
                    commit.abort(e);
                }
            }

        }

        private boolean hasJobs()
        {
            return !_jobQueue.isEmpty();
        }

        public void addJob(BDBCommitFuture commit, final boolean sync)
        {

            _jobQueue.add(commit);
            if(sync)
            {
                synchronized (_lock)
                {
                    _lock.notifyAll();
                }
            }
        }

        public void close()
        {
            synchronized (_lock)
            {
                _stopped.set(true);
                _lock.notifyAll();
            }
        }
    }


    private class StoredBDBMessage implements StoredMessage<StorableMessageMetaData>
    {

        private final long _messageId;
        private volatile SoftReference<StorableMessageMetaData> _metaDataRef;

        private StorableMessageMetaData _metaData;
        private volatile SoftReference<byte[]> _dataRef;
        private byte[] _data;

        StoredBDBMessage(long messageId, StorableMessageMetaData metaData)
        {
            this(messageId, metaData, true);
        }


        StoredBDBMessage(long messageId,
                           StorableMessageMetaData metaData, boolean persist)
        {
            try
            {
                _messageId = messageId;
                _metaData = metaData;

                _metaDataRef = new SoftReference<StorableMessageMetaData>(metaData);

            }
            catch (DatabaseException e)
            {
                throw new RuntimeException(e);
            }

        }

        public StorableMessageMetaData getMetaData()
        {
            StorableMessageMetaData metaData = _metaDataRef.get();
            if(metaData == null)
            {
                try
                {
                    metaData = BDBMessageStore.this.getMessageMetaData(_messageId);
                }
                catch (AMQStoreException e)
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
                try
                {
                    return BDBMessageStore.this.getContent(_messageId, offsetInMessage, dst);
                }
                catch (AMQStoreException e)
                {
                    // TODO maybe should throw a checked exception, or at least log before throwing
                    throw new RuntimeException(e);
                }
            }
        }

        public ByteBuffer getContent(int offsetInMessage, int size)
        {
            byte[] data = _dataRef == null ? null : _dataRef.get();
            if(data != null)
            {
                return ByteBuffer.wrap(data,offsetInMessage,size);
            }
            else
            {
                ByteBuffer buf = ByteBuffer.allocate(size);
                getContent(offsetInMessage, buf);
                buf.position(0);
                return  buf;
            }
        }

        synchronized void store(com.sleepycat.je.Transaction txn)
        {

            if(_metaData != null)
            {
                try
                {
                    _dataRef = new SoftReference<byte[]>(_data);
                    BDBMessageStore.this.storeMetaData(txn, _messageId, _metaData);
                    BDBMessageStore.this.addContent(txn, _messageId,
                                                    _data == null ? ByteBuffer.allocate(0) : ByteBuffer.wrap(_data));
                }
                catch(DatabaseException e)
                {
                    throw new RuntimeException(e);
                }
                catch (AMQStoreException e)
                {
                    throw new RuntimeException(e);
                }
                catch (RuntimeException e)
                {
                    e.printStackTrace();
                    throw e;
                }
                finally
                {
                    _metaData = null;
                    _data = null;
                }
            }
        }

        public synchronized StoreFuture flushToStore()
        {
            if(_metaData != null)
            {
                com.sleepycat.je.Transaction txn = _environment.beginTransaction(null, null);
                store(txn);
                BDBMessageStore.this.commit(txn,true);

            }
            return IMMEDIATE_FUTURE;
        }

        public void remove()
        {
            try
            {
                BDBMessageStore.this.removeMessage(_messageId, false);
            }
            catch (AMQStoreException e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    private class BDBTransaction implements Transaction
    {
        private com.sleepycat.je.Transaction _txn;

        private BDBTransaction()
        {
            try
            {
                _txn = _environment.beginTransaction(null, null);
            }
            catch (DatabaseException e)
            {
                throw new RuntimeException(e);
            }
        }

        public void enqueueMessage(TransactionLogResource queue, EnqueableMessage message) throws AMQStoreException
        {
            if(message.getStoredMessage() instanceof StoredBDBMessage)
            {
                ((StoredBDBMessage)message.getStoredMessage()).store(_txn);
            }

            BDBMessageStore.this.enqueueMessage(_txn, queue, message.getMessageNumber());
        }

        public void dequeueMessage(TransactionLogResource queue, EnqueableMessage message) throws AMQStoreException
        {
            BDBMessageStore.this.dequeueMessage(_txn, queue, message.getMessageNumber());
        }

        public void commitTran() throws AMQStoreException
        {
            BDBMessageStore.this.commitTranImpl(_txn, true);
        }

        public StoreFuture commitTranAsync() throws AMQStoreException
        {
            return BDBMessageStore.this.commitTranImpl(_txn, false);
        }

        public void abortTran() throws AMQStoreException
        {
            BDBMessageStore.this.abortTran(_txn);
        }

        public void removeXid(long format, byte[] globalId, byte[] branchId) throws AMQStoreException
        {
            BDBMessageStore.this.removeXid(_txn, format, globalId, branchId);
        }

        public void recordXid(long format, byte[] globalId, byte[] branchId, Record[] enqueues,
                              Record[] dequeues) throws AMQStoreException
        {
            BDBMessageStore.this.recordXid(_txn, format, globalId, branchId, enqueues, dequeues);
        }
    }


}
