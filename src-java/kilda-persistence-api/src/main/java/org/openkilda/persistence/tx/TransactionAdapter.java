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

package org.openkilda.persistence.tx;

import lombok.AccessLevel;
import lombok.Getter;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

public abstract class TransactionAdapter {
    private static final ThreadLocal<ActiveAdapters> globals = new ThreadLocal<>();

    @Getter(AccessLevel.PROTECTED)
    private final TransactionArea area;

    public TransactionAdapter(TransactionArea area) {
        this.area = area;
    }

    /**
     * Call {@code open()} if transaction for specific area should be opened by this adapter.
     */
    public void activate() throws Exception {
        if (initAdapters().add(area, this)) {
            open();
        }
    }

    public boolean isOpen() {
        ActiveAdapters adapters = getAdapters();
        return adapters != null && adapters.size() > 0;
    }

    /**
     * Set success maker on current transactions set.
     */
    public void markSuccess() {
        ActiveAdapters adapters = getAdapters();
        if (adapters != null) {
            adapters.markSuccess();
        }
    }

    /**
     * Set failed marker on current transactions set.
     */
    public void markFail() {
        ActiveAdapters adapters = getAdapters();
        if (adapters != null) {
            adapters.markFail();
        }
    }

    /**
     * Close all active transaction/adapters/areas if it root adapter.
     */
    public void close() throws Exception {
        ActiveAdapters adapters = getAdapters();
        if (adapters == null) {
            throw new IllegalStateException(String.format("%s was not activated", getClass().getName()));
        }
        if (adapters.close(this)) {
            globals.remove();
        }
    }

    public Exception wrapException(Exception ex) {
        return ex;
    }

    protected abstract void open() throws Exception;

    protected abstract void commit() throws Exception;

    protected abstract void rollback() throws Exception;

    private ActiveAdapters getAdapters() {
        return globals.get();
    }

    private ActiveAdapters initAdapters() {
        ActiveAdapters adapters = globals.get();
        if (adapters == null) {
            adapters = new ActiveAdapters(this);
            globals.set(adapters);
        }
        return adapters;
    }

    private static final class ActiveAdapters {
        private final TransactionAdapter root;

        private boolean success = false;
        private boolean fail = false;

        private final Set<TransactionArea> openAreas = new HashSet<>();
        private final LinkedList<TransactionAdapter> adapters = new LinkedList<>();

        private ActiveAdapters(TransactionAdapter root) {
            this.root = root;
        }

        public boolean add(TransactionArea area, TransactionAdapter entry) {
            if (openAreas.add(area)) {
                adapters.addFirst(entry);  // the later it is open, the earlier it is closed
                return true;
            }
            return false;
        }

        public boolean close(TransactionAdapter current) throws Exception {
            if (current != root) {
                return false;
            }

            boolean canCommit = fail || success;
            for (TransactionAdapter entry : adapters) {
                if (canCommit) {
                    entry.commit();
                } else {
                    entry.rollback();
                }
            }

            adapters.clear();
            openAreas.clear();
            return true;
        }

        public void markSuccess() {
            success = true;
        }

        public void markFail() {
            fail = true;
        }

        public int size() {
            return openAreas.size();
        }
    }
}
