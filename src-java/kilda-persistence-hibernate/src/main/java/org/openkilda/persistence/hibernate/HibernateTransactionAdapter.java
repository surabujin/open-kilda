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

package org.openkilda.persistence.hibernate;

import org.openkilda.persistence.tx.TransactionAdapter;
import org.openkilda.persistence.tx.TransactionArea;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.context.internal.ManagedSessionContext;

public class HibernateTransactionAdapter extends TransactionAdapter {
    private final SessionFactory sessionFactory;
    private Session session;

    public HibernateTransactionAdapter(TransactionArea area, SessionFactory sessionFactory) {
        super(area);
        this.sessionFactory = sessionFactory;
    }

    @Override
    protected void open() throws Exception {
        session = sessionFactory.openSession();
        session.beginTransaction();
        ManagedSessionContext.bind(session);
    }

    @Override
    protected void commit() throws Exception {
        commitOrRollback(true);
    }

    @Override
    protected void rollback() throws Exception {
        commitOrRollback(false);

    }

    private void commitOrRollback(boolean isSuccess) {
        if (session == null) {
            throw new IllegalStateException("The session was not created");
        }

        Transaction transaction = session.getTransaction();
        if (isSuccess) {
            transaction.commit();
        } else {
            transaction.rollback();
        }
    }
}
