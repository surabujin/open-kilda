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
import org.openkilda.model.IslDownReason;
import org.openkilda.model.IslStatus;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.model.IslReference;
import org.openkilda.wfm.topology.network.controller.isl.IslFsm.IslFsmContext;
import org.openkilda.wfm.topology.network.controller.isl.IslFsm.IslFsmEvent;
import org.openkilda.wfm.topology.network.model.LinkStatus;

import java.util.Optional;

public class DiscoveryPortStatusMonitor extends DiscoveryMonitor<LinkStatus> {
    public DiscoveryPortStatusMonitor(IslReference reference) {
        super(reference);

        discoveryData.putBoth(LinkStatus.UP);
    }

    @Override
    public void actualUpdate(Endpoint endpoint, IslFsmEvent event, IslFsmContext context) {
        LinkStatus update = null;
        switch (event) {
            case ISL_DOWN:
                if (context.getDownReason() == IslDownReason.PORT_DOWN) {
                    update = LinkStatus.DOWN;
                }
                break;
            case ISL_UP:
                update = LinkStatus.UP;
                break;

            default:
                // nothing to do here
        }

        if (update != null) {
            discoveryData.put(endpoint, update);
        }
    }

    @Override
    public Optional<IslStatus> evaluateStatus() {
        boolean isDown = discoveryData.stream()
                .anyMatch(entry -> entry == LinkStatus.DOWN);
        if (isDown) {
            return Optional.of(IslStatus.INACTIVE);
        }
        return Optional.empty();
    }

    @Override
    public void sync(Endpoint endpoint, Isl persistentView) {
        // TODO
    }

    @Override
    public boolean isSyncRequired() {
        return false;
    }
}
