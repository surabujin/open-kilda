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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.openkilda.floodlight.api.request.FlowSegmentRequest;
import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.command.flow.FlowRequest;
import org.openkilda.messaging.error.ErrorMessage;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.flow.FlowResponse;
import org.openkilda.model.Cookie;
import org.openkilda.model.FeatureToggles;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.IslEndpoint;
import org.openkilda.model.SwitchId;
import org.openkilda.pce.Path;
import org.openkilda.pce.Path.Segment;
import org.openkilda.pce.PathComputer;
import org.openkilda.pce.PathPair;
import org.openkilda.persistence.Neo4jBasedTest;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.TransactionCallback;
import org.openkilda.persistence.TransactionCallbackWithoutResult;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.dummy.IslDirectionalReference;
import org.openkilda.persistence.repositories.FeatureTogglesRepository;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.share.flow.resources.FlowResourcesConfig;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;

import com.google.common.collect.ImmutableList;
import lombok.SneakyThrows;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.hamcrest.MockitoHamcrest;
import org.mockito.stubbing.Answer;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;

public abstract class AbstractFlowTest extends Neo4jBasedTest {
    protected static final SwitchId SWITCH_SOURCE = new SwitchId(1);
    protected static final SwitchId SWITCH_DEST = new SwitchId(2);
    protected static final SwitchId SWITCH_TRANSIT = new SwitchId(3L);

    protected final IslDirectionalReference islSourceDest = new IslDirectionalReference(
            new IslEndpoint(SWITCH_SOURCE, 24),
            new IslEndpoint(SWITCH_DEST, 24));
    protected final IslDirectionalReference islSourceTransit = new IslDirectionalReference(
            new IslEndpoint(SWITCH_SOURCE, 25),
            new IslEndpoint(SWITCH_TRANSIT, 25));
    protected final IslDirectionalReference islTransitDest = new IslDirectionalReference(
            new IslEndpoint(SWITCH_TRANSIT, 26),
            new IslEndpoint(SWITCH_DEST, 26));

    protected final FlowEndpoint flowSource = new FlowEndpoint(SWITCH_SOURCE, 1, 101);
    protected final FlowEndpoint flowDestination = new FlowEndpoint(SWITCH_DEST, 2, 102);

    private FlowRepository flowRepositorySpy = null;
    private FlowPathRepository flowPathRepositorySpy = null;
    private IslRepository islRepositorySpy = null;

    protected FlowResourcesManager flowResourcesManager = null;

    protected final String dummyRequestKey = "test-key";
    protected final String injectedErrorMessage = "Unit-test injected failure";

    protected CommandContext commandContext = new CommandContext();

    @Mock
    PersistenceManager persistenceManagerMock;
    @Mock
    FlowRepository flowRepository;
    @Mock
    FlowPathRepository flowPathRepository;
    @Mock
    PathComputer pathComputer;
    @Mock
    FlowResourcesManager flowResourcesManagerMock;
    @Mock
    FeatureTogglesRepository featureTogglesRepository;
    @Mock
    IslRepository islRepository;

    final Queue<FlowSegmentRequest> requests = new ArrayDeque<>();
    final Map<SwitchId, Map<Cookie, FlowSegmentRequest>> installedSegments = new HashMap<>();

    @Before
    public void before() {
        when(persistenceManagerMock.getTransactionManager()).thenReturn(new TransactionManager() {
            @SneakyThrows
            @Override
            public <T, E extends Throwable> T doInTransaction(TransactionCallback<T, E> action) throws E {
                return action.doInTransaction();
            }

            @Override
            public <T, E extends Throwable> T doInTransaction(RetryPolicy retryPolicy, TransactionCallback<T, E> action)
                    throws E {
                return Failsafe.with(retryPolicy).get(action::doInTransaction);
            }

            @SneakyThrows
            @Override
            public <E extends Throwable> void doInTransaction(TransactionCallbackWithoutResult<E> action) throws E {
                action.doInTransaction();
            }

            @Override
            public <E extends Throwable> void doInTransaction(RetryPolicy retryPolicy,
                                                              TransactionCallbackWithoutResult<E> action) throws E {
                Failsafe.with(retryPolicy).run(action::doInTransaction);
            }

            @Override
            public RetryPolicy makeRetryPolicyBlank() {
                return new RetryPolicy().retryIf(result -> false);
            }
        });

        when(featureTogglesRepository.find()).thenReturn(Optional.of(
                FeatureToggles.DEFAULTS.toBuilder()
                        .createFlowEnabled(true)
                        .updateFlowEnabled(true)
                        .deleteFlowEnabled(true)
                        .build()
        ));

        FlowResourcesConfig resourceConfig = configurationProvider.getConfiguration(FlowResourcesConfig.class);
        flowResourcesManager = spy(new FlowResourcesManager(persistenceManager, resourceConfig));

        alterFeatureToggles(true, true, true);

        dummyFactory.makeSwitch(SWITCH_SOURCE);
        dummyFactory.makeSwitch(SWITCH_DEST);
        dummyFactory.makeSwitch(SWITCH_TRANSIT);
        for (IslDirectionalReference reference : new IslDirectionalReference[]{
                islSourceDest, islSourceTransit, islTransitDest}) {
            dummyFactory.makeIsl(reference.getAEnd(), reference.getZEnd());
            dummyFactory.makeIsl(reference.getZEnd(), reference.getAEnd());
        }
    }

