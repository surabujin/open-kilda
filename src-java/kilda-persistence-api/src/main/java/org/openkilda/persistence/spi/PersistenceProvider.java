/* Copyright 2018 Telstra Open Source
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

package org.openkilda.persistence.spi;

import org.openkilda.config.provider.ConfigurationProvider;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.context.PersistenceContextManager;

import java.util.ServiceLoader;

/**
 * A provider for persistence manager(s). SPI is used to locate an implementation.
 *
 * @see ServiceLoader
 */
public interface PersistenceProvider {
    /**
     * Creates a {@link PersistenceManager} for given configuration.
     *
     * @param configurationProvider configuration provider to initialize the manager.
     * @return a {@link PersistenceManager} implementation.
     */
    PersistenceManager getPersistenceManager(ConfigurationProvider configurationProvider);

    /**
     * Obtains a {@link PersistenceContextManager}.
     */
    PersistenceContextManager getPersistenceContextManager();

    String getImplementationName();
}
