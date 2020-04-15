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

package org.openkilda.wfm.topology.network.controller.isl;

import org.openkilda.messaging.command.reroute.RerouteAffectedFlows;
import org.openkilda.messaging.command.reroute.RerouteInactiveFlows;
import org.openkilda.messaging.info.event.IslStatusUpdateNotification;
import org.openkilda.messaging.info.event.PathNode;
import org.openkilda.model.FeatureToggles;
import org.openkilda.model.Isl;
import org.openkilda.model.Isl.IslBuilder;
import org.openkilda.model.IslDownReason;
import org.openkilda.model.IslStatus;
import org.openkilda.model.LinkProps;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.model.SwitchStatus;
import org.openkilda.persistence.PersistenceException;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.FeatureTogglesRepository;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.LinkPropsRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.model.IslReference;
import org.openkilda.wfm.share.utils.AbstractBaseFsm;
import org.openkilda.wfm.share.utils.FsmExecutor;
import org.openkilda.wfm.topology.network.NetworkTopologyDashboardLogger;
import org.openkilda.wfm.topology.network.controller.isl.IslFsm.IslFsmContext;
import org.openkilda.wfm.topology.network.controller.isl.IslFsm.IslFsmEvent;
import org.openkilda.wfm.topology.network.controller.isl.IslFsm.IslFsmState;
import org.openkilda.wfm.topology.network.model.BiIslDataHolder;
import org.openkilda.wfm.topology.network.model.IslDataHolder;
import org.openkilda.wfm.topology.network.model.IslEndpointRoundTripStatus;
import org.openkilda.wfm.topology.network.model.IslEndpointStatus;
import org.openkilda.wfm.topology.network.model.NetworkOptions;
import org.openkilda.wfm.topology.network.model.RoundTripStatus;
import org.openkilda.wfm.topology.network.model.facts.DiscoveryFacts;
import org.openkilda.wfm.topology.network.service.IIslCarrier;
import org.openkilda.wfm.topology.network.storm.bolt.isl.BfdManager;

import com.google.common.collect.ImmutableList;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.RetryPolicy;
import org.squirrelframework.foundation.fsm.Condition;
import org.squirrelframework.foundation.fsm.StateMachineBuilder;
import org.squirrelframework.foundation.fsm.StateMachineBuilderFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
public final class IslFsm extends AbstractBaseFsm<IslFsm, IslFsmState, IslFsmEvent, IslFsmContext> {
    private final IslReference reference;
    private IslStatus effectiveStatus;

    private final List<DiscoveryMonitor> monitorsByPriority;

    // FIXME - start
    private final Clock clock;

    private final IslReportFsm reportFsm;
    private final BfdManager bfdManager;

    private final IslRepository islRepository;
    private final LinkPropsRepository linkPropsRepository;
    private final FlowPathRepository flowPathRepository;
    private final SwitchRepository switchRepository;
    private final TransactionManager transactionManager;
    private final FeatureTogglesRepository featureTogglesRepository;
    private final SwitchPropertiesRepository switchPropertiesRepository;

    private final RetryPolicy transactionRetryPolicy;

    private final BiIslDataHolder<IslEndpointStatus> endpointStatus;
    private final BiIslDataHolder<IslEndpointRoundTripStatus> endpointRoundTripStatus;

    private final DiscoveryFacts discoveryFacts;
    private boolean ignoreRerouteOnUp = false;  // TODO: ??? do not work now
    private final NetworkOptions options;
    private long islRulesAttempts;
    // FIXME - end

    public static IslFsmFactory factory(Clock clock, PersistenceManager persistenceManager,
                                        NetworkTopologyDashboardLogger.Builder dashboardLoggerBuilder) {
        return new IslFsmFactory(clock, persistenceManager, dashboardLoggerBuilder);
    }

    public IslFsm(Clock clock, PersistenceManager persistenceManager, IslReportFsm reportFsm, BfdManager bfdManager,
                  NetworkOptions options, IslReference reference) {
        this.reference = reference;

        monitorsByPriority = ImmutableList.of(
                new DiscoveryPortStatusMonitor(reference),
                new DiscoveryBfdMonitor(reference),
                new DiscoveryRoundTripMonitor(reference, clock, options),
                new DiscoveryPollMonitor(reference));

        // FIXME - start
        this.clock = clock;

        this.reportFsm = reportFsm;
        this.bfdManager = bfdManager;

        transactionManager = persistenceManager.getTransactionManager();
        transactionRetryPolicy = transactionManager.makeRetryPolicyBlank()
                .withMaxDuration(options.getDbRepeatMaxDurationSeconds(), TimeUnit.SECONDS);

        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        islRepository = repositoryFactory.createIslRepository();
        linkPropsRepository = repositoryFactory.createLinkPropsRepository();
        flowPathRepository = repositoryFactory.createFlowPathRepository();
        switchRepository = repositoryFactory.createSwitchRepository();
        featureTogglesRepository = repositoryFactory.createFeatureTogglesRepository();
        switchPropertiesRepository = repositoryFactory.createSwitchPropertiesRepository();

        endpointStatus = new BiIslDataHolder<>(reference);
        endpointStatus.putBoth(new IslEndpointStatus(IslEndpointStatus.Status.DOWN));

        endpointRoundTripStatus = new BiIslDataHolder<>(reference);
        endpointRoundTripStatus.put(reference.getSource(), new IslEndpointRoundTripStatus());
        endpointRoundTripStatus.put(reference.getDest(), new IslEndpointRoundTripStatus());

        discoveryFacts = new DiscoveryFacts(reference);
        this.options = options;
        // FIXME - start
    }

