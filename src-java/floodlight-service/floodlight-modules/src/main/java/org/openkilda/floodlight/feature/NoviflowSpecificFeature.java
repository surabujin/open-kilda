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

import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.SwitchDescription;

import java.util.Optional;

abstract class NoviflowSpecificFeature extends AbstractFeature {
    public static final String NOVIFLOW_MANUFACTURER_SUFFIX = "noviflow";

    boolean isNoviSwitch(IOFSwitch sw) {
        return getManufacturer(sw).toLowerCase().contains(NOVIFLOW_MANUFACTURER_SUFFIX);
    }

    boolean is100GbHw(IOFSwitch sw) {
        if (! isNoviSwitch(sw)) {
            return false;
        }

        Optional<SwitchDescription> description = Optional.ofNullable(sw.getSwitchDescription());
        if (E_SWITCH_MANUFACTURER_DESCRIPTION.equalsIgnoreCase(getManufacturer(sw))) {
            return false;
        }

        return E_SWITCH_HARDWARE_DESCRIPTION_REGEX.matcher(
                description
                .map(SwitchDescription::getHardwareDescription)
                .orElse("")).matches();
    }

    boolean isVirtual(IOFSwitch sw) {
        if (! isNoviSwitch(sw)) {
            return false;
        }

        Optional<SwitchDescription> description = Optional.ofNullable(sw.getSwitchDescription());

        return NOVIFLOW_VIRTUAL_SWITCH_HARDWARE_DESCRIPTION_REGEX.matcher(
                description.map(SwitchDescription::getHardwareDescription)
                        .orElse("")).matches();
    }

    protected String getManufacturer(IOFSwitch sw) {
        return Optional.ofNullable(sw.getSwitchDescription())
                .map(SwitchDescription::getManufacturerDescription)
                .orElse("");
    }
}
