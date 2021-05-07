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

import org.openkilda.model.SwitchId;
import org.openkilda.model.history.PortHistory;
import org.openkilda.model.history.PortHistory.PortHistoryData;
import org.openkilda.persistence.hibernate.entities.history.PortEvent;
import org.openkilda.persistence.hibernate.entities.history.PortEvent_;
import org.openkilda.persistence.repositories.history.PortHistoryRepository;
import org.openkilda.persistence.tx.TransactionManager;

import org.hibernate.SessionFactory;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;

public class HibernateHistoryPortEventRepository
        extends HibernateGenericRepository<PortHistory, PortHistoryData, PortEvent> implements PortHistoryRepository {
    public HibernateHistoryPortEventRepository(TransactionManager transactionManager, SessionFactory sessionFactory) {
        super(transactionManager, sessionFactory);
    }

    @Override
    public List<PortHistory> findBySwitchIdAndPortNumber(
            SwitchId switchId, int portNumber, Instant start, Instant end) {
        CriteriaBuilder builder = getSession().getCriteriaBuilder();
        CriteriaQuery<PortEvent> query = builder.createQuery(PortEvent.class);
        Root<PortEvent> root = query.from(PortEvent.class);
        query.select(root);
        query.where(builder.and(
                builder.equal(root.get(PortEvent_.switchId), switchId),
                builder.equal(root.get(PortEvent_.portNumber), portNumber)));

        return getSession().createQuery(query).getResultList().stream()
                .map(PortHistory::new)
                .collect(
                        Collectors.toList()
                );
    }

    @Override
    protected PortEvent makeEntity(PortHistoryData view) {
        PortEvent entity = new PortEvent();
        PortHistory.PortHistoryCloner.INSTANCE.copy(view, entity);
        return entity;
    }

    @Override
    protected PortHistoryData doDetach(PortHistory model, PortEvent entity) {
        return PortHistory.PortHistoryCloner.INSTANCE.deepCopy(entity);
    }
}
