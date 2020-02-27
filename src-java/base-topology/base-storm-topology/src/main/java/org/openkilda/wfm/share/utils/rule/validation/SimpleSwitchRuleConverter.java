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

package org.openkilda.wfm.share.utils.rule.validation;

import org.openkilda.adapter.FlowSideAdapter;
import org.openkilda.messaging.info.meter.SwitchMeterEntries;
import org.openkilda.messaging.info.rule.FlowApplyActions;
import org.openkilda.messaging.info.rule.FlowEntry;
import org.openkilda.messaging.info.rule.FlowSetFieldAction;
import org.openkilda.messaging.info.rule.SwitchFlowEntries;
import org.openkilda.model.EncapsulationId;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.FlowPath;
import org.openkilda.model.Meter;
import org.openkilda.model.PathSegment;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;

import org.apache.commons.lang3.math.NumberUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

// TODO: reuse OF flows mod generation code to produce validation artefacts and drop this class completely
public class SimpleSwitchRuleConverter {

    private static final String VLAN_VID = "vlan_vid";
    private static final String IN_PORT = "in_port";

    private final SwitchPropertiesRepository switchPropertiesRepository;

    public SimpleSwitchRuleConverter(SwitchPropertiesRepository switchPropertiesRepository) {
        this.switchPropertiesRepository = switchPropertiesRepository;
    }

    /**
     * Convert {@link FlowPath} to list of {@link SimpleSwitchRule}.
     */
    public List<SimpleSwitchRule> convertFlowPathToSimpleSwitchRules(Flow flow, FlowPath flowPath,
                                                                     EncapsulationId encapsulationId,
                                                                     long flowMeterMinBurstSizeInKbits,
                                                                     double flowMeterBurstCoefficient) {
        List<SimpleSwitchRule> rules = new ArrayList<>();
        if (!flowPath.isProtected()) {
            rules.add(buildIngressSimpleSwitchRule(flow, flowPath, encapsulationId, flowMeterMinBurstSizeInKbits,
                    flowMeterBurstCoefficient));
        }
        rules.addAll(buildTransitAndEgressSimpleSwitchRules(flow, flowPath, encapsulationId));
        return rules;
    }

    private SimpleSwitchRule buildIngressSimpleSwitchRule(Flow flow, FlowPath flowPath,
                                                          EncapsulationId encapsulationId,
                                                          long flowMeterMinBurstSizeInKbits,
                                                          double flowMeterBurstCoefficient) {
        FlowEndpoint ingressEndpoint = FlowSideAdapter.makeIngressAdapter(flow, flowPath).getEndpoint();
        SimpleSwitchRule.SimpleSwitchRuleBuilder rule = SimpleSwitchRule.builder()
                .switchId(flowPath.getSrcSwitch().getSwitchId())
                .cookie(flowPath.getCookie().getValue())
                .inPort(ingressEndpoint.getPortNumber())
                .meterId(flowPath.getMeterId() != null ? flowPath.getMeterId().getValue() : null)
                .meterRate(flow.getBandwidth())
                .meterBurstSize(Meter.calculateBurstSize(flow.getBandwidth(), flowMeterMinBurstSizeInKbits,
                        flowMeterBurstCoefficient, flowPath.getSrcSwitch().getDescription()))
                .meterFlags(Meter.getMeterKbpsFlags());

        if (isMultiTableSwitch(ingressEndpoint.getSwitchId())) {
            addMultiTableIngressMatch(rule, ingressEndpoint);
        } else {
            rule.inVlan(ingressEndpoint.getOuterVlanId());
        }

        if (flow.isOneSwitchFlow()) {
            FlowEndpoint egressEndpoint = FlowSideAdapter.makeEgressAdapter(flow, flowPath).getEndpoint();
            rule.outPort(egressEndpoint.getPortNumber());
            rule.outVlan(egressEndpoint.getOuterVlanId());
            addVlanEncapsulationActions(rule, ingressEndpoint.getVlanStack(), egressEndpoint.getVlanStack());
        } else {
            PathSegment ingressSegment = flowPath.getSegments().stream()
                    .filter(segment -> segment.getSrcSwitch().getSwitchId()
                            .equals(flowPath.getSrcSwitch().getSwitchId()))
                    .findAny()
                    .orElseThrow(() -> new IllegalStateException(
                            String.format("PathSegment was not found for ingress flow rule, flowId: %s",
                                    flow.getFlowId())));

            rule.outPort(ingressSegment.getSrcPort());
            if (flow.getEncapsulationType().equals(FlowEncapsulationType.TRANSIT_VLAN)) {
                int vlanId = encapsulationId.getEncapsulationId();
                rule.outVlan(vlanId);
                addVlanEncapsulationActions(
                        rule, ingressEndpoint.getVlanStack(),
                        Collections.singletonList(vlanId));
            } else if (flow.getEncapsulationType().equals(FlowEncapsulationType.VXLAN)) {
                rule.tunnelId(encapsulationId.getEncapsulationId());
            }
        }

        return rule.build();
    }

