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

import org.openkilda.model.history.FlowDump;
import org.openkilda.model.history.FlowEvent.FlowEventData;
import org.openkilda.model.history.FlowHistory;
import org.openkilda.persistence.hibernate.entities.EntityBase;
import org.openkilda.persistence.hibernate.entities.JsonPayloadBase;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Delegate;
import org.hibernate.annotations.Table;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.OneToMany;

@Getter
@Setter
@Entity(name = "FlowEvent")
@Table(appliesTo = "flow_events_history")
public class FlowEvent extends EntityBase implements FlowEventData {
    @Id
    @GeneratedValue
    private Long id;

    @Column(name = "flow_id")
    private String flowId;

    @Column(name = "task_id")
    private String taskId;

    @Column(name = "action")
    private String action;

    @Delegate
    @Type(type = "json")
    @Column(name = "unstructured", columnDefinition = "json")
    private FlowEventUnstructured unstructured;

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL)
    private List<FlowEventAction> actions = new ArrayList<>();

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL)
    private List<FlowEventDump> dumps = new ArrayList<>();

    public FlowEvent() {
        unstructured = new FlowEventUnstructured();
    }

    public Instant getTimestamp() {
        return getTimeCreated();
    }

    /**
     * .
     */
    public void setTimestamp(Instant value) {
        if (timeCreated != null) {
            throw new IllegalStateException("Unable to update creation_time");
        }
        timeCreated = value;
    }

    @Override
    public List<FlowHistory> getHistoryRecords() {
        return actions.stream()
                .map(FlowHistory::new)
                .collect(Collectors.toList());
    }

    @Override
    public List<FlowDump> getFlowDumps() {
        return dumps.stream()
                .map(FlowDump::new)
                .collect(Collectors.toList());
    }

    public void addAction(FlowEventAction entry) {
        actions.add(entry);
        entry.setFlowEventId(getId());
    }

    public void addDump(FlowEventDump entry) {
        dumps.add(entry);
        entry.setFlowEventId(getId());
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class FlowEventUnstructured extends JsonPayloadBase {
        String actor;
        String details;
    }
}
