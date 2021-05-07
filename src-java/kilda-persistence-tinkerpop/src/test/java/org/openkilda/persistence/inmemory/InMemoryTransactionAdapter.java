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

package org.openkilda.persistence.inmemory;

import org.openkilda.persistence.tx.TransactionAdapter;
import org.openkilda.persistence.tx.TransactionArea;

import java.util.Optional;

public class InMemoryTransactionAdapter extends TransactionAdapter {
    private static final ThreadLocal<Integer> fakedTransactions = new ThreadLocal<>();

    public InMemoryTransactionAdapter(TransactionArea area) {
        super(area);
    }

    public static boolean isFakedTxOpen() {
        return Optional.ofNullable(fakedTransactions.get()).orElse(0) > 0;
    }

    @Override
    public void activate() throws Exception {
        fakedTransactions.set(Optional.ofNullable(fakedTransactions.get()).orElse(0) + 1);
        super.activate();
    }

    @Override
    protected void open() throws Exception {
        // nothing to do here
    }

    @Override
    public void close() throws Exception {
        super.close();

        int txLeft = fakedTransactions.get() - 1;
        if (txLeft < 0) {
            throw new IllegalStateException(String.format(
                    "%s API contract violation - close() call without corresponding activate() call",
                    TransactionAdapter.class.getName()));
        }
        if (txLeft < 1) {
            fakedTransactions.remove();
        }
        fakedTransactions.set(txLeft);
    }

    @Override
    protected void commit() throws Exception {
        // nothing to do here
    }

    @Override
    protected void rollback() throws Exception {
        // nothing to do here
    }
}
