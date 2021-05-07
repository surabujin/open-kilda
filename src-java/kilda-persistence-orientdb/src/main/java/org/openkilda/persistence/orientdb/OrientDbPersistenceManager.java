/* Copyright 2020 Telstra Open Source
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.openkilda.persistence.orientdb;

import org.openkilda.persistence.NetworkConfig;
import org.openkilda.persistence.PersistenceConfig;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.orientdb.repositories.OrientDbRepositoryFactory;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.tx.DelegateTransactionManagerFactory;
import org.openkilda.persistence.tx.TransactionArea;
import org.openkilda.persistence.tx.TransactionManager;

import com.syncleus.ferma.DelegatingFramedGraph;
import lombok.extern.slf4j.Slf4j;

/**
 * OrientDB implementation of {@link PersistenceManager}.
 */
@Slf4j
public class OrientDbPersistenceManager implements PersistenceManager {
    protected final PersistenceConfig persistenceConfig;
    protected final OrientDbConfig config;
    protected final NetworkConfig networkConfig;

    private transient volatile OrientDbGraphFactory graphFactory;
    private transient volatile TransactionManager transactionManager;

    public OrientDbPersistenceManager(PersistenceConfig persistenceConfig,
                                      OrientDbConfig config, NetworkConfig networkConfig) {
        this.persistenceConfig = persistenceConfig;
        this.config = config;
        this.networkConfig = networkConfig;
    }

    @Override
    public TransactionManager getTransactionManager() {
        return getTransactionManager(TransactionArea.FLAT);
    }

    @Override
    public TransactionManager getTransactionManager(TransactionArea area) {
        return lazyCreateTransactionManager();  // ignore area, this is flat DB schema
    }

    @Override
    public RepositoryFactory getRepositoryFactory() {
        DelegateTransactionManagerFactory transactionManagerFactory = new DelegateTransactionManagerFactory(this);
        return new OrientDbRepositoryFactory(lazyCreateGraphFactory(), transactionManagerFactory, networkConfig);
    }

    private TransactionManager lazyCreateTransactionManager() {
        @SuppressWarnings("unchecked")
        FramedGraphFactory<DelegatingFramedGraph<?>> orientDbFactory = lazyCreateGraphFactory();

        if (transactionManager == null) {
            synchronized (this) {
                if (transactionManager == null) {
                    transactionManager = makeOrientDbTransactionManager(orientDbFactory, TransactionArea.FLAT);
                }
            }
        }
        return transactionManager;
    }

    protected OrientDbGraphFactory lazyCreateGraphFactory() {
        if (graphFactory == null) {
            synchronized (this) {
                if (graphFactory == null) {
                    log.debug("Creating an instance of OrientDbGraphFactory for {}", config);
                    graphFactory = new OrientDbGraphFactory(config);
                }
            }
        }
        return graphFactory;
    }

    protected TransactionManager makeOrientDbTransactionManager(
            FramedGraphFactory<DelegatingFramedGraph<?>> orientDbFactory, TransactionArea area) {
        OrientDbTransactionAdapterFactory adapterFactory = new OrientDbTransactionAdapterFactory(area, orientDbFactory);
        return new TransactionManager(
                adapterFactory,
                persistenceConfig.getTransactionRetriesLimit(),
                persistenceConfig.getTransactionRetriesMaxDelay());
    }
}
