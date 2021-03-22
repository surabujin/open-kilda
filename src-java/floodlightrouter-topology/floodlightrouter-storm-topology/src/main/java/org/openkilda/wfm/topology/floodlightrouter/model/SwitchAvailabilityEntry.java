/* Copyright 2021 Telstra Open Source
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

import lombok.Value;

import java.net.InetSocketAddress;
import java.time.Instant;

@Value
public class SwitchAvailabilityEntry {
    boolean active;

    Instant becomeAvailableAt;

    InetSocketAddress switchSocketAddress;

    InetSocketAddress speakerSocketAddress;

    public SwitchAvailabilityEntry makeActive() {
        if (active) {
            return this;
        }
        return new SwitchAvailabilityEntry(true, becomeAvailableAt, switchSocketAddress, speakerSocketAddress);
    }
}