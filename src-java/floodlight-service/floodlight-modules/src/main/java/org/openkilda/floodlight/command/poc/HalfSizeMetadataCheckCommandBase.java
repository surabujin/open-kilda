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

package org.openkilda.floodlight.command.poc;

import org.openkilda.floodlight.command.SpeakerCommand;
import org.openkilda.floodlight.command.SpeakerCommandProcessor;
import org.openkilda.floodlight.service.session.Session;
import org.openkilda.floodlight.switchmanager.SwitchManager;
import org.openkilda.floodlight.utils.metadata.MetadataAdapter;
import org.openkilda.floodlight.utils.metadata.MetadataAdapter.MetadataMatch;
import org.openkilda.messaging.MessageContext;
import org.openkilda.model.Cookie;
import org.openkilda.model.SwitchId;
import org.openkilda.model.bitops.cookie.CookieSchema.CookieType;
import org.openkilda.model.bitops.cookie.ServiceCookieSchema;

import com.google.common.collect.ImmutableList;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.OFMetadata;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TableId;
import org.projectfloodlight.openflow.types.U64;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public abstract class HalfSizeMetadataCheckCommandBase extends SpeakerCommand<CheckCommandReport> {
    private static final int PRIORITY_BASE = 32000;

    private final int portNumber;

    private final Cookie cookie;

    public HalfSizeMetadataCheckCommandBase(
            MessageContext messageContext, SwitchId switchId, UUID commandId, int portNumber) {
        super(messageContext, switchId, commandId);
        this.portNumber = portNumber;

        cookie = ServiceCookieSchema.INSTANCE.make(CookieType.SERVICE_OR_FLOW_SEGMENT, 0xff00001);
    }

    @Override
    protected CompletableFuture<CheckCommandReport> makeExecutePlan(SpeakerCommandProcessor commandProcessor) {
        List<CompletableFuture<Optional<OFMessage>>> writeBatch = new ArrayList<>();
        try (Session session = getSessionService().open(messageContext, getSw())) {
            for (OFMessage message : makeFlowModMessages()) {
                writeBatch.add(session.write(message));
            }
        }

        return CompletableFuture.allOf(writeBatch.toArray(new CompletableFuture[0]))
                .thenApply(ignore -> makeReport(null));
    }

    @Override
    protected CheckCommandReport makeReport(Exception error) {
        return new CheckCommandReport(this, error);
    }

    private List<OFMessage> makeFlowModMessages() {
        List<OFMessage> flowMods = new ArrayList<>();
        OFFactory of = getSw().getOFFactory();
        MetadataAdapter metadataAdapter = new MetadataAdapter(Collections.emptySet());

        flowMods.add(makeTableDefaultModMessage(of, 0));
        flowMods.add(makeTableDefaultModMessage(of, SwitchManager.PRE_INGRESS_TABLE_ID));
        flowMods.add(makeTableDefaultModMessage(of, SwitchManager.INGRESS_TABLE_ID));
        flowMods.add(makeTableDefaultModMessage(of, SwitchManager.POST_INGRESS_TABLE_ID));
        flowMods.add(makePortRedirectMessage(of));
        flowMods.add(makeMetadataMarker(of, metadataAdapter));
        flowMods.add(makeUpperHalfMetadataBit(of, metadataAdapter));
        flowMods.add(makeLowerHalfMetadataBit(of, metadataAdapter, SwitchManager.INGRESS_TABLE_ID));
        flowMods.add(makeLowerHalfMetadataBit(of, metadataAdapter, SwitchManager.POST_INGRESS_TABLE_ID));
        return flowMods;
    }

    private OFMessage makeTableDefaultModMessage(OFFactory of, int tableNumber) {
        return makeFlowModBuilder(of)
                .setTableId(TableId.of(tableNumber))
                .setPriority(0)
                .setCookie(U64.of(cookie.getValue()))
                .build();
    }

    private OFMessage makePortRedirectMessage(OFFactory of) {
        return makeFlowModBuilder(of)
                .setPriority(PRIORITY_BASE + 100)
                .setMatch(of.buildMatch().setExact(MatchField.IN_PORT, OFPort.of(portNumber)).build())
                .setInstructions(Collections.singletonList(
                        of.instructions().gotoTable(TableId.of(SwitchManager.PRE_INGRESS_TABLE_ID))))
                .build();
    }

    private OFMessage makeMetadataMarker(OFFactory of, MetadataAdapter metadataAdapter) {
        MetadataMatch metadata = metadataAdapter.addressOuterVlanPresenceFlag(true);
        metadata = metadataAdapter.addressLldpMarkerFlag(metadata, true);
        return makeFlowModBuilder(of)
                .setTableId(TableId.of(SwitchManager.PRE_INGRESS_TABLE_ID))
                .setPriority(PRIORITY_BASE + 100) // FIXME: need be above discovery catch flow
                .setCookie(U64.of(cookie.getValue()))
                .setMatch(of.buildMatch()
                        .setExact(MatchField.IN_PORT, OFPort.of(portNumber))
                        .build())
                .setInstructions(ImmutableList.of(
                        of.instructions().writeMetadata(metadata.getValue(), metadata.getMask()),
                        of.instructions().gotoTable(TableId.of(SwitchManager.INGRESS_TABLE_ID))))
                .build();
    }

    private OFMessage makeUpperHalfMetadataBit(OFFactory of, MetadataAdapter metadataAdapter) {
        MetadataMatch metadata = metadataAdapter.addressOuterVlanPresenceFlag(true);
        return makeFlowModBuilder(of)
                .setTableId(TableId.of(SwitchManager.INGRESS_TABLE_ID))
                .setPriority(PRIORITY_BASE + 100)
                .setCookie(U64.of(cookie.getValue()))
                .setMatch(of.buildMatch()
                        .setMasked(
                                MatchField.METADATA,
                                OFMetadata.of(metadata.getValue()), OFMetadata.of(metadata.getMask())
                        ).build())
                .setInstructions(Collections.singletonList(
                        of.instructions().gotoTable(TableId.of(SwitchManager.POST_INGRESS_TABLE_ID))))
                .build();
    }

    private OFMessage makeLowerHalfMetadataBit(OFFactory of, MetadataAdapter metadataAdapter, int tableNumber) {
        MetadataMatch metadata = metadataAdapter.addressLldpMarkerFlag(true);
        return makeFlowModBuilder(of)
                .setTableId(TableId.of(tableNumber))
                .setPriority(PRIORITY_BASE + 50)
                .setCookie(U64.of(cookie.getValue()))
                .setMatch(of.buildMatch()
                        .setMasked(
                                MatchField.METADATA,
                                OFMetadata.of(metadata.getValue()), OFMetadata.of(metadata.getMask())
                        ).build())
                .setInstructions(Collections.singletonList(
                        of.instructions().gotoTable(TableId.of(SwitchManager.EGRESS_TABLE_ID))))
                .build();
    }

    protected abstract OFFlowMod.Builder makeFlowModBuilder(OFFactory of);
}
