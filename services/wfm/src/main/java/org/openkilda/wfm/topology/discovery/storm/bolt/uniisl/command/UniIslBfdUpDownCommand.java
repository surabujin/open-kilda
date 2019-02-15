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

import org.openkilda.wfm.topology.discovery.model.Endpoint;
import org.openkilda.wfm.topology.discovery.service.DiscoveryUniIslService;
import org.openkilda.wfm.topology.discovery.service.IUniIslCarrier;

public class UniIslBfdUpDownCommand extends UniIslCommand {
    private final boolean up;

    public UniIslBfdUpDownCommand(Endpoint endpoint, boolean up) {
        super(endpoint);
        this.up = up;
    }

    @Override
    public void apply(DiscoveryUniIslService service, IUniIslCarrier carrier) {
        service.uniIslBfdUpDown(carrier, getEndpoint(), up);
    }
}