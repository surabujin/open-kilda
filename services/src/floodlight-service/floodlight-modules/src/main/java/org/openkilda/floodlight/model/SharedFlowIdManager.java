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

package org.openkilda.floodlight.model;

import java.util.HashSet;
import java.util.Set;

public class SharedFlowIdManager {
    private final Set<Integer> busySet = new HashSet<>();

    private static final int ALLOCATION_RANGE_START = 1;
    private static final int ALLOCATION_RANGE_END = 0x7fff_ffff;

    private int allocationPointer = ALLOCATION_RANGE_START;

    public synchronized int allocate() {
        boolean isLooped = false;
        while (! busySet.add(allocationPointer)) {
            allocationPointer += 1;
            if (allocationPointer == ALLOCATION_RANGE_END) {
                if (isLooped) {
                    throw new IllegalStateException(String.format(
                            "There is no free ID in range from %d to %d",
                            ALLOCATION_RANGE_START, ALLOCATION_RANGE_END));
                }

                isLooped = true;
                allocationPointer = ALLOCATION_RANGE_START;
            }
        }
        return allocationPointer;
    }

    public synchronized void release(int value) {
        busySet.remove(value);
    }
}
