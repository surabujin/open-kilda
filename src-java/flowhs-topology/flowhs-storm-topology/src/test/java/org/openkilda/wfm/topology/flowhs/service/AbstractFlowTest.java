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

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.openkilda.floodlight.api.request.FlowSegmentRequest;
import org.openkilda.floodlight.api.response.SpeakerFlowSegmentResponse;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.error.ErrorMessage;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.flow.FlowResponse;
import org.openkilda.model.Cookie;
import org.openkilda.model.FeatureToggles;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.IslEndpoint;
import org.openkilda.model.SwitchId;
import org.openkilda.pce.PathComputer;
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
import org.openkilda.wfm.share.flow.resources.FlowResourcesConfig;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.model.IslReference;

import lombok.SneakyThrows;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.junit.Assert;
import org.junit.Before;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;

public abstract class AbstractFlowTest extends Neo4jBasedTest {
    protected static final SwitchId SWITCH_1 = new SwitchId(1);
    protected static final SwitchId SWITCH_2 = new SwitchId(2);

    private FlowRepository flowRepositorySpy = null;
    private FlowPathRepository flowPathRepositorySpy = null;

    protected FlowResourcesManager flowResourcesManager = null;

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

    protected Flow verifyFlowStatus(String flowId, FlowStatus expectedStatus) {
        Flow flow = fetchFlow(flowId);
        assertEquals(expectedStatus, flow.getStatus());
        return flow;
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

    protected void verifyNorthboundErrorResponse(FlowGenericCarrier carrierMock, ErrorType expectedErrorType) {
        ArgumentCaptor<Message> responseCaptor = ArgumentCaptor.forClass(Message.class);
        verify(carrierMock).sendNorthboundResponse(responseCaptor.capture());

        Message rawResponse = responseCaptor.getValue();
        Assert.assertNotNull(rawResponse);
        Assert.assertTrue(rawResponse instanceof ErrorMessage);
        ErrorMessage response = (ErrorMessage) rawResponse;

        Assert.assertSame(expectedErrorType, response.getData().getErrorType());
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

    protected Flow make2SwitchFlow() {
        FlowEndpoint aEnd = new FlowEndpoint(SWITCH_1, 10, 100);
        FlowEndpoint zEnd = new FlowEndpoint(SWITCH_2, 20, 200);
        return dummyFactory.makeFlow(
                aEnd, zEnd,
                new IslDirectionalReference(
                        new IslEndpoint(aEnd.getSwitchId(), 11),
                        new IslEndpoint(zEnd.getSwitchId(), 21)));
    }

    protected void flushFlowChanges(Flow flow) {
        FlowRepository repository = persistenceManager.getRepositoryFactory().createFlowRepository();
        repository.createOrUpdate(flow);
    }
}
