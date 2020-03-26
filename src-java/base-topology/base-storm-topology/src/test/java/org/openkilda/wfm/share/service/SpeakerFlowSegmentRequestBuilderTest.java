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

package org.openkilda.wfm.share.service;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

import org.openkilda.floodlight.api.request.EgressFlowSegmentRequest;
import org.openkilda.floodlight.api.request.FlowSegmentRequest;
import org.openkilda.floodlight.api.request.IngressFlowSegmentRequest;
import org.openkilda.floodlight.api.request.OneSwitchFlowRequest;
import org.openkilda.floodlight.api.request.TransitFlowSegmentRequest;
import org.openkilda.floodlight.api.request.factory.FlowSegmentRequestFactory;
import org.openkilda.model.Cookie;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathDirection;
import org.openkilda.model.FlowTransitEncapsulation;
import org.openkilda.model.MeterConfig;
import org.openkilda.model.MeterId;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.TransitVlan;
import org.openkilda.model.bitops.cookie.FlowSegmentCookieSchema;
import org.openkilda.persistence.Neo4jBasedTest;
import org.openkilda.persistence.repositories.TransitVlanRepository;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.share.flow.resources.EncapsulationResources;
import org.openkilda.wfm.share.flow.resources.FlowResources;
import org.openkilda.wfm.share.flow.resources.FlowResourcesConfig;
import org.openkilda.wfm.share.flow.resources.FlowResourcesManager;
import org.openkilda.wfm.share.flow.resources.transitvlan.TransitVlanEncapsulation;
import org.openkilda.wfm.share.model.FlowPathSpeakerView;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;
import com.google.common.collect.ImmutableList;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

public class SpeakerFlowSegmentRequestBuilderTest extends Neo4jBasedTest {
    private static final CommandContext COMMAND_CONTEXT = new CommandContext();
    private static final SwitchId SWITCH_1 = new SwitchId("00:00:00:00:00:00:00:01");
    private static final SwitchId SWITCH_2 = new SwitchId("00:00:00:00:00:00:00:02");
    private static final SwitchId SWITCH_3 = new SwitchId("00:00:00:00:00:00:00:03");
    private static final Iterator<Long> cookieFactory = LongStream.iterate(1, entry -> entry + 1).iterator();
    private static final Iterator<Integer> meterFactory = IntStream.iterate(1, entry -> entry + 1).iterator();
    private static final Iterator<Integer> vlanFactory = IntStream.iterate(
            1, entry -> entry < 4096 ? entry + 1 : 1).iterator();

    private final NoArgGenerator commandIdGenerator = Generators.timeBasedGenerator();

    private SpeakerFlowSegmentRequestBuilder target;
    // TODO(surabujin): there is no more requests for persistent object in target code, so tests can be reworked too
    private TransitVlanRepository vlanRepository;

    @Before
    public void setUp() {
        FlowResourcesManager resourcesManager = new FlowResourcesManager(persistenceManager,
                configurationProvider.getConfiguration(FlowResourcesConfig.class));
        target = new SpeakerFlowSegmentRequestBuilder();
        vlanRepository = persistenceManager.getRepositoryFactory().createTransitVlanRepository();
    }

    @Test
    public void shouldCreateNonIngressRequestsWithoutVlans() {
        Switch srcSwitch = Switch.builder().switchId(SWITCH_1).build();
        Switch destSwitch = Switch.builder().switchId(SWITCH_2).build();

        Flow flow = buildFlow(srcSwitch, 1, 0, destSwitch, 2, 0, 0);
        FlowPath forwardPath = flow.getForwardPath();
        FlowPath reversePath = flow.getReversePath();
        setSegmentsWithoutTransitSwitches(
                Objects.requireNonNull(forwardPath), Objects.requireNonNull(reversePath));

        List<FlowSegmentRequestFactory> commands = target.buildAllExceptIngress(
                COMMAND_CONTEXT, flow,
                buildFlowPathSnapshot(forwardPath, reversePath, FlowEncapsulationType.TRANSIT_VLAN),
                buildFlowPathSnapshot(reversePath, forwardPath, FlowEncapsulationType.TRANSIT_VLAN));
        assertEquals(2, commands.size());

        verifyForwardEgressRequest(flow, makeInstallRequest(commands.get(0)));
        verifyReverseEgressRequest(flow, makeInstallRequest(commands.get(1)));
    }