    // -- FSM actions --

    public void loadPersistedState(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        transactionManager.doInTransaction(() -> {
            loadPersistentData(reference.getSource(), reference.getDest());
            loadPersistentData(reference.getDest(), reference.getSource());
        });

        effectiveStatus = evaluateStatus();
        emitBecomeStateEvent(context);
    }

    // FIXME - {{{
    public void historyRestoreUp(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        sendBfdEnable(context.getOutput());
    }

    public void handleInitialDiscovery(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        updateLinkData(context.getEndpoint(), context.getIslData());
        updateEndpointStatusByEvent(event, context);
        saveStatusTransaction();
    }

    public void updateEndpointStatus(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        updateEndpointStatusByEvent(event, context);
    }

    public void updateAndPersistEndpointStatus(IslFsmState from, IslFsmState to, IslFsmEvent event,
                                               IslFsmContext context) {
        updateEndpointStatusByEvent(event, context);
        saveStatusTransaction();
    }

    public void downEnter(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        reportFsm.fire(IslReportFsm.Event.BECOME_DOWN);

        saveStatusTransaction();
        sendIslStatusUpdateNotification(context, IslStatus.INACTIVE);
    }

    public void handleUpAttempt(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        updateLinkData(context.getEndpoint(), context.getIslData());

        IslFsmEvent route;
        if (evaluateStatus() == IslEndpointStatus.Status.UP) {
            route = IslFsmEvent._UP_ATTEMPT_SUCCESS;
        } else {
            route = IslFsmEvent._UP_ATTEMPT_FAIL;
        }
        fire(route, context);
    }

    public void setUpResourcesEnter(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        log.info("ISL {} become {}", discoveryFacts.getReference(), to);
        islRulesAttempts = options.getRulesSynchronizationAttempts();
        sendInstallMultitable(context);
    }

    public void setUpResourcesTimeout(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        islRulesAttempts -= 1;
        if (islRulesAttempts >= 0) {
            log.info("Retrying to install rules for multi table mode on isl {}", discoveryFacts.getReference());
            sendInstallMultitable(context);
        } else {
            log.warn("Failed to install rules for multi table mode on isl {}, required manual rule sync",
                    discoveryFacts.getReference());
            endpointStatus.getForward().setHasIslRules(true);
            endpointStatus.getReverse().setHasIslRules(true);
            fire(IslFsmEvent.ISL_UP, context);
        }
    }

    public void cleanUpResourcesTimeout(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        islRulesAttempts -= 1;
        if (islRulesAttempts >= 0) {
            log.info("Retrying to remove rules for multi table mode on isl {}", discoveryFacts.getReference());
            sendRemoveMultitable(context);
        } else {
            log.warn("Failed to remove rules for multi table mode on isl {}, required manual rule sync",
                    discoveryFacts.getReference());
            endpointStatus.getForward().setHasIslRules(false);
            endpointStatus.getReverse().setHasIslRules(false);
            fire(IslFsmEvent.ISL_REMOVE_FINISHED, context);
        }
    }

    public void upEnter(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        reportFsm.fire(IslReportFsm.Event.BECOME_UP);

        saveAllTransaction();
        sendBfdEnable(context.getOutput());

        if (!ignoreRerouteOnUp) {
            // Do not produce reroute during recovery system state from DB
            triggerDownFlowReroute(context);
        } else {
            ignoreRerouteOnUp = false;
        }
    }

    public void upHandlePhysicalDown(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        updateEndpointStatusByEvent(event, context);
        saveStatusAndSetIslUnstableTimeTransaction();
    }

    public void upHandlePollEvent(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        updateEndpointStatusByEvent(event, context);
        saveStatusTransaction();

        if (evaluateStatus() != IslEndpointStatus.Status.UP) {
            fire(IslFsmEvent._BECOME_DOWN, context);
        }
    }

    public void upHandleRoundTripStatus(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        RoundTripStatus status = context.getRoundTripStatus();
        IslEndpointRoundTripStatus endpointData = endpointRoundTripStatus.get(status.getEndpoint());
        endpointData.setExpireAt(evaluateRoundTripExpireAtTime(status));

        IslStatus actualState = evaluateRoundTripStatus(endpointData, clock.instant());
        if (! Objects.equals(actualState, endpointData.getStoredStatus())) {
            saveStatusTransaction();
        }

        if (evaluateStatus() != IslEndpointStatus.Status.UP) {
            fire(IslFsmEvent._BECOME_DOWN, context);
        }
    }

    public void upExit(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        log.info("ISL {} is no more UP (reason:{})",
                  discoveryFacts.getReference(), context.getDownReason());

        endpointRoundTripStatus.stream()
                .forEach(entry -> entry.setExpireAt(null));

        updateEndpointStatusByEvent(event, context);
        triggerAffectedFlowReroute(context);
    }

    public void movedEnter(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        reportFsm.fire(IslReportFsm.Event.BECOME_MOVED);

        saveStatusTransaction();
        sendIslStatusUpdateNotification(context, IslStatus.MOVED);
        bfdManager.disable(context.getOutput());
    }

