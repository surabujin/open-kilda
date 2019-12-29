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

package org.openkilda.floodlight.utils.metadata;

import org.openkilda.model.bitops.BitField;

public class MetadataSchema {
    public static final MetadataSchema INSTANCE = new MetadataSchema();

    private static final BitField LLDP_MARKER_FLAG         = new BitField(0x0000_0000_0000_0001L);
    private static final BitField ONE_SWITCH_FLOW_FLAG     = new BitField(0x0000_0000_0000_0002L);
    private static final BitField ARP_MARKER_FLAG          = new BitField(0x0000_0000_0000_0004L);
    private static final BitField OUTER_VLAN_PRESENCE_FLAG = new BitField(0x0000_0000_0000_0008L);

    private static final BitField OUTER_VLAN_FIELD         = new BitField(0x0000_0000_0000_fff0L);

    public BitField getLldpMarkerFlagField() {
        return LLDP_MARKER_FLAG;
    }

    public BitField getArpMarkerFlagField() {
        return ARP_MARKER_FLAG;
    }

    public BitField getOneSwitchFlowFlagField() {
        return ONE_SWITCH_FLOW_FLAG;
    }

    public BitField getOuterVlanField() {
        return OUTER_VLAN_FIELD;
    }

    public BitField getOuterVlanPresenceFlag() {
        return OUTER_VLAN_PRESENCE_FLAG;
    }

    protected MetadataSchema() {
        // hide public constructor
    }
}