    @After
    public void tearDown() throws Exception {
        if (flowRepositorySpy != null) {
            reset(flowRepositorySpy);
        }
        if (flowPathRepositorySpy != null) {
            reset(flowPathRepositorySpy);
        }
        if (islRepositorySpy != null) {
            reset(islRepositorySpy);
        }
    }

    protected SpeakerFlowSegmentResponse buildSpeakerResponse(FlowSegmentRequest flowRequest) {
        return SpeakerFlowSegmentResponse.builder()
                        .messageContext(flowRequest.getMessageContext())
                        .commandId(flowRequest.getCommandId())
                        .metadata(flowRequest.getMetadata())
                        .switchId(flowRequest.getSwitchId())
                        .success(true)
                        .build();
    }

    Answer getSpeakerCommandsAnswer() {
        return invocation -> {
            FlowSegmentRequest request = invocation.getArgument(0);
            requests.offer(request);

            if (request.isInstallRequest()) {
                installedSegments.computeIfAbsent(request.getSwitchId(), ignore -> new HashMap<>())
                        .put(request.getCookie(), request);
            }

            return request;
        };
    }

    SpeakerFlowSegmentResponse buildResponseOnVerifyRequest(FlowSegmentRequest request) {
        return SpeakerFlowSegmentResponse.builder()
                .commandId(request.getCommandId())
                .metadata(request.getMetadata())
                .messageContext(request.getMessageContext())
                .switchId(request.getSwitchId())
                .success(true)
                .build();
    }

    protected Flow fetchFlow(String flowId) {
        FlowRepository repository = persistenceManager.getRepositoryFactory().createFlowRepository();
        return repository.findById(flowId)
                .orElseThrow(() -> new AssertionError(String.format(
                        "Flow %s not found in persistent storage", flowId)));
    }

    protected FlowRepository setupFlowRepositorySpy() {
        if (flowRepositorySpy == null) {
            flowRepositorySpy = spy(persistenceManager.getRepositoryFactory().createFlowRepository());
            when(repositoryFactorySpy.createFlowRepository()).thenReturn(flowRepositorySpy);
        }
        return flowRepositorySpy;
    }

    protected FlowPathRepository setupFlowPathRepositorySpy() {
        if (flowPathRepositorySpy == null) {
            flowPathRepositorySpy = spy(persistenceManager.getRepositoryFactory().createFlowPathRepository());
            when(repositoryFactorySpy.createFlowPathRepository()).thenReturn(flowPathRepositorySpy);
        }
        return flowPathRepositorySpy;
    }

    protected IslRepository setupIslRepositorySpy() {
        if (islRepositorySpy == null) {
            islRepositorySpy = spy(persistenceManager.getRepositoryFactory().createIslRepository());
            when(repositoryFactorySpy.createIslRepository()).thenReturn(islRepositorySpy);
        }
        return islRepositorySpy;
    }

    protected Flow verifyFlowStatus(String flowId, FlowStatus expectedStatus) {
        Flow flow = fetchFlow(flowId);
        assertEquals(expectedStatus, flow.getStatus());
        return flow;
    }

    protected void verifyFlowPathStatus(FlowPath path, FlowPathStatus expectedStatus, String name) {
        Assert.assertNotNull(String.format("%s flow path not defined (is null)", name), path);
        Assert.assertSame(
                String.format("%s flow path status is invalid", name),
                expectedStatus, path.getStatus());
    }

    protected void verifyNorthboundSuccessResponse(FlowGenericCarrier carrierMock) {
        ArgumentCaptor<Message> responseCaptor = ArgumentCaptor.forClass(Message.class);
        verify(carrierMock).sendNorthboundResponse(responseCaptor.capture());

        Message rawResponse = responseCaptor.getValue();
        Assert.assertNotNull(rawResponse);
        Assert.assertTrue(rawResponse instanceof InfoMessage);

        InfoData rawPayload = ((InfoMessage) rawResponse).getData();
        Assert.assertTrue(rawPayload instanceof FlowResponse);
    }

