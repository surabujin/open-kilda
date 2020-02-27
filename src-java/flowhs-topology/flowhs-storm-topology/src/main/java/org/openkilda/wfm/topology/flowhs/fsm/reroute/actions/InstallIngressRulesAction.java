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

package org.openkilda.wfm.topology.flowhs.fsm.reroute.actions;

import org.openkilda.floodlight.api.request.factory.FlowSegmentRequestFactory;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.model.FlowPathSnapshot;
import org.openkilda.wfm.share.service.FlowCommandBuilderFactory;
import org.openkilda.wfm.topology.flowhs.fsm.common.actions.FlowProcessingAction;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteContext;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.Event;
import org.openkilda.wfm.topology.flowhs.fsm.reroute.FlowRerouteFsm.State;
import org.openkilda.wfm.topology.flowhs.service.FlowCommandBuilder;
import org.openkilda.wfm.topology.flowhs.utils.SpeakerInstallSegmentEmitter;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;

@Slf4j
public class InstallIngressRulesAction extends FlowProcessingAction<FlowRerouteFsm, State, Event, FlowRerouteContext> {
    private final FlowCommandBuilderFactory commandBuilderFactory;

    public InstallIngressRulesAction(PersistenceManager persistenceManager, FlowResourcesManager resourcesManager) {
        super(persistenceManager);
        commandBuilderFactory = new FlowCommandBuilderFactory();
    }

    @Override
    protected void perform(State from, State to, Event event, FlowRerouteContext context, FlowRerouteFsm stateMachine) {
        Flow flow = getFlow(stateMachine.getFlowId());

        FlowEncapsulationType encapsulationType = stateMachine.getNewEncapsulationType() != null
                ? stateMachine.getNewEncapsulationType() : flow.getEncapsulationType();
        FlowCommandBuilder commandBuilder = commandBuilderFactory.getBuilder(encapsulationType);

        Collection<FlowSegmentRequestFactory> requestFactories = new ArrayList<>();
        if (stateMachine.getNewPrimaryForwardPath() != null && stateMachine.getNewPrimaryReversePath() != null) {
            FlowPathSnapshot newForward = getFlowPath(flow, stateMachine.getNewPrimaryForwardPath());
            FlowPathSnapshot newReverse = getFlowPath(flow, stateMachine.getNewPrimaryReversePath());
            requestFactories.addAll(commandBuilder.buildIngressOnly(
                    stateMachine.getCommandContext(), flow, newForward, newReverse));
        }

        // Installation of ingress rules for protected paths is skipped. These paths are activated on swap.

        stateMachine.getIngressCommands().clear();
        SpeakerInstallSegmentEmitter.INSTANCE.emitBatch(
                stateMachine.getCarrier(), requestFactories, stateMachine.getIngressCommands());
        stateMachine.getPendingCommands().addAll(stateMachine.getIngressCommands().keySet());
        stateMachine.getRetriedCommands().clear();

        if (requestFactories.isEmpty()) {
            stateMachine.saveActionToHistory("No need to install ingress rules");
            stateMachine.fire(Event.INGRESS_IS_SKIPPED);
        } else {
            stateMachine.saveActionToHistory("Commands for installing ingress rules have been sent");
        }
    }
}
