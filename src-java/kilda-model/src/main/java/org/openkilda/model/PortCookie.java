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

package org.openkilda.model;

import org.openkilda.model.bitops.BitField;

import com.google.common.collect.ImmutableSet;
import lombok.Builder;
import org.apache.commons.lang3.ArrayUtils;

import java.util.Set;

public class PortCookie extends Cookie {
    private static final Set<CookieType> allowedTypes = ImmutableSet.of(
            CookieType.LLDP_INPUT_CUSTOMER_TYPE,
            CookieType.MULTI_TABLE_ISL_VLAN_EGRESS_RULES,
            CookieType.MULTI_TABLE_ISL_VXLAN_EGRESS_RULES,
            CookieType.MULTI_TABLE_ISL_VXLAN_TRANSIT_RULES,
            CookieType.MULTI_TABLE_INGRESS_RULES,
            CookieType.ARP_INPUT_CUSTOMER_TYPE
    );

    // update ALL_FIELDS if modify fields list
    //                    used by generic cookie -> 0x9FF0_0000_0000_0000L
    static final BitField PORT_FIELD = new BitField(0x0000_0000_FFFF_FFFFL);

    // used by unit tests to check fields intersections
    static final BitField[] ALL_FIELDS = ArrayUtils.addAll(Cookie.ALL_FIELDS, PORT_FIELD);

    public PortCookie(long value) {
        super(value);
    }

    @Builder
    public PortCookie(CookieType type, int portNumber) {

    }

    public static PortCookieBuilder toBuilder() {

    }

    private static long makeValue(CookieType type, int portNumber) {
        // TODO validate type

        long value = setField(0, SERVICE_FLAG, 1);
        return setField(value, PORT_FIELD, portNumber);
    }

    public static class PortCookieBuilder extends CookieBuilder {
        // lombok will produce all required fields and methods here
    }
}