    public void cleanUpResourcesEnter(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        islRulesAttempts = options.getRulesSynchronizationAttempts();
        sendRemoveMultitable(context);
    }

    public void handleInstalledRule(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        Endpoint installedRulesEndpoint = context.getInstalledRulesEndpoint();
        if (installedRulesEndpoint != null) {
            endpointStatus.get(installedRulesEndpoint).setHasIslRules(true);
        }


        if (endpointStatus.getForward().isHasIslRules() && endpointStatus.getReverse().isHasIslRules()) {
            fire(IslFsmEvent.ISL_UP, context);
        }
    }

    public void handleRemovedRule(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        Endpoint removedRulesEndpoint = context.getRemovedRulesEndpoint();
        if (removedRulesEndpoint != null) {
            endpointStatus.get(removedRulesEndpoint).setHasIslRules(false);
        }

        if (!endpointStatus.getForward().isHasIslRules() && !endpointStatus.getReverse().isHasIslRules()) {
            fire(IslFsmEvent.ISL_REMOVE_FINISHED, context);
        }

    }

    public void removeAttempt(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        // FIXME(surabujin): this check is always true, because it is called from DOWN or MOVED state
        if (evaluateStatus() != IslEndpointStatus.Status.UP) {
            fire(IslFsmEvent._ISL_REMOVE_SUCCESS, context);
        }
    }

    // -- private/service methods --

    private void sendInstallMultitable(IslFsmContext context) {
        Optional<SwitchProperties> sourceSwitchFeatures = switchPropertiesRepository
                .findBySwitchId(discoveryFacts.getReference().getSource().getDatapath());
        boolean waitForSource = false;
        if (sourceSwitchFeatures.isPresent() && sourceSwitchFeatures.get().isMultiTable()) {
            context.getOutput().islDefaultRulesInstall(discoveryFacts.getReference().getSource(),
                    discoveryFacts.getReference().getDest());
            waitForSource = true;
        } else {
            endpointStatus.get(discoveryFacts.getReference().getSource()).setHasIslRules(true);
        }
        Optional<SwitchProperties> destSwitchFeatures = switchPropertiesRepository
                .findBySwitchId(discoveryFacts.getReference().getDest().getDatapath());
        boolean waitForDest = false;
        if (destSwitchFeatures.isPresent() && destSwitchFeatures.get().isMultiTable()) {
            context.getOutput().islDefaultRulesInstall(discoveryFacts.getReference().getDest(),
                    discoveryFacts.getReference().getSource());
            waitForDest = true;
        } else {
            endpointStatus.get(discoveryFacts.getReference().getDest()).setHasIslRules(true);
        }
        if (!waitForSource && !waitForDest) {
            fire(IslFsmEvent.ISL_RULE_INSTALLED, context);
        }
    }

    private void sendRemoveMultitable(IslFsmContext context) {
        SwitchId sourceSwitchId = discoveryFacts.getReference().getSource().getDatapath();
        int sourcePort = discoveryFacts.getReference().getSource().getPortNumber();
        Optional<SwitchProperties> sourceSwitchFeatures = switchPropertiesRepository
                .findBySwitchId(sourceSwitchId);
        boolean waitForSource = false;
        if (sourceSwitchFeatures.isPresent() && sourceSwitchFeatures.get().isMultiTable()) {
            if (islRepository.findByEndpoint(sourceSwitchId, sourcePort).isEmpty()) {
                context.getOutput().islDefaultRulesDelete(discoveryFacts.getReference().getSource(),
                        discoveryFacts.getReference().getDest());
                waitForSource = true;
            }
        } else {
            endpointStatus.get(discoveryFacts.getReference().getSource()).setHasIslRules(false);
        }
        SwitchId destSwitchId = discoveryFacts.getReference().getDest().getDatapath();
        int destPort = discoveryFacts.getReference().getDest().getPortNumber();
        Optional<SwitchProperties> destSwitchFeatures = switchPropertiesRepository
                .findBySwitchId(destSwitchId);
        boolean waitForDest = false;
        if (destSwitchFeatures.isPresent() && destSwitchFeatures.get().isMultiTable()) {
            if (islRepository.findByEndpoint(destSwitchId, destPort).isEmpty()) {
                context.getOutput().islDefaultRulesDelete(discoveryFacts.getReference().getDest(),
                          discoveryFacts.getReference().getSource());
                waitForDest = true;
            }
        } else {
            endpointStatus.get(discoveryFacts.getReference().getDest()).setHasIslRules(false);
        }

        if (!waitForSource && !waitForDest) {
            fire(IslFsmEvent.ISL_RULE_REMOVED, context);
        }
    }

    private void sendBfdEnable(IIslCarrier carrier) {
        if (shouldSetupBfd()) {
            bfdManager.enable(carrier);
        }
    }

    private void sendIslStatusUpdateNotification(IslFsmContext context, IslStatus status) {
        IslStatusUpdateNotification trigger = new IslStatusUpdateNotification(
                discoveryFacts.getReference().getSource().getDatapath(),
                discoveryFacts.getReference().getSource().getPortNumber(),
                discoveryFacts.getReference().getDest().getDatapath(),
                discoveryFacts.getReference().getDest().getPortNumber(),
                status);
        context.getOutput().islStatusUpdateNotification(trigger);
    }

    private void updateLinkData(Endpoint bind, IslDataHolder data) {
        log.info("ISL {} data update - bind:{} - {}", discoveryFacts.getReference(), bind, data);
        discoveryFacts.put(bind, data);
    }

