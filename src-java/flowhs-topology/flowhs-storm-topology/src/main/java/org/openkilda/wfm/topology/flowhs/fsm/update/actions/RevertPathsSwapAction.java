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

package org.openkilda.wfm.topology.flowhs.fsm.update.actions;

import static java.lang.String.format;

import org.openkilda.model.Flow;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.PathId;
import org.openkilda.persistence.FetchStrategy;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.FlowProcessingAction;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateContext;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.update.FlowUpdateFsm.State;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RevertPathsSwapAction extends FlowProcessingAction<FlowUpdateFsm, State, Event, FlowUpdateContext> {
    public RevertPathsSwapAction(PersistenceManager persistenceManager) {
        super(persistenceManager);
    }

    @Override
    protected void perform(State from, State to, Event event, FlowUpdateContext context, FlowUpdateFsm stateMachine) {
        persistenceManager.getTransactionManager().doInTransaction(() -> {
            Flow flow = getFlow(stateMachine.getFlowId(), FetchStrategy.DIRECT_RELATIONS);

            if (stateMachine.getOldPrimaryForwardPath() != null && stateMachine.getOldPrimaryReversePath() != null) {
                FlowPath oldForward = injectActualFlowPath(stateMachine.getOldPrimaryForwardPath()).getPath();
                if (oldForward.getStatus() != FlowPathStatus.ACTIVE) {
                    flowPathRepository.updateStatus(oldForward.getPathId(),
                            stateMachine.getOldPrimaryForwardPathStatus());
                }

                FlowPath oldReverse = injectActualFlowPath(stateMachine.getOldPrimaryReversePath()).getPath();
                if (oldReverse.getStatus() != FlowPathStatus.ACTIVE) {
                    flowPathRepository.updateStatus(oldReverse.getPathId(),
                            stateMachine.getOldPrimaryReversePathStatus());
                }

                log.debug("Swapping back the primary paths {}/{} with {}/{}",
                        flow.getForwardPath().getPathId(), flow.getReversePath().getPathId(),
                        oldForward.getPathId(), oldReverse.getPathId());

                flow.setForwardPath(oldForward.getPathId());
                flow.setReversePath(oldReverse.getPathId());

                saveHistory(stateMachine, flow.getFlowId(), oldForward.getPathId(), oldReverse.getPathId());
            }

            if (stateMachine.getOldProtectedForwardPath() != null
                    && stateMachine.getOldProtectedReversePath() != null) {
                FlowPath oldForward = injectActualFlowPath(stateMachine.getOldProtectedForwardPath()).getPath();
                if (oldForward.getStatus() != FlowPathStatus.ACTIVE) {
                    flowPathRepository.updateStatus(oldForward.getPathId(),
                            stateMachine.getOldProtectedForwardPathStatus());
                }

                FlowPath oldReverse = injectActualFlowPath(stateMachine.getOldProtectedReversePath()).getPath();
                if (oldReverse.getStatus() != FlowPathStatus.ACTIVE) {
                    flowPathRepository.updateStatus(oldReverse.getPathId(),
                            stateMachine.getOldProtectedReversePathStatus());
                }

                log.debug("Swapping back the protected paths {}/{} with {}/{}",
                        flow.getProtectedForwardPath().getPathId(), flow.getProtectedReversePath().getPathId(),
                        oldForward.getPathId(), oldReverse.getPathId());

                flow.setProtectedForwardPath(oldForward.getPathId());
                flow.setProtectedReversePath(oldReverse.getPathId());

                saveHistory(stateMachine, flow.getFlowId(), oldForward.getPathId(), oldReverse.getPathId());
            }

            flowRepository.createOrUpdate(flow);
        });
    }

    private void saveHistory(FlowUpdateFsm stateMachine, String flowId, PathId forwardPath, PathId reversePath) {
        stateMachine.saveActionToHistory("Flow was reverted to old paths",
                format("The flow %s was updated with paths %s / %s", flowId, forwardPath, reversePath));
    }
}
