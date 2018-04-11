package org.openkilda.floodlight.spike;

import org.projectfloodlight.openflow.types.DatapathId;

public class SwitchAddSignal extends AbstractSwitchSignal {
    public SwitchAddSignal(DatapathId dpId) {
        super(dpId);
    }
}