    private void updateEndpointStatusByEvent(IslFsmEvent event, IslFsmContext context) {
        IslEndpointStatus status = null;
        switch (event) {
            case ISL_UP:
                status = new IslEndpointStatus(IslEndpointStatus.Status.UP);
                break;
            case ISL_DOWN:
                status = new IslEndpointStatus(IslEndpointStatus.Status.DOWN, context.getDownReason());
                break;
            case ISL_MOVE:
                status = new IslEndpointStatus(IslEndpointStatus.Status.MOVED);
                break;
            default:
                log.debug("Ignore status update for not applicable event {} (context={}, state={})",
                          event, context, getCurrentState());
        }

        if (status != null) {
            endpointStatus.put(context.getEndpoint(), status);
        }
    }

    private void loadPersistentData(Endpoint start, Endpoint end) {
        Optional<Isl> potentialIsl = islRepository.findByEndpoints(
                start.getDatapath(), start.getPortNumber(),
                end.getDatapath(), end.getPortNumber());
        if (potentialIsl.isPresent()) {
            Isl isl = potentialIsl.get();

            for (DiscoveryMonitor<?> entry : monitorsByPriority) {
                entry.load(start, isl);
            }
        } else {
            log.error("There is no persistent ISL data {} ==> {} (possible race condition during topology "
                              + "initialisation)", start, end);
        }
    }

    private void triggerAffectedFlowReroute(IslFsmContext context) {
        Endpoint source = discoveryFacts.getReference().getSource();

        String reason = makeRerouteReason(context.getEndpoint(), context.getDownReason());

        // FIXME (surabujin): why do we send only one ISL endpoint here?
        PathNode pathNode = new PathNode(source.getDatapath(), source.getPortNumber(), 0);
        RerouteAffectedFlows trigger = new RerouteAffectedFlows(pathNode, reason);
        context.getOutput().triggerReroute(trigger);
    }

    private void triggerDownFlowReroute(IslFsmContext context) {
        if (shouldEmitDownFlowReroute()) {
            Endpoint source = discoveryFacts.getReference().getSource();
            PathNode pathNode = new PathNode(source.getDatapath(), source.getPortNumber(), 0);
            RerouteInactiveFlows trigger = new RerouteInactiveFlows(pathNode, String.format(
                    "ISL %s status become %s", discoveryFacts.getReference(), IslStatus.ACTIVE));
            context.getOutput().triggerReroute(trigger);
        }
    }

    private void saveAllTransaction() {
        transactionManager.doInTransaction(transactionRetryPolicy, () -> saveAll(clock.instant()));
    }

    private void saveStatusTransaction() {
        transactionManager.doInTransaction(transactionRetryPolicy, () -> saveStatus(Instant.now()));
    }

    private void saveStatusAndSetIslUnstableTimeTransaction() {
        transactionManager.doInTransaction(transactionRetryPolicy, () -> {
            Instant timeNow = Instant.now();

            saveStatus(timeNow);
            setIslUnstableTime(timeNow);
        });
    }

    private void saveAll(Instant timeNow) {
        Socket socket = prepareSocket();
        saveAll(socket.getSource(), socket.getDest(), timeNow, reference.getSource());
        saveAll(socket.getDest(), socket.getSource(), timeNow, reference.getDest());
    }

    private void saveAll(Anchor source, Anchor dest, Instant timeNow, Endpoint endpoint) {
        Isl link = loadOrCreateIsl(source, dest, timeNow);

        link.setTimeModify(timeNow);

        applyIslGenericData(link);
        applyIslMaxBandwidth(link, source.getEndpoint(), dest.getEndpoint());
        applyIslAvailableBandwidth(link, source.getEndpoint(), dest.getEndpoint());
        applyIslStatus(link, endpoint, timeNow);

        pushIslChanges(link);
    }

    private void saveStatus(Instant timeNow) {
        Socket socket = prepareSocket();
        saveStatus(socket.getSource(), socket.getDest(), timeNow, reference.getSource());
        saveStatus(socket.getDest(), socket.getSource(), timeNow, reference.getDest());
    }

    private void saveStatus(Anchor source, Anchor dest, Instant timeNow, Endpoint endpoint) {
        Isl link = loadOrCreateIsl(source, dest, timeNow);

        applyIslStatus(link, endpoint, timeNow);
        pushIslChanges(link);
    }

    private void setIslUnstableTime(Instant timeNow) {
        Socket socket = prepareSocket();
        setIslUnstableTime(socket.getSource(), socket.getDest(), timeNow);
        setIslUnstableTime(socket.getDest(), socket.getSource(), timeNow);
    }

    private void setIslUnstableTime(Anchor source, Anchor dest, Instant timeNow) {
        Isl link = loadOrCreateIsl(source, dest, timeNow);

        log.debug("Set ISL {} ===> {} unstable time due to physical port down", source, dest);

        link.setTimeModify(timeNow);
        link.setTimeUnstable(timeNow);
        pushIslChanges(link);
    }

    private Socket prepareSocket() {
        IslReference reference = discoveryFacts.getReference();
        Anchor source = loadSwitchCreateIfMissing(reference.getSource());
        Anchor dest = loadSwitchCreateIfMissing(reference.getDest());
        switchRepository.lockSwitches(source.getSw(), dest.getSw());

        return new Socket(source, dest);
    }

