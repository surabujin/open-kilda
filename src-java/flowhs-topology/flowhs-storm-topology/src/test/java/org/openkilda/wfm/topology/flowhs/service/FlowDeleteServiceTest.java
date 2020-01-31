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

package org.openkilda.wfm.topology.flowhs.service;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.openkilda.floodlight.api.request.FlowSegmentRequest;
import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.floodlight.flow.response.FlowErrorResponse;
import org.openkilda.floodlight.flow.response.FlowErrorResponse.ErrorCode;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.model.Cookie;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.MeterId;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.Switch;
import org.openkilda.model.TransitVlan;
import org.openkilda.persistence.RecoverablePersistenceException;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.history.FlowEventRepository;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.share.flow.resources.FlowResources.PathResources;
import org.openkilda.wfm.share.flow.resources.transitvlan.TransitVlanEncapsulation;

import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.hamcrest.MockitoHamcrest;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.Optional;

@RunWith(MockitoJUnitRunner.class)
public class FlowDeleteServiceTest extends AbstractFlowTest {
    private static final int TRANSACTION_RETRIES_LIMIT = 3;
    private static final int SPEAKER_COMMAND_RETRIES_LIMIT = 3;

    private final String dummyRequestKey = "test-key";
    private final String injectedErrorMessage = "Unit-test injected failure";

    @Mock
    private FlowDeleteHubCarrier carrier;

    private CommandContext commandContext = new CommandContext();

    @Before
    public void setUp() {
        doAnswer(getSpeakerCommandsAnswer()).when(carrier).sendSpeakerRequest(any());

        // must be done before first service create attempt, because repository objects are cached inside FSM actions
        setupFlowRepositorySpy();
        setupFlowPathRepositorySpy();
    }

    @Test
    public void shouldFailDeleteFlowIfNoFlowFound() {
        String flowId = "dummy-flow";

        // make sure flow is missing
        FlowRepository repository = persistenceManager.getRepositoryFactory().createFlowRepository();
        Assert.assertFalse(repository.findById(flowId).isPresent());

        makeService().handleRequest(dummyRequestKey, commandContext, flowId);

        verify(carrier, never()).sendSpeakerRequest(any());
        verifyNorthboundErrorResponse(carrier, ErrorType.NOT_FOUND);
    }

    @Test
    public void shouldFailDeleteFlowOnLockedFlow() {
        Flow flow = make2SwitchFlow();
        flow.setStatus(FlowStatus.IN_PROGRESS);
        flushFlowChanges(flow);

        makeService().handleRequest(dummyRequestKey, commandContext, flow.getFlowId());

        verify(carrier, never()).sendSpeakerRequest(any());
        verifyNorthboundErrorResponse(carrier, ErrorType.REQUEST_INVALID);
        verifyFlowStatus(flow.getFlowId(), FlowStatus.IN_PROGRESS);
    }

    @Test
    public void shouldCompleteDeleteOnLockedSwitches() {
        String flowId = make2SwitchFlow().getFlowId();

        FlowPathRepository repository = setupFlowPathRepositorySpy();
        doThrow(new RecoverablePersistenceException(injectedErrorMessage))
                .when(repository).lockInvolvedSwitches(any(), any());

        FlowDeleteService service = makeService();
        service.handleRequest(dummyRequestKey, commandContext, flowId);

        Flow flow = verifyFlowStatus(flowId, FlowStatus.IN_PROGRESS);
        verifyNorthboundSuccessResponse(carrier);

        FlowSegmentRequest flowRequest;
        while ((flowRequest = requests.poll()) != null) {
            service.handleAsyncResponse(dummyRequestKey, SpeakerFlowSegmentResponse.builder()
                    .messageContext(flowRequest.getMessageContext())
                    .commandId(flowRequest.getCommandId())
                    .metadata(flowRequest.getMetadata())
                    .switchId(flowRequest.getSwitchId())
                    .success(true)
                    .build());
        }

        verify(carrier, times(4)).sendSpeakerRequest(any());
        verifyFlowIsMissing(flow);
    }

