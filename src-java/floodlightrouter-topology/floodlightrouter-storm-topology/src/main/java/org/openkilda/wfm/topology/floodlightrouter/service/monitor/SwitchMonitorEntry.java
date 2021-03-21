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

package org.openkilda.wfm.topology.floodlightrouter.service.monitor;

import org.openkilda.messaging.info.InfoData;
import org.openkilda.messaging.info.discovery.NetworkDumpSwitchData;
import org.openkilda.messaging.info.event.PortInfoData;
import org.openkilda.messaging.info.event.SwitchInfoData;
import org.openkilda.messaging.model.SpeakerSwitchView;
import org.openkilda.messaging.model.SwitchAvailabilityData;
import org.openkilda.model.SwitchConnectMode;
import org.openkilda.model.SwitchId;
import org.openkilda.wfm.topology.floodlightrouter.mapper.SwitchNotificationMapper;
import org.openkilda.wfm.topology.floodlightrouter.model.RegionMappingAdd;
import org.openkilda.wfm.topology.floodlightrouter.model.RegionMappingSet;
import org.openkilda.wfm.topology.floodlightrouter.model.SwitchAvailabilityEntry;
import org.openkilda.wfm.topology.floodlightrouter.service.SwitchMonitorCarrier;

import com.google.common.collect.ImmutableList;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
public class SwitchMonitorEntry {
    private final SwitchMonitorCarrier carrier;
    private final Clock clock;

    private final SwitchId switchId;

    private final List<SwitchConnectMonitor> basicMonitors;

    private final Map<String, SwitchAvailabilityEntry> availableInReadWrite = new HashMap<>();

    private final Map<String, SwitchAvailabilityEntry> availableInReadOnly = new HashMap<>();

    public SwitchMonitorEntry(SwitchMonitorCarrier carrier, Clock clock, SwitchId switchId) {
        this.carrier = carrier;
        this.clock = clock;

        this.switchId = switchId;
        // ---
        basicMonitors = ImmutableList.of(
                new SwitchReadOnlyConnectMonitor(carrier, clock, switchId),
                new SwitchReadWriteConnectMonitor(carrier, clock, switchId));
    }

    /**
     * Handle status update notification.
     */
    public void handleStatusUpdateNotification(SwitchInfoData notification, String region) {
        switch (notification.getState()) {
            case ADDED:
                processConnectNotification(notification, region, false);
                break;
            case ACTIVATED:
                processConnectNotification(notification, region, true);
                break;
            case DEACTIVATED:
                processDisconnectNotification(notification, region, true);
                break;
            case REMOVED:
                processDisconnectNotification(notification, region, false);
                break;
            default:
                proxyNotification(notification);
        }


        boolean isHandled = false;
        Iterator<SwitchConnectMonitor> iter = basicMonitors.iterator();
        while (! isHandled && iter.hasNext()) {
            isHandled = iter.next().handleSwitchStatusNotification(notification, region);
        }

        if (! isHandled) {
            carrier.networkStatusUpdateNotification(notification.getSwitchId(), notification);
        }
    }

    /**
     * Handle region offline notification.
     */
    public void handleRegionOfflineNotification(String region) {
        for (SwitchConnectMonitor entry : basicMonitors) {
            entry.handleRegionOfflineNotification(region);
        }
    }

    /**
     * Handle network dump entry/response.
     */
    public void handleNetworkDumpResponse(NetworkDumpSwitchData response, String region) {
        for (SwitchConnectMonitor entry : basicMonitors) {
            entry.handleNetworkDumpResponse(response, region);
        }
    }

    /**
     * Handle port status update notification.
     */
    public void handlePortStatusUpdateNotification(PortInfoData notification, String region) {
        for (SwitchConnectMonitor entry : basicMonitors) {
            entry.handlePortStatusUpdateNotification(notification, region);
        }
    }

    private void processConnectNotification(SwitchInfoData notification, String region, boolean isReadWrite) {
        Map<String, SwitchAvailabilityEntry> availabilityMap;
        if (isReadWrite) {
            availabilityMap = availableInReadWrite;
        } else {
            availabilityMap = availableInReadOnly;
        }

        if (processConnectNotification(availabilityMap, notification, region)) {
            reportBecomeAvailable(availabilityMap.keySet(), region, isReadWrite);
            acquireRegion(notification, region, isReadWrite);
        }
    }