    private Isl loadOrCreateIsl(Anchor source, Anchor dest, Instant timeNow) {
        return loadIsl(source.getEndpoint(), dest.getEndpoint())
                .orElseGet(() -> createIsl(source, dest, timeNow));
    }

    private Isl createIsl(Anchor source, Anchor dest, Instant timeNow) {
        final Endpoint sourceEndpoint = source.getEndpoint();
        final Endpoint destEndpoint = dest.getEndpoint();
        IslBuilder islBuilder = Isl.builder()
                .timeCreate(timeNow)
                .timeModify(timeNow)
                .srcSwitch(source.getSw())
                .srcPort(sourceEndpoint.getPortNumber())
                .destSwitch(dest.getSw())
                .destPort(destEndpoint.getPortNumber())
                .underMaintenance(source.getSw().isUnderMaintenance() || dest.getSw().isUnderMaintenance());
        initializeFromLinkProps(sourceEndpoint, destEndpoint, islBuilder);
        Isl link = islBuilder.build();

        log.debug("Create new DB object (prefilled): {}", link);
        return link;
    }

    private Anchor loadSwitchCreateIfMissing(Endpoint endpoint) {
        final SwitchId datapath = endpoint.getDatapath();
        Switch sw = switchRepository.findById(datapath)
                .orElseGet(() -> {
                    log.error("Switch {} is missing in DB, create empty switch record", datapath);
                    return createSwitch(datapath);
                });
        return new Anchor(endpoint, sw);
    }

    private Switch createSwitch(SwitchId datapath) {
        Switch sw = Switch.builder()
                .switchId(datapath)
                .status(SwitchStatus.INACTIVE)
                .description(String.format("auto created as part of ISL %s discovery", discoveryFacts.getReference()))
                .build();

        switchRepository.createOrUpdate(sw);

        return sw;
    }

    private Optional<Isl> loadIsl(Endpoint source, Endpoint dest) {
        return islRepository.findByEndpoints(
                source.getDatapath(), source.getPortNumber(),
                dest.getDatapath(), dest.getPortNumber())
                .map(link -> {
                    log.debug("Read ISL object: {}", link);
                    return link;
                });
    }

    private void applyIslGenericData(Isl link) {
        IslDataHolder aggData = discoveryFacts.makeAggregatedData();
        if (aggData == null) {
            throw new IllegalStateException(String.format(
                    "There is no ISL data available for %s, unable to calculate available_bandwidth",
                    discoveryFacts.getReference()));
        }

        link.setSpeed(aggData.getSpeed());
        link.setMaxBandwidth(aggData.getMaximumBandwidth());
        link.setDefaultMaxBandwidth(aggData.getEffectiveMaximumBandwidth());
    }

    private void applyIslStatus(Isl link, Endpoint endpoint, Instant timeNow) {
        IslEndpointStatus pollData = endpointStatus.get(endpoint);

        IslStatus pollStatus = mapStatus(pollData.getStatus());
        IslStatus roundTripStatus = evaluateRoundTripStatus(endpoint, timeNow);
        IslStatus aggStatus = mapStatus(evaluateStatus(timeNow));
        if (link.getActualStatus() != pollStatus
                || link.getStatus() != aggStatus
                || link.getRoundTripStatus() != roundTripStatus) {
            link.setTimeModify(timeNow);

            link.setActualStatus(pollStatus);
            link.setStatus(aggStatus);
            link.setRoundTripStatus(roundTripStatus);
            link.setDownReason(pollData.getDownReason());
        }

        IslEndpointRoundTripStatus roundTripData = endpointRoundTripStatus.get(endpoint);
        roundTripData.setStoredStatus(link.getRoundTripStatus());
    }

    private void applyIslMaxBandwidth(Isl link, Endpoint source, Endpoint dest) {
        loadLinkProps(source, dest)
                .ifPresent(props -> applyIslMaxBandwidth(link, props));
    }

    private void applyIslMaxBandwidth(Isl link, LinkProps props) {
        Long maxBandwidth = props.getMaxBandwidth();
        if (maxBandwidth != null) {
            link.setMaxBandwidth(maxBandwidth);
        }
    }

    private void applyIslAvailableBandwidth(Isl link, Endpoint source, Endpoint dest) {
        long usedBandwidth = flowPathRepository.getUsedBandwidthBetweenEndpoints(
                source.getDatapath(), source.getPortNumber(),
                dest.getDatapath(), dest.getPortNumber());
        link.setAvailableBandwidth(link.getMaxBandwidth() - usedBandwidth);
    }

    private void initializeFromLinkProps(Endpoint source, Endpoint dest, IslBuilder isl) {
        Optional<LinkProps> linkProps = loadLinkProps(source, dest);
        if (linkProps.isPresent()) {
            LinkProps entry = linkProps.get();

            Integer cost = entry.getCost();
            if (cost != null) {
                isl.cost(cost);
            }

            Long maxBandwidth = entry.getMaxBandwidth();
            if (maxBandwidth != null) {
                isl.maxBandwidth(maxBandwidth);
            }
        }
    }

    private void pushIslChanges(Isl link) {
        log.debug("Write ISL object: {}", link);
        islRepository.createOrUpdate(link);
    }

