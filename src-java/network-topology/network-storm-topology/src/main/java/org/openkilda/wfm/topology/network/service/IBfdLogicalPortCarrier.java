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

package org.openkilda.wfm.topology.network.service;

import org.openkilda.model.BfdProperties;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.model.IslReference;

public interface IBfdLogicalPortCarrier {
    String createLogicalPort(Endpoint logical, int physicalPortNumber);

    String deleteLogicalPort(Endpoint logical);

    void createSession(Endpoint logical, int physicalPortNumber);

    void enableUpdateSession(Endpoint physical, IslReference reference, BfdProperties properties);

    void disableSession(Endpoint physical);

    void updateSessionOnlineStatus(Endpoint logical, boolean isOnline);

    void bfdKillNotification(Endpoint physicalEndpoint);

    void logicalPortControllerAddNotification(Endpoint physical);

    void logicalPortControllerDelNotification(Endpoint physical);
}
