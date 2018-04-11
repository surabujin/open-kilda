package org.openkilda.floodlight.spike;

import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.types.DatapathId;

public class SwitchWriteRepeatableError extends SwitchWriteError {
    public SwitchWriteRepeatableError(DatapathId dpId, OFMessage payload) {
        super(dpId, payload);
    }
}
