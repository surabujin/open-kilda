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

import org.openkilda.model.Cookie;
import org.openkilda.model.bitops.BitField;

import org.junit.Test;

public class CookieSchemaTest extends GenericCookieSchemaTest {
    @Test(expected = IllegalArgumentException.class)
    public void ensureNoEmptyMasks() {
        new BitField(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void ensureNoGapsInMask() {
        new BitField(5);
    }

    @Test
    public void ensureNoChangeOutsideFieldBoundary() {
        testFieldReadWrite(new Cookie(0x0009L), 0x000BL, new BitField(0x6), 0x1);
        testFieldReadWrite(new Cookie(0x0000L), 0x0002L, new BitField(0x6), 0x1);
        testFieldReadWrite(new Cookie(0x0000L), 0x0006L, new BitField(0x6), 0x7, 0x3);
    }

    @Test
    public void goBeyondUpperBoundary() {
        testFieldReadWrite(
                new Cookie(0), 0x8000_0000_0000_0000L, new BitField(0x8000_0000_0000_0000L), 3, 1);
    }

    @Test
    public void ensureOverrideZeroBitsInsideField() {
        testFieldReadWrite(new Cookie(0x0004L), 0x000AL, new BitField(0xE), 0x5);
    }

    @Test
    public void defaultFlagLocation() {
        testFieldReadWrite(new Cookie(-1L), ~0x8000_0000_0000_0000L, CookieSchema.SERVICE_FLAG, 0);
    }

    @Test
    public void typeFieldLocation() {
        testFieldReadWrite(new Cookie(-1L), ~0x1FF0_0000_0000_0000L, CookieSchema.TYPE_FIELD, 0);
    }

    @Test
    public void ensureNoFieldsIntersection() {
        testFieldsIntersection(CookieSchema.ALL_FIELDS);
    }
}