    @Test
    public void shouldCompleteDeleteOnUnsuccessfulSpeakerResponse() {
        testSpeakerErrorResponse(make2SwitchFlow().getFlowId(), ErrorCode.UNKNOWN);
    }

    @Test
    public void shouldCompleteDeleteOnTimeoutSpeakerResponse() {
        testSpeakerErrorResponse(make2SwitchFlow().getFlowId(), ErrorCode.OPERATION_TIMED_OUT);
    }

    private void testSpeakerErrorResponse(String flowId, ErrorCode errorCode) {
        FlowDeleteService service = makeService();
        service.handleRequest(dummyRequestKey, commandContext, flowId);

        Flow flow = verifyFlowStatus(flowId, FlowStatus.IN_PROGRESS);
        verifyNorthboundSuccessResponse(carrier);

        FlowSegmentRequest flowRequest;
        while ((flowRequest = requests.poll()) != null) {
            service.handleAsyncResponse(dummyRequestKey, FlowErrorResponse.errorBuilder()
                    .errorCode(errorCode)
                    .description("Switch is unavailable")
                    .commandId(flowRequest.getCommandId())
                    .metadata(flowRequest.getMetadata())
                    .switchId(flowRequest.getSwitchId())
                    .messageContext(flowRequest.getMessageContext())
                    .build());
        }

        // 4 times sending 4 rules = 16 requests.
        verify(carrier, times(16)).sendSpeakerRequest(any());
        verifyFlowIsMissing(flow);
    }

    @Test
    public void shouldFailDeleteOnTimeoutDuringRuleRemoval() {
        String flowId = make2SwitchFlow().getFlowId();

        FlowDeleteService service = makeService();

        service.handleRequest(dummyRequestKey, commandContext, flowId);
        verifyFlowStatus(flowId, FlowStatus.IN_PROGRESS);
        verifyNorthboundSuccessResponse(carrier);

        service.handleTimeout(dummyRequestKey);

        verify(carrier, times(4)).sendSpeakerRequest(any());
        // FIXME(surabujin): flow stays in IN_PROGRESS status, any further request can't be handled.
        //  em... there is no actual handling for timeout event, so FSM stack in memory forever
        verifyFlowStatus(flowId, FlowStatus.IN_PROGRESS);
    }

    @Test
    public void shouldCompleteDeleteOnErrorDuringCompletingFlowPathRemoval() {
        Flow target = make2SwitchFlow();
        FlowPath forwardPath = target.getForwardPath();
        Assert.assertNotNull(forwardPath);

        FlowPathRepository repository = setupFlowPathRepositorySpy();
        doThrow(new RuntimeException(injectedErrorMessage))
                .when(repository)
                .delete(MockitoHamcrest.argThat(
                        Matchers.hasProperty("pathId", is(forwardPath.getPathId()))));

        FlowDeleteService service = makeService();
        service.handleRequest(dummyRequestKey, commandContext, target.getFlowId());

        verifyFlowStatus(target.getFlowId(), FlowStatus.IN_PROGRESS);
        verifyNorthboundSuccessResponse(carrier);

        FlowSegmentRequest flowRequest;
        while ((flowRequest = requests.poll()) != null) {
            service.handleAsyncResponse(dummyRequestKey, SpeakerFlowSegmentResponse.builder()
                    .messageContext(flowRequest.getMessageContext())
                    .commandId(flowRequest.getCommandId())
                    .metadata(flowRequest.getMetadata())
                    .switchId(flowRequest.getSwitchId())
                    .success(true)
                    .build());
        }

        verify(carrier, times(4)).sendSpeakerRequest(any());
        verifyFlowIsMissing(target);
    }