    private Optional<LinkProps> loadLinkProps(Endpoint source, Endpoint dest) {
        Collection<LinkProps> storedProps = linkPropsRepository.findByEndpoints(
                source.getDatapath(), source.getPortNumber(),
                dest.getDatapath(), dest.getPortNumber());
        Optional<LinkProps> result = Optional.empty();
        for (LinkProps entry : storedProps) {
            result = Optional.of(entry);
            // We can/should put "break" here but it lead to warnings... Anyway only one match possible
            // by such(full) query so we can avoid "break" here.
        }
        return result;
    }

    private IslStatus evaluateStatus() {
        for (DiscoveryMonitor<?> entry : monitorsByPriority) {
            Optional<IslStatus> status = entry.evaluateStatus();
            if (status.isPresent()) {
                return status.get();
            }
        }
        return IslStatus.INACTIVE;
    }

    private void emitBecomeStateEvent(IslFsmContext context) {
        IslFsmEvent route;
        switch (effectiveStatus) {
            case ACTIVE:
                route = IslFsmEvent._BECOME_UP;
                break;
            case INACTIVE:
                route = IslFsmEvent._BECOME_DOWN;
                break;
            case MOVED:
                route = IslFsmEvent._BECOME_MOVED;
                break;
            default:
                throw new IllegalArgumentException(makeInvalidMappingMessage(
                        effectiveStatus.getClass(), IslFsmEvent.class, effectiveStatus));
        }
        fire(route, context);
    }

    private boolean shouldSetupBfd() {
        // TODO(surabujin): ensure the switch is BFD capable

        IslReference reference = discoveryFacts.getReference();
        return isPerIslBfdToggleEnabled(reference.getSource(), reference.getDest())
                || isPerIslBfdToggleEnabled(reference.getDest(), reference.getSource());
    }

    private boolean isPerIslBfdToggleEnabled(Endpoint source, Endpoint dest) {
        return loadIsl(source, dest)
                .map(Isl::isEnableBfd)
                .orElseThrow(() -> new PersistenceException(
                        String.format("Isl %s ===> %s record not found in DB", source, dest)));
    }

    // TODO(surabujin): should this check been moved into reroute topology?
    private boolean shouldEmitDownFlowReroute() {
        return featureTogglesRepository.find()
                .map(FeatureToggles::getFlowsRerouteOnIslDiscoveryEnabled)
                .orElse(FeatureToggles.DEFAULTS.getFlowsRerouteOnIslDiscoveryEnabled());
    }

    private String makeRerouteReason(Endpoint endpoint, IslDownReason reason) {
        IslStatus status = mapStatus(evaluateStatus());
        IslReference reference = discoveryFacts.getReference();
        if (reason == null) {
            return String.format("ISL %s status become %s", reference, status);
        }

        String humanReason;
        switch (reason) {
            case PORT_DOWN:
                humanReason = String.format("ISL %s become %s due to physical link DOWN event on %s",
                                            reference, status, endpoint);
                break;
            case POLL_TIMEOUT:
                humanReason = String.format("ISL %s become %s because of FAIL TIMEOUT (endpoint:%s)",
                                            reference, status, endpoint);
                break;
            case BFD_DOWN:
                humanReason = String.format("ISL %s become %s because BFD detect link failure (endpoint:%s)",
                                            reference, status, endpoint);
                break;

            default:
                humanReason = String.format("ISL %s become %s (endpoint:%s, reason:%s)",
                                            reference, status, endpoint, reason);
        }

        return humanReason;
    }

    private static IslStatus mapStatus(IslEndpointStatus.Status status) {
        switch (status) {
            case UP:
                return IslStatus.ACTIVE;
            case DOWN:
                return IslStatus.INACTIVE;
            case MOVED:
                return IslStatus.MOVED;
            default:
                throw new IllegalArgumentException(
                        makeInvalidMappingMessage(IslEndpointStatus.Status.class, IslStatus.class, status));
        }
    }

    private static IslEndpointStatus.Status mapStatus(IslStatus status) {
        switch (status) {
            case ACTIVE:
                return IslEndpointStatus.Status.UP;
            case INACTIVE:
                return IslEndpointStatus.Status.DOWN;
            case MOVED:
                return IslEndpointStatus.Status.MOVED;
            default:
                throw new IllegalArgumentException(
                        makeInvalidMappingMessage(IslStatus.class, IslEndpointStatus.Status.class, status));
        }
    }

    private static String makeInvalidMappingMessage(Class<?> from, Class<?> to, Object value) {
        return String.format("There is no mapping defined between %s and %s for %s", from.getName(),
                             to.getName(), value);
    }

    @Value
    private static class Anchor {
        Endpoint endpoint;
        Switch sw;
    }

    @Value
    private static class Socket {
        Anchor source;
        Anchor dest;
    }

    // FIXME - }}}
    public static class IslFsmFactory {
        private final Clock clock;

        private final IslReportFsm.IslReportFsmFactory reportFsmFactory;

        private final PersistenceManager persistenceManager;
        private final StateMachineBuilder<IslFsm, IslFsmState, IslFsmEvent, IslFsmContext> builder;