    private boolean processConnectNotification(
            Map<String, SwitchAvailabilityEntry> availabilityMap, SwitchInfoData notification, String region) {
        SwitchAvailabilityEntry update = makeAvailabilityEntry(notification, availabilityMap.isEmpty());
        SwitchAvailabilityEntry current = availabilityMap.get(region);
        if (current == null) {
            availabilityMap.put(region, update);
        } else {
            availabilityMap.put(region, mergeAvailabilityEntries(current, update));
        }

        return current == null;
    }

    private void processDisconnectNotification(SwitchInfoData notification, String region, boolean isReadWrite) {

    }

    private void acquireRegion(SwitchInfoData notification, String region, boolean isReadWrite) {
        if (isReadWrite) {
            acquireReadWriteRegion(notification, region);
        } else {
            acquireReadOnlyRegion(notification, region);
        }
    }

    private void acquireReadWriteRegion(SwitchInfoData notification, String region) {
        if (availableInReadWrite.size() == 1) {
            carrier.regionUpdateNotification(new RegionMappingSet(switchId, region, true));
        }

        // TODO
    }

    private void acquireReadOnlyRegion(SwitchInfoData notification, String region) {
        carrier.regionUpdateNotification(new RegionMappingAdd(switchId, region, false));
        // TODO emit availability update notification
        reportNotificationDrop(notification, region);
    }

    private void proxyNotification(SwitchInfoData notification) {
        carrier.networkStatusUpdateNotification(notification.getSwitchId(), notification);
    }

    /**
     * Merge all basic monitors becomeUnavailableAt values.
     */
    public Optional<Instant> getBecomeUnavailableAt() {
        // FIXME
        Instant result = null;
        for (SwitchConnectMonitor entry : basicMonitors) {
            Instant current = entry.getBecomeUnavailableAt();
            if (entry.isAvailable() || current == null) {
                return Optional.empty(); // at least one of connections are active
            }

            if (result == null || result.isBefore(current)) {
                result = current;
            }
        }

        return Optional.ofNullable(result);
    }

    private void reportNotificationDrop(InfoData notification, String region) {
        log.debug("Drop speaker switch {} notification in region {}: {}", switchId, region, notification);
    }

    protected void reportBecomeAvailable(Set<String> availabilitySet, String region, boolean isReadWrite) {
        String mode = formatAvailabilityMode(isReadWrite);
        log.info(
                "Switch {} become {} available in region \"{}\", all {} regions: {}",
                switchId, mode, region, mode, formatAvailabilitySet(availabilitySet));
    }

    private SwitchAvailabilityEntry makeAvailabilityEntry(
            SwitchInfoData source, boolean isActive) {
        InetSocketAddress switchAddress = null;
        InetSocketAddress speakerAddress = null;

        SpeakerSwitchView speakerView = source.getSwitchView();
        if (speakerView != null) {
            switchAddress = speakerView.getSwitchSocketAddress();
            speakerAddress = speakerView.getSpeakerSocketAddress();
        }

        return new SwitchAvailabilityEntry(isActive, clock.instant(), switchAddress, speakerAddress);
    }

    private SwitchAvailabilityEntry mergeAvailabilityEntries(
            SwitchAvailabilityEntry current, SwitchAvailabilityEntry update) {
        return new SwitchAvailabilityEntry(
                current.isActive() || update.isActive(), current.getBecomeAvailableAt(),
                update.getSwitchSocketAddress(), update.getSwitchSocketAddress());
    }

    private SwitchAvailabilityData makeAvailabilityUpdatePayload() {
        SwitchAvailabilityData.SwitchAvailabilityDataBuilder builder = SwitchAvailabilityData.builder();

        for (Map.Entry<String, SwitchAvailabilityEntry> entry : availableInReadWrite.entrySet()) {
            builder.connection(SwitchNotificationMapper.INSTANCE.toMessaging(
                    entry.getValue(), entry.getKey(), SwitchConnectMode.READ_WRITE));
        }

        for (Map.Entry<String, SwitchAvailabilityEntry> entry : availableInReadOnly.entrySet()) {
            if (availableInReadWrite.containsKey(entry.getKey())) {
                continue;
            }
            builder.connection(SwitchNotificationMapper.INSTANCE.toMessaging(
                    entry.getValue(), entry.getKey(), SwitchConnectMode.READ_ONLY));
        }

        return builder.build();
    }

    private static String formatAvailabilityMode(boolean mode) {
        return mode ? "RW" : "RO";
    }

    private static String formatAvailabilitySet(Set<String> availabilitySet) {
        return "{" + availabilitySet.stream()
                .sorted()
                .collect(Collectors.joining(", ")) + "}";
    }
}
