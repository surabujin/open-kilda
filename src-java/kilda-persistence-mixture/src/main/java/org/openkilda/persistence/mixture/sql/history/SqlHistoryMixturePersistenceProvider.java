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

import org.openkilda.config.provider.ConfigurationProvider;
import org.openkilda.persistence.NetworkConfig;
import org.openkilda.persistence.PersistenceConfig;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.context.PersistenceContextManager;
import org.openkilda.persistence.hibernate.HibernateConfig;
import org.openkilda.persistence.orientdb.OrientDbConfig;
import org.openkilda.persistence.orientdb.ThreadLocalPersistenceContextHolder;
import org.openkilda.persistence.spi.PersistenceProvider;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SqlHistoryMixturePersistenceProvider implements PersistenceProvider {
    @Override
    public PersistenceManager getPersistenceManager(ConfigurationProvider configurationProvider) {
        PersistenceConfig persistenceConfig = configurationProvider.getConfiguration(PersistenceConfig.class);
        OrientDbConfig orientDbConfig = configurationProvider.getConfiguration(OrientDbConfig.class);
        HibernateConfig hibernateConfig = configurationProvider.getConfiguration(HibernateConfig.class);
        NetworkConfig networkConfig = configurationProvider.getConfiguration(NetworkConfig.class);
        return new SqlHistoryPersistenceManager(persistenceConfig, orientDbConfig, hibernateConfig, networkConfig);
    }

    @Override
    public PersistenceContextManager getPersistenceContextManager() {
        return ThreadLocalPersistenceContextHolder.INSTANCE;
    }

    @Override
    public String getImplementationName() {
        return "orientdb-sql-mixture1";
    }
}