    @Test
    public void shouldCreateNonIngressCommandsWithTransitSwitch() {
        Switch srcSwitch = Switch.builder().switchId(SWITCH_1).build();
        Switch destSwitch = Switch.builder().switchId(SWITCH_3).build();

        Flow flow = buildFlow(srcSwitch, 1, 101, destSwitch, 2, 102, 0);
        FlowPath forwardPath = flow.getForwardPath();
        FlowPath reversePath = flow.getReversePath();
        setSegmentsWithTransitSwitches(
                Objects.requireNonNull(forwardPath), Objects.requireNonNull(reversePath));

        List<FlowSegmentRequestFactory> commands = target.buildAllExceptIngress(
                COMMAND_CONTEXT, flow,
                buildFlowPathSnapshot(forwardPath, reversePath, FlowEncapsulationType.TRANSIT_VLAN),
                buildFlowPathSnapshot(reversePath, forwardPath, FlowEncapsulationType.TRANSIT_VLAN));
        assertEquals(4, commands.size());

        verifyForwardTransitRequest(flow, SWITCH_2, makeInstallRequest(commands.get(0)));
        verifyForwardEgressRequest(flow, makeInstallRequest(commands.get(1)));

        verifyReverseTransitRequest(flow, SWITCH_2, makeInstallRequest(commands.get(2)));
        verifyReverseEgressRequest(flow, makeInstallRequest(commands.get(3)));
    }

    @Test
    public void shouldCreateIngressWithoutMeterCommands() {
        commonIngressCommandTest(0);
    }

    @Test
    public void shouldCreateIngressCommands() {
        commonIngressCommandTest(1000);
    }

    @Test
    public void shouldCreateOneSwitchFlow() {
        Switch sw = Switch.builder().switchId(SWITCH_1).build();
        Flow flow = buildFlow(sw, 1, 10, sw, 2, 12, 1000);
        FlowPath forwardPath = flow.getForwardPath();
        FlowPath reversePath = flow.getReversePath();
        List<FlowSegmentRequestFactory> commands = target.buildAll(
                COMMAND_CONTEXT, flow,
                buildFlowPathSnapshot(forwardPath, reversePath, FlowEncapsulationType.TRANSIT_VLAN),
                buildFlowPathSnapshot(reversePath, forwardPath, FlowEncapsulationType.TRANSIT_VLAN));

        assertEquals(2, commands.size());

        verifyForwardOneSwitchRequest(flow, makeInstallRequest(commands.get(0)));
        verifyReverseOneSwitchRequest(flow, makeInstallRequest(commands.get(1)));
    }

    private void commonIngressCommandTest(int bandwidth) {
        Switch srcSwitch = Switch.builder().switchId(SWITCH_1).build();
        Switch destSwitch = Switch.builder().switchId(SWITCH_2).build();

        Flow flow = buildFlow(srcSwitch, 1, 101, destSwitch, 2, 102, bandwidth);
        FlowPath forwardPath = flow.getForwardPath();
        FlowPath reversePath = flow.getReversePath();
        setSegmentsWithoutTransitSwitches(
                Objects.requireNonNull(forwardPath), Objects.requireNonNull(reversePath));

        List<FlowSegmentRequestFactory> commands = target.buildIngressOnly(
                COMMAND_CONTEXT, flow,
                buildFlowPathSnapshot(forwardPath, reversePath, FlowEncapsulationType.TRANSIT_VLAN),
                buildFlowPathSnapshot(reversePath, forwardPath, FlowEncapsulationType.TRANSIT_VLAN));
        assertEquals(2, commands.size());

        verifyForwardIngressRequest(flow, makeInstallRequest(commands.get(0)));
        verifyReverseIngressRequest(flow, makeInstallRequest(commands.get(1)));
    }

    private IngressFlowSegmentRequest verifyForwardIngressRequest(Flow flow, FlowSegmentRequest rawRequest) {
        FlowPath path = Objects.requireNonNull(flow.getForwardPath());
        IngressFlowSegmentRequest request = verifyCommonIngressRequest(flow, path, rawRequest);

        assertEquals(flow.getSrcSwitch().getSwitchId(), request.getSwitchId());
        FlowEndpoint endpoint = new FlowEndpoint(
                flow.getSrcSwitch().getSwitchId(), flow.getSrcPort(), flow.getSrcVlan());
        assertEquals(endpoint, request.getEndpoint());

        return request;
    }