    protected void verifyNorthboundErrorResponse(FlowGenericCarrier carrier, ErrorType expectedErrorType) {
        ArgumentCaptor<Message> responseCaptor = ArgumentCaptor.forClass(Message.class);
        verify(carrier).sendNorthboundResponse(responseCaptor.capture());

        Message rawResponse = responseCaptor.getValue();
        Assert.assertNotNull(rawResponse);
        Assert.assertTrue(rawResponse instanceof ErrorMessage);
        ErrorMessage response = (ErrorMessage) rawResponse;

        Assert.assertSame(expectedErrorType, response.getData().getErrorType());
    }

    protected void verifyNoSpeakerInteraction(FlowGenericCarrier carrier) {
        verify(carrier, never()).sendSpeakerRequest(any());
    }

    protected void alterFeatureToggles(Boolean isCreateAllowed, Boolean isUpdateAllowed, Boolean isDeleteAllowed) {
        FeatureTogglesRepository repository = persistenceManager
                .getRepositoryFactory().createFeatureTogglesRepository();

        FeatureToggles toggles = repository.find()
                .orElseGet(FeatureToggles::new);

        if (isCreateAllowed != null) {
            toggles.setCreateFlowEnabled(isCreateAllowed);
        }
        if (isUpdateAllowed != null) {
            toggles.setUpdateFlowEnabled(isUpdateAllowed);
        }
        if (isDeleteAllowed != null) {
            toggles.setDeleteFlowEnabled(isDeleteAllowed);
        }

        repository.createOrUpdate(toggles);
    }

    protected FlowRequest.FlowRequestBuilder makeRequest() {
        return FlowRequest.builder()
                .bandwidth(1000L)
                .sourceSwitch(flowSource.getSwitchId())
                .sourcePort(flowSource.getPortNumber())
                .sourceVlan(flowSource.getVlanId())
                .destinationSwitch(flowDestination.getSwitchId())
                .destinationPort(flowDestination.getPortNumber())
                .destinationVlan(flowDestination.getVlanId());
    }

    protected PathPair makeOneSwitchPathPair() {
        return PathPair.builder()
                .forward(Path.builder()
                        .srcSwitchId(SWITCH_SOURCE)
                        .destSwitchId(SWITCH_SOURCE)
                        .segments(Collections.emptyList())
                        .build())
                .reverse(Path.builder()
                        .srcSwitchId(SWITCH_SOURCE)
                        .destSwitchId(SWITCH_SOURCE)
                        .segments(Collections.emptyList())
                        .build())
                .build();
    }

    protected PathPair make2SwitchesPathPair() {
        List<Segment> forwardSegments = ImmutableList.of(
                makePathSegment(islSourceDest));
        List<Segment> reverseSegments = ImmutableList.of(
                makePathSegment(islSourceDest.makeOpposite()));

        return PathPair.builder()
                .forward(Path.builder()
                        .srcSwitchId(SWITCH_SOURCE)
                        .destSwitchId(SWITCH_DEST)
                        .segments(forwardSegments)
                        .build())
                .reverse(Path.builder()
                        .srcSwitchId(SWITCH_DEST)
                        .destSwitchId(SWITCH_SOURCE)
                        .segments(reverseSegments)
                        .build())
                .build();
    }

    protected PathPair make3SwitchesPathPair() {
        List<Segment> forwardSegments = ImmutableList.of(
                makePathSegment(islSourceTransit),
                makePathSegment(islTransitDest));
        List<Segment> reverseSegments = ImmutableList.of(
                makePathSegment(islTransitDest.makeOpposite()),
                makePathSegment(islSourceTransit.makeOpposite()));

        return PathPair.builder()
                .forward(Path.builder()
                        .srcSwitchId(SWITCH_SOURCE)
                        .destSwitchId(SWITCH_DEST)
                        .segments(forwardSegments)
                        .build())
                .reverse(Path.builder()
                        .srcSwitchId(SWITCH_DEST)
                        .destSwitchId(SWITCH_SOURCE)
                        .segments(reverseSegments)
                        .build())
                .build();
    }

    private Segment makePathSegment(IslDirectionalReference reference) {
        IslEndpoint aEnd = reference.getAEnd();
        IslEndpoint zEnd = reference.getZEnd();
        return Segment.builder()
                .srcSwitchId(aEnd.getSwitchId())
                .srcPort(aEnd.getPortNumber())
                .destSwitchId(zEnd.getSwitchId())
                .destPort(zEnd.getPortNumber())
                .build();
    }

    protected Flow makeFlow() {
        return makeFlow(flowSource, flowDestination);
    }

    private Flow makeFlow(FlowEndpoint aEnd, FlowEndpoint zEnd) {
        return dummyFactory.makeFlow(aEnd, zEnd, islSourceDest);
    }

    protected Flow makeFlowArgumentMatch(String flowId) {
        return MockitoHamcrest.argThat(
                Matchers.hasProperty("flowId", is(flowId)));
    }

    protected void flushFlowChanges(Flow flow) {
        FlowRepository repository = persistenceManager.getRepositoryFactory().createFlowRepository();
        repository.createOrUpdate(flow);
    }
}
