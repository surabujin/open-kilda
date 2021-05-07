/* Copyright 2021 Telstra Open Source
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

package org.openkilda.persistence.mixture.sql.history;

import org.openkilda.persistence.NetworkConfig;
import org.openkilda.persistence.PersistenceConfig;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.hibernate.HibernateConfig;
import org.openkilda.persistence.hibernate.HibernateTransactionAdapterFactory;
import org.openkilda.persistence.orientdb.OrientDbConfig;
import org.openkilda.persistence.orientdb.OrientDbPersistenceManager;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.tx.DelegateTransactionManagerFactory;
import org.openkilda.persistence.tx.TransactionArea;
import org.openkilda.persistence.tx.TransactionManager;

import com.syncleus.ferma.DelegatingFramedGraph;
import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataBuilder;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.SessionFactoryBuilder;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.internal.ManagedSessionContext;

import java.util.HashMap;
import java.util.Map;

public class SqlHistoryPersistenceManager extends OrientDbPersistenceManager {
    private final HibernateConfig hibernateConfig;

    private transient volatile SessionFactory sessionFactory;

    private final Map<TransactionArea, TransactionManager> transactionManagerMap = new HashMap<>();

    public SqlHistoryPersistenceManager(
            PersistenceConfig persistenceConfig, OrientDbConfig orientDbConfig, HibernateConfig hibernateConfig,
            NetworkConfig networkConfig) {
        super(persistenceConfig, orientDbConfig, networkConfig);
        this.hibernateConfig = hibernateConfig;
    }

    @Override
    public TransactionManager getTransactionManager() {
        return getTransactionManager(TransactionArea.COMMON);
    }

    @Override
    public TransactionManager getTransactionManager(TransactionArea area) {
        @SuppressWarnings("unchecked")
        FramedGraphFactory<DelegatingFramedGraph<?>> orientDbFactory = lazyCreateGraphFactory();
        lazyCreateHibernateSessionFactory();
        synchronized (transactionManagerMap) {
            return transactionManagerMap.computeIfAbsent(
                    area, needle -> makeTransactionManager(orientDbFactory, area));
        }
    }

    @Override
    public RepositoryFactory getRepositoryFactory() {
        DelegateTransactionManagerFactory transactionManagerFactory = new DelegateTransactionManagerFactory(this);
        return new SqlHistoryRepositoryFactory(
                lazyCreateGraphFactory(), networkConfig, transactionManagerFactory, sessionFactory);
    }

    private TransactionManager makeTransactionManager(
            FramedGraphFactory<DelegatingFramedGraph<?>> orientDbFactory, TransactionArea area) {
        if (area == TransactionArea.HISTORY) {
            return makeHibernateTransactionManager(area);
        }
        return makeOrientDbTransactionManager(orientDbFactory, area);
    }

    private TransactionManager makeHibernateTransactionManager(TransactionArea area) {
        HibernateTransactionAdapterFactory adapterFactory = new HibernateTransactionAdapterFactory(
                area, sessionFactory);
        return new TransactionManager(
                adapterFactory,
                persistenceConfig.getTransactionRetriesLimit(), persistenceConfig.getTransactionRetriesMaxDelay());
    }

    private SessionFactory lazyCreateHibernateSessionFactory() {
        if (sessionFactory == null) {
            synchronized (this) {
                if (sessionFactory == null) {
                    sessionFactory = makeHibernateSessionFactory();
                }
            }
        }
        return sessionFactory;
    }

    private SessionFactory makeHibernateSessionFactory() {
        StandardServiceRegistry standardRegistry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.DRIVER, hibernateConfig.getDriverClass())
                .applySetting(AvailableSettings.USER, hibernateConfig.getUser())
                .applySetting(AvailableSettings.PASS, hibernateConfig.getPassword())
                .applySetting(AvailableSettings.URL, hibernateConfig.getUser())
                .applySetting(AvailableSettings.CURRENT_SESSION_CONTEXT_CLASS, ManagedSessionContext.class.getName())
                .build();

        MetadataSources sources = new MetadataSources(standardRegistry);
        MetadataBuilder metadataBuilder = sources.getMetadataBuilder();
        Metadata metadata = metadataBuilder.build();

        SessionFactoryBuilder sessionFactoryBuilder = metadata.getSessionFactoryBuilder();

        return sessionFactoryBuilder.build();
    }
}
