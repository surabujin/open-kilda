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

package org.openkilda.model.bitops.cookie;

import org.openkilda.exception.InvalidCookieException;
import org.openkilda.model.Cookie;
import org.openkilda.model.Cookie.CookieType;
import org.openkilda.model.MeterId;
import org.openkilda.model.ServiceCookie.ServiceCookieTag;
import org.openkilda.model.bitops.BitField;
import org.openkilda.model.bitops.NumericEnumField;

import org.apache.commons.lang3.ArrayUtils;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ServiceCookieSchema extends CookieSchema {
    public static final ServiceCookieSchema INSTANCE = new ServiceCookieSchema();

    public Cookie make(ServiceCookieTag tag) {
        return make(CookieType.SERVICE_OR_FLOW_SEGMENT, tag.getValue());
    }

    public Cookie make(CookieType type) {
        return make(type, 0);
    }

    /**
     * Create new cookie instance and fill type and id fields.
     */
    public Cookie make(CookieType type, long uniqueId) {

        long payload = setType(0, type);
        payload = setField(payload, SERVICE_FLAG, 1);
        payload = setField(payload, UNIQUE_ID, uniqueId);
        return new Cookie(payload);
    }

    public long getUniqueId(Cookie cookie) {
        return getField(cookie.getValue(), UNIQUE_ID);
    }

    protected ServiceCookieSchema() {
        super();
    }
}
