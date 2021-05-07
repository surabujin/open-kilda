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

package org.openkilda.persistence.spi;

import org.openkilda.config.provider.ConfigurationProvider;
import org.openkilda.persistence.PersistenceConfig;

import java.util.ServiceLoader;
import java.util.function.Supplier;

public class PersistenceProviderSupplier implements Supplier<PersistenceProvider> {
    private static PersistenceProviderSupplier ACTIVE = null;

    private final PersistenceProvider implementation;

    /**
     * Create new {@link PersistenceProvider} using data provided into {@link ConfigurationProvider}.
     */
    public static PersistenceProvider pull(ConfigurationProvider configurationProvider) {
        PersistenceConfig persistenceConfig = configurationProvider.getConfiguration(PersistenceConfig.class);
        ACTIVE = new PersistenceProviderSupplier(persistenceConfig.getImplementationName());
        return ACTIVE.get();
    }

    public static void push(PersistenceProvider implementation) {
        ACTIVE = new PersistenceProviderSupplier(implementation);
    }

    public static void clear() {
        ACTIVE = null;
    }

    public static PersistenceProvider getActive() {
        return ACTIVE.get();
    }

    /**
     * Locate and init a {@link PersistenceProvider} instance. The provider is loaded using the {@link
     * ServiceLoader#load(Class)} method.
     *
     * @see ServiceLoader
     */
    public PersistenceProviderSupplier(String implementationName) {
        this(loadByServiceLoader(implementationName));
    }

    public PersistenceProviderSupplier(PersistenceProvider implementation) {
        this.implementation = implementation;
    }

    @Override
    public PersistenceProvider get() {
        return implementation;
    }

    private static PersistenceProvider loadByServiceLoader(String implementationName) {
        ServiceLoader<PersistenceProvider> loader = ServiceLoader.load(PersistenceProvider.class);

        String normalizedName = implementationName.toLowerCase();
        PersistenceProvider needle = null;
        for (PersistenceProvider entry : loader) {
            if (normalizedName.equals(entry.getImplementationName().toLowerCase())) {
                if (needle != null) {
                    throw new IllegalStateException(String.format(
                            "Locate more than 1 persistence provider implementation names as \"%s\"",
                            implementationName));
                }
                needle = entry;
            }
        }
        if (needle == null) {
            throw new IllegalStateException("No implementation for PersistenceProvider found.");
        }

        return needle;
    }
}
