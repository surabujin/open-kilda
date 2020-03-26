/* Copyright 2018 Telstra Open Source
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

package org.openkilda.floodlight.command.ping;

import org.openkilda.floodlight.command.Command;
import org.openkilda.floodlight.command.CommandContext;
import org.openkilda.floodlight.error.CorruptedNetworkDataException;
import org.openkilda.floodlight.model.OfInput;
import org.openkilda.floodlight.model.PingData;
import org.openkilda.floodlight.model.PingWiredView;
import org.openkilda.floodlight.service.ping.PingService;
import org.openkilda.messaging.floodlight.response.PingResponse;
import org.openkilda.messaging.model.Ping;
import org.openkilda.messaging.model.PingMeters;
import org.openkilda.model.FlowEndpoint;
import org.openkilda.model.SwitchId;

import com.auth0.jwt.interfaces.DecodedJWT;
import net.floodlightcontroller.packet.Ethernet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PingResponseCommand extends PingCommand {
    private static final Logger log = LoggerFactory.getLogger(PingResponseCommand.class);

    private final OfInput input;

    public PingResponseCommand(CommandContext context, OfInput input) {
        super(context);

        this.input = input;
    }

    @Override
    public Command call() throws Exception {
        log.debug("{} - {}", getClass().getCanonicalName(), input);

        byte[] payload = unwrap();
        if (payload == null) {
            return null;
        }

        log.info("Receive flow ping packet from switch {} OF-xid:{}", input.getDpId(), input.getMessage().getXid());
        try {
            PingData pingData = decode(payload);
            getContext().setCorrelationId(pingData.getPingId().toString());

            process(pingData);
        } catch (CorruptedNetworkDataException e) {
            logPing.error(String.format("dpid:%s %s", input.getDpId(), e));
        }

        return null;
    }

    private byte[] unwrap() {
        if (input.packetInCookieMismatchAll(log, PingService.OF_CATCH_RULE_COOKIE,
                PingService.OF_CATCH_RULE_COOKIE_VXLAN)) {
            return null;
        }

        Ethernet ethernetPackage = input.getPacketInPayload();
        if (ethernetPackage == null) {
            log.error("{} - payload is missing", input);
            return null;
        }

        PingWiredView wiredView = getPingService().unwrapData(input.getDpId(), ethernetPackage);
        if (wiredView == null) {
            return null;
        }

        return wiredView.getPayload();
    }

    private PingData decode(byte[] payload) throws CorruptedNetworkDataException {
        DecodedJWT token = getPingService().getSignature().verify(payload);
        return PingData.of(token);
    }

    private void process(PingData data) {
        Long latency = input.getLatency();
        if (latency == null) {
            log.warn("There is no latency info for {} - ping latency is unreliable", data.getPingId());
            latency = 0L;
        }
        PingMeters meters = data.produceMeasurements(input.getReceiveTime(), latency);
        logCatch(data, meters);

        PingResponse response = new PingResponse(getContext().getCtime(), data.getPingId(), meters);
        sendResponse(response);
    }

    private void logCatch(PingData data, PingMeters meters) {
        String sourceEndpoint = Ping.formatEndpoint(
                new SwitchId(data.getSource().getLong()), data.getIngressPortNumber(),
                FlowEndpoint.makeVlanStack(data.getIngressVlanId(), data.getIngressInnerVlanId()));
        String pingId = String.format("ping{%s}", data.getPingId().toString());
        logPing.info(
                "Catch ping {} ===( {}, latency: {}ms )===> {}",
                sourceEndpoint, pingId, meters.getNetworkLatency(), data.getDest());
    }
}
