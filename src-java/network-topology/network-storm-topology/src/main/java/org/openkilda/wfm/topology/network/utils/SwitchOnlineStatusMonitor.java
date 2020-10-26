/*
 * Copyright 2020 Telstra Open Source
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

package org.openkilda.wfm.topology.network.utils;

import org.openkilda.model.SwitchId;

import lombok.Getter;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class SwitchOnlineStatusMonitor {
    private final Map<SwitchId, MonitorEntry> monitors = new HashMap<>();

    private static final MonitorEntry dummy = new MonitorEntry(new SwitchId(0));

    public boolean queryStatus(SwitchId switchId) {
        MonitorEntry entry = monitors.getOrDefault(switchId, dummy);
        return entry.isOnline();
    }

    private static class MonitorEntry {
        @Getter
        private final SwitchId switchId;

        @Getter
        private boolean online = false;

        private final List<SwitchOnlineStatusListener> subscribers = new LinkedList<>();

        public MonitorEntry(SwitchId switchId) {
            this.switchId = switchId;
        }
    }
}