    private IngressFlowSegmentRequest verifyReverseIngressRequest(Flow flow, FlowSegmentRequest rawRequest) {
        FlowPath path = Objects.requireNonNull(flow.getReversePath());
        IngressFlowSegmentRequest request = verifyCommonIngressRequest(flow, path, rawRequest);

        assertEquals(flow.getDestSwitch().getSwitchId(), request.getSwitchId());
        FlowEndpoint endpoint = new FlowEndpoint(
                flow.getDestSwitch().getSwitchId(), flow.getDestPort(), flow.getDestVlan());
        assertEquals(endpoint, request.getEndpoint());

        return request;
    }

    private IngressFlowSegmentRequest verifyCommonIngressRequest(
            Flow flow, FlowPath path, FlowSegmentRequest rawRequest) {
        assertThat(
                "Should be egress segment request", rawRequest, instanceOf(IngressFlowSegmentRequest.class));
        IngressFlowSegmentRequest request = (IngressFlowSegmentRequest) rawRequest;

        assertEquals(flow.getFlowId(), request.getFlowId());
        assertEquals(path.getCookie(), request.getCookie());
        assertEquals(path.getSegments().get(0).getSrcPort(), (int) request.getIslPort());

        if (0 < flow.getBandwidth()) {
            MeterConfig config = new MeterConfig(path.getMeterId(), flow.getBandwidth());
            assertEquals(config, request.getMeterConfig());
        } else {
            assertNull(request.getMeterConfig());
        }

        verifyVlanEncapsulation(flow, path, request.getEncapsulation());

        return request;
    }

    private void verifyForwardTransitRequest(Flow flow, SwitchId datapath, FlowSegmentRequest rawRequest) {
        FlowPath path = Objects.requireNonNull(flow.getForwardPath());
        verifyCommonTransitRequest(flow, path, datapath, rawRequest);
    }

    private void verifyReverseTransitRequest(Flow flow, SwitchId datapath, FlowSegmentRequest rawRequest) {
        FlowPath path = Objects.requireNonNull(flow.getReversePath());
        verifyCommonTransitRequest(flow, path, datapath, rawRequest);
    }

    private TransitFlowSegmentRequest verifyCommonTransitRequest(
            Flow flow, FlowPath path, SwitchId datapath, FlowSegmentRequest rawRequest) {
        assertThat(
                "Should be egress segment request", rawRequest, instanceOf(TransitFlowSegmentRequest.class));
        TransitFlowSegmentRequest request = (TransitFlowSegmentRequest) rawRequest;

        assertEquals(flow.getFlowId(), request.getFlowId());
        assertEquals(path.getCookie(), request.getCookie());
        assertEquals(SWITCH_2, request.getSwitchId());

        PathSegment ingress = null;
        PathSegment egress = null;
        for (PathSegment segment : path.getSegments()) {
            if (datapath.equals(segment.getDestSwitch().getSwitchId())) {
                ingress = segment;
            } else if (datapath.equals(segment.getSrcSwitch().getSwitchId())) {
                egress = segment;
            }
        }

        assertNotNull(ingress);
        assertNotNull(egress);

        assertEquals(ingress.getDestPort(), (int) request.getIngressIslPort());
        assertEquals(egress.getSrcPort(), (int) request.getEgressIslPort());

        verifyVlanEncapsulation(flow, path, request.getEncapsulation());
        return request;
    }

    private void verifyForwardEgressRequest(Flow flow, FlowSegmentRequest rawRequest) {
        FlowPath path = Objects.requireNonNull(flow.getForwardPath());
        EgressFlowSegmentRequest request = verifyCommonEgressRequest(flow, path, rawRequest);

        FlowEndpoint expectedEndpoint = new FlowEndpoint(
                flow.getDestSwitch().getSwitchId(), flow.getDestPort(), flow.getDestVlan());
        assertEquals(expectedEndpoint, request.getEndpoint());

        FlowEndpoint expectedIngressEndpoint = new FlowEndpoint(
                flow.getSrcSwitch().getSwitchId(), flow.getSrcPort(), flow.getSrcVlan());
        assertEquals(expectedIngressEndpoint, request.getIngressEndpoint());
    }

    private void verifyReverseEgressRequest(Flow flow, FlowSegmentRequest rawRequest) {
        FlowPath path = Objects.requireNonNull(flow.getReversePath());
        EgressFlowSegmentRequest request = verifyCommonEgressRequest(flow, path, rawRequest);

        FlowEndpoint expectedEndpoint = new FlowEndpoint(
                flow.getSrcSwitch().getSwitchId(), flow.getSrcPort(), flow.getSrcVlan());
        assertEquals(expectedEndpoint, request.getEndpoint());

        FlowEndpoint expectedIngressEndpoint = new FlowEndpoint(
                flow.getDestSwitch().getSwitchId(), flow.getDestPort(), flow.getDestVlan());
        assertEquals(expectedIngressEndpoint, request.getIngressEndpoint());
    }

