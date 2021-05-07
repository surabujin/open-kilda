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

import org.openkilda.model.history.FlowHistory.FlowHistoryData;
import org.openkilda.persistence.hibernate.entities.EntityBase;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Table;

import java.time.Instant;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.ManyToOne;
import javax.persistence.Transient;

@Getter
@Setter
@Entity(name = "FlowEventAction")
@Table(appliesTo = "flow_event_actions")
public class FlowEventAction extends EntityBase implements FlowHistoryData {
    @Id
    @GeneratedValue
    private Long id;

    @Column(name = "flow_event_id")
    private Long flowEventId;

    @Column(name = "action")
    private String action;

    @Column(name = "details")
    private String details;

    @Transient
    private String taskId;

    @ManyToOne
    private FlowEvent event;

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
    public String getTaskId() {
        if (taskId != null) {
            return taskId;
        }
        if (event != null) {
            return event.getTaskId();
        }
        return null;
    }

    @Override
    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }
}
