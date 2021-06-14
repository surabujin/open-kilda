/* Copyright 2019 Telstra Open Source
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

package org.openkilda.wfm.share.history.service;

import org.openkilda.model.SwitchId;
import org.openkilda.model.history.FlowDump;
import org.openkilda.model.history.FlowEvent;
import org.openkilda.model.history.FlowHistory;
import org.openkilda.model.history.FlowStatusView;
import org.openkilda.model.history.PortHistory;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.history.FlowDumpRepository;
import org.openkilda.persistence.repositories.history.FlowEventRepository;
import org.openkilda.persistence.repositories.history.FlowHistoryRepository;
import org.openkilda.persistence.repositories.history.PortHistoryRepository;
import org.openkilda.persistence.tx.TransactionManager;
import org.openkilda.wfm.share.history.model.FlowHistoryHolder;
import org.openkilda.wfm.share.history.model.PortHistoryData;
import org.openkilda.wfm.share.mappers.HistoryMapper;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
public class HistoryService {
    private final TransactionManager transactionManager;
    private final FlowEventRepository flowEventRepository;
    private final FlowHistoryRepository flowHistoryRepository;
    private final FlowDumpRepository flowDumpRepository;
    private final PortHistoryRepository portHistoryRepository;

    public HistoryService(PersistenceManager persistenceManager) {
        this(persistenceManager.getTransactionManager(), persistenceManager.getRepositoryFactory());
    }

    public HistoryService(TransactionManager transactionManager, RepositoryFactory repositoryFactory) {
        this.transactionManager = transactionManager;
        flowEventRepository = repositoryFactory.createFlowEventRepository();
        flowHistoryRepository = repositoryFactory.createFlowHistoryRepository();
        flowDumpRepository = repositoryFactory.createFlowDumpRepository();
        portHistoryRepository = repositoryFactory.createPortHistoryRepository();
    }

    /**
     * Save history data into data storage.
     *
     * @param historyHolder holder of history information.
     */
    public void store(FlowHistoryHolder historyHolder) {
        transactionManager.doInTransaction(() -> {
            String taskId = historyHolder.getTaskId();
            if (historyHolder.getFlowEventData() != null) {
                FlowEvent event = HistoryMapper.INSTANCE.map(historyHolder.getFlowEventData());
                event.setTaskId(taskId);
                flowEventRepository.add(event);
            }

            if (historyHolder.getFlowHistoryData() != null) {
                FlowHistory history = HistoryMapper.INSTANCE.map(historyHolder.getFlowHistoryData());
                history.setTaskId(taskId);
                flowHistoryRepository.add(history);
            }

            if (historyHolder.getFlowDumpData() != null) {
                FlowDump dump = HistoryMapper.INSTANCE.map(historyHolder.getFlowDumpData());
                dump.setTaskId(taskId);
                flowDumpRepository.add(dump);
            }
        });
    }

    /**
     * Persist the history record.
     */
    public void store(PortHistoryData data) {
        PortHistory entity = HistoryMapper.INSTANCE.map(data);
        entity.setRecordId(UUID.randomUUID());
        portHistoryRepository.add(entity);
    }

    /**
     * Fetches flow history records by a flow ID and a time period.
     */
    public List<FlowEvent> listFlowEvents(String flowId, Instant timeFrom, Instant timeTo, int maxCount) {
        List<FlowEvent> result = flowEventRepository.findByFlowIdAndTimeFrame(flowId, timeFrom, timeTo, maxCount);
        result.forEach(flowEventRepository::detach);
        return result;
    }

    /**
     * Fetches flow status timestamps by a flow ID and a time period.
     */
    public List<FlowStatusView> getFlowStatusTimestamps(String flowId,
                                                        Instant timeFrom, Instant timeTo, int maxCount) {
        return flowEventRepository.findFlowStatusesByFlowIdAndTimeFrame(flowId, timeFrom, timeTo, maxCount);
    }

    /**
     * Fetches port history records.
     */
    public List<PortHistory> listPortHistory(SwitchId switchId, int portNumber, Instant start, Instant end) {
        List<PortHistory> result = portHistoryRepository.findBySwitchIdAndPortNumber(switchId, portNumber, start, end);
        result.forEach(portHistoryRepository::detach);
        return result;
    }
}
