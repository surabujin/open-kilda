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

import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import org.openkilda.messaging.info.meter.MeterEntry;
import org.openkilda.messaging.info.meter.SwitchMeterEntries;
import org.openkilda.messaging.info.rule.FlowApplyActions;
import org.openkilda.messaging.info.rule.FlowEntry;
import org.openkilda.messaging.info.rule.FlowInstructions;
import org.openkilda.messaging.info.rule.FlowMatchField;
import org.openkilda.messaging.info.rule.FlowSetFieldAction;
import org.openkilda.messaging.info.rule.SwitchFlowEntries;
import org.openkilda.model.Cookie;
import org.openkilda.model.Flow;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.FlowPath;
import org.openkilda.model.FlowPathStatus;
import org.openkilda.model.FlowStatus;
import org.openkilda.model.Meter;
import org.openkilda.model.MeterId;
import org.openkilda.model.PathId;
import org.openkilda.model.PathSegment;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.TransitVlan;
import org.openkilda.model.Vxlan;

import com.google.common.collect.Lists;
import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SimpleSwitchRuleConverterTest {
    private static final SwitchId TEST_SWITCH_ID_A = new SwitchId(1);
    private static final SwitchId TEST_SWITCH_ID_B = new SwitchId(2);
    private static final SwitchId TEST_SWITCH_ID_C = new SwitchId(3);
    private static final SwitchId TEST_SWITCH_ID_D = new SwitchId(4);

    private static final String TEST_FLOW_ID_A = "test_flow_id_a";
    private static final String TEST_FLOW_ID_B = "test_flow_id_b";

    private static final int FLOW_A_SRC_PORT = 10;
    private static final int FLOW_A_DST_PORT = 20;
    private static final int FLOW_A_SEGMENT_A_SRC_PORT = 11;
    private static final int FLOW_A_SEGMENT_A_DST_PORT = 15;
    private static final int FLOW_A_SEGMENT_B_SRC_PORT = 16;
    private static final int FLOW_A_SEGMENT_B_DST_PORT = 19;
    private static final int FLOW_A_SRC_VLAN = 110;
    private static final int FLOW_A_ENCAP_ID = 120;
    private static final PathId FLOW_A_FORWARD_PATH_ID = new PathId(TEST_FLOW_ID_A + "_forward_path");
    private static final int FLOW_A_DST_VLAN = 140;
    private static final long FLOW_A_FORWARD_METER_ID = 32L;
    private static final long FLOW_A_FORWARD_COOKIE = Cookie.buildForwardCookie(1L).getValue();
    private static final long FLOW_A_BANDWIDTH = 10000;
    private static final int FLOW_B_SRC_PORT = 1;
    private static final int FLOW_B_SRC_VLAN = 150;
    private static final int FLOW_B_DST_VLAN = 160;
    private static final long FLOW_B_FORWARD_COOKIE = Cookie.buildForwardCookie(2L).getValue();
    private static final long FLOW_B_FORWARD_METER_ID = 34L;
    private static final long FLOW_B_BANDWIDTH = 11000;

    private static final long MIN_BURST_SIZE_IN_KBITS = 1024;
    private static final double BURST_COEFFICIENT = 1.05;

    private SimpleSwitchRuleConverter simpleSwitchRuleConverter = new SimpleSwitchRuleConverter();

    @Test
    public void shouldConvertFlowPathWithTransitVlanEncapToSimpleSwitchRules() {
        Flow flow = buildFlow(FlowEncapsulationType.TRANSIT_VLAN);
        List<SimpleSwitchRule> expectedSwitchRules = getSimpleSwitchRuleForTransitVlan();

        List<SimpleSwitchRule> switchRules = simpleSwitchRuleConverter.convertFlowPathToSimpleSwitchRules(flow,
                flow.getForwardPath(),
                TransitVlan.builder()
                        .flowId(TEST_FLOW_ID_A)
                        .pathId(FLOW_A_FORWARD_PATH_ID)
                        .vlan(FLOW_A_ENCAP_ID)
                        .build(),
                MIN_BURST_SIZE_IN_KBITS,
                BURST_COEFFICIENT);

        assertEquals(expectedSwitchRules, switchRules);
    }

    @Test
    public void shouldConvertFlowPathWithVxlanEncapToSimpleSwitchRules() {
        Flow flow = buildFlow(FlowEncapsulationType.VXLAN);
        List<SimpleSwitchRule> expectedSwitchRules = getSimpleSwitchRuleForVxlan();

        List<SimpleSwitchRule> switchRules = simpleSwitchRuleConverter.convertFlowPathToSimpleSwitchRules(flow,
                flow.getForwardPath(),
                Vxlan.builder()
                        .flowId(TEST_FLOW_ID_A)
                        .pathId(FLOW_A_FORWARD_PATH_ID)
                        .vni(FLOW_A_ENCAP_ID)
                        .build(),
                MIN_BURST_SIZE_IN_KBITS,
                BURST_COEFFICIENT);

        assertEquals(expectedSwitchRules, switchRules);
    }

    @Test
    public void shouldConvertFlowPathOneSwitchFlowToSimpleSwitchRules() {
        Flow flow = buildOneSwitchPortFlow();
        List<SimpleSwitchRule> expectedSwitchRules = getSimpleSwitchRuleForOneSwitchFlow();

        List<SimpleSwitchRule> switchRules = simpleSwitchRuleConverter.convertFlowPathToSimpleSwitchRules(flow,
                flow.getForwardPath(), null, MIN_BURST_SIZE_IN_KBITS, BURST_COEFFICIENT);

        assertEquals(expectedSwitchRules, switchRules);
    }

    @Test
    public void shouldConvertFlowEntriesTransitVlanFlowToSimpleSwitchRules() {
        List<SimpleSwitchRule> expectedSwitchRules = getSimpleSwitchRuleForTransitVlan();

        List<SwitchFlowEntries> switchFlowEntries = getSwitchFlowEntriesWithTransitVlan();
        List<SwitchMeterEntries> switchMeterEntries = getSwitchMeterEntries();

        for (int i = 0; i < switchFlowEntries.size(); i++) {
            List<SimpleSwitchRule> switchRules =
                    simpleSwitchRuleConverter.convertSwitchFlowEntriesToSimpleSwitchRules(switchFlowEntries.get(i),
                            switchMeterEntries.get(i));
            assertThat(switchRules, hasSize(1));
            assertEquals(expectedSwitchRules.get(i), switchRules.get(0));
        }
    }

    @Test
    public void shouldConvertFlowEntriesVxlanFlowToSimpleSwitchRules() {
        List<SimpleSwitchRule> expectedSwitchRules = getSimpleSwitchRuleForVxlan();

        List<SwitchFlowEntries> switchFlowEntries = getSwitchFlowEntriesWithVxlan();
        List<SwitchMeterEntries> switchMeterEntries = getSwitchMeterEntries();

        for (int i = 0; i < switchFlowEntries.size(); i++) {
            List<SimpleSwitchRule> switchRules =
                    simpleSwitchRuleConverter.convertSwitchFlowEntriesToSimpleSwitchRules(switchFlowEntries.get(i),
                            switchMeterEntries.get(i));
            assertThat(switchRules, hasSize(1));
            assertEquals(expectedSwitchRules.get(i), switchRules.get(0));
        }
    }

    @Test
    public void shouldConvertFlowEntriesOneSwitchFlowToSimpleSwitchRules() {
        List<SimpleSwitchRule> expectedSwitchRules = getSimpleSwitchRuleForOneSwitchFlow();

        List<SwitchFlowEntries> switchFlowEntries = getSwitchFlowEntriesOneSwitchFlow();

        List<SimpleSwitchRule> switchRules =
                simpleSwitchRuleConverter.convertSwitchFlowEntriesToSimpleSwitchRules(switchFlowEntries.get(0),
                        getSwitchMeterEntriesOneSwitchFlow());

        assertEquals(expectedSwitchRules, switchRules);
    }

    private Flow buildFlow(FlowEncapsulationType flowEncapsulationType) {
        Switch switchA = Switch.builder().switchId(TEST_SWITCH_ID_A).description("").build();
        Switch switchB = Switch.builder().switchId(TEST_SWITCH_ID_B).description("").build();
        Switch switchC = Switch.builder().switchId(TEST_SWITCH_ID_C).description("").build();

        Flow flow = Flow.builder()
                .flowId(TEST_FLOW_ID_A)
                .srcSwitch(switchA)
                .srcPort(FLOW_A_SRC_PORT)
                .srcVlan(FLOW_A_SRC_VLAN)
                .destSwitch(switchC)
                .destPort(FLOW_A_DST_PORT)
                .destVlan(FLOW_A_DST_VLAN)
                .allocateProtectedPath(true)
                .encapsulationType(flowEncapsulationType)
                .bandwidth(FLOW_A_BANDWIDTH)
                .status(FlowStatus.UP)
                .timeCreate(Instant.now())
                .timeModify(Instant.now())
                .build();

        FlowPath forwardFlowPath = FlowPath.builder()
                .pathId(FLOW_A_FORWARD_PATH_ID)
                .flow(flow)
                .cookie(new Cookie(FLOW_A_FORWARD_COOKIE))
                .meterId(new MeterId(FLOW_A_FORWARD_METER_ID))
                .srcSwitch(switchA)
                .destSwitch(switchC)
                .status(FlowPathStatus.ACTIVE)
                .bandwidth(FLOW_A_BANDWIDTH)
                .timeCreate(Instant.now())
                .timeModify(Instant.now())
                .build();
        flow.setForwardPath(forwardFlowPath);

        PathSegment forwardSegmentA = PathSegment.builder()
                .srcSwitch(switchA)
                .srcPort(FLOW_A_SEGMENT_A_SRC_PORT)
                .destSwitch(switchB)
                .destPort(FLOW_A_SEGMENT_A_DST_PORT)
                .build();

        PathSegment forwardSegmentB = PathSegment.builder()
                .srcSwitch(switchB)
                .srcPort(FLOW_A_SEGMENT_B_SRC_PORT)
                .destSwitch(switchC)
                .destPort(FLOW_A_SEGMENT_B_DST_PORT)
                .build();
        forwardFlowPath.setSegments(Lists.newArrayList(forwardSegmentA, forwardSegmentB));

        return flow;
    }

    private Flow buildOneSwitchPortFlow() {
        Switch switchD = Switch.builder().switchId(TEST_SWITCH_ID_D).description("").build();

        Flow flow = Flow.builder()
                .flowId(TEST_FLOW_ID_B)
                .srcSwitch(switchD)
                .srcPort(FLOW_B_SRC_PORT)
                .srcVlan(FLOW_B_SRC_VLAN)
                .destSwitch(switchD)
                .destPort(FLOW_B_SRC_PORT)
                .destVlan(FLOW_B_DST_VLAN)
                .encapsulationType(FlowEncapsulationType.TRANSIT_VLAN)
                .bandwidth(FLOW_B_BANDWIDTH)
                .status(FlowStatus.UP)
                .timeCreate(Instant.now())
                .timeModify(Instant.now())
                .build();

        FlowPath forwardFlowPath = FlowPath.builder()
                .pathId(new PathId(TEST_FLOW_ID_B + "_forward_path"))
                .flow(flow)
                .cookie(new Cookie(FLOW_B_FORWARD_COOKIE))
                .meterId(new MeterId(FLOW_B_FORWARD_METER_ID))
                .srcSwitch(switchD)
                .destSwitch(switchD)
                .status(FlowPathStatus.ACTIVE)
                .bandwidth(FLOW_B_BANDWIDTH)
                .segments(Collections.emptyList())
                .timeCreate(Instant.now())
                .timeModify(Instant.now())
                .build();
        flow.setForwardPath(forwardFlowPath);

        return flow;
    }

    protected List<SwitchFlowEntries> getSwitchFlowEntriesWithTransitVlan() {
        List<SwitchFlowEntries> switchEntries = new ArrayList<>();

        switchEntries.add(getSwitchFlowEntries(TEST_SWITCH_ID_A,
                getFlowEntry(FLOW_A_FORWARD_COOKIE, FLOW_A_SRC_PORT, FLOW_A_SRC_VLAN,
                        String.valueOf(FLOW_A_SEGMENT_A_SRC_PORT), 0, getFlowSetFieldAction(FLOW_A_ENCAP_ID),
                        (long) FLOW_A_FORWARD_METER_ID, false)));

        switchEntries.add(getSwitchFlowEntries(TEST_SWITCH_ID_B,
                getFlowEntry(FLOW_A_FORWARD_COOKIE, FLOW_A_SEGMENT_A_DST_PORT, FLOW_A_ENCAP_ID,
                        String.valueOf(FLOW_A_SEGMENT_B_SRC_PORT), 0, null, null, false)));

        switchEntries.add(getSwitchFlowEntries(TEST_SWITCH_ID_C,
                getFlowEntry(FLOW_A_FORWARD_COOKIE, FLOW_A_SEGMENT_B_DST_PORT, FLOW_A_ENCAP_ID,
                        String.valueOf(FLOW_A_DST_PORT), 0, getFlowSetFieldAction(FLOW_A_DST_VLAN), null, false)));

        return switchEntries;
    }

    protected List<SwitchFlowEntries> getSwitchFlowEntriesWithVxlan() {
        List<SwitchFlowEntries> switchEntries = new ArrayList<>();

        switchEntries.add(getSwitchFlowEntries(TEST_SWITCH_ID_A,
                getFlowEntry(FLOW_A_FORWARD_COOKIE, FLOW_A_SRC_PORT, FLOW_A_SRC_VLAN,
                        String.valueOf(FLOW_A_SEGMENT_A_SRC_PORT), FLOW_A_ENCAP_ID, null,
                        (long) FLOW_A_FORWARD_METER_ID, true)));

        switchEntries.add(getSwitchFlowEntries(TEST_SWITCH_ID_B,
                getFlowEntry(FLOW_A_FORWARD_COOKIE, FLOW_A_SEGMENT_A_DST_PORT, 0,
                        String.valueOf(FLOW_A_SEGMENT_B_SRC_PORT), FLOW_A_ENCAP_ID, null, null, false)));

        switchEntries.add(getSwitchFlowEntries(TEST_SWITCH_ID_C,
                getFlowEntry(FLOW_A_FORWARD_COOKIE, FLOW_A_SEGMENT_B_DST_PORT, 0, String.valueOf(FLOW_A_DST_PORT),
                        FLOW_A_ENCAP_ID, getFlowSetFieldAction(FLOW_A_DST_VLAN), null, false)));

        return switchEntries;
    }

    private List<SwitchMeterEntries> getSwitchMeterEntries() {
        List<SwitchMeterEntries> switchMeterEntries = new ArrayList<>();
        switchMeterEntries.add(SwitchMeterEntries.builder()
                .switchId(TEST_SWITCH_ID_A)
                .meterEntries(Collections.singletonList(MeterEntry.builder()
                        .meterId(FLOW_A_FORWARD_METER_ID)
                        .rate(FLOW_A_BANDWIDTH)
                        .burstSize(Meter
                                .calculateBurstSize(FLOW_A_BANDWIDTH, MIN_BURST_SIZE_IN_KBITS, BURST_COEFFICIENT, ""))
                        .flags(Meter.getMeterKbpsFlags())
                        .build()))
                .build());

        switchMeterEntries.add(SwitchMeterEntries.builder()
                .switchId(TEST_SWITCH_ID_B)
                .meterEntries(Collections.emptyList())
                .build());

        switchMeterEntries.add(SwitchMeterEntries.builder()
                .switchId(TEST_SWITCH_ID_C)
                .meterEntries(Collections.emptyList())
                .build());

        return switchMeterEntries;
    }

    private List<SwitchFlowEntries> getSwitchFlowEntriesOneSwitchFlow() {
        List<SwitchFlowEntries> switchEntries = new ArrayList<>();

        switchEntries.add(getSwitchFlowEntries(TEST_SWITCH_ID_D,
                getFlowEntry(FLOW_B_FORWARD_COOKIE, FLOW_B_SRC_PORT, FLOW_B_SRC_VLAN, "in_port", 0,
                        getFlowSetFieldAction(FLOW_B_DST_VLAN), (long) FLOW_B_FORWARD_METER_ID, false)));

        return switchEntries;
    }

    private SwitchMeterEntries getSwitchMeterEntriesOneSwitchFlow() {
        List<MeterEntry> meterEntries = new ArrayList<>();
        meterEntries.add(MeterEntry.builder()
                .meterId(FLOW_B_FORWARD_METER_ID)
                .rate(FLOW_B_BANDWIDTH)
                .burstSize(Meter
                        .calculateBurstSize(FLOW_B_BANDWIDTH, MIN_BURST_SIZE_IN_KBITS, BURST_COEFFICIENT, ""))
                .flags(Meter.getMeterKbpsFlags())
                .build());

        return SwitchMeterEntries.builder()
                .switchId(TEST_SWITCH_ID_D)
                .meterEntries(meterEntries)
                .build();
    }

    private SwitchFlowEntries getSwitchFlowEntries(SwitchId switchId, FlowEntry... flowEntries) {
        return SwitchFlowEntries.builder()
                .switchId(switchId)
                .flowEntries(Lists.newArrayList(flowEntries))
                .build();
    }

    private FlowEntry getFlowEntry(long cookie, int srcPort, int srcVlan, String dstPort, int tunnelId,
                                   FlowSetFieldAction flowSetFieldAction, Long meterId, boolean tunnelIdIngressRule) {
        return FlowEntry.builder()
                .cookie(cookie)
                .packetCount(7)
                .byteCount(480)
                .version("OF_13")
                .match(FlowMatchField.builder()
                        .inPort(String.valueOf(srcPort))
                        .vlanVid(String.valueOf(srcVlan))
                        .tunnelId(!tunnelIdIngressRule ? String.valueOf(tunnelId) : null)
                        .build())
                .instructions(FlowInstructions.builder()
                        .applyActions(FlowApplyActions.builder()
                                .flowOutput(dstPort)
                                .fieldAction(flowSetFieldAction)
                                .pushVxlan(tunnelIdIngressRule ? String.valueOf(tunnelId) : null)
                                .build())
                        .goToMeter(meterId)
                        .build())
                .build();
    }

    private FlowSetFieldAction getFlowSetFieldAction(int dstVlan) {
        return FlowSetFieldAction.builder()
                .fieldName("vlan_vid")
                .fieldValue(String.valueOf(dstVlan))
                .build();
    }

    private List<SimpleSwitchRule> getSimpleSwitchRuleForTransitVlan() {
        List<SimpleSwitchRule> simpleSwitchRules = new ArrayList<>();
        simpleSwitchRules.add(SimpleSwitchRule.builder()
                .switchId(TEST_SWITCH_ID_A)
                .cookie(FLOW_A_FORWARD_COOKIE)
                .inPort(FLOW_A_SRC_PORT)
                .outPort(FLOW_A_SEGMENT_A_SRC_PORT)
                .inVlan(FLOW_A_SRC_VLAN)
                .outVlan(FLOW_A_ENCAP_ID)
                .meterId(FLOW_A_FORWARD_METER_ID)
                .meterRate(FLOW_A_BANDWIDTH)
                .meterBurstSize(Meter
                        .calculateBurstSize(FLOW_A_BANDWIDTH, MIN_BURST_SIZE_IN_KBITS, BURST_COEFFICIENT, ""))
                .meterFlags(Meter.getMeterKbpsFlags())
                .build());
        simpleSwitchRules.add(SimpleSwitchRule.builder()
                .switchId(TEST_SWITCH_ID_B)
                .cookie(FLOW_A_FORWARD_COOKIE)
                .inPort(FLOW_A_SEGMENT_A_DST_PORT)
                .outPort(FLOW_A_SEGMENT_B_SRC_PORT)
                .inVlan(FLOW_A_ENCAP_ID)
                .build());
        simpleSwitchRules.add(SimpleSwitchRule.builder()
                .switchId(TEST_SWITCH_ID_C)
                .cookie(FLOW_A_FORWARD_COOKIE)
                .inPort(FLOW_A_SEGMENT_B_DST_PORT)
                .outPort(FLOW_A_DST_PORT)
                .inVlan(FLOW_A_ENCAP_ID)
                .outVlan(FLOW_A_DST_VLAN)
                .build());
        return simpleSwitchRules;
    }

    private List<SimpleSwitchRule> getSimpleSwitchRuleForVxlan() {
        List<SimpleSwitchRule> simpleSwitchRules = new ArrayList<>();
        simpleSwitchRules.add(SimpleSwitchRule.builder()
                .switchId(TEST_SWITCH_ID_A)
                .cookie(FLOW_A_FORWARD_COOKIE)
                .inPort(FLOW_A_SRC_PORT)
                .outPort(FLOW_A_SEGMENT_A_SRC_PORT)
                .inVlan(FLOW_A_SRC_VLAN)
                .tunnelId(FLOW_A_ENCAP_ID)
                .meterId(FLOW_A_FORWARD_METER_ID)
                .meterRate(FLOW_A_BANDWIDTH)
                .meterBurstSize(Meter
                        .calculateBurstSize(FLOW_A_BANDWIDTH, MIN_BURST_SIZE_IN_KBITS, BURST_COEFFICIENT, ""))
                .meterFlags(Meter.getMeterKbpsFlags())
                .build());
        simpleSwitchRules.add(SimpleSwitchRule.builder()
                .switchId(TEST_SWITCH_ID_B)
                .cookie(FLOW_A_FORWARD_COOKIE)
                .inPort(FLOW_A_SEGMENT_A_DST_PORT)
                .outPort(FLOW_A_SEGMENT_B_SRC_PORT)
                .tunnelId(FLOW_A_ENCAP_ID)
                .build());
        simpleSwitchRules.add(SimpleSwitchRule.builder()
                .switchId(TEST_SWITCH_ID_C)
                .cookie(FLOW_A_FORWARD_COOKIE)
                .inPort(FLOW_A_SEGMENT_B_DST_PORT)
                .outPort(FLOW_A_DST_PORT)
                .tunnelId(FLOW_A_ENCAP_ID)
                .outVlan(FLOW_A_DST_VLAN)
                .build());
        return simpleSwitchRules;
    }

    private List<SimpleSwitchRule> getSimpleSwitchRuleForOneSwitchFlow() {
        List<SimpleSwitchRule> simpleSwitchRules = new ArrayList<>();
        simpleSwitchRules.add(SimpleSwitchRule.builder()
                .switchId(TEST_SWITCH_ID_D)
                .cookie(FLOW_B_FORWARD_COOKIE)
                .inPort(FLOW_B_SRC_PORT)
                .outPort(FLOW_B_SRC_PORT)
                .inVlan(FLOW_B_SRC_VLAN)
                .outVlan(FLOW_B_DST_VLAN)
                .meterId(FLOW_B_FORWARD_METER_ID)
                .meterRate(FLOW_B_BANDWIDTH)
                .meterBurstSize(Meter
                        .calculateBurstSize(FLOW_B_BANDWIDTH, MIN_BURST_SIZE_IN_KBITS, BURST_COEFFICIENT, ""))
                .meterFlags(Meter.getMeterKbpsFlags())
                .build());
        return simpleSwitchRules;
    }
}
