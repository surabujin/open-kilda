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

package org.openkilda.persistence.inmemory;

import org.openkilda.persistence.NetworkConfig;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.ferma.AnnotationFrameFactoryWithConverterSupport;
import org.openkilda.persistence.ferma.FramedGraphFactory;
import org.openkilda.persistence.inmemory.repositories.InMemoryRepositoryFactory;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.tx.TransactionArea;
import org.openkilda.persistence.tx.TransactionManager;

import com.syncleus.ferma.DelegatingFramedGraph;
import com.syncleus.ferma.typeresolvers.UntypedTypeResolver;
import lombok.extern.slf4j.Slf4j;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;

/**
 * In-memory implementation of {@link PersistenceManager}.
 * Built on top of Tinkerpop / Ferma implementation.
 */
@Slf4j
public class InMemoryGraphPersistenceManager implements PersistenceManager {
    private final NetworkConfig networkConfig;

    private static TinkerGraph tinkerGraph = TinkerGraph.open();
    private static transient volatile FramedGraphFactory<DelegatingFramedGraph<?>> graphFactory;

    public InMemoryGraphPersistenceManager(NetworkConfig networkConfig) {
        this.networkConfig = networkConfig;
    }

    @Override
    public TransactionManager getTransactionManager() {
        return getTransactionManager(TransactionArea.FLAT);
    }

    public TransactionManager getTransactionManager(TransactionArea area) {
        return new TransactionManager(new InMemoryTransactionAdapterFactory(area), 0, 0);
    }

    @Override
    public RepositoryFactory getRepositoryFactory() {
        return new InMemoryRepositoryFactory(getGraphFactory(), getTransactionManager(), networkConfig);
    }

    private FramedGraphFactory<DelegatingFramedGraph<?>> getGraphFactory() {
        if (graphFactory == null) {
            synchronized (InMemoryGraphPersistenceManager.class) {
                if (graphFactory == null) {
                    graphFactory = new FramedGraphFactory<DelegatingFramedGraph<?>>() {
                        final DelegatingFramedGraph<?> framedGraph =
                                new DelegatingFramedGraph<>(tinkerGraph,
                                        new AnnotationFrameFactoryWithConverterSupport(), new UntypedTypeResolver());

                        @Override
                        public DelegatingFramedGraph<?> getGraph() {
                            return framedGraph;
                        }
                    };
                }
            }
        }
        return graphFactory;
    }

    /**
     * Purge in-memory graph data.
     */
    public void purgeData() {
        synchronized (InMemoryGraphPersistenceManager.class) {
            if (tinkerGraph != null) {
                tinkerGraph.clear();
            }
        }
    }
}