        IslFsmFactory(Clock clock, PersistenceManager persistenceManager,
                      NetworkTopologyDashboardLogger.Builder dashboardLoggerBuilder) {
            this.clock = clock;

            this.persistenceManager = persistenceManager;
            this.reportFsmFactory = IslReportFsm.factory(dashboardLoggerBuilder);

            builder = StateMachineBuilderFactory.create(
                    IslFsm.class, IslFsmState.class, IslFsmEvent.class, IslFsmContext.class,
                    // extra parameters
                    Clock.class, PersistenceManager.class, IslReportFsm.class, BfdManager.class, NetworkOptions.class,
                    IslReference.class);

            // OPERATIONAL
            builder.onEntry(IslFsmState.OPERATIONAL)
                    .callMethod("loadPersistedState");

            // FIXME - {{{
            String updateEndpointStatusMethod = "updateEndpointStatus";
            String updateAndPersistEndpointStatusMethod = "updateAndPersistEndpointStatus";

            // INIT
            builder.transition()
                    .from(IslFsmState.INIT).to(IslFsmState.DOWN).on(IslFsmEvent.ISL_UP)
                    .callMethod("handleInitialDiscovery");
            builder.transition()
                    .from(IslFsmState.INIT).to(IslFsmState.DOWN).on(IslFsmEvent.ISL_DOWN)
                    .callMethod(updateAndPersistEndpointStatusMethod);
            builder.transition()
                    .from(IslFsmState.INIT).to(IslFsmState.MOVED).on(IslFsmEvent.ISL_MOVE)
                    .callMethod(updateAndPersistEndpointStatusMethod);
            builder.transition()
                    .from(IslFsmState.INIT).to(IslFsmState.DOWN).on(IslFsmEvent._HISTORY_DOWN);
            builder.transition()
                    .from(IslFsmState.INIT).to(IslFsmState.SET_UP_RESOURCES).on(IslFsmEvent._HISTORY_UP)
                    .callMethod("historyRestoreUp");
            builder.transition()
                    .from(IslFsmState.INIT).to(IslFsmState.MOVED).on(IslFsmEvent._HISTORY_MOVED);
            builder.internalTransition()
                    .within(IslFsmState.INIT).on(IslFsmEvent.HISTORY)
                    .callMethod("handleHistory");

            // DOWN
            builder.transition()
                    .from(IslFsmState.DOWN).to(IslFsmState.UP_ATTEMPT).on(IslFsmEvent.ISL_UP)
                    .callMethod(updateEndpointStatusMethod);
            builder.transition()
                    .from(IslFsmState.DOWN).to(IslFsmState.MOVED).on(IslFsmEvent.ISL_MOVE)
                    .callMethod(updateEndpointStatusMethod);
            builder.internalTransition()
                    .within(IslFsmState.DOWN).on(IslFsmEvent.ISL_DOWN)
                    .callMethod(updateAndPersistEndpointStatusMethod);
            builder.internalTransition()
                    .within(IslFsmState.DOWN).on(IslFsmEvent.ISL_REMOVE)
                    .callMethod("removeAttempt");
            builder.transition()
                    .from(IslFsmState.DOWN).to(IslFsmState.CLEAN_UP_RESOURCES).on(IslFsmEvent._ISL_REMOVE_SUCCESS);
            builder.onEntry(IslFsmState.DOWN)
                    .callMethod("downEnter");

            // UP_ATTEMPT
            builder.transition()
                    .from(IslFsmState.UP_ATTEMPT).to(IslFsmState.DOWN).on(IslFsmEvent._UP_ATTEMPT_FAIL);
            builder.transition()
                    .from(IslFsmState.UP_ATTEMPT).to(IslFsmState.SET_UP_RESOURCES).on(IslFsmEvent._UP_ATTEMPT_SUCCESS);
            builder.onEntry(IslFsmState.UP_ATTEMPT)
                    .callMethod("handleUpAttempt");

            // SET_UP_RESOURCES
            builder.internalTransition()
                    .within(IslFsmState.SET_UP_RESOURCES).on(IslFsmEvent.ISL_RULE_INSTALLED)
                    .callMethod("handleInstalledRule");
            builder.transition()
                    .from(IslFsmState.SET_UP_RESOURCES).to(IslFsmState.DOWN).on(IslFsmEvent.ISL_DOWN);
            builder.transition()
                    .from(IslFsmState.SET_UP_RESOURCES).to(IslFsmState.MOVED).on(IslFsmEvent.ISL_MOVE)
                    .callMethod(updateEndpointStatusMethod);
            builder.transition()
                    .from(IslFsmState.SET_UP_RESOURCES).to(IslFsmState.UP).on(IslFsmEvent.ISL_UP);
            builder.internalTransition()
                    .within(IslFsmState.SET_UP_RESOURCES).on(IslFsmEvent.ISL_RULE_TIMEOUT)
                    .callMethod("setUpResourcesTimeout");
            builder.onEntry(IslFsmState.SET_UP_RESOURCES).callMethod("setUpResourcesEnter");


            // UP
            final String upHandlePollEventMethod = "upHandlePollEvent";

            builder.transition()
                    .from(IslFsmState.UP).to(IslFsmState.DOWN).on(IslFsmEvent._BECOME_DOWN);
            builder.onEntry(IslFsmState.UP)
                    .callMethod("upEnter");
            builder.transition()
                    .from(IslFsmState.UP).to(IslFsmState.DOWN).on(IslFsmEvent.ISL_DOWN)
                    .when(new Condition<IslFsmContext>() {
                        @Override
                        public boolean isSatisfied(IslFsmContext context) {
                            return IslDownReason.PORT_DOWN.equals(context.getDownReason());
                        }

                        @Override
                        public String name() {
                            return "isl-down-by-physical-port-down";
                        }
                    })
                    .callMethod("upHandlePhysicalDown");
            builder.transition()
                    .from(IslFsmState.UP).to(IslFsmState.MOVED).on(IslFsmEvent.ISL_MOVE)
                    .callMethod(updateEndpointStatusMethod);
            // FIXME(surabujin): missing on FSM diagram (review whole diagram and sync code and diagram to each other)
            builder.transition()
                    .from(IslFsmState.UP).to(IslFsmState.SET_UP_RESOURCES).on(IslFsmEvent.ISL_MULTI_TABLE_MODE_UPDATED);
            builder.internalTransition().within(IslFsmState.UP).on(IslFsmEvent.ISL_UP)
                    .callMethod(upHandlePollEventMethod);
            builder.internalTransition().within(IslFsmState.UP).on(IslFsmEvent.ISL_DOWN)
                    .callMethod(upHandlePollEventMethod);
            builder.internalTransition().within(IslFsmState.UP).on(IslFsmEvent.ROUND_TRIP_STATUS)
                    .callMethod("upHandleRoundTripStatus");
            builder.onExit(IslFsmState.UP)
                    .callMethod("upExit");

            // MOVED
            builder.transition()
                    .from(IslFsmState.MOVED).to(IslFsmState.UP_ATTEMPT).on(IslFsmEvent.ISL_UP)
                    .callMethod(updateEndpointStatusMethod);
            builder.internalTransition()
                    .within(IslFsmState.MOVED).on(IslFsmEvent.ISL_DOWN)
                    .callMethod(updateAndPersistEndpointStatusMethod);
            builder.internalTransition()
                    .within(IslFsmState.MOVED).on(IslFsmEvent.ISL_REMOVE)
                    .callMethod("removeAttempt");
            builder.transition()
                    .from(IslFsmState.MOVED).to(IslFsmState.CLEAN_UP_RESOURCES).on(IslFsmEvent._ISL_REMOVE_SUCCESS);
            builder.onEntry(IslFsmState.MOVED)
                    .callMethod("movedEnter");

            // CLEAN_UP_RESOURCES
            builder.onEntry(IslFsmState.CLEAN_UP_RESOURCES).callMethod("cleanUpResourcesEnter");
            builder.internalTransition()
                    .within(IslFsmState.CLEAN_UP_RESOURCES).on(IslFsmEvent.ISL_RULE_REMOVED)
                    .callMethod("handleRemovedRule");
            builder.transition().from(IslFsmState.CLEAN_UP_RESOURCES).to(IslFsmState.DELETED)
                    .on(IslFsmEvent.ISL_REMOVE_FINISHED);
            builder.internalTransition()
                    .within(IslFsmState.CLEAN_UP_RESOURCES).on(IslFsmEvent.ISL_RULE_TIMEOUT)
                    .callMethod("cleanUpResourcesTimeout");
            // FIXME - }}}

            // DELETED
            builder.defineFinalState(IslFsmState.DELETED);
        }

