/* Copyright 2021 Telstra Open Source
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

package org.openkilda.persistence.hibernate.entities.history;

import org.openkilda.model.SwitchId;
import org.openkilda.model.history.PortHistory.PortHistoryData;
import org.openkilda.persistence.hibernate.converters.SwitchIdConverter;
import org.openkilda.persistence.hibernate.entities.EntityBase;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Delegate;
import org.hibernate.annotations.Table;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.UUID;
import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;

@Getter
@Setter
@Entity(name = "PortEvent")
@Table(appliesTo = "port_events_history")
public class PortEvent extends EntityBase implements PortHistoryData {
    @Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "string")
    private UUID recordId;

    @Convert(converter = SwitchIdConverter.class)
    @Column(name = "switch_id")
    private SwitchId switchId;

    @Column(name = "port_number")
    private int portNumber;

    @Column(name = "event")
    String event;

    @Delegate
    @Type(type = "json")
    @Column(name = "unstructured", columnDefinition = "json")
    private PortEventUnstructured unstructured;

    public PortEvent() {
        unstructured = new PortEventUnstructured();
    }

    @Override
    public Instant getTime() {
        return getTimeCreated();
    }

    @Override
    public void setTime(Instant value) {
        if (timeCreated != null) {
            throw new IllegalStateException("Unable to update creation_time");
        }
        timeCreated = value;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class PortEventUnstructured {
        int upEventsCount;
        int downEventsCount;
    }
}