    private List<SimpleSwitchRule> buildTransitAndEgressSimpleSwitchRules(Flow flow, FlowPath flowPath,
                                                                          EncapsulationId encapsulationId) {
        if (flow.isOneSwitchFlow()) {
            return Collections.emptyList();
        }

        List<PathSegment> orderedSegments = flowPath.getSegments().stream()
                .sorted(Comparator.comparingInt(PathSegment::getSeqId))
                .collect(Collectors.toList());

        List<SimpleSwitchRule> rules = new ArrayList<>();

        for (int i = 1; i < orderedSegments.size(); i++) {
            PathSegment srcPathSegment = orderedSegments.get(i - 1);
            PathSegment dstPathSegment = orderedSegments.get(i);
            rules.add(buildTransitSimpleSwitchRule(flow, flowPath, srcPathSegment, dstPathSegment, encapsulationId));
        }

        PathSegment egressSegment = orderedSegments.get(orderedSegments.size() - 1);
        if (!egressSegment.getDestSwitch().getSwitchId().equals(flowPath.getDestSwitch().getSwitchId())) {
            throw new IllegalStateException(
                    String.format("PathSegment was not found for egress flow rule, flowId: %s", flow.getFlowId()));
        }
        rules.add(buildEgressSimpleSwitchRule(flow, flowPath, egressSegment, encapsulationId));

        return rules;
    }

    private SimpleSwitchRule buildTransitSimpleSwitchRule(Flow flow, FlowPath flowPath,
                                                          PathSegment srcPathSegment, PathSegment dstPathSegment,
                                                          EncapsulationId encapsulationId) {

        SimpleSwitchRule rule = SimpleSwitchRule.builder()
                .switchId(srcPathSegment.getDestSwitch().getSwitchId())
                .inPort(srcPathSegment.getDestPort())
                .outPort(dstPathSegment.getSrcPort())
                .cookie(flowPath.getCookie().getValue())
                .build();
        if (flow.getEncapsulationType().equals(FlowEncapsulationType.TRANSIT_VLAN)) {
            rule.setInVlan(encapsulationId.getEncapsulationId());
        } else if (flow.getEncapsulationType().equals(FlowEncapsulationType.VXLAN)) {
            rule.setTunnelId(encapsulationId.getEncapsulationId());
        }

        return rule;
    }

    private SimpleSwitchRule buildEgressSimpleSwitchRule(Flow flow, FlowPath flowPath,
                                                         PathSegment egressSegment,
                                                         EncapsulationId encapsulationId) {
        FlowEndpoint egressEndpoint = FlowSideAdapter.makeEgressAdapter(flow, flowPath).getEndpoint();
        SimpleSwitchRule.SimpleSwitchRuleBuilder rule = SimpleSwitchRule.builder()
                .switchId(flowPath.getDestSwitch().getSwitchId())
                .outPort(egressEndpoint.getPortNumber())
                .outVlan(egressEndpoint.getOuterVlanId())
                .inPort(egressSegment.getDestPort())
                .cookie(flowPath.getCookie().getValue());

        if (flow.getEncapsulationType().equals(FlowEncapsulationType.TRANSIT_VLAN)) {
            int vlanId = encapsulationId.getEncapsulationId();
            rule.inVlan(vlanId);
            addVlanEncapsulationActions(rule, Collections.singletonList(vlanId), egressEndpoint.getVlanStack());
        } else if (flow.getEncapsulationType().equals(FlowEncapsulationType.VXLAN)) {
            FlowEndpoint ingressEndpoint = FlowSideAdapter.makeIngressAdapter(flow, flowPath).getEndpoint();
            rule.tunnelId(encapsulationId.getEncapsulationId());
            addVlanEncapsulationActions(rule, ingressEndpoint.getVlanStack(), egressEndpoint.getVlanStack());
        }

        return rule.build();
    }

    /**
     * Convert {@link SwitchFlowEntries} to list of {@link SimpleSwitchRule}.
     */
    public List<SimpleSwitchRule> convertSwitchFlowEntriesToSimpleSwitchRules(SwitchFlowEntries rules,
                                                                              SwitchMeterEntries meters) {
        if (rules == null || rules.getFlowEntries() == null) {
            return Collections.emptyList();
        }

        List<SimpleSwitchRule> simpleRules = new ArrayList<>();
        for (FlowEntry flowEntry : rules.getFlowEntries()) {
            simpleRules.add(buildSimpleSwitchRule(rules.getSwitchId(), flowEntry, meters));
        }
        return simpleRules;
    }

