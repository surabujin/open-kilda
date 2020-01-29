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

package org.openkilda.persistence.dummy;

import org.openkilda.model.SwitchId;

import lombok.Value;

@Value
public class InnerSwitchLink {
    private SwitchId switchId;
    private int aEndPort;
    private int zEndPort;

    public InnerSwitchLink makeOpposite() {
        return new InnerSwitchLink(switchId, zEndPort, aEndPort);
    }
}