    @Test
    public void shouldCompleteDeleteOnErrorDuringResourceDeallocation() {
        Flow target = make2SwitchFlow();
        FlowPath forwardPath = target.getForwardPath();
        Assert.assertNotNull(forwardPath);

        doThrow(new RuntimeException(injectedErrorMessage))
                .when(flowResourcesManager)
                .deallocatePathResources(MockitoHamcrest.argThat(
                        Matchers.hasProperty("forward",
                                Matchers.<PathResources>hasProperty("pathId", is(forwardPath.getPathId())))));

        FlowDeleteService service = makeService();

        service.handleRequest(dummyRequestKey, commandContext, target.getFlowId());
        verifyFlowStatus(target.getFlowId(), FlowStatus.IN_PROGRESS);
        verifyNorthboundSuccessResponse(carrier);

        FlowSegmentRequest flowRequest;
        while ((flowRequest = requests.poll()) != null) {
            service.handleAsyncResponse(dummyRequestKey, SpeakerFlowSegmentResponse.builder()
                    .messageContext(flowRequest.getMessageContext())
                    .commandId(flowRequest.getCommandId())
                    .metadata(flowRequest.getMetadata())
                    .switchId(flowRequest.getSwitchId())
                    .success(true)
                    .build());
        }

        verify(carrier, times(4)).sendSpeakerRequest(any());
        verifyFlowIsMissing(target);
    }

    @Test
    public void shouldCompleteDeleteOnErrorDuringRemovingFlow() {
        Flow target = make2SwitchFlow();

        FlowRepository repository = setupFlowRepositorySpy();
        doThrow(new RuntimeException(injectedErrorMessage))
                .when(repository)
                .delete(MockitoHamcrest.argThat(
                        Matchers.hasProperty("flowId", is(target.getFlowId()))));

        FlowDeleteService service = makeService();

        service.handleRequest(dummyRequestKey, commandContext, target.getFlowId());
        verifyFlowStatus(target.getFlowId(), FlowStatus.IN_PROGRESS);
        verifyNorthboundSuccessResponse(carrier);

        FlowSegmentRequest flowRequest;
        while ((flowRequest = requests.poll()) != null) {
            service.handleAsyncResponse(dummyRequestKey, SpeakerFlowSegmentResponse.builder()
                    .messageContext(flowRequest.getMessageContext())
                    .commandId(flowRequest.getCommandId())
                    .metadata(flowRequest.getMetadata())
                    .switchId(flowRequest.getSwitchId())
                    .success(true)
                    .build());
        }

        verify(carrier, times(4)).sendSpeakerRequest(any());
        // FIXME(surabujin): one more way to make not removable via kilda-API flow
        verifyFlowStatus(target.getFlowId(), FlowStatus.IN_PROGRESS);
    }

    @Test
    public void shouldSuccessfullyDeleteFlow() {
        Flow target = make2SwitchFlow();

        FlowDeleteService service = makeService();

        service.handleRequest(dummyRequestKey, commandContext, target.getFlowId());
        verifyFlowStatus(target.getFlowId(), FlowStatus.IN_PROGRESS);
        verifyNorthboundSuccessResponse(carrier);

        FlowSegmentRequest flowRequest;
        while ((flowRequest = requests.poll()) != null) {
            service.handleAsyncResponse(dummyRequestKey, SpeakerFlowSegmentResponse.builder()
                    .messageContext(flowRequest.getMessageContext())
                    .commandId(flowRequest.getCommandId())
                    .metadata(flowRequest.getMetadata())
                    .switchId(flowRequest.getSwitchId())
                    .success(true)
                    .build());
        }

        verify(carrier, times(4)).sendSpeakerRequest(any());
        verifyFlowIsMissing(target);
    }

    private void verifyFlowIsMissing(Flow flow) {
        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        FlowRepository flowRepository = repositoryFactory.createFlowRepository();
        FlowPathRepository flowPathRepository = repositoryFactory.createFlowPathRepository();

        Assert.assertFalse(flowRepository.findById(flow.getFlowId()).isPresent());

        for (FlowPath path : flow.getPaths()) {
            Assert.assertFalse(
                    String.format("Flow path %s still exists", path.getPathId()),
                    flowPathRepository.findById(path.getPathId()).isPresent());
        }

        // TODO(surabujin): maybe we should make more deep scanning for flow related resources and nested objects
    }

    private FlowDeleteService makeService() {
        return new FlowDeleteService(
                carrier, persistenceManager, flowResourcesManager,
                TRANSACTION_RETRIES_LIMIT, SPEAKER_COMMAND_RETRIES_LIMIT);
    }
}