    private SimpleSwitchRule buildSimpleSwitchRule(SwitchId switchId, FlowEntry flowEntry, SwitchMeterEntries meters) {
        SimpleSwitchRule rule = SimpleSwitchRule.builder()
                .switchId(switchId)
                .cookie(flowEntry.getCookie())
                .pktCount(flowEntry.getPacketCount())
                .byteCount(flowEntry.getByteCount())
                .version(flowEntry.getVersion())
                .build();

        if (flowEntry.getMatch() != null) {
            rule.setInPort(NumberUtils.toInt(flowEntry.getMatch().getInPort()));
            rule.setInVlan(NumberUtils.toInt(flowEntry.getMatch().getVlanVid()));
            rule.setTunnelId(Optional.ofNullable(flowEntry.getMatch().getTunnelId())
                    .map(Integer::decode)
                    .orElse(NumberUtils.INTEGER_ZERO));
        }

        if (flowEntry.getInstructions() != null) {
            if (flowEntry.getInstructions().getApplyActions() != null) {
                FlowApplyActions applyActions = flowEntry.getInstructions().getApplyActions();
                rule.setOutVlan(applyActions.getFieldActions().stream()
                        .filter(action -> VLAN_VID.equals(action.getFieldName()))
                        .findFirst()
                        .map(FlowSetFieldAction::getFieldValue)
                        .map(NumberUtils::toInt)
                        .orElse(NumberUtils.INTEGER_ZERO));
                String outPort = applyActions.getFlowOutput();
                if (IN_PORT.equals(outPort) && flowEntry.getMatch() != null) {
                    outPort = flowEntry.getMatch().getInPort();
                }
                rule.setOutPort(NumberUtils.toInt(outPort));

                if (rule.getTunnelId() == NumberUtils.INTEGER_ZERO) {
                    rule.setTunnelId(Optional.ofNullable(applyActions.getPushVxlan())
                            .map(Integer::parseInt)
                            .orElse(NumberUtils.INTEGER_ZERO));
                }

                if (applyActions.getEncapsulationActions() != null) {
                    rule.setEncapsulationActions(applyActions.getEncapsulationActions());
                }
            }

            Optional.ofNullable(flowEntry.getInstructions().getGoToMeter())
                    .ifPresent(meterId -> {
                        rule.setMeterId(meterId);
                        if (meters != null && meters.getMeterEntries() != null) {
                            meters.getMeterEntries().stream()
                                    .filter(entry -> meterId.equals(entry.getMeterId()))
                                    .findFirst()
                                    .ifPresent(entry -> {
                                        rule.setMeterRate(entry.getRate());
                                        rule.setMeterBurstSize(entry.getBurstSize());
                                        rule.setMeterFlags(entry.getFlags());
                                    });
                        }
                    });
        }

        return rule;
    }

    /**
     * Fields are defined into org.openkilda.floodlight.utils.MetadataAdapter.
     */
    private void addMultiTableIngressMatch(SimpleSwitchRule.SimpleSwitchRuleBuilder rule, FlowEndpoint endpoint) {
        if (FlowEndpoint.isVlanIdSet(endpoint.getOuterVlanId())) {
            long presenceFlag = 0x01000000_00000000L;
            long value = presenceFlag | endpoint.getOuterVlanId();
            long mask = presenceFlag | 0x00000000_00000fffL;

            rule.matchMetadata(formatMetadataMatch(value, mask));
        }
    }

    /**
     * Inspired by org.openkilda.floodlight.utils.OfAdapter#makeVlanReplaceActions.
     *
     * <p>Get rid of this ugly code duplication.
     */
    private void addVlanEncapsulationActions(
            SimpleSwitchRule.SimpleSwitchRuleBuilder rule,
            List<Integer> currentVlanStack, List<Integer> desiredVlanStack) {
        Iterator<Integer> currentIter = currentVlanStack.iterator();
        Iterator<Integer> desiredIter = desiredVlanStack.iterator();

        while (currentIter.hasNext() && desiredIter.hasNext()) {
            Integer current = currentIter.next();
            Integer desired = desiredIter.next();
            if (current == null || desired == null) {
                throw new IllegalArgumentException(
                        "Null elements are not allowed inside currentVlanStack and desiredVlanStack arguments");
            }

            if (!current.equals(desired)) {
                // remove all extra VLANs
                while (currentIter.hasNext()) {
                    currentIter.next();
                    rule.encapsulationAction("vlan_pop");
                }
                // rewrite existing VLAN stack "head"
                rule.encapsulationAction(formatSetVlanAction(desired));
                break;
            }
        }

        // remove all extra VLANs (if previous loops ends with lack of desired VLANs
        while (currentIter.hasNext()) {
            currentIter.next();
            rule.encapsulationAction("vlan_pop");
        }
        while (desiredIter.hasNext()) {
            rule.encapsulationAction("vlan_push");
            rule.encapsulationAction(formatSetVlanAction(desiredIter.next()));
        }
    }

    private boolean isMultiTableSwitch(SwitchId switchId) {
        return switchPropertiesRepository.findBySwitchId(switchId)
                .map(SwitchProperties::isMultiTable)
                .orElse(false);
    }

    private String formatMetadataMatch(long value, long mask) {
        return String.format("0x%016x/0x%016x", value, mask);
    }

    public static String formatSetVlanAction(int vlanId) {
        return String.format("vlan_vid=%d", vlanId);
    }
}