    private EgressFlowSegmentRequest verifyCommonEgressRequest(
            Flow flow, FlowPath path, FlowSegmentRequest rawRequest) {
        assertThat(
                "Should be egress segment request", rawRequest, instanceOf(EgressFlowSegmentRequest.class));
        EgressFlowSegmentRequest request = (EgressFlowSegmentRequest) rawRequest;

        assertEquals(flow.getFlowId(), request.getFlowId());
        assertEquals(path.getDestSwitch().getSwitchId(), request.getSwitchId());
        assertEquals(path.getCookie(), request.getCookie());
        assertEquals(path.getSegments().get(path.getSegments().size() - 1).getDestPort(), (int) request.getIslPort());

        verifyVlanEncapsulation(flow, path, request.getEncapsulation());

        return request;
    }

    private void verifyForwardOneSwitchRequest(Flow flow, FlowSegmentRequest rawRequest) {
        FlowPath path = Objects.requireNonNull(flow.getForwardPath());
        OneSwitchFlowRequest request = verifyCommonOneSwitchRequest(flow, path, rawRequest);

        assertEquals(
                new FlowEndpoint(flow.getSrcSwitch().getSwitchId(), flow.getSrcPort(), flow.getSrcVlan()),
                request.getEndpoint());
        assertEquals(
                new FlowEndpoint(flow.getDestSwitch().getSwitchId(), flow.getDestPort(), flow.getDestVlan()),
                request.getEgressEndpoint());
    }

    private void verifyReverseOneSwitchRequest(Flow flow, FlowSegmentRequest rawRequest) {
        FlowPath path = Objects.requireNonNull(flow.getReversePath());
        OneSwitchFlowRequest request = verifyCommonOneSwitchRequest(flow, path, rawRequest);

        assertEquals(
                new FlowEndpoint(flow.getDestSwitch().getSwitchId(), flow.getDestPort(), flow.getDestVlan()),
                request.getEndpoint());
        assertEquals(
                new FlowEndpoint(flow.getSrcSwitch().getSwitchId(), flow.getSrcPort(), flow.getSrcVlan()),
                request.getEgressEndpoint());
    }

    private OneSwitchFlowRequest verifyCommonOneSwitchRequest(
            Flow flow, FlowPath path, FlowSegmentRequest rawRequest) {
        assertThat(
                "Should be one switch flow request", rawRequest, instanceOf(OneSwitchFlowRequest.class));
        OneSwitchFlowRequest request = (OneSwitchFlowRequest) rawRequest;

        assertEquals(flow.getFlowId(), request.getFlowId());
        assertEquals(path.getCookie(), request.getCookie());

        return request;
    }

    private void verifyVlanEncapsulation(Flow flow, FlowPath path, FlowTransitEncapsulation encapsulation) {
        assertEquals(FlowEncapsulationType.TRANSIT_VLAN, encapsulation.getType());
        TransitVlan transitVlan = vlanRepository.findByPathId(path.getPathId(),
                                                              flow.getOppositePathId(path.getPathId()).orElse(null))
                .stream().findAny()
                .orElseThrow(() -> new IllegalStateException("Vlan should be present"));
        assertEquals(transitVlan.getVlan(), (int) encapsulation.getId());
    }

    private void setSegmentsWithoutTransitSwitches(FlowPath forward, FlowPath reverse) {
        PathSegment switch1ToSwitch2 = PathSegment.builder()
                .srcSwitch(forward.getSrcSwitch())
                .srcPort(12)
                .destSwitch(forward.getDestSwitch())
                .destPort(22)
                .build();
        forward.setSegments(ImmutableList.of(switch1ToSwitch2));
        PathSegment switch2ToSwitch1 = PathSegment.builder()
                .srcSwitch(reverse.getSrcSwitch())
                .srcPort(22)
                .destSwitch(reverse.getDestSwitch())
                .destPort(12)
                .build();
        reverse.setSegments(ImmutableList.of(switch2ToSwitch1));
    }

