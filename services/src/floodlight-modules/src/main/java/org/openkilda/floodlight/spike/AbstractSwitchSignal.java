package org.openkilda.floodlight.spike;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.projectfloodlight.openflow.types.DatapathId;

public abstract class AbstractSwitchSignal extends Signal {
    private DatapathId dpId;

    public AbstractSwitchSignal(DatapathId dpId) {
        this.dpId = dpId;
    }

    public DatapathId getDpId() {
        return dpId;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("dpId", dpId)
                .toString();
    }
}
