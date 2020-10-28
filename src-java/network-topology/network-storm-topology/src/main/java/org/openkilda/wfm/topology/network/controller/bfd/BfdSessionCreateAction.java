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

package org.openkilda.wfm.topology.network.controller.bfd;

import org.openkilda.messaging.floodlight.response.BfdSessionResponse;
import org.openkilda.messaging.model.NoviBfdSession;
import org.openkilda.wfm.topology.network.service.IBfdSessionCarrier;

class BfdSessionCreateAction extends BfdSessionAction {
    BfdSessionCreateAction(IBfdSessionCarrier carrier, NoviBfdSession requestPayload) {
        super(carrier.sendWorkerBfdSessionCreateRequest(requestPayload));
    }

    @Override
    public String getLogIdentifier() {
        return "BFD session setup";
    }

    @Override
    protected ActionResult makeResult(BfdSessionResponse response) {
        return ActionResult.of(response, false);
    }
}
