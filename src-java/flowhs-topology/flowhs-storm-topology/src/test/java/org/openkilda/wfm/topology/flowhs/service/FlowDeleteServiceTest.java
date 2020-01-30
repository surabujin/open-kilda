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
import org.mockito.hamcrest.MockitoHamcrest;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.Optional;

@RunWith(MockitoJUnitRunner.class)
public class FlowDeleteServiceTest extends AbstractFlowTest {
    private static final int TRANSACTION_RETRIES_LIMIT = 3;
    private static final int SPEAKER_COMMAND_RETRIES_LIMIT = 3;
    private static final String FLOW_ID = "TEST_FLOW";
    private static final PathId FORWARD_FLOW_PATH = new PathId(FLOW_ID + "_forward");
    private static final PathId REVERSE_FLOW_PATH = new PathId(FLOW_ID + "_reverse");

    @Mock
    private FlowDeleteHubCarrier carrier;
    @Mock
    private CommandContext commandContext;

    @Before
    public void setUp() {
        RepositoryFactory repositoryFactory = mock(RepositoryFactory.class);
        when(repositoryFactory.createFlowRepository()).thenReturn(flowRepository);
        when(repositoryFactory.createFlowPathRepository()).thenReturn(flowPathRepository);
        when(repositoryFactory.createFeatureTogglesRepository()).thenReturn(featureTogglesRepository);

        IslRepository islRepository = mock(IslRepository.class);
        when(repositoryFactory.createIslRepository()).thenReturn(islRepository);

        FlowEventRepository flowEventRepository = mock(FlowEventRepository.class);
        when(flowEventRepository.existsByTaskId(any())).thenReturn(false);
        when(repositoryFactory.createFlowEventRepository()).thenReturn(flowEventRepository);

        when(persistenceManagerMock.getRepositoryFactory()).thenReturn(repositoryFactory);

        doAnswer(getSpeakerCommandsAnswer()).when(carrier).sendSpeakerRequest(any());
    }

    @Test
    public void shouldFailDeleteFlowIfNoFlowFound() {
        FlowDeleteService deleteService = new FlowDeleteService(carrier, persistenceManager,
                flowResourcesManagerMock, TRANSACTION_RETRIES_LIMIT, SPEAKER_COMMAND_RETRIES_LIMIT);

        deleteService.handleRequest("test_key", commandContext, FLOW_ID);

        verify(carrier, never()).sendSpeakerRequest(any());
        verifyNorthboundResponseType(carrier, ErrorType.NOT_FOUND);
    }

    @Test
    public void shouldFailDeleteFlowOnLockedFlow() {
        Flow flow = make2SwitchFlow();
        flow.setStatus(FlowStatus.IN_PROGRESS);
        flushFlowChanges(flow);

        FlowDeleteService deleteService = new FlowDeleteService(carrier, persistenceManager,
                flowResourcesManagerMock, TRANSACTION_RETRIES_LIMIT, SPEAKER_COMMAND_RETRIES_LIMIT);

        deleteService.handleRequest("test_key", commandContext, FLOW_ID);

        verify(carrier, never()).sendSpeakerRequest(any());
        // TODO(surabujin): should we use other error type here
        verifyNorthboundResponseType(carrier, ErrorType.NOT_FOUND);
    }

