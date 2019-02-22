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

package org.openkilda.wfm.topology.discovery.storm.bolt.uniisl.command;

import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.wfm.topology.discovery.model.Endpoint;
import org.openkilda.wfm.topology.discovery.service.DiscoveryUniIslService;
import org.openkilda.wfm.topology.discovery.service.IUniIslCarrier;

public class UniIslDiscoveryCommand extends UniIslCommand {
    private final IslInfoData speakerDiscoveryEvent;

    public UniIslDiscoveryCommand(IslInfoData discoveryEvent) {
        super(new Endpoint(discoveryEvent.getDestination()));
        this.speakerDiscoveryEvent = discoveryEvent;
    }

    @Override
    public void apply(DiscoveryUniIslService service, IUniIslCarrier carrier) {
        service.uniIslDiscovery(getEndpoint(), speakerDiscoveryEvent);
    }
}
