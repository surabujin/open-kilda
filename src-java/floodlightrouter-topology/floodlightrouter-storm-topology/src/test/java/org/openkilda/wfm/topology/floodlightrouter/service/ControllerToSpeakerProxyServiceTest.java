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

package org.openkilda.wfm.topology.floodlightrouter.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

import org.openkilda.messaging.command.BroadcastWrapper;
import org.openkilda.messaging.command.CommandMessage;
import org.openkilda.messaging.command.stats.StatsRequest;
import org.openkilda.model.SwitchId;
import org.openkilda.stubs.ManualClock;
import org.openkilda.wfm.topology.floodlightrouter.model.RegionMappingUpdate;

import com.google.common.collect.ImmutableSet;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;

@RunWith(MockitoJUnitRunner.class)
public class ControllerToSpeakerProxyServiceTest {
    private static final String REGION_ALPHA = "region-alpha";
    private static final String REGION_BETA = "region-beta";
    private static final String REGION_GAMMA = "region-gamma";
    private static final String REGION_DELTA = "region-delta";
    
    private static final SwitchId SWITCH_ALPHA = new SwitchId(1);
    private static final SwitchId SWITCH_BETA = new SwitchId(2);
    private static final SwitchId SWITCH_GAMMA = new SwitchId(3);

    private final ManualClock clock = new ManualClock();
    private final Duration switchMappingRemoveDelay = Duration.ofSeconds(5);

    @Mock
    private ControllerToSpeakerProxyCarrier carrier;

    @Test
    public void verifyStatsPreferRoRegions() {
        ControllerToSpeakerProxyService subject = makeSubject();

        // sw-alpha in alpha - RO
        subject.switchMappingUpdate(new RegionMappingUpdate(SWITCH_ALPHA, REGION_ALPHA, false));

        // sw-alpha in beta - RO+RW
        subject.switchMappingUpdate(new RegionMappingUpdate(SWITCH_ALPHA, REGION_BETA, false));
        subject.switchMappingUpdate(new RegionMappingUpdate(SWITCH_ALPHA, REGION_BETA, true));
        // sw-beta in beta - RO
        subject.switchMappingUpdate(new RegionMappingUpdate(SWITCH_BETA, REGION_BETA, false));
        subject.switchMappingUpdate(new RegionMappingUpdate(SWITCH_BETA, REGION_BETA, true));

        verifyZeroInteractions(carrier);

        String correlationId = "dummy-request";
        StatsRequest request = new StatsRequest(Collections.emptyList());
        subject.statsRequest(request, correlationId);

        // RO only region
        verify(carrier).sendToSpeaker(
                makeStatsRegionRequest(request, ImmutableSet.of(SWITCH_ALPHA), correlationId), REGION_ALPHA);
        verify(carrier).sendToSpeaker(
                makeStatsRegionRequest(request, ImmutableSet.of(SWITCH_BETA), correlationId), REGION_BETA);
        verifyNoMoreInteractions(carrier);
    }

    private ControllerToSpeakerProxyService makeSubject() {
        Set<String> allRegions = ImmutableSet.of(
                REGION_ALPHA, REGION_BETA, REGION_GAMMA, REGION_DELTA);
        return new ControllerToSpeakerProxyService(clock, carrier, allRegions, switchMappingRemoveDelay);
    }

    private CommandMessage makeStatsRegionRequest(StatsRequest seed, Set<SwitchId> scope, String correlationId) {
        BroadcastWrapper wrapper = new BroadcastWrapper(scope, seed);
        return new CommandMessage(wrapper, clock.instant().toEpochMilli(), correlationId);
    }
}
