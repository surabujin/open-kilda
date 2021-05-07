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

import org.openkilda.model.history.FlowHistory;
import org.openkilda.model.history.FlowHistory.FlowHistoryCloner;
import org.openkilda.model.history.FlowHistory.FlowHistoryData;
import org.openkilda.persistence.hibernate.entities.history.FlowEventAction;
import org.openkilda.persistence.repositories.history.FlowHistoryRepository;
import org.openkilda.persistence.tx.TransactionManager;

import org.hibernate.SessionFactory;

public class HibernateHistoryFlowEventActionRepository
        extends HibernateGenericRepository<FlowHistory, FlowHistoryData, FlowEventAction>
        implements FlowHistoryRepository {
    private final HibernateHistoryFlowEventRepository flowEventRepository;

    public HibernateHistoryFlowEventActionRepository(
            TransactionManager transactionManager, SessionFactory sessionFactory,
            HibernateHistoryFlowEventRepository flowEventRepository) {
        super(transactionManager, sessionFactory);
        this.flowEventRepository = flowEventRepository;
    }

    @Override
    protected FlowEventAction makeEntity(FlowHistoryData view) {
        FlowEventAction entity = new FlowEventAction();
        FlowHistoryCloner.INSTANCE.copy(view, entity);
        entity.setFlowEventId(flowEventRepository.lookupEventIdByTaskId(view.getTaskId()).orElse(null));
        return entity;
    }

    @Override
    protected FlowHistoryData doDetach(FlowHistory model, FlowEventAction entity) {
        return FlowHistoryCloner.INSTANCE.deepCopy(entity);
    }
}
