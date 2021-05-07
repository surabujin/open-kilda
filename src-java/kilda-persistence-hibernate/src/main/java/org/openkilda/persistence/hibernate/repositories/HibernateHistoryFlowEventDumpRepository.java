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

package org.openkilda.persistence.hibernate.repositories;

import org.openkilda.model.history.FlowDump;
import org.openkilda.model.history.FlowDump.FlowDumpCloner;
import org.openkilda.model.history.FlowDump.FlowDumpData;
import org.openkilda.persistence.hibernate.entities.history.FlowEventDump;
import org.openkilda.persistence.repositories.history.FlowDumpRepository;
import org.openkilda.persistence.tx.TransactionManager;

import org.hibernate.SessionFactory;

public class HibernateHistoryFlowEventDumpRepository
        extends HibernateGenericRepository<FlowDump, FlowDumpData, FlowEventDump> implements FlowDumpRepository {
    private final HibernateHistoryFlowEventRepository flowEventRepository;

    public HibernateHistoryFlowEventDumpRepository(
            TransactionManager transactionManager, SessionFactory sessionFactory,
            HibernateHistoryFlowEventRepository flowEventRepository) {
        super(transactionManager, sessionFactory);
        this.flowEventRepository = flowEventRepository;
    }

    @Override
    protected FlowEventDump makeEntity(FlowDumpData view) {
        FlowEventDump entity = new FlowEventDump();
        FlowDumpCloner.INSTANCE.copy(view, entity);
        entity.setFlowEventId(flowEventRepository.lookupEventIdByTaskId(view.getTaskId()).orElse(null));
        return entity;
    }

    @Override
    protected FlowDumpData doDetach(FlowDump model, FlowEventDump entity) {
        return FlowDumpCloner.INSTANCE.deepCopy(entity);
    }
}
