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

package org.openkilda.wfm.topology.floodlightrouter.model;

import org.openkilda.model.SwitchId;

import lombok.Value;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Value
public class SwitchToRegionMapping {
    private Map<SwitchId, String> readWrite = new HashMap<>();
    private Map<SwitchId, String> readOnly = new HashMap<>();

    /**
     * Looks for a region for switchId.
     */
    public Optional<String> lookupReadWriteRegion(SwitchId switchId) {
        return Optional.ofNullable(readWrite.get(switchId));
    }

    public Optional<String> lookupReadOnlyRegion(SwitchId switchId) {
        return Optional.ofNullable(readOnly.get(switchId));
    }

    public Map<String, Set<SwitchId>> organizeReadWritePopulationPerRegion() {
        return organizePopulationPerRegion(readWrite);
    }

    public Map<String, Set<SwitchId>> organizeReadOnlyPopulationPerRegion() {
        return organizePopulationPerRegion(readOnly);
    }

    /**
     * Updates region mapping for switch.
     */
    public void update(RegionMappingUpdate update) {
        Map<SwitchId, String> target = update.isReadWriteMode() ? readWrite : readOnly;

        if (update.getRegion() != null) {
            target.put(update.getSwitchId(), update.getRegion());
        } else {
            target.remove(update.getSwitchId());
        }
    }

    private static Map<String, Set<SwitchId>> organizePopulationPerRegion(Map<SwitchId, String> mapping) {
        Map<String, Set<SwitchId>> population = new HashMap<>();
        for (Map.Entry<SwitchId, String> entry : mapping.entrySet()) {
            population.computeIfAbsent(entry.getValue(), key -> new HashSet<>())
                    .add(entry.getKey());
        }
        return population;
    }
}
