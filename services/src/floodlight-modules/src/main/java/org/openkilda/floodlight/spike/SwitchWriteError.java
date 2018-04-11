package org.openkilda.floodlight.spike;

import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.types.DatapathId;

public class SwitchWriteError extends AbstractOfModError {

    public SwitchWriteError(DatapathId dpId, OFMessage payload) {
        super(dpId, payload, makeErrorMessage(dpId, payload));
    }

    private static String makeErrorMessage(DatapathId dpId, OFMessage payload) {
        return String.format("failure during uploading OFMessage to the switch (%s <= %s)", dpId, payload);
    }
}
