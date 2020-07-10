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

import org.openkilda.messaging.info.event.SwitchChangeType;
import org.openkilda.messaging.info.event.SwitchInfoData;
import org.openkilda.model.SwitchId;

public class SwitchReadOnlyConnectMonitorTest extends SwitchConnectMonitorTest {
    @Override
    protected SwitchReadOnlyConnectMonitor makeSubject(SwitchId switchId) {
        return new SwitchReadOnlyConnectMonitor(carrier, clock, switchId);
    }

    @Override
    protected SwitchInfoData makeConnectNotification(SwitchId switchId) {
        return new SwitchInfoData(switchId, SwitchChangeType.ADDED);
    }

    @Override
    protected SwitchInfoData makeDisconnectNotification(SwitchId switchId) {
        return new SwitchInfoData(switchId, SwitchChangeType.REMOVED);
    }
}
