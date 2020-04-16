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
import org.openkilda.wfm.topology.network.model.IslDataHolder;
import org.openkilda.wfm.topology.network.model.IslPollStatus;

import java.util.Optional;

public class DiscoveryPollMonitor extends DiscoveryMonitor<IslPollStatus> {
    public DiscoveryPollMonitor(IslReference reference) {
        super(reference);
        IslPollStatus dummy = new IslPollStatus(IslStatus.INACTIVE);
    }

    @Override
    public void load(Endpoint endpoint, Isl persistentView) {
        super.load(endpoint, persistentView);

        IslDataHolder islData = new IslDataHolder(persistentView);
        IslPollStatus status = new IslPollStatus(islData, persistentView.getStatus());

        discoveryData.put(endpoint, status);
        cache.put(endpoint, status);
    }

    @Override
    public void actualUpdate(IslFsmEvent event, IslFsmContext context) {
        Endpoint endpoint = context.getEndpoint();
        IslPollStatus update = discoveryData.get(endpoint);
        switch (event) {
            case ISL_UP:
                update = new IslPollStatus(context.getIslData(), IslStatus.ACTIVE);
                break;

            case ISL_DOWN:
                update = new IslPollStatus(update.getIslData(), IslStatus.INACTIVE);
                break;

            case ISL_MOVE:
                update = new IslPollStatus(update.getIslData(), IslStatus.MOVED);
                break;

            default:
                // nothing to do here
        }
        discoveryData.put(endpoint, update);
    }

    @Override
    public Optional<IslStatus> evaluateStatus() {
        IslStatus forward = discoveryData.getForward().getStatus();
        IslStatus reverse = discoveryData.getReverse().getStatus();

        if (forward == IslStatus.MOVED || reverse == IslStatus.MOVED) {
            return Optional.of(IslStatus.MOVED);
        }

        if (forward == reverse) {
            return Optional.of(forward);
        }

        return Optional.of(IslStatus.INACTIVE);
    }

    @Override
    public void sync(Endpoint endpoint, Isl persistentView) {
        // TODO
    }
}
