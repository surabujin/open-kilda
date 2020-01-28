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

import static org.openkilda.model.SwitchFeature.RESET_COUNTS_FLAG;

import org.openkilda.model.SwitchFeature;

import net.floodlightcontroller.core.IOFSwitch;
import org.apache.commons.lang3.StringUtils;
import org.projectfloodlight.openflow.protocol.OFVersion;

import java.util.Optional;

public class ResetCountsFlagFeature extends AbstractFeature {
    @Override
    public Optional<SwitchFeature> discover(IOFSwitch sw) {
        // TODO(surabujin) recheck and fix detection or feature handling
        if (sw.getOFFactory().getVersion().compareTo(OFVersion.OF_12) <= 0) {
            // acton switches do not report RESET_COUNT flags into flows stats response, we can suppose it
            // do not support (or do not keep it in flow record).
            return Optional.empty();
        }

        if (StringUtils.contains(sw.getSwitchDescription().getManufacturerDescription(), CENTEC_MANUFACTURED)) {
            return Optional.empty();
        } else {
            return Optional.of(RESET_COUNTS_FLAG);
        }
    }
}
