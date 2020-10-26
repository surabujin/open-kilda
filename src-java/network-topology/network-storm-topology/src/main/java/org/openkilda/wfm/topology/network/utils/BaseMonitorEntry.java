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

package org.openkilda.wfm.topology.network.utils;

import lombok.Getter;
import lombok.NonNull;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

abstract class BaseMonitorEntry<L, S> {
    protected final List<L> subscribers = new LinkedList<>();

    @Getter
    @NonNull
    protected S status;

    public BaseMonitorEntry(@NonNull S status) {
        this.status = status;
    }

    void subscribe(L listener) {
        subscribers.add(listener);
    }

    boolean unsubscribe(L listener) {
        subscribers.remove(listener);
        return subscribers.isEmpty();
    }

    void update(@NonNull S change) {
        if (Objects.equals(status, change)) {
            return;
        }

        status = change;
        for (L entry : subscribers) {
            propagate(entry, change);
        }
    }

    abstract void propagate(L listener, S change);
}
