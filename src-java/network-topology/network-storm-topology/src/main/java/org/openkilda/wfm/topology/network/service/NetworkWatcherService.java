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

package org.openkilda.wfm.topology.network.service;

import org.openkilda.messaging.command.discovery.DiscoverIslCommandData;
import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.model.IslReference;

import com.google.common.annotations.VisibleForTesting;
import lombok.Data;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

@Slf4j
public class NetworkWatcherService {
    private final Clock clock;

    private final IWatcherCarrier carrier;
    private final long awaitTime;
    private final Integer taskId;

    private long packetNo = 0;
    private Map<Packet, WatcherEntry> inFlightEntries = new HashMap<>();
    private SortedMap<Long, Set<Packet>> timeouts = new TreeMap<>();

    private Map<Endpoint, Instant> lastSeenRoundTrip = new HashMap<>();


    public NetworkWatcherService(IWatcherCarrier carrier, long awaitTime, Integer taskId) {
        this(Clock.systemUTC(), carrier, awaitTime, taskId);
    }

    @VisibleForTesting
    NetworkWatcherService(Clock clock, IWatcherCarrier carrier, long awaitTime, Integer taskId) {
        this.clock = clock;
        this.carrier = carrier;
        this.awaitTime = awaitTime;
        this.taskId = taskId;
    }

    public void addWatch(Endpoint endpoint) {
        addWatch(endpoint, now());
    }

    void addWatch(Endpoint endpoint, long currentTime) {
        Packet packet = Packet.of(endpoint, packetNo);
        log.debug("Watcher service receive ADD-watch request for {} and produce packet id:{} task:{}",
                  endpoint, packet.packetNo, taskId);

        inFlightEntries.put(packet, new WatcherEntry());
        timeouts.computeIfAbsent(currentTime + awaitTime, key -> new HashSet<>())
                .add(packet);

        DiscoverIslCommandData discoveryRequest = new DiscoverIslCommandData(
                endpoint.getDatapath(), endpoint.getPortNumber(), packetNo);
        carrier.sendDiscovery(discoveryRequest);

        packetNo += 1;
    }

    /**
     * Remove endpoint from discovery process.
     */
    public void removeWatch(Endpoint endpoint) {
        log.debug("Watcher service receive REMOVE-watch request for {}", endpoint);
        carrier.clearDiscovery(endpoint);
        inFlightEntries.keySet().removeIf(packet -> packet.getEndpoint().equals(endpoint));
    }

    void tick(long tickTime) {
        SortedMap<Long, Set<Packet>> range = timeouts.subMap(0L, tickTime + 1);
        if (!range.isEmpty()) {
            for (Set<Packet> e : range.values()) {
                for (Packet ee : e) {
                    timeoutAction(ee);
                }
            }
            range.clear();
        }
    }

    public void tick() {
        tick(now());
    }

    /**
     * .
     */
    public void confirmation(Endpoint endpoint, long packetNo) {
        log.debug("Watcher service receive SEND-confirmation for {} id:{} task:{}", endpoint, packetNo, taskId);

        WatcherEntry entry = inFlightEntries.get(Packet.of(endpoint, packetNo));
        if (entry != null) {
            entry.markConfirmed();
        } else if (log.isDebugEnabled()) {
            log.debug("Can't find produced packet for {} id:{} task:{}", endpoint, packetNo, taskId);
        }
    }

    /**
     * Consume discovery event.
     */
    public void discovery(IslInfoData discoveryEvent) {
        Endpoint source = new Endpoint(discoveryEvent.getSource());
        Long packetId = discoveryEvent.getPacketId();
        if (packetId == null) {
            log.error("Got corrupted discovery packet into {} - packetId field is empty", source);
        } else {
            discovery(discoveryEvent, Packet.of(source, packetId));
        }
    }

    private void discovery(IslInfoData discoveryEvent, Packet packet) {
        if (log.isDebugEnabled()) {
            IslReference ref = IslReference.of(discoveryEvent);
            log.debug("Watcher service receive DISCOVERY event for {} id:{} task:{} - {}",
                      packet.endpoint, packet.packetNo, taskId, ref);
        }

        WatcherEntry entry = inFlightEntries.get(packet);
        if (entry == null) {
            log.error("Receive invalid or removed discovery packet on {} id:{} task:{}",
                      packet.endpoint, packet.packetNo, taskId);
        } else if (entry.isDiscovered()) {
            log.error("Receive duplicate discovery packet on {} id:{} task:{}",
                      packet.endpoint, packet.packetNo, taskId);
        } else {
            entry.markDiscovered();
            carrier.discoveryReceived(packet.endpoint, packet.packetNo, discoveryEvent, now());
        }
    }

    public void roundTripDiscovery(Endpoint endpoint, long packetId) {
        log.debug("Watcher service receive ROUND TRIP DISCOVERY for {} id:{} task:{}",
                  endpoint, packetId, taskId);

        WatcherEntry entry = inFlightEntries.get(Packet.of(endpoint, packetId));
        if (entry == null) {
            log.error("Receive invalid/stale round trip discovery packet for {} id:{} task:{}",
                      endpoint, packetId, taskId);
        } else if (entry.isRoundTripDiscovered()) {
            log.error("Receive multiple round trip discovery packet for {} id:{} task:{}",
                      endpoint, packetId, taskId);
        } else {
            entry.markRoundTripDiscovered();
            lastSeenRoundTrip.put(endpoint, clock.instant());
        }
    }

    private void timeoutAction(Packet packet) {
        producedPackets.remove(packet);

        if (confirmedPackets.remove(packet)) {
            log.debug("Detect discovery packet lost sent via {} id:{} task:{}",
                      packet.endpoint, packet.packetNo, taskId);
            carrier.discoveryFailed(packet.getEndpoint(), packet.packetNo, now());
        }
    }

    private long now() {
        return System.nanoTime();
    }

    @VisibleForTesting
    Set<Packet> getProducedPackets() {
        return producedPackets;
    }

    @VisibleForTesting
    Set<Packet> getConfirmedPackets() {
        return confirmedPackets;
    }

    @VisibleForTesting
    SortedMap<Long, Set<Packet>> getTimeouts() {
        return timeouts;
    }

    @Value(staticConstructor = "of")
    public static class Packet {
        private final Endpoint endpoint;
        private final long packetNo;
    }

    @Getter
    public static class WatcherEntry {
        private boolean confirmed = false;
        private boolean discovered = false;
        private boolean roundTripDiscovered = false;

        public void markConfirmed() {
            confirmed = true;
        }

        public void markDiscovered() {
            confirmed = discovered = true;
        }

        public void markRoundTripDiscovered() {
            confirmed = roundTripDiscovered = true;
        }
    }
}
