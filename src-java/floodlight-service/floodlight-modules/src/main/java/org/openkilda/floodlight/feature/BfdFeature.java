/* Copyright 2018 Telstra Open Source
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

import org.openkilda.model.SwitchFeature;

import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.SwitchDescription;

import java.util.Optional;

public class BfdFeature extends AbstractFeature {

    @Override
    public Optional<SwitchFeature> discover(IOFSwitch sw) {
        Optional<SwitchFeature> empty = Optional.empty();

        SwitchDescription description = sw.getSwitchDescription();
        if (description == null || description.getSoftwareDescription() == null) {
            return empty;
        }
        if (!NOVIFLOW_SOFTWARE_DESCRIPTION_REGEX.matcher(description.getSoftwareDescription()).matches()) {
            return empty;
        }

        return Optional.of(SwitchFeature.BFD);
    }
}
