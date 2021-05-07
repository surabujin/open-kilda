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
import org.openkilda.model.history.FlowEvent.FlowEventCloner;
import org.openkilda.model.history.FlowEvent.FlowEventData;
import org.openkilda.model.history.FlowHistory;
import org.openkilda.model.history.FlowHistory.FlowHistoryCloner;
import org.openkilda.model.history.FlowStatusView;
import org.openkilda.persistence.exceptions.PersistenceException;
import org.openkilda.persistence.hibernate.entities.history.FlowEvent;
import org.openkilda.persistence.hibernate.entities.history.FlowEventAction;
import org.openkilda.persistence.hibernate.entities.history.FlowEventDump;
import org.openkilda.persistence.hibernate.entities.history.FlowEvent_;
import org.openkilda.persistence.repositories.history.FlowEventRepository;
import org.openkilda.persistence.tx.TransactionManager;

import org.hibernate.SessionFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

public class HibernateHistoryFlowEventRepository
        extends HibernateGenericRepository<org.openkilda.model.history.FlowEvent, FlowEventData, FlowEvent>
        implements FlowEventRepository {
    public HibernateHistoryFlowEventRepository(TransactionManager transactionManager, SessionFactory sessionFactory) {
        super(transactionManager, sessionFactory);
    }

    @Override
    public boolean existsByTaskId(String taskId) {
        return fetchByTaskId(taskId).isPresent();
    }

    @Override
    public Optional<org.openkilda.model.history.FlowEvent> findByTaskId(String taskId) {
        return fetchByTaskId(taskId).map(org.openkilda.model.history.FlowEvent::new);
    }

    @Override
    public List<org.openkilda.model.history.FlowEvent> findByFlowIdAndTimeFrame(
            String flowId, Instant timeFrom, Instant timeTo, int maxCount) {
        return fetch(flowId, timeFrom, timeTo, maxCount).stream()
                .map(org.openkilda.model.history.FlowEvent::new)
                .collect(Collectors.toList());
    }

    @Override
    public List<FlowStatusView> findFlowStatusesByFlowIdAndTimeFrame(
            String flowId, Instant timeFrom, Instant timeTo, int maxCount) {
        return fetch(flowId, timeFrom, timeTo, maxCount).stream()
                .flatMap(entry -> entry.getActions().stream())
                .map(this::extractStatusUpdates)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    public Optional<Long> lookupEventIdByTaskId(String taskId) {
        return fetchByTaskId(taskId).map(FlowEvent::getId);
    }

    @Override
    protected FlowEvent makeEntity(FlowEventData view) {
        FlowEvent entity = new FlowEvent();
        FlowEventCloner.INSTANCE.copyWithoutRecordsAndDumps(view, entity);

        for (FlowHistory entry : view.getHistoryRecords()) {
            FlowEventAction action = new FlowEventAction();
            FlowHistoryCloner.INSTANCE.copy(entry.getData(), action);
            entity.addAction(action);
        }

        for (FlowDump entry : view.getFlowDumps()) {
            FlowEventDump dump = new FlowEventDump();
            FlowDumpCloner.INSTANCE.copy(entry.getData(), dump);
            entity.addDump(dump);
        }

        return entity;
    }

    @Override
    protected FlowEventData doDetach(org.openkilda.model.history.FlowEvent model, FlowEvent entity) {
        return FlowEventCloner.INSTANCE.deepCopy(entity);
    }

    private List<FlowEvent> fetch(String flowId, Instant timeFrom, Instant timeTo, int maxCount) {
        CriteriaBuilder builder = getSession().getCriteriaBuilder();
        CriteriaQuery<FlowEvent> query = builder.createQuery(FlowEvent.class);
        Root<FlowEvent> root = query.from(FlowEvent.class);
        query.select(root);
        query.where(makeQueryFilter(root, flowId, timeFrom, timeTo).toArray(new Predicate[0]));
        query.orderBy(builder.asc(root.get(FlowEvent_.timeCreated)));
        return getSession().createQuery(query).setMaxResults(maxCount).getResultList();
    }

    private Optional<FlowEvent> fetchByTaskId(String taskId) {
        CriteriaBuilder builder = getSession().getCriteriaBuilder();
        CriteriaQuery<FlowEvent> query = builder.createQuery(FlowEvent.class);
        Root<FlowEvent> root = query.from(FlowEvent.class);
        query.select(root);
        query.where(builder.equal(root.get(FlowEvent_.taskId), taskId));
        List<FlowEvent> results = getSession().createQuery(query).getResultList();

        if (1 < results.size()) {
            throw new PersistenceException(String.format(
                    "Unique constraint violation on field %s of %s", FlowEvent_.taskId, FlowEvent.class.getName()));
        }
        if (! results.isEmpty()) {
            return Optional.of(results.get(0));
        }
        return Optional.empty();
    }

    private List<Predicate> makeQueryFilter(Root<FlowEvent> root, String flowId, Instant timeFrom, Instant timeTo) {
        List<Predicate> filters = new ArrayList<>(3);
        CriteriaBuilder builder = getSession().getCriteriaBuilder();
        filters.add(builder.equal(root.get(FlowEvent_.flowId), flowId));
        if (timeFrom != null) {
            filters.add(builder.greaterThanOrEqualTo(root.get(FlowEvent_.timeCreated), timeFrom));
        }
        if (timeTo != null) {
            filters.add(builder.lessThan(root.get(FlowEvent_.timeCreated), timeTo));
        }
        return filters;
    }

    private Optional<FlowStatusView> extractStatusUpdates(FlowEventAction actionEntry) {
        String action = actionEntry.getAction();
        if (action.equals(org.openkilda.model.history.FlowEvent.FLOW_DELETED_ACTION)) {
            return Optional.of(new FlowStatusView(actionEntry.getTimestamp(), "DELETED"));
        }
        for (String actionPart : org.openkilda.model.history.FlowEvent.FLOW_STATUS_ACTION_PARTS) {
            if (action.contains(actionPart)) {
                return Optional.of(new FlowStatusView(actionEntry.getTimestamp(),
                        action.replace(actionPart, "")));
            }
        }

        return Optional.empty();
    }
}