        public FsmExecutor<IslFsm, IslFsmState, IslFsmEvent, IslFsmContext> produceExecutor() {
            return new FsmExecutor<>(IslFsmEvent.NEXT);
        }

        /**
         * Create and properly initialize new {@link IslFsm}.
         */
        public IslFsm produce(BfdManager bfdManager, NetworkOptions options, IslReference reference) {
            IslReportFsm reportFsm = reportFsmFactory.produce(reference);
            IslFsm fsm = builder.newStateMachine(
                    IslFsmState.INIT, clock, persistenceManager, reportFsm, bfdManager, options, reference);
            fsm.start();
            return fsm;
        }
    }

    @Value
    @Builder
    public static class IslFsmContext {
        // FIXME - {{{
        private final IIslCarrier output;
        private final Endpoint endpoint;

        private Isl history;
        private IslDataHolder islData;

        private IslDownReason downReason;
        private Endpoint installedRulesEndpoint;
        private Endpoint removedRulesEndpoint;
        private Boolean bfdEnable;

        private RoundTripStatus roundTripStatus;
        // FIXME - }}}

        /**
         * .
         */
        public static IslFsmContextBuilder builder(IIslCarrier output, Endpoint endpoint) {
            return new IslFsmContextBuilder()
                    .output(output)
                    .endpoint(endpoint);
        }
    }

    public enum IslFsmEvent {
        NEXT,
        _BECOME_UP, _BECOME_DOWN, _BECOME_MOVED,

        // FIXME - {{{
        HISTORY, _HISTORY_DOWN, _HISTORY_UP, _HISTORY_MOVED,
        ISL_UP, ISL_DOWN, ISL_MOVE, ROUND_TRIP_STATUS,

        _UP_ATTEMPT_SUCCESS, ISL_REMOVE, ISL_REMOVE_FINISHED, _ISL_REMOVE_SUCCESS, _UP_ATTEMPT_FAIL,
        ISL_RULE_INSTALLED, ISL_RULE_INSTALL_FAILED,
        ISL_RULE_REMOVED, ISL_RULE_REMOVE_FAILED,
        ISL_RULE_TIMEOUT,
        ISL_MULTI_TABLE_MODE_UPDATED,
        // FIXME - }}}
    }

    public enum IslFsmState {
        OPERATIONAL,
        SET_UP_RESOURCES, CLEAN_UP_RESOURCES,
        DELETED,
        // FIXME - {{{
        UP, DOWN, MOVED,
        // FIXME - }}}
    }
}
