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

package org.openkilda.wfm.topology.network.controller.isl;

import org.openkilda.model.Isl;
import org.openkilda.model.IslStatus;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.model.IslReference;
import org.openkilda.wfm.topology.network.controller.isl.IslFsm.IslFsmContext;
import org.openkilda.wfm.topology.network.controller.isl.IslFsm.IslFsmEvent;
import org.openkilda.wfm.topology.network.model.NetworkOptions;
import org.openkilda.wfm.topology.network.model.RoundTripStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public class DiscoveryRoundTripMonitor extends DiscoveryMonitor<Instant> {
    private final Clock clock;
    private final Duration roundTripExpirationTime;

    public DiscoveryRoundTripMonitor(IslReference reference, Clock clock, NetworkOptions options) {
        super(reference);
        this.clock = clock;
        roundTripExpirationTime = Duration.ofNanos(options.getDiscoveryTimeout());
    }

    @Override
    public void actualUpdate(Endpoint endpoint, IslFsmEvent event, IslFsmContext context) {
        Instant update = discoveryData.get(endpoint);
        switch (event) {
            case ROUND_TRIP_STATUS:
                update = evaluateExpireAtTime(context.getRoundTripStatus());
                break;

            default:
                // nothing to do here
        }
        discoveryData.put(endpoint, update);
    }

    @Override
    public Optional<IslStatus> evaluateStatus() {
        Instant now = Instant.now(clock);
        boolean isActive = discoveryData.stream()
                .filter(Objects::nonNull)
                .anyMatch(now::isBefore);
        if (isActive) {
            return Optional.of(IslStatus.ACTIVE);
        }
        return Optional.empty();
    }

    @Override
    public void sync(Endpoint endpoint, Isl persistentView) {
        // TODO
    }

    private Instant evaluateExpireAtTime(RoundTripStatus status) {
        Duration foreignObsolescence = Duration.between(status.getLastSeen(), status.getNow());
        Duration timeLeft = roundTripExpirationTime.minus(foreignObsolescence);
        if (timeLeft.isNegative()) {
            return null;
        }

        return clock.instant().plus(timeLeft);
    }
}
