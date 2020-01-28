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

package org.openkilda.floodlight.feature;

import static org.apache.commons.lang3.StringUtils.containsIgnoreCase;

import org.openkilda.model.SwitchFeature;

import net.floodlightcontroller.core.IOFSwitch;

import java.util.Optional;

public class InaccurateSetVlanVidAction extends AbstractFeature {
    @Override
    public Optional<SwitchFeature> discover(IOFSwitch sw) {
        if (containsIgnoreCase(sw.getSwitchDescription().getManufacturerDescription(), CENTEC_MANUFACTURED)) {
            return Optional.of(SwitchFeature.INACCURATE_SET_VLAN_VID_ACTION);
        }

        return Optional.empty();
    }
}