    @Test
    public void shouldCompleteDeleteOnLockedSwitches() {
        String flowId = make2SwitchFlow().getFlowId();

        FlowPathRepository repository = setupFlowPathRepositorySpy();
        doThrow(new RecoverablePersistenceException("Must fail"))
                .when(repository).lockInvolvedSwitches(any(), any());

        FlowDeleteService deleteService = new FlowDeleteService(carrier, persistenceManager,
                flowResourcesManagerMock, TRANSACTION_RETRIES_LIMIT, SPEAKER_COMMAND_RETRIES_LIMIT);

        deleteService.handleRequest("test_key", commandContext, flowId);

        Flow flow = fetchFlow(flowId);
        assertEquals(FlowStatus.IN_PROGRESS, flow.getStatus());
        verify(carrier, times(1)).sendNorthboundResponse(any());

        FlowSegmentRequest flowRequest;
        while ((flowRequest = requests.poll()) != null) {
            deleteService.handleAsyncResponse("test_key", SpeakerFlowSegmentResponse.builder()
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
    public void shouldCompleteDeleteOnUnsuccessfulRuleRemoval() {
        Flow flow = build2SwitchFlow();
        buildFlowResources();

        FlowDeleteService deleteService = new FlowDeleteService(carrier, persistenceManagerMock,
                flowResourcesManagerMock, TRANSACTION_RETRIES_LIMIT, SPEAKER_COMMAND_RETRIES_LIMIT);

        deleteService.handleRequest("test_key", commandContext, FLOW_ID);

        assertEquals(FlowStatus.IN_PROGRESS, flow.getStatus());
        verify(carrier, times(1)).sendNorthboundResponse(any());

        FlowSegmentRequest flowRequest;
        while ((flowRequest = requests.poll()) != null) {
            deleteService.handleAsyncResponse("test_key", FlowErrorResponse.errorBuilder()
                    .errorCode(ErrorCode.UNKNOWN)
                    .description("Switch is unavailable")
                    .commandId(flowRequest.getCommandId())
                    .metadata(flowRequest.getMetadata())
                    .switchId(flowRequest.getSwitchId())
                    .messageContext(flowRequest.getMessageContext())
                    .build());
        }

        // 4 times sending 4 rules = 16 requests.
        verify(carrier, times(16)).sendSpeakerRequest(any());
        verify(flowPathRepository, times(1)).delete(MockitoHamcrest.argThat(
                Matchers.hasProperty("pathId", is(FORWARD_FLOW_PATH))));
        verify(flowPathRepository, times(1)).delete(MockitoHamcrest.argThat(
                Matchers.hasProperty("pathId", is(REVERSE_FLOW_PATH))));
        verify(flowResourcesManagerMock, times(1)).deallocatePathResources(MockitoHamcrest.argThat(
                Matchers.hasProperty("forward",
                        Matchers.<PathResources>hasProperty("pathId", is(FORWARD_FLOW_PATH)))));
        verify(flowRepository, times(1)).delete(eq(flow));
    }

    @Test
    public void shouldCompleteDeleteOnTimeoutRuleRemoval() {
        Flow flow = build2SwitchFlow();
        buildFlowResources();

        FlowDeleteService deleteService = new FlowDeleteService(carrier, persistenceManagerMock,
                flowResourcesManagerMock, TRANSACTION_RETRIES_LIMIT, SPEAKER_COMMAND_RETRIES_LIMIT);

        deleteService.handleRequest("test_key", commandContext, FLOW_ID);

        assertEquals(FlowStatus.IN_PROGRESS, flow.getStatus());
        verify(carrier, times(1)).sendNorthboundResponse(any());

        FlowSegmentRequest flowRequest;
        while ((flowRequest = requests.poll()) != null) {
            deleteService.handleAsyncResponse("test_key", FlowErrorResponse.errorBuilder()
                    .errorCode(ErrorCode.OPERATION_TIMED_OUT)
                    .description("Switch is unavailable")
                    .commandId(flowRequest.getCommandId())
                    .metadata(flowRequest.getMetadata())
                    .switchId(flowRequest.getSwitchId())
                    .messageContext(flowRequest.getMessageContext())
                    .build());
        }

        // 4 times sending 4 rules = 16 requests.
        verify(carrier, times(16)).sendSpeakerRequest(any());
        verify(flowPathRepository, times(1)).delete(MockitoHamcrest.argThat(
                Matchers.hasProperty("pathId", is(FORWARD_FLOW_PATH))));
        verify(flowPathRepository, times(1)).delete(MockitoHamcrest.argThat(
                Matchers.hasProperty("pathId", is(REVERSE_FLOW_PATH))));
        verify(flowResourcesManagerMock, times(1)).deallocatePathResources(MockitoHamcrest.argThat(
                Matchers.hasProperty("forward",
                        Matchers.<PathResources>hasProperty("pathId", is(FORWARD_FLOW_PATH)))));
        verify(flowRepository, times(1)).delete(eq(flow));
    }

    @Test
    public void shouldFailDeleteOnTimeoutDuringRuleRemoval() {
        Flow flow = build2SwitchFlow();
        buildFlowResources();

        FlowDeleteService deleteService = new FlowDeleteService(carrier, persistenceManagerMock,
                flowResourcesManagerMock, TRANSACTION_RETRIES_LIMIT, SPEAKER_COMMAND_RETRIES_LIMIT);

        deleteService.handleRequest("test_key", commandContext, FLOW_ID);

        assertEquals(FlowStatus.IN_PROGRESS, flow.getStatus());
        verify(carrier, times(1)).sendNorthboundResponse(any());

        deleteService.handleTimeout("test_key");

        verify(carrier, times(4)).sendSpeakerRequest(any());
        verify(flowResourcesManagerMock, never()).deallocatePathResources(any());
        verify(flowPathRepository, never()).delete(any());
        verify(flowRepository, never()).delete(any());
    }

    @Test
    public void shouldCompleteDeleteOnErrorDuringCompletingFlowPathRemoval() {
        Flow flow = build2SwitchFlow();
        buildFlowResources();

        FlowDeleteService deleteService = new FlowDeleteService(carrier, persistenceManagerMock,
                flowResourcesManagerMock, TRANSACTION_RETRIES_LIMIT, SPEAKER_COMMAND_RETRIES_LIMIT);

        deleteService.handleRequest("test_key", commandContext, FLOW_ID);

        assertEquals(FlowStatus.IN_PROGRESS, flow.getStatus());
        verify(carrier, times(1)).sendNorthboundResponse(any());

        doThrow(new RuntimeException("A persistence error"))
                .when(flowPathRepository).delete(MockitoHamcrest.argThat(
                Matchers.hasProperty("pathId", is(FORWARD_FLOW_PATH))));

        FlowSegmentRequest flowRequest;
        while ((flowRequest = requests.poll()) != null) {
            deleteService.handleAsyncResponse("test_key", SpeakerFlowSegmentResponse.builder()
                    .messageContext(flowRequest.getMessageContext())
                    .commandId(flowRequest.getCommandId())
                    .metadata(flowRequest.getMetadata())
                    .switchId(flowRequest.getSwitchId())
                    .success(true)
                    .build());
        }

        verify(carrier, times(4)).sendSpeakerRequest(any());
        verify(flowResourcesManagerMock, times(1)).deallocatePathResources(MockitoHamcrest.argThat(
                Matchers.hasProperty("forward",
                        Matchers.<PathResources>hasProperty("pathId", is(FORWARD_FLOW_PATH)))));
        verify(flowRepository, times(1)).delete(eq(flow));
    }

    @Test
    public void shouldCompleteDeleteOnErrorDuringResourceDeallocation() {
        Flow flow = build2SwitchFlow();
        buildFlowResources();

        FlowDeleteService deleteService = new FlowDeleteService(carrier, persistenceManagerMock,
                flowResourcesManagerMock, TRANSACTION_RETRIES_LIMIT, SPEAKER_COMMAND_RETRIES_LIMIT);

        deleteService.handleRequest("test_key", commandContext, FLOW_ID);

        assertEquals(FlowStatus.IN_PROGRESS, flow.getStatus());
        verify(carrier, times(1)).sendNorthboundResponse(any());

        doThrow(new RuntimeException("A persistence error"))
                .when(flowResourcesManagerMock).deallocatePathResources(MockitoHamcrest.argThat(
                Matchers.hasProperty("forward",
                        Matchers.<PathResources>hasProperty("pathId", is(FORWARD_FLOW_PATH)))));

        FlowSegmentRequest flowRequest;
        while ((flowRequest = requests.poll()) != null) {
            deleteService.handleAsyncResponse("test_key", SpeakerFlowSegmentResponse.builder()
                    .messageContext(flowRequest.getMessageContext())
                    .commandId(flowRequest.getCommandId())
                    .metadata(flowRequest.getMetadata())
                    .switchId(flowRequest.getSwitchId())
                    .success(true)
                    .build());
        }

        verify(carrier, times(4)).sendSpeakerRequest(any());
        verify(flowPathRepository, times(1)).delete(MockitoHamcrest.argThat(
                Matchers.hasProperty("pathId", is(FORWARD_FLOW_PATH))));
        verify(flowPathRepository, times(1)).delete(MockitoHamcrest.argThat(
                Matchers.hasProperty("pathId", is(REVERSE_FLOW_PATH))));
        verify(flowResourcesManagerMock, times(1)).deallocatePathResources(MockitoHamcrest.argThat(
                Matchers.hasProperty("forward",
                        Matchers.<PathResources>hasProperty("pathId", is(FORWARD_FLOW_PATH)))));
        verify(flowRepository, times(1)).delete(eq(flow));
    }

    @Test
    public void shouldCompleteDeleteOnErrorDuringRemovingFlow() {
        Flow flow = build2SwitchFlow();
        buildFlowResources();

        FlowDeleteService deleteService = new FlowDeleteService(carrier, persistenceManagerMock,
                flowResourcesManagerMock, TRANSACTION_RETRIES_LIMIT, SPEAKER_COMMAND_RETRIES_LIMIT);

        deleteService.handleRequest("test_key", commandContext, FLOW_ID);

        assertEquals(FlowStatus.IN_PROGRESS, flow.getStatus());
        verify(carrier, times(1)).sendNorthboundResponse(any());

        doThrow(new RuntimeException("A persistence error"))
                .when(flowRepository).delete(eq(flow));

        FlowSegmentRequest flowRequest;
        while ((flowRequest = requests.poll()) != null) {
            deleteService.handleAsyncResponse("test_key", SpeakerFlowSegmentResponse.builder()
                    .messageContext(flowRequest.getMessageContext())
                    .commandId(flowRequest.getCommandId())
                    .metadata(flowRequest.getMetadata())
                    .switchId(flowRequest.getSwitchId())
                    .success(true)
                    .build());
        }

        verify(carrier, times(4)).sendSpeakerRequest(any());
        verify(flowPathRepository, times(1)).delete(MockitoHamcrest.argThat(
                Matchers.hasProperty("pathId", is(FORWARD_FLOW_PATH))));
        verify(flowPathRepository, times(1)).delete(MockitoHamcrest.argThat(
                Matchers.hasProperty("pathId", is(REVERSE_FLOW_PATH))));
        verify(flowResourcesManagerMock, times(1)).deallocatePathResources(MockitoHamcrest.argThat(
                Matchers.hasProperty("forward",
                        Matchers.<PathResources>hasProperty("pathId", is(FORWARD_FLOW_PATH)))));
        verify(flowRepository, times(1)).delete(eq(flow));
    }

    @Test
    public void shouldSuccessfullyDeleteFlow() {
        Flow flow = build2SwitchFlow();
        buildFlowResources();

        FlowDeleteService deleteService = new FlowDeleteService(carrier, persistenceManagerMock,
                flowResourcesManagerMock, TRANSACTION_RETRIES_LIMIT, SPEAKER_COMMAND_RETRIES_LIMIT);

        deleteService.handleRequest("test_key", commandContext, FLOW_ID);

        assertEquals(FlowStatus.IN_PROGRESS, flow.getStatus());
        verify(carrier, times(1)).sendNorthboundResponse(any());

        FlowSegmentRequest flowRequest;
        while ((flowRequest = requests.poll()) != null) {
            deleteService.handleAsyncResponse("test_key", SpeakerFlowSegmentResponse.builder()
                    .messageContext(flowRequest.getMessageContext())
                    .commandId(flowRequest.getCommandId())
                    .metadata(flowRequest.getMetadata())
                    .switchId(flowRequest.getSwitchId())
                    .success(true)
                    .build());
        }

        verify(carrier, times(4)).sendSpeakerRequest(any());
        verify(flowPathRepository, times(1)).delete(MockitoHamcrest.argThat(
                Matchers.hasProperty("pathId", is(FORWARD_FLOW_PATH))));
        verify(flowPathRepository, times(1)).delete(MockitoHamcrest.argThat(
                Matchers.hasProperty("pathId", is(REVERSE_FLOW_PATH))));
        verify(flowResourcesManagerMock, times(1)).deallocatePathResources(MockitoHamcrest.argThat(
                Matchers.hasProperty("forward",
                        Matchers.<PathResources>hasProperty("pathId", is(FORWARD_FLOW_PATH)))));
        verify(flowRepository, times(1)).delete(eq(flow));
    }

    private void verifyFlowIsMissing(Flow flow) {
        FlowRepository repository = persistenceManager.getRepositoryFactory().createFlowRepository();
        Optional<Flow> potentialFlow = repository.findById(flow.getFlowId());
        Assert.assertFalse(potentialFlow.isPresent());

        // TODO(surabujin): maybe we should make more deep scanning for flow related resources and nested objects
    }

    private Flow build2SwitchFlow() {
        Switch src = Switch.builder().switchId(SWITCH_1).build();
        Switch dst = Switch.builder().switchId(SWITCH_2).build();

        Flow flow = Flow.builder().flowId(FLOW_ID)
                .srcSwitch(src).destSwitch(dst)
                .status(FlowStatus.UP)
                .encapsulationType(FlowEncapsulationType.TRANSIT_VLAN)
                .build();

        FlowPath forwardPath = FlowPath.builder()
                .pathId(FORWARD_FLOW_PATH)
                .flow(flow)
                .cookie(Cookie.buildForwardCookie(2))
                .srcSwitch(src).destSwitch(dst)
                .status(FlowPathStatus.ACTIVE)
                .build();
        forwardPath.setSegments(Collections.singletonList(PathSegment.builder()
                .srcSwitch(src)
                .srcPort(1)
                .destSwitch(dst)
                .destPort(2)
                .build()));
        flow.setForwardPath(forwardPath);

        FlowPath reversePath = FlowPath.builder()
                .pathId(REVERSE_FLOW_PATH)
                .flow(flow)
                .cookie(Cookie.buildReverseCookie(2))
                .srcSwitch(dst).destSwitch(src)
                .status(FlowPathStatus.ACTIVE)
                .build();
        reversePath.setSegments(Collections.singletonList(PathSegment.builder()
                .srcSwitch(dst)
                .srcPort(2)
                .destSwitch(src)
                .destPort(1)
                .build()));
        flow.setReversePath(reversePath);

        when(flowRepository.findById(any())).thenReturn(Optional.of(flow));
        when(flowRepository.findById(any(), any())).thenReturn(Optional.of(flow));

        doAnswer(invocation -> {
            FlowStatus status = invocation.getArgument(1);
            flow.setStatus(status);
            return null;
        }).when(flowRepository).updateStatus(any(), any());

        return flow;
    }

    private FlowResources buildFlowResources() {
        FlowResources flowResources = FlowResources.builder()
                .unmaskedCookie(1)
                .forward(PathResources.builder()
                        .pathId(FORWARD_FLOW_PATH)
                        .meterId(new MeterId(MeterId.MIN_FLOW_METER_ID + 1))
                        .build())
                .reverse(PathResources.builder()
                        .pathId(REVERSE_FLOW_PATH)
                        .meterId(new MeterId(MeterId.MIN_FLOW_METER_ID + 2))
                        .build())
                .build();

        when(flowResourcesManagerMock.getEncapsulationResources(eq(FORWARD_FLOW_PATH), eq(REVERSE_FLOW_PATH),
                eq(FlowEncapsulationType.TRANSIT_VLAN)))
                .thenReturn(Optional.of(TransitVlanEncapsulation.builder().transitVlan(
                        TransitVlan.builder().flowId(FLOW_ID).pathId(FORWARD_FLOW_PATH).vlan(101).build())
                        .build()));
        when(flowResourcesManagerMock.getEncapsulationResources(eq(REVERSE_FLOW_PATH), eq(FORWARD_FLOW_PATH),
                eq(FlowEncapsulationType.TRANSIT_VLAN)))
                .thenReturn(Optional.of(TransitVlanEncapsulation.builder().transitVlan(
                        TransitVlan.builder().flowId(FLOW_ID).pathId(REVERSE_FLOW_PATH).vlan(102).build())
                        .build()));

        return flowResources;
    }
}
