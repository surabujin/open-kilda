/* Copyright 2020 Telstra Open Source
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

package org.openkilda.persistence.dummy;

import static java.util.Collections.emptyList;

import org.openkilda.model.Cookie;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowCookie;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.FlowMeter;
import org.openkilda.model.FlowPath;
import org.openkilda.model.Isl;
import org.openkilda.model.IslEndpoint;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.model.TransitVlan;
import org.openkilda.model.Vxlan;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.FlowCookieRepository;
import org.openkilda.persistence.repositories.FlowMeterRepository;
import org.openkilda.persistence.repositories.FlowRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.persistence.repositories.TransitVlanRepository;
import org.openkilda.persistence.repositories.VxlanRepository;

import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PersistenceEntityDummyFactory {
    private TransactionManager txManager;

    private final SwitchRepository switchRepository;
    private final SwitchPropertiesRepository switchPropertiesRepository;
    private final IslRepository islRepository;
    private final FlowRepository flowRepository;
    private final FlowMeterRepository flowMeterRepository;
    private final FlowCookieRepository flowCookieRepository;
    private final TransitVlanRepository transitVlanRepository;
    private final VxlanRepository transitVxLanRepository;

    private final IdProvider idProvider = new IdProvider();

    @Getter
    private final SwitchDefaults switchDefaults = new SwitchDefaults();

    @Getter
    private final SwitchPropertiesDefaults switchPropertiesDefaults = new SwitchPropertiesDefaults();

    @Getter
    private final IslDefaults islDefaults = new IslDefaults();

    @Getter
    private final FlowDefaults flowDefaults = new FlowDefaults();

    @Getter
    private final FlowPathDefaults flowPathDefaults = new FlowPathDefaults();


    public PersistenceEntityDummyFactory(PersistenceManager persistenceManager) {
        txManager = persistenceManager.getTransactionManager();

        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        switchRepository = repositoryFactory.createSwitchRepository();
        switchPropertiesRepository = repositoryFactory.createSwitchPropertiesRepository();
        islRepository = repositoryFactory.createIslRepository();
        flowRepository = repositoryFactory.createFlowRepository();
        flowMeterRepository = repositoryFactory.createFlowMeterRepository();
        flowCookieRepository = repositoryFactory.createFlowCookieRepository();
        transitVlanRepository = repositoryFactory.createTransitVlanRepository();
        transitVxLanRepository = repositoryFactory.createVxlanRepository();
    }

    public Switch fetchOrCreateSwitch(SwitchId switchId) {
        return switchRepository.findById(switchId)
                .orElseGet(() -> makeSwitch(switchId));
    }

    public Isl fetchOrCreateIsl(IslEndpoint aEnd, IslEndpoint zEnd) {
        return islRepository.findByEndpoints(
                aEnd.getSwitchId(), aEnd.getPortNumber(),
                zEnd.getSwitchId(), zEnd.getPortNumber())
                .orElseGet(() -> makeIsl(aEnd, zEnd));
    }

    public Switch makeSwitch(SwitchId switchId) {
        Switch sw = switchDefaults.fill(Switch.builder())
                .switchId(switchId)
                .build();
        switchRepository.createOrUpdate(sw);

        switchPropertiesRepository.createOrUpdate(
                switchPropertiesDefaults.fill(SwitchProperties.builder())
                        .switchObj(sw)
                        .build());

        return sw;
    }

    public Isl makeIsl(IslEndpoint aEnd, IslEndpoint zEnd) {
        Switch aEndSwitch = fetchOrCreateSwitch(aEnd.getSwitchId());
        Switch zEndSwitch = fetchOrCreateSwitch(zEnd.getSwitchId());

        Instant now = Instant.now();
        Isl isl = islDefaults.fill(Isl.builder())
                .srcSwitch(aEndSwitch).srcPort(aEnd.getPortNumber())
                .destSwitch(zEndSwitch).destPort(zEnd.getPortNumber())
                .timeCreate(now).timeModify(now)
                .build();
        islRepository.createOrUpdate(isl);

        return isl;
    }

    public Flow makeFlow(FlowEndpoint aEnd, FlowEndpoint zEnd, InnerSwitchLink... trace) {
        return makeFlow(aEnd, zEnd, Arrays.asList(trace));
    }

    public Flow makeFlow(FlowEndpoint aEnd, FlowEndpoint zEnd, List<InnerSwitchLink> trace) {
        Flow flow = flowDefaults.fill(Flow.builder())
                .flowId(idProvider.provideFlowId())
                .srcSwitch(fetchOrCreateSwitch(aEnd.getSwitchId()))
                .srcPort(aEnd.getPortNumber())
                .srcVlan(aEnd.getVlanId())
                .destSwitch(fetchOrCreateSwitch(zEnd.getSwitchId()))
                .destPort(zEnd.getPortNumber())
                .destVlan(zEnd.getVlanId())
                .build();
        txManager.doInTransaction(() -> {
            makeFlowPathPair(flow, aEnd, zEnd, trace);
            if (flow.isAllocateProtectedPath()) {
                makeFlowPathPair(flow, aEnd, zEnd, trace, Stream.of("protected"));
            }
            flowRepository.createOrUpdate(flow);
        });

        return flow;
    }

    private void makeFlowPathPair(
            Flow flow, FlowEndpoint aEnd, FlowEndpoint zEnd, List<InnerSwitchLink> forwardTrace) {
        makeFlowPathPair(flow, aEnd, zEnd, forwardTrace, Stream.of());
    }

    private void makeFlowPathPair(
            Flow flow, FlowEndpoint aEnd, FlowEndpoint zEnd, List<InnerSwitchLink> forwardTrace,
            Stream<String> tags) {
        long flowEffectiveId = idProvider.provideFlowEffectiveId();
        makeFlowCookie(flow.getFlowId(), flowEffectiveId);

        List<InnerSwitchLink> reverseTrace = forwardTrace.stream()
                .map(InnerSwitchLink::makeOpposite)
                .collect(Collectors.toList());
        Collections.reverse(reverseTrace);  // inline

        List<PathSegment> forwardSegments = makePathSegments(aEnd.getSwitchId(), zEnd.getSwitchId(), forwardTrace);
        flow.setForwardPath(makePath(
                flow, aEnd, zEnd, forwardSegments, Cookie.buildForwardCookie(flowEffectiveId),
                Stream.concat(tags, Stream.of("forward"))));

        List<PathSegment> reverseSegments = makePathSegments(zEnd.getSwitchId(), aEnd.getSwitchId(), reverseTrace);
        flow.setReversePath(makePath(
                flow, zEnd, aEnd, reverseSegments, Cookie.buildReverseCookie(flowEffectiveId),
                Stream.concat(tags, Stream.of("reverse"))));
    }

    private FlowPath makePath(
            Flow flow, FlowEndpoint ingress, FlowEndpoint egress, List<PathSegment> segments, Cookie cookie,
            Stream<String> tags) {
        PathId pathId = idProvider.providePathId(flow.getFlowId(), tags);
        if (FlowEncapsulationType.TRANSIT_VLAN == flow.getEncapsulationType()) {
            makeTransitVlan(flow.getFlowId(), pathId);
        } else if (FlowEncapsulationType.VXLAN == flow.getEncapsulationType()) {
            makeTransitVxLan(flow.getFlowId(), pathId);
        } else {
            throw new IllegalArgumentException(String.format(
                    "Unsupported flow transit encapsulation %s", flow.getEncapsulationType()));
        }

        // caller responsible for saving this entity into persistence storage
        return flowPathDefaults.fill(FlowPath.builder())
                .pathId(pathId)
                .flow(flow)
                .srcSwitch(fetchOrCreateSwitch(ingress.getSwitchId()))
                .destSwitch(fetchOrCreateSwitch(egress.getSwitchId()))
                .cookie(cookie)
                .meterId(makeFlowMeter(ingress.getSwitchId(), flow.getFlowId(), pathId).getMeterId())
                .bandwidth(flow.getBandwidth())
                .segments(segments)
                .build();
    }

    private List<PathSegment> makePathSegments(
            SwitchId aEndSwitchId, SwitchId zEndSwitchId, List<InnerSwitchLink> trace) {
        Iterator<InnerSwitchLink> aSideIter = trace.iterator();
        Iterator<InnerSwitchLink> zSideIter = trace.iterator();

        if (!zSideIter.hasNext()) {
            return emptyList();
        }

        InnerSwitchLink first = zSideIter.next();  // seek z-side to correct location

        List<PathSegment> results = new ArrayList<>();
        InnerSwitchLink aEnd;
        InnerSwitchLink zEnd = first;
        while (zSideIter.hasNext()) {
            aEnd = aSideIter.next();
            zEnd = zSideIter.next();

            Switch aEndSwitch = fetchOrCreateSwitch(aEnd.getSwitchId());
            Switch zEndSwitch = fetchOrCreateSwitch(zEnd.getSwitchId());

            fetchOrCreateIsl(
                    new IslEndpoint(aEnd.getSwitchId(), aEnd.getZEndPort()),
                    new IslEndpoint(zEnd.getSwitchId(), zEnd.getAEndPort()));

            results.add(PathSegment.builder()
                    .srcSwitch(aEndSwitch).srcPort(aEnd.getZEndPort())
                    .destSwitch(zEndSwitch).destPort(zEnd.getAEndPort())
                    .build());
        }

        if (! aEndSwitchId.equals(first.getSwitchId())) {
            throw new IllegalArgumentException(String.format(
                    "Flow's trace do not start on flow endpoint (a-end switch %s, first trace entry %s)",
                    aEndSwitchId, first));
        }
        if (! zEndSwitchId.equals(zEnd.getSwitchId())) {
            throw new IllegalArgumentException(String.format(
                    "Flow's trace do not end on flow endpoint (z-end switch %s, last trace entry %s)",
                    zEndSwitchId, zEnd));
        }

        return results;
    }

    private FlowCookie makeFlowCookie(String flowId, long effectiveFlowId) {
        FlowCookie flowCookie = FlowCookie.builder()
                .flowId(flowId)
                .unmaskedCookie(effectiveFlowId)
                .build();
        flowCookieRepository.createOrUpdate(flowCookie);
        return flowCookie;
    }

    private FlowMeter makeFlowMeter(SwitchId swId, String flowId, PathId pathId) {
        FlowMeter meter = FlowMeter.builder()
                .switchId(swId)
                .meterId(idProvider.provideMeterId(swId))
                .pathId(pathId)
                .flowId(flowId)
                .build();
        flowMeterRepository.createOrUpdate(meter);
        return meter;
    }

    private TransitVlan makeTransitVlan(String flowId, PathId pathId) {
        TransitVlan entity = TransitVlan.builder()
                .flowId(flowId)
                .pathId(pathId)
                .vlan(idProvider.provideTransitVlanId())
                .build();
        transitVlanRepository.createOrUpdate(entity);
        return entity;
    }

    private Vxlan makeTransitVxLan(String flowId, PathId pathId) {
        Vxlan entity = Vxlan.builder()
                .flowId(flowId)
                .pathId(pathId)
                .vni(idProvider.provideTransitVxLanId())
                .build();
        transitVxLanRepository.createOrUpdate(entity);
        return entity;
    }
}
