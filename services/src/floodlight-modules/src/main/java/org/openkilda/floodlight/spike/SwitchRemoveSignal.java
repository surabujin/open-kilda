package org.openkilda.floodlight.spike;

import org.projectfloodlight.openflow.types.DatapathId;

public class SwitchRemoveSignal extends AbstractSwitchSignal {

    public SwitchRemoveSignal(DatapathId dpId) {
        super(dpId);
    }
}
