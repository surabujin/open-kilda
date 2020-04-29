/*
 * Copyright 2020 Telstra Open Source
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

import lombok.Builder;
import org.apache.commons.lang3.ArrayUtils;

public class FlowSharedSegmentCookie extends CookieBase {
    // update ALL_FIELDS if modify fields list
    //                           used by generic cookie -> 0x9FF0_0000_0000_0000L
    static final BitField SHARED_TYPE_FIELD = new BitField(0x000F_0000_0000_0000L);
    static final BitField UNIQUE_ID_FIELD   = new BitField(0x0000_0000_FFFF_FFFFL);

    // used by unit tests to check fields intersections
    static final BitField[] ALL_FIELDS = ArrayUtils.addAll(CookieBase.ALL_FIELDS, SHARED_TYPE_FIELD, UNIQUE_ID_FIELD);

    @Builder
    protected FlowSharedSegmentCookie(long value) {
        super(value);
    }
}
