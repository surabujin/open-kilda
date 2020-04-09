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

import org.openkilda.exception.InvalidCookieException;
import org.openkilda.model.bitops.BitField;
import org.openkilda.model.bitops.NumericEnumField;
import org.openkilda.model.bitops.cookie.CookieSchema.CookieType;
import org.openkilda.model.bitops.cookie.ServiceCookieSchema;
import org.openkilda.model.bitops.cookie.ServiceCookieSchema.ServiceCookieTag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Value;

import java.io.Serializable;

/**
 * Represents information about a cookie.
 * Uses 64 bit to encode information about the flow:
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |            Payload Reserved           |                       |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |           Reserved Prefix           |C|     Rule Type   | | | |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * <p>
 * Rule types:
 * 0 - Customer flow rule
 * 1 - LLDP rule
 * 2 - Multi-table ISL rule for vlan encapsulation for egress table
 * 3 - Multi-table ISL rule for vxlan encapsulation for egress table
 * 4 - Multi-table ISL rule for vxlan encapsulation for transit table
 * 5 - Multi-table customer flow rule for ingress table pass-through
 * </p>
 */
@EqualsAndHashCode(of = {"value"})
public class Cookie implements Comparable<Cookie>, Serializable {
    private static final long serialVersionUID = 1L;

