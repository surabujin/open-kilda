/* Copyright 2019 Telstra Open Source
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

package org.openkilda.wfm.topology.floodlightrouter.bolts;

import org.openkilda.messaging.AliveRequest;
import org.openkilda.messaging.AliveResponse;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.command.CommandMessage;
import org.openkilda.messaging.command.discovery.NetworkCommandData;
import org.openkilda.messaging.info.InfoData;
import org.openkilda.model.FeatureToggles;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.repositories.FeatureTogglesRepository;
import org.openkilda.wfm.AbstractBolt;
import org.openkilda.wfm.CommandContext;
import org.openkilda.wfm.topology.AbstractTopology;
import org.openkilda.wfm.topology.floodlightrouter.ComponentType;
import org.openkilda.wfm.topology.floodlightrouter.RegionAwareKafkaTopicSelector;
import org.openkilda.wfm.topology.floodlightrouter.Stream;
import org.openkilda.wfm.topology.floodlightrouter.service.FloodlightTracker;
import org.openkilda.wfm.topology.floodlightrouter.service.RegionMonitorCarrier;
import org.openkilda.wfm.topology.utils.AbstractTickRichBolt;

import lombok.extern.slf4j.Slf4j;
import org.apache.storm.kafka.bolt.mapper.FieldNameBasedTupleToKafkaMapper;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

// FIXME(surabujin) must use AbstractBolt as base
@Slf4j
public class RegionTrackerBolt extends AbstractTickRichBolt implements RegionMonitorCarrier {
    public static final String BOLT_ID = ComponentType.KILDA_TOPO_DISCO_BOLT;

    public static final String FIELD_ID_REGION = SpeakerToNetworkProxyBolt.FIELD_ID_REGION;

    public static final String STREAM_SPEAKER_ID = Stream.SPEAKER_DISCO;

    public static final String STREAM_REGION_NOTIFICATION_ID = "region";  // FIXME - update subscriptions
    public static final Fields STREAM_REGION_NOTIFICATION_FIELDS = new Fields(
            FIELD_ID_REGION, AbstractBolt.FIELD_ID_CONTEXT);

    private final String kafkaSpeakerTopic;

    private final PersistenceManager persistenceManager;

    private final Set<String> floodlights;
    private final long floodlightAliveTimeout;
    private final long floodlightAliveInterval;
    private final long floodlightDumpInterval;
    private long lastNetworkDumpTimestamp;

    private transient FeatureTogglesRepository featureTogglesRepository;
    private transient FloodlightTracker floodlightTracker;
    private transient CommandContext commandContext;

    private Tuple currentTuple;

    public RegionTrackerBolt(
            String kafkaSpeakerTopic, PersistenceManager persistenceManager, Set<String> floodlights,
            long floodlightAliveTimeout, long floodlightAliveInterval, long floodlightDumpInterval) {
        super();

        this.kafkaSpeakerTopic = kafkaSpeakerTopic;

        this.persistenceManager = persistenceManager;
        this.floodlights = floodlights;
        this.floodlightAliveTimeout = floodlightAliveTimeout;
        this.floodlightAliveInterval = floodlightAliveInterval;
        this.floodlightDumpInterval = TimeUnit.SECONDS.toMillis(floodlightDumpInterval);
    }

    @Override
    protected void doTick(Tuple tuple) {
        setupTuple(tuple);

        try {
            handleTick();
        } finally {
            cleanupTuple();
        }
    }

    @Override
    protected void doWork(Tuple input) {
        setupTuple(input);

        try {
            handleInput(input);
        } catch (Exception e) {
            log.error("Failed to process tuple {}", input, e);
        } finally {
            outputCollector.ack(input);
            cleanupTuple();
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer outputManager) {
        Fields fields = new Fields(
                FieldNameBasedTupleToKafkaMapper.BOLT_KEY, FieldNameBasedTupleToKafkaMapper.BOLT_MESSAGE,
                RegionAwareKafkaTopicSelector.FIELD_ID_TOPIC, RegionAwareKafkaTopicSelector.FIELD_ID_REGION);
        outputManager.declareStream(STREAM_SPEAKER_ID, fields);

        outputManager.declareStream(STREAM_REGION_NOTIFICATION_ID, STREAM_REGION_NOTIFICATION_FIELDS);
    }

    @Override
    public void prepare(Map map, TopologyContext topologyContext, OutputCollector outputCollector) {
        super.prepare(map, topologyContext, outputCollector);

        featureTogglesRepository = persistenceManager.getRepositoryFactory().createFeatureTogglesRepository();
        floodlightTracker = new FloodlightTracker(this, floodlights, floodlightAliveTimeout, floodlightAliveInterval);
    }

    private void handleTick() {
        floodlightTracker.emitAliveRequests();
        floodlightTracker.handleAliveExpiration();

        long now = System.currentTimeMillis();
        if (now >= lastNetworkDumpTimestamp + floodlightDumpInterval) {
            doNetworkDump();
            lastNetworkDumpTimestamp = now;
        }
    }

    private void handleInput(Tuple input) {
        String sourceComponent = input.getSourceComponent();
        Message message = (Message) input.getValueByField(AbstractTopology.MESSAGE_FIELD);

        // setup correct command context
        commandContext = new CommandContext(message);

        if (SpeakerToNetworkProxyBolt.BOLT_ID.equals(sourceComponent)) {
            handleNetworkNotification(input);
        } else {
            reportUnhandledTuple(input);
        }
    }

    private void handleNetworkNotification(Tuple input) {
        String stream = input.getSourceStreamId();
        if (SpeakerToNetworkProxyBolt.STREAM_ALIVE_EVIDENCE_ID.equals(stream)) {
            handleAliveEvidenceNotification(input);
        } else if (SpeakerToNetworkProxyBolt.STREAM_REGION_NOTIFICATION_ID.equals(stream)) {
            handleRegionNotification(input);
        } else {
            reportUnhandledTuple(input);
        }
    }

    private void handleAliveEvidenceNotification(Tuple input) {
        String region = input.getStringByField(SpeakerToNetworkProxyBolt.FIELD_ID_REGION);
        long timestamp = input.getLongByField(SpeakerToNetworkProxyBolt.FIELD_ID_TIMESTAMP);

        floodlightTracker.handleAliveEvidence(region, timestamp);
    }

    private void handleRegionNotification(Tuple input) {
        String region = input.getStringByField(SpeakerToNetworkProxyBolt.FIELD_ID_REGION);
        InfoData payload = (InfoData) input.getValueByField(SpeakerToNetworkProxyBolt.FIELD_ID_PAYLOAD);
        handleRegionNotification(region, payload);
    }

    private void handleRegionNotification(String region, InfoData payload) {
        if (payload instanceof AliveResponse) {
            floodlightTracker.handleAliveResponse(region, (AliveResponse) payload);
        } else {
            log.error(
                    "Got unexpected notification from {}:{} - {}",
                    SpeakerToNetworkProxyBolt.BOLT_ID, SpeakerToNetworkProxyBolt.STREAM_REGION_NOTIFICATION_ID,
                    payload);
        }
    }

    private void setupTuple(Tuple input) {
        currentTuple = input;
        commandContext = new CommandContext();
    }

    private void cleanupTuple() {
        currentTuple = null;
        commandContext = null;
    }

    private void reportUnhandledTuple(Tuple input) {
        log.error(
                "There is no handler for tuple from {}:{}: {}",
                input.getSourceComponent(), input.getSourceStreamId(), input);
    }

    // SwitchStatusCarrier implementation

    @Override
    public void emitSpeakerAliveRequest(String region) {
        AliveRequest request = new AliveRequest();
        CommandMessage message = new CommandMessage(request, System.currentTimeMillis(),
                                                    commandContext.fork(String.format("alive-request(%s)", region))
                                                            .getCorrelationId());
        outputCollector.emit(
                STREAM_SPEAKER_ID, currentTuple, makeSpeakerTuple(pullKeyFromCurrentTuple(), message, region));
    }

    /**
     * Send network dump requests for target region.
     */
    @Override
    public void emitNetworkDumpRequest(String region) {
        String correlationId = commandContext.fork(String.format("network-dump(%s)", region)).getCorrelationId();
        CommandMessage command = new CommandMessage(new NetworkCommandData(),
                                                    System.currentTimeMillis(), correlationId);

        log.info("Send network dump request (correlation-id: {})", correlationId);
        outputCollector.emit(
                STREAM_SPEAKER_ID, currentTuple, makeSpeakerTuple(correlationId, command, region));
    }

    @Override
    public void emitRegionBecameUnavailableNotification(String region) {
        outputCollector.emit(STREAM_REGION_NOTIFICATION_ID, currentTuple, makeRegionNotificationTuple(region));
    }

    private String pullKeyFromCurrentTuple() {
        if (currentTuple.getFields().contains(AbstractTopology.KEY_FIELD)) {
            return currentTuple.getStringByField(AbstractTopology.KEY_FIELD);
        }
        return null;
    }

    private void doNetworkDump() {
        if (!queryPeriodicSyncFeatureToggle()) {
            log.warn("Skip periodic network sync (disabled by feature toggle)");
            return;
        }

        log.debug("Do periodic network dump request");
        for (String region : floodlights) {
            emitNetworkDumpRequest(region);
        }
    }

    private boolean queryPeriodicSyncFeatureToggle() {
        return featureTogglesRepository.find()
                .map(FeatureToggles::getFloodlightRoutePeriodicSync)
                .orElse(FeatureToggles.DEFAULTS.getFloodlightRoutePeriodicSync());
    }

    private Values makeSpeakerTuple(String key, Message payload, String region) {
        return new Values(key, payload, kafkaSpeakerTopic, region);
    }

    private Values makeRegionNotificationTuple(String region) {
        return new Values(region, commandContext);
    }
}
