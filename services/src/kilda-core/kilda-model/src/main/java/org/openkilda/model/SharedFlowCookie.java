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

package org.openkilda.model;

import org.openkilda.utils.ICookieEnumField;

import lombok.Getter;

public class SharedFlowCookie extends Cookie {
    // update ALL_FIELDS if modify fields list
    // 0xFFF0_0000_0000_0000 used by generic cookie
    // 0x000F_0000_0000_0000
    static final BitField SHARED_TYPE_FIELD = new BitField(4, 48);
    // 0x000F_0000_FFFF_FFFF
    static final BitField SHARED_ID = new BitField(32, 0);

    // used by unit tests to check fields intersections
    static final BitField[] ALL_FIELDS = new BitField[]{
            SHARED_TYPE_FIELD};

    protected SharedFlowCookie(Cookie baseValue) {
        super(baseValue.getValue());
    }

    public SharedFlowCookie setSharedId(long id) {
        return fork()
                .setField(SHARED_ID, id);
    }

    public long getSharedId() {
        return getField(SHARED_ID);
    }

    public SharedFlowCookie setSharedType(SharedFlowType type) {
        return fork()
                .setField(SHARED_TYPE_FIELD, type.getValue());
    }

    public SharedFlowType getSharedType() {
        return resolveEnum(SharedFlowType.values(), getField(SHARED_TYPE_FIELD), SharedFlowType.class);
    }

    @Override
    protected SharedFlowCookie setField(BitField field, long payload) {
        super.setField(field, payload);
        return this;
    }

    private SharedFlowCookie fork() {
        return new SharedFlowCookie(this);
    }

    public enum SharedFlowType implements ICookieEnumField {
        INGRESS_SEGMENT_OUTER_VLAN_MATCH(0);

        @Getter
        private final int value;

        SharedFlowType(int value) {
            this.value = value;
        }
    }
}