    // FIXME(surabujin): get rid from this constants
    public static final long DROP_RULE_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.DROP_RULE_COOKIE).getValue();
    public static final long VERIFICATION_BROADCAST_RULE_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.VERIFICATION_BROADCAST_RULE_COOKIE).getValue();
    public static final long VERIFICATION_UNICAST_RULE_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.VERIFICATION_UNICAST_RULE_COOKIE).getValue();
    public static final long DROP_VERIFICATION_LOOP_RULE_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.DROP_VERIFICATION_LOOP_RULE_COOKIE).getValue();
    public static final long CATCH_BFD_RULE_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.CATCH_BFD_RULE_COOKIE).getValue();
    public static final long ROUND_TRIP_LATENCY_RULE_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.ROUND_TRIP_LATENCY_RULE_COOKIE).getValue();
    public static final long VERIFICATION_UNICAST_VXLAN_RULE_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.VERIFICATION_UNICAST_VXLAN_RULE_COOKIE).getValue();
    public static final long MULTITABLE_PRE_INGRESS_PASS_THROUGH_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.MULTITABLE_PRE_INGRESS_PASS_THROUGH_COOKIE).getValue();
    public static final long MULTITABLE_INGRESS_DROP_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.MULTITABLE_INGRESS_DROP_COOKIE).getValue();
    public static final long MULTITABLE_POST_INGRESS_DROP_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.MULTITABLE_POST_INGRESS_DROP_COOKIE).getValue();
    public static final long MULTITABLE_EGRESS_PASS_THROUGH_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.MULTITABLE_EGRESS_PASS_THROUGH_COOKIE).getValue();
    public static final long MULTITABLE_TRANSIT_DROP_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.MULTITABLE_TRANSIT_DROP_COOKIE).getValue();
    public static final long LLDP_INPUT_PRE_DROP_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.LLDP_INPUT_PRE_DROP_COOKIE).getValue();
    public static final long LLDP_TRANSIT_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.LLDP_TRANSIT_COOKIE).getValue();
    public static final long LLDP_INGRESS_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.LLDP_INGRESS_COOKIE).getValue();
    public static final long LLDP_POST_INGRESS_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.LLDP_POST_INGRESS_COOKIE).getValue();
    public static final long LLDP_POST_INGRESS_VXLAN_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.LLDP_POST_INGRESS_VXLAN_COOKIE).getValue();
    public static final long LLDP_POST_INGRESS_ONE_SWITCH_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.LLDP_POST_INGRESS_ONE_SWITCH_COOKIE).getValue();
    public static final long ARP_INPUT_PRE_DROP_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.ARP_INPUT_PRE_DROP_COOKIE).getValue();
    public static final long ARP_TRANSIT_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.ARP_TRANSIT_COOKIE).getValue();
    public static final long ARP_INGRESS_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.ARP_INGRESS_COOKIE).getValue();
    public static final long ARP_POST_INGRESS_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.ARP_POST_INGRESS_COOKIE).getValue();
    public static final long ARP_POST_INGRESS_VXLAN_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.ARP_POST_INGRESS_VXLAN_COOKIE).getValue();
    public static final long ARP_POST_INGRESS_ONE_SWITCH_COOKIE = ServiceCookieSchema.INSTANCE.make(
            ServiceCookieTag.ARP_POST_INGRESS_ONE_SWITCH_COOKIE).getValue();

    // update ALL_FIELDS if modify fields list
    static final BitField TYPE_FIELD = new BitField(0x1FF0_0000_0000_0000L);
    static final BitField SERVICE_FLAG = new BitField(0x8000_0000_0000_0000L);

    // used by unit tests to check fields intersections
    static final BitField[] ALL_FIELDS = new BitField[]{SERVICE_FLAG, TYPE_FIELD};

    private final long value;

    @JsonCreator
    public Cookie(long value) {
        this.value = value;
    }

    @Builder
    public Cookie(CookieType type) {
        this(0, type);
    }

    protected Cookie(long blank, CookieType type) {
        value = setField(blank, TYPE_FIELD, type.getValue());
    }

    public boolean safeValidate() {
        try {
            validate();
            return true;
        } catch (InvalidCookieException e) {
            return false;
        }
    }

    public void validate() throws InvalidCookieException {
        // inheritors can implement validate logic
    }

    /**
     * Extract and return "type" field.
     */
    public CookieType getType() {
        int numericType = (int) getField(TYPE_FIELD);
        return resolveEnum(CookieType.values(), numericType, CookieType.class);
    }

    /**
     * Convert port number into isl-VLAN-egress "cookie".
     */
    @Deprecated
    public static long encodeIslVlanEgress(int port) {
        // FIXME(surabujin): do not allow to return "raw" long value
        return ServiceCookieSchema.INSTANCE.make(CookieType.MULTI_TABLE_ISL_VLAN_EGRESS_RULES, port).getValue();
    }

    /**
     * Convert port number into isl-VxLAN-egress "cookie".
     */
    @Deprecated
    public static long encodeIslVxlanEgress(int port) {
        // FIXME(surabujin): do not allow to return "raw" long value
        return ServiceCookieSchema.INSTANCE.make(CookieType.MULTI_TABLE_ISL_VXLAN_EGRESS_RULES, port).getValue();
    }

    /**
     * Convert port number into isl-VxLAN-transit "cookie".
     */
    @Deprecated
    public static long encodeIslVxlanTransit(int port) {
        // FIXME(surabujin): do not allow to return "raw" long value
        return ServiceCookieSchema.INSTANCE.make(CookieType.MULTI_TABLE_ISL_VXLAN_TRANSIT_RULES, port).getValue();
    }

    /**
     * Convert port number into ingress-rule-pass-through "cookie".
     */
    @Deprecated
    public static long encodeIngressRulePassThrough(int port) {
        // FIXME(surabujin): do not allow to return "raw" long value
        return ServiceCookieSchema.INSTANCE.make(CookieType.MULTI_TABLE_INGRESS_RULES, port).getValue();
    }

    /**
     * Creates masked cookie for LLDP rule.
     */
    @Deprecated
    public static long encodeLldpInputCustomer(int port) {
        // FIXME(surabujin): do not allow to return "raw" long value
        return ServiceCookieSchema.INSTANCE.make(CookieType.LLDP_INPUT_CUSTOMER_TYPE, port).getValue();
    }

    @Deprecated
    public static long encodeArpInputCustomer(int port) {
        // FIXME(surabujin): do not allow to return "raw" long value
        return ServiceCookieSchema.INSTANCE.make(CookieType.ARP_INPUT_CUSTOMER_TYPE, port).getValue();
    }

    /**
     * Create Cookie from meter ID of default rule by using of `DEFAULT_RULES_FLAG`.
     *
     * @param meterId meter ID
     * @return cookie
     * @throws IllegalArgumentException if meter ID is out of range of default meter ID range
     */
    @Deprecated
    public static Cookie createCookieForDefaultRule(long meterId) {
        // FIXME(surabujin): replace with direct schema call
        Cookie blank = ServiceCookieSchema.INSTANCE.makeBlank();
        return ServiceCookieSchema.INSTANCE.setMeterId(blank, new MeterId(meterId));
    }

    @Deprecated
    public static boolean isDefaultRule(long cookie) {
        // FIXME(surabujin): replace with direct cookie call
        return new ServiceCookie(cookie).safeValidate();
    }

    /**
     * Check is cookie have type MULTI_TABLE_INGRESS_RULES.
     *
     * <p>Deprecated {@code ServiceCookieSchema.getType()} must be used instead of this method.
     */
    @Deprecated
    public static boolean isIngressRulePassThrough(long raw) {
        // FIXME(surabujin): replace with direct cookie call
        return new Cookie(raw).getType() == CookieType.MULTI_TABLE_INGRESS_RULES;
    }

    protected void validateServiceFlag(boolean expectedValue) throws InvalidCookieException {
        boolean actual = getField(SERVICE_FLAG) != 0;
        if (expectedValue != actual) {
            throw new InvalidCookieException(
                    String.format("Service flag is expected to be %s", expectedValue ? "set" : "unset"), this);
        }
    }

    @JsonValue
    public long getValue() {
        return value;
    }

    /**
     * Convert existing object into builder.
     *
     * <p>Can't delegate production of this method to lombok, because it can't read "virtual" fields i.e. fields not
     * declared into class but accessible via getters.
     */
    public CookieBuilder toBuilder() {
        return new CookieBuilder()
                .type(getType());
    }

    @Override
    public String toString() {
        return toString(value);
    }

    protected long getField(BitField field) {
        long payload = value & field.getMask();
        return payload >>> field.getOffset();
    }

    protected static <T extends NumericEnumField> T resolveEnum(T[] valuesSpace, long needle, Class<T> typeRef) {
        for (T entry : valuesSpace) {
            if (entry.getValue() == needle) {
                return entry;
            }
        }

        throw new IllegalArgumentException(String.format(
                "Unable to map value %x value into %s value", needle, typeRef.getSimpleName()));
    }

    protected static long setField(long value, BitField field, long payload) {
        long mask = field.getMask();
        payload <<= field.getOffset();
        payload &= mask;
        return (value & ~mask) | payload;
    }

    public static String toString(long cookie) {
        return String.format("0x%016X", cookie);
    }

    @Override
    public int compareTo(Cookie compareWith) {
        return Long.compare(value, compareWith.value);
    }

    // 9 bit long type field
    public enum CookieType implements NumericEnumField {
        SERVICE_OR_FLOW_SEGMENT(0x000),
        LLDP_INPUT_CUSTOMER_TYPE(0x001),
        MULTI_TABLE_ISL_VLAN_EGRESS_RULES(0x002),
        MULTI_TABLE_ISL_VXLAN_EGRESS_RULES(0x003),
        MULTI_TABLE_ISL_VXLAN_TRANSIT_RULES(0x004),
        MULTI_TABLE_INGRESS_RULES(0x005),
        ARP_INPUT_CUSTOMER_TYPE(0x006),
        INGRESS_SEGMENT(0x007),   // used for ingress flow segment and for one switch flow segments
        SHARED_OF_FLOW(0x008);

        private int value;

        CookieType(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }
}
