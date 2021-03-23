/* Copyright 2021 Telstra Open Source
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

package org.openkilda.wfm.topology.floodlightrouter.mapper;

import org.openkilda.messaging.model.SwitchAvailabilityEntry;
import org.openkilda.model.SwitchConnectMode;
import org.openkilda.wfm.topology.floodlightrouter.model.SwitchConnect;

import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.factory.Mappers;

import java.time.Clock;
import java.time.Duration;

public abstract class SwitchNotificationMapper {
    public static final SwitchNotificationMapper INSTANCE = Mappers.getMapper(SwitchNotificationMapper.class);

    public org.openkilda.messaging.model.SwitchAvailabilityEntry toMessaging(
            Clock clock, SwitchConnect source, String regionName, SwitchConnectMode connectMode) {
        SwitchAvailabilityEntry.SwitchAvailabilityEntryBuilder target = SwitchAvailabilityEntry.builder();
        generatedMap(target, source, regionName, connectMode);
        target.connectDuration(Duration.between(clock.instant(), source.getBecomeAvailableAt()));
        return target.build();
    }

    @Mapping(target = "connectDuration", ignore = true)
    public abstract void generatedMap(
            @MappingTarget SwitchAvailabilityEntry.SwitchAvailabilityEntryBuilder target,
            SwitchConnect source, String regionName,
            SwitchConnectMode connectMode);
}
