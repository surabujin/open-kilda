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

package org.openkilda.wfm.topology.network.controller.bfd;

import org.openkilda.model.BfdProperties;
import org.openkilda.model.SwitchId;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.model.IslReference;
import org.openkilda.wfm.topology.network.model.BfdSessionData;

import lombok.Getter;

public class BfdSessionBlank {
    private final BfdSessionController controller;

    @Getter
    private final Endpoint logicalEndpoint;

    @Getter
    private final int physicalPortNumber;

    @Getter
    private final BfdSessionData sessionData;

    public BfdSessionBlank(
            BfdSessionController controller, Endpoint logicalEndpoint, int physicalPortNumber,
            BfdSessionData sessionData) {
        this.controller = controller;
        this.logicalEndpoint = logicalEndpoint;
        this.physicalPortNumber = physicalPortNumber;
        this.sessionData = sessionData;
    }

    public void completeNotification(boolean error) {
        controller.handleCompleteNotification(error);
    }

    public SwitchId getSwitchId() {
        return logicalEndpoint.getDatapath();
    }

    public IslReference getIslReference() {
        return sessionData.getReference();
    }

    public BfdProperties getProperties() {
        return sessionData.getProperties();
    }

    public Endpoint getPhysicalEndpoint() {
        return Endpoint.of(logicalEndpoint.getDatapath(), physicalPortNumber);
    }
}
