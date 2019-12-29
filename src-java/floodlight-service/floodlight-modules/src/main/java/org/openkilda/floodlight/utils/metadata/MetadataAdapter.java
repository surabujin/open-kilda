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

import org.openkilda.model.SwitchFeature;
import org.openkilda.model.bitops.BitField;

import lombok.NonNull;
import lombok.Value;
import org.projectfloodlight.openflow.types.OFVlanVidMatch;
import org.projectfloodlight.openflow.types.U64;

import java.util.Set;

public class MetadataAdapter {
    private final MetadataSchema schema;

    public MetadataAdapter(@NonNull Set<SwitchFeature> features) {
        if (features.contains(SwitchFeature.HALF_SIZE_METADATA)) {
            schema = HalfSizeMetadataSchema.INSTANCE;
        } else {
            schema = MetadataSchema.INSTANCE;
        }
    }

    public MetadataMatch addressLldpMarkerFlag(boolean flag) {
        return addressLldpMarkerFlag(MetadataMatch.ZERO, flag);
    }

    public MetadataMatch addressLldpMarkerFlag(MetadataMatch base, boolean flag) {
        return setBooleanField(base, flag, schema.getLldpMarkerFlagField());
    }

    public MetadataMatch addressArpMakerFlag(boolean flag) {
        return addressArpMakerFlag(MetadataMatch.ZERO, flag);
    }

    public MetadataMatch addressArpMakerFlag(MetadataMatch base, boolean flag) {
        return setBooleanField(base, flag, schema.getArpMarkerFlagField());
    }

    public MetadataMatch addressOneSwitchFlowFlag(boolean flag) {
        return addressOneSwitchFlowFlag(MetadataMatch.ZERO, flag);
    }

    public MetadataMatch addressOneSwitchFlowFlag(MetadataMatch base, boolean flag) {
        return setBooleanField(base, flag, schema.getOneSwitchFlowFlagField());
    }

    public MetadataMatch addressOuterVlan(OFVlanVidMatch vlanMatch) {
        return addressOuterVlan(MetadataMatch.ZERO, vlanMatch);
    }

    /**
     * Address outer VLAN ID bits inside metadata.
     */
    public MetadataMatch addressOuterVlan(MetadataMatch base, OFVlanVidMatch vlanMatch) {
        U64 value = setField(base.getValue(), vlanMatch.getVlan(), schema.getOuterVlanField());
        value = setField(value, -1, schema.getOuterVlanPresenceFlag());

        U64 mask = setField(base.getMask(), -1, schema.getOuterVlanField());
        mask = setField(mask, -1, schema.getOuterVlanPresenceFlag());

        return new MetadataMatch(value, mask);
    }

    private MetadataMatch setBooleanField(MetadataMatch base, boolean flag, BitField field) {
        U64 value = setField(base.getValue(), flag ? -1 : 0, field);
        U64 mask = setField(base.getMask(), -1, field);
        return new MetadataMatch(value, mask);
    }

    private static U64 setField(U64 target, long value, BitField field) {
        U64 result = target.and(U64.of(~field.getMask()));
        value <<= field.getOffset();
        return result.or(U64.of(value & field.getMask()));
    }

    @Value
    public static class MetadataMatch {
        protected static final MetadataMatch ZERO = new MetadataMatch(U64.ZERO, U64.ZERO);

        private final U64 value;
        private final U64 mask;

        protected MetadataMatch merge(U64 extendValue, U64 extendMask) {
            return new MetadataMatch(
                    value.or(extendValue),
                    mask.or(extendMask));
        }
    }
}