    private void setSegmentsWithTransitSwitches(FlowPath forward, FlowPath reverse) {
        PathSegment switch1ToSwitch2 = PathSegment.builder()
                .srcSwitch(forward.getSrcSwitch())
                .srcPort(12)
                .destSwitch(Switch.builder().switchId(SWITCH_2).build())
                .destPort(21)
                .build();
        PathSegment switch2ToSwitch3 = PathSegment.builder()
                .srcSwitch(Switch.builder().switchId(SWITCH_2).build())
                .srcPort(23)
                .destSwitch(forward.getDestSwitch())
                .destPort(32)
                .build();
        forward.setSegments(ImmutableList.of(switch1ToSwitch2, switch2ToSwitch3));

        PathSegment switch3ToSwitch2 = PathSegment.builder()
                .srcSwitch(reverse.getSrcSwitch())
                .srcPort(32)
                .destSwitch(Switch.builder().switchId(SWITCH_2).build())
                .destPort(23)
                .build();
        PathSegment switch2ToSwitch1 = PathSegment.builder()
                .srcSwitch(Switch.builder().switchId(SWITCH_2).build())
                .srcPort(21)
                .destSwitch(reverse.getDestSwitch())
                .destPort(12)
                .build();
        reverse.setSegments(ImmutableList.of(switch3ToSwitch2, switch2ToSwitch1));
    }

    private FlowPathSpeakerView buildFlowPathSnapshot(
            FlowPath path, FlowPath oppositePath, FlowEncapsulationType encapsulationType) {
        if (path == null) {
            return null;
        }

        EncapsulationResources encapsulationResources;
        if (encapsulationType == FlowEncapsulationType.TRANSIT_VLAN) {
            Iterator<TransitVlan> vlan = vlanRepository.findByPathId(path.getPathId(), oppositePath.getPathId())
                    .iterator();
            Assert.assertTrue(vlan.hasNext());
            encapsulationResources = TransitVlanEncapsulation.builder().transitVlan(vlan.next()).build();
        } else {
            throw new IllegalArgumentException(String.format("Unsupported encapsulation type %s", encapsulationType));
        }

        FlowResources.PathResources pathResources = FlowResources.PathResources.builder()
                .encapsulationResources(encapsulationResources)
                .build();
        return FlowPathSpeakerView.builder(path)
                .resources(pathResources)
                .build();
    }

    private FlowPath buildFlowPath(Flow flow, Switch srcSwitch, Switch dstSwitch, Cookie cookie) {
        PathId forwardPathId = new PathId(UUID.randomUUID().toString());
        TransitVlan transitVlan = TransitVlan.builder()
                .flowId(flow.getFlowId())
                .pathId(forwardPathId)
                .vlan(vlanFactory.next())
                .build();
        vlanRepository.createOrUpdate(transitVlan);
        return FlowPath.builder()
                .flow(flow)
                .bandwidth(flow.getBandwidth())
                .cookie(cookie)
                .meterId(flow.getBandwidth() != 0 ? new MeterId(meterFactory.next()) : null)
                .srcSwitch(srcSwitch)
                .destSwitch(dstSwitch)
                .pathId(forwardPathId)
                .build();
    }

    private Flow buildFlow(Switch srcSwitch, int srcPort, int srcVlan, Switch dstSwitch, int dstPort, int dstVlan,
                           int bandwidth) {
        Flow flow = Flow.builder()
                .flowId(UUID.randomUUID().toString())
                .srcSwitch(srcSwitch)
                .srcPort(srcPort)
                .srcVlan(srcVlan)
                .destSwitch(dstSwitch)
                .destPort(dstPort)
                .destVlan(dstVlan)
                .bandwidth(bandwidth)
                .encapsulationType(FlowEncapsulationType.TRANSIT_VLAN)
                .build();

        Long rawCookie = cookieFactory.next();

        flow.setForwardPath(buildFlowPath(
                flow, flow.getSrcSwitch(), flow.getDestSwitch(), FlowSegmentCookieSchema.INSTANCE.make(
                        rawCookie, FlowPathDirection.FORWARD)));
        flow.setReversePath(buildFlowPath(
                flow, flow.getDestSwitch(), flow.getSrcSwitch(), FlowSegmentCookieSchema.INSTANCE.make(
                        rawCookie, FlowPathDirection.REVERSE)));

        return flow;
    }

    private FlowSegmentRequest makeInstallRequest(FlowSegmentRequestFactory requestFactory) {
        return requestFactory.makeInstallRequest(commandIdGenerator.generate())
                .orElseThrow(() -> new AssertionError("no request produced"));
    }
}
