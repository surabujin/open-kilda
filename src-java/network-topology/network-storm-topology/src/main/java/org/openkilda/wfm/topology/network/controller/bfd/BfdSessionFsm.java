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

package org.openkilda.wfm.topology.network.controller.bfd;

import org.openkilda.messaging.floodlight.response.BfdSessionResponse;
import org.openkilda.messaging.model.NoviBfdSession;
import org.openkilda.messaging.model.SwitchReference;
import org.openkilda.model.BfdProperties;
import org.openkilda.model.BfdSession;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.exceptions.ConstraintViolationException;
import org.openkilda.persistence.repositories.BfdSessionRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.persistence.tx.TransactionCallback;
import org.openkilda.persistence.tx.TransactionManager;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.model.IslReference;
import org.openkilda.wfm.share.utils.AbstractBaseFsm;
import org.openkilda.wfm.share.utils.FsmExecutor;
import org.openkilda.wfm.topology.network.controller.bfd.BfdSessionFsm.BfdSessionFsmContext;
import org.openkilda.wfm.topology.network.controller.bfd.BfdSessionFsm.Event;
import org.openkilda.wfm.topology.network.controller.bfd.BfdSessionFsm.State;
import org.openkilda.wfm.topology.network.error.SwitchReferenceLookupException;
import org.openkilda.wfm.topology.network.model.BfdDescriptor;
import org.openkilda.wfm.topology.network.model.BfdSessionData;
import org.openkilda.wfm.topology.network.model.LinkStatus;
import org.openkilda.wfm.topology.network.service.IBfdSessionCarrier;
import org.openkilda.wfm.topology.network.utils.EndpointStatusMonitor;
import org.openkilda.wfm.topology.network.utils.SwitchOnlineStatusMonitor;

import lombok.Builder;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.tinkerpop.gremlin.structure.T;
import org.squirrelframework.foundation.fsm.StateMachineBuilder;
import org.squirrelframework.foundation.fsm.StateMachineBuilderFactory;
import org.squirrelframework.foundation.fsm.StateMachineLogger;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.Random;

@Slf4j
public final class BfdSessionFsm extends AbstractBaseFsm<BfdSessionFsm, State, Event, BfdSessionFsmContext> {
    static final int BFD_UDP_PORT = 3784;

    private final Random random = new Random();

    private final TransactionManager transactionManager;
    private final SwitchRepository switchRepository;
    private final BfdSessionRepository bfdSessionRepository;

    private final IBfdSessionCarrier carrier;

    @Getter
    private final IslReference islReference;
    @Getter
    private final Endpoint logicalEndpoint;
    private final int physicalPortNumber;

    @Getter
    private BfdProperties properties;
    private Integer discriminator;

    private BfdSessionAction action = null;

    private boolean tearingDown;

    public static BfdSessionFsmFactory factory(
            PersistenceManager persistenceManager, SwitchOnlineStatusMonitor switchOnlineStatusMonitor,
            EndpointStatusMonitor endpointStatusMonitor, IBfdSessionCarrier carrier) {
        return new BfdSessionFsmFactory(persistenceManager, switchOnlineStatusMonitor, endpointStatusMonitor, carrier);
    }

    public BfdSessionFsm(
            PersistenceManager persistenceManager,
            SwitchOnlineStatusMonitor switchOnlineStatusMonitor, EndpointStatusMonitor endpointStatusMonitor,
            IBfdSessionCarrier carrier, IslReference islReference, Endpoint logical, Integer physicalPortNumber) {
        transactionManager = persistenceManager.getTransactionManager();

        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        this.switchRepository = repositoryFactory.createSwitchRepository();
        this.bfdSessionRepository = repositoryFactory.createBfdSessionRepository();

        this.carrier = carrier;
        this.islReference = islReference;

        this.logicalEndpoint = logical;
        this.physicalPortNumber = physicalPortNumber;
    }

    // -- external API --

    public boolean enableIfReady(BfdSessionData sessionData) {
        if (tearingDown) {
            return false;
        }
        if (this.properties != null && ! this.properties.equals(sessionData.getProperties())) {
            return false;
        }

        BfdSessionFsmContext context = BfdSessionFsmContext.builder()
                .islReference(sessionData.getReference())
                .properties(sessionData.getProperties())
                .build();
        BfdSessionFsmFactory.EXECUTOR.fire(this, Event.ENABLE, context);
        return true;
    }

    public void disableIfConfigured() {
        if (properties != null) {
            BfdSessionFsmContext context = BfdSessionFsmContext.builder().build();
            BfdSessionFsmFactory.EXECUTOR.fire(this, Event.DISABLE, context);
        }
    }

    // -- FSM actions --

    public void enterEnterAction(State from, State to, Event event, BfdSessionFsmContext context) {
        loadExistingSession();
    }

    public void prepareEnterAction(State from, State to, Event event, BfdSessionFsmContext context) {
PREPARE : enter / allocate(lock) BFD-discriminator
PREPARE : enter / [isOnline] make BFD-setup action and fire ready
    }
/*
    public void consumeHistory(State from, State to, Event event, BfdSessionFsmContext context) {
        // TODO del
    }

    public void handleInitChoice(State from, State to, Event event, BfdSessionFsmContext context) {
        if (sessionDescriptor == null) {
            fire(Event._INIT_CHOICE_CLEAN, context);
        } else {
            fire(Event._INIT_CHOICE_DIRTY, context);
        }
    }

    public void idleEnter(State from, State to, Event event, BfdSessionFsmContext context) {
        logInfo("ready for setup requests");
    }

    public void doSetupEnter(State from, State to, Event event, BfdSessionFsmContext context) {
        portStatusMonitor.cleanTransitions();

        logInfo(String.format("BFD session setup process have started - discriminator:%s, remote-datapath:%s",
                sessionDescriptor.getDiscriminator(), sessionDescriptor.getRemote().getDatapath()));
        action = new BfdSessionSetupAction(context.getOutput(), makeBfdSessionRecord(properties));
    }

    public void saveIslReference(State from, State to, Event event, BfdSessionFsmContext context) {
        islReference = context.getIslReference();
        properties = context.getProperties();
    }

    public void savePropertiesAction(State from, State to, Event event, BfdSessionFsmContext context) {
        properties = context.getProperties();
    }

    public void doAllocateResources(State from, State to, Event event, BfdSessionFsmContext context) {
        try {
            sessionDescriptor = allocateDiscriminator(makeSessionDescriptor(islReference));
        } catch (SwitchReferenceLookupException e) {
            logError(String.format("Can't allocate BFD-session resources - %s", e.getMessage()));
            fire(Event.FAIL, context);
        }
    }

    public void doReleaseResources(State from, State to, Event event, BfdSessionFsmContext context) {
        transactionManager.doInTransaction(() -> {
            bfdSessionRepository.findBySwitchIdAndPort(logicalEndpoint.getDatapath(), logicalEndpoint.getPortNumber())
                    .ifPresent(value -> {
                        if (value.getDiscriminator().equals(sessionDescriptor.getDiscriminator())) {
                            bfdSessionRepository.remove(value);
                        }
                    });
        });
        sessionDescriptor = null;
    }

    public void activeEnter(State from, State to, Event event, BfdSessionFsmContext context) {
        logInfo("BFD session is operational");

        effectiveProperties = properties;
        saveEffectiveProperties();
    }

    public void activeExit(State from, State to, Event event, BfdSessionFsmContext context) {
        logInfo("notify consumer(s) to STOP react on BFD event");
        portStatusMonitor.cleanTransitions();
        context.getOutput().bfdKillNotification(physicalEndpoint);
    }

    public void waitStatusEnter(State from, State to, Event event, BfdSessionFsmContext context) {
        portStatusMonitor.pull(context.getOutput());
    }

    public void upEnter(State from, State to, Event event, BfdSessionFsmContext context) {
        logInfo("LINK detected");
        context.getOutput().bfdUpNotification(physicalEndpoint);
    }

    public void downEnter(State from, State to, Event event, BfdSessionFsmContext context) {
        logInfo("LINK corrupted");
        context.getOutput().bfdDownNotification(physicalEndpoint);
    }

    public void setupFailEnter(State from, State to, Event event, BfdSessionFsmContext context) {
        logError("BFD-setup action have failed");
        context.getOutput().bfdFailNotification(physicalEndpoint);
    }

    public void removeFailEnter(State from, State to, Event event, BfdSessionFsmContext context) {
        logError("BFD-remove action have failed");
        context.getOutput().bfdFailNotification(physicalEndpoint);
    }

    public void chargedFailEnter(State from, State to, Event event, BfdSessionFsmContext context) {
        logError("BFD-remove action have failed (for re-setup)");
    }

    public void makeBfdRemoveAction(State from, State to, Event event, BfdSessionFsmContext context) {
        logInfo(String.format("perform BFD session remove - discriminator:%s, remote-datapath:%s",
                sessionDescriptor.getDiscriminator(), sessionDescriptor.getRemote().getDatapath()));
        BfdProperties bfdProperties = this.effectiveProperties;
        if (bfdProperties == null) {
            bfdProperties = properties;
        }
        action = new BfdSessionRemoveAction(context.getOutput(), makeBfdSessionRecord(bfdProperties));
    }

    public void proxySpeakerResponseIntoAction(State from, State to, Event event, BfdSessionFsmContext context) {
        action.consumeSpeakerResponse(context.getRequestKey(), context.getSpeakerResponse())
                .ifPresent(result -> handleActionResult(result, context));
    }

    public void reportSetupSuccess(State from, State to, Event event, BfdSessionFsmContext context) {
        logInfo("BFD session setup is successfully completed");
    }
*/

    // -- private/service methods --

    private void loadExistingSession() {
        transactionManager.doInTransaction(() -> loadBfdSession().ifPresent(this::loadExistingSession));
    }

    private void loadExistingSession(BfdSession dbView) {
        discriminator = dbView.getDiscriminator();
        properties = BfdProperties.builder()
                .interval(dbView.getInterval())
                .multiplier(dbView.getMultiplier())
                .build();
    }

/*

    private NoviBfdSession makeBfdSessionRecord(BfdProperties bfdProperties) {
        if (bfdProperties == null) {
            throw new IllegalArgumentException(String.format(
                    "Can't produce %s without properties (properties is null)", NoviBfdSession.class.getSimpleName()));
        }
        return NoviBfdSession.builder()
                .target(sessionDescriptor.getLocal())
                .remote(sessionDescriptor.getRemote())
                .physicalPortNumber(physicalEndpoint.getPortNumber())
                .logicalPortNumber(logicalEndpoint.getPortNumber())
                .udpPortNumber(BFD_UDP_PORT)
                .discriminator(sessionDescriptor.getDiscriminator())
                .keepOverDisconnect(true)
                .intervalMs((int) bfdProperties.getInterval().toMillis())
                .multiplier(bfdProperties.getMultiplier())
                .build();
    }
*/
    private BfdDescriptor allocateDiscriminator() {
        try {
            BfdDescriptor descriptor = transactionManager.doInTransaction(
                    (TransactionCallback<BfdDescriptor, SwitchReferenceLookupException>) this::makeSessionDescriptor);
        } catch (SwitchReferenceLookupException e) {
            logError(e.getMessage());
        }
    }

    private BfdDescriptor allocateDiscriminator(BfdDescriptor descriptor) {
        BfdSession dbView;
        while (true) {
            try {
                dbView = transactionManager.doInTransaction(() -> {
                    BfdSession bfdSession = loadBfdSession().orElse(null);
                    if (bfdSession == null || bfdSession.getDiscriminator() == null) {
                        // FIXME(surabujin): loop will never end if all possible discriminators are allocated
                        int discriminator = random.nextInt();
                        if (bfdSession != null) {
                            bfdSession.setDiscriminator(discriminator);
                            descriptor.fill(bfdSession);
                        } else {
                            bfdSession = BfdSession.builder()
                                    .switchId(logicalEndpoint.getDatapath())
                                    .port(logicalEndpoint.getPortNumber())
                                    .physicalPort(physicalPortNumber)
                                    .discriminator(discriminator)
                                    .build();
                            descriptor.fill(bfdSession);
                            bfdSessionRepository.add(bfdSession);
                        }
                    }
                    return bfdSession;
                });
                break;
            } catch (ConstraintViolationException ex) {
                log.warn("ConstraintViolationException on allocate bfd discriminator");
            }
        }

        return descriptor.toBuilder()
                .discriminator(dbView.getDiscriminator())
                .build();
    }
/*
    private void saveEffectiveProperties() {
        transactionManager.doInTransaction(this::saveEffectivePropertiesTransaction);
    }

    private void saveEffectivePropertiesTransaction() {
        Optional<BfdSession> session = loadBfdSession();
        if (session.isPresent()) {
            BfdSession dbView = session.get();
            dbView.setInterval(properties.getInterval());
            dbView.setMultiplier(properties.getMultiplier());
        } else {
            logError("DB session is missing, unable to save effective properties values");
        }
    }
*/

    private Optional<BfdSession> loadBfdSession() {
        return bfdSessionRepository.findBySwitchIdAndPort(
                logicalEndpoint.getDatapath(), logicalEndpoint.getPortNumber());
    }

    private BfdDescriptor makeSessionDescriptor() throws SwitchReferenceLookupException {
        Endpoint remoteEndpoint = islReference.getOpposite(makePhysicalEndpoint());
        return BfdDescriptor.builder()
                .local(makeSwitchReference(logicalEndpoint.getDatapath()))
                .remote(makeSwitchReference(remoteEndpoint.getDatapath()))
                .build();
    }

    private SwitchReference makeSwitchReference(SwitchId datapath) throws SwitchReferenceLookupException {
        Switch sw = switchRepository.findById(datapath)
                .orElseThrow(() -> new SwitchReferenceLookupException(datapath, "persistent record is missing"));
        return new SwitchReference(datapath, sw.getSocketAddress().getAddress());
    }

    private SwitchReference makeSwitchReference(SwitchId datapath, String ipAddress)
            throws SwitchReferenceLookupException {
        if (ipAddress == null) {
            throw new SwitchReferenceLookupException(datapath, "null switch address is provided");
        }

        InetAddress address;
        try {
            address = InetAddress.getByName(ipAddress);
        } catch (UnknownHostException e) {
            throw new SwitchReferenceLookupException(
                    datapath,
                    String.format("unable to parse switch address \"%s\"", ipAddress));
        }

        return new SwitchReference(datapath, address);
    }

    /*
    private void handleActionResult(BfdSessionAction.ActionResult result, BfdSessionFsmContext context) {
        Event event;
        if (result.isSuccess()) {
            event = Event.ACTION_SUCCESS;
        } else {
            event = Event.ACTION_FAIL;
            reportActionFailure(result);
        }
        fire(event, context);
    }

    private void reportActionFailure(BfdSessionAction.ActionResult result) {
        String prefix = String.format("%s action have FAILED", action.getLogIdentifier());
        if (result.getErrorCode() == null) {
            logError(String.format("%s due to TIMEOUT on speaker request", prefix));
        } else {
            logError(String.format("%s with error %s", prefix, result.getErrorCode()));
        }
    }
*/

    private Endpoint makePhysicalEndpoint() {
        return Endpoint.of(logicalEndpoint.getDatapath(), physicalPortNumber);
    }

    private void logInfo(String message, Object... args) {
        if (log.isInfoEnabled()) {
            log.info("{} - " + message, makeLogPrefix(), args);
        }
    }

    private void logError(String message, Object... args) {
        if (log.isErrorEnabled()) {
            log.error("{} - " + message, makeLogPrefix(), args);
        }
    }

    private String makeLogPrefix() {
        return String.format("BFD session %s(physical-port:%s)", logicalEndpoint, physicalPortNumber);
    }

    public static class BfdSessionFsmFactory {
        public static final FsmExecutor<BfdSessionFsm, State, Event, BfdSessionFsmContext> EXECUTOR
                = new FsmExecutor<>(Event.NEXT);

        private final PersistenceManager persistenceManager;
        private final SwitchOnlineStatusMonitor switchOnlineStatusMonitor;
        private final EndpointStatusMonitor endpointStatusMonitor;

        @Getter
        private final IBfdSessionCarrier carrier;

        private final StateMachineBuilder<BfdSessionFsm, State, Event, BfdSessionFsmContext> builder;

        BfdSessionFsmFactory(
                PersistenceManager persistenceManager, SwitchOnlineStatusMonitor switchOnlineStatusMonitor,
                EndpointStatusMonitor endpointStatusMonitor, IBfdSessionCarrier carrier) {
            this.persistenceManager = persistenceManager;
            this.switchOnlineStatusMonitor = switchOnlineStatusMonitor;
            this.endpointStatusMonitor = endpointStatusMonitor;
            this.carrier = carrier;

/*
            final String doReleaseResourcesMethod = "doReleaseResources";
            final String saveIslReferenceMethod = "saveIslReference";
            final String savePropertiesMethod = "savePropertiesAction";
            final String makeBfdRemoveActionMethod = "makeBfdRemoveAction";
            final String proxySpeakerResponseIntoActionMethod = "proxySpeakerResponseIntoAction";
*/
            builder = StateMachineBuilderFactory.create(
                    BfdSessionFsm.class, State.class, Event.class, BfdSessionFsmContext.class,
                    // extra parameters
                    PersistenceManager.class, SwitchOnlineStatusMonitor.class, EndpointStatusMonitor.class,
                    IBfdSessionCarrier.class, IslReference.class, Endpoint.class, Integer.class);

            // ENTER
            builder.onEntry(State.ENTER)
                    .callMethod("enterEnterAction");
            builder.transition()
                    .from(State.ENTER).to(State.PREPARE).on(Event.ENABLE);
            builder.transition().from(
                    State.ENTER).to(State.REMOVING).on(Event.DISABLE);

            // PREPARE
            builder.onEntry(State.PREPARE)
                    .callMethod("prepareEnterAction");
            /*
PREPARE --> CREATING : ready
PREPARE --> HOUSEKEEPING : disable
PREPARE : online / make BFD-setup action and fire ready

CREATING : enter / clean port status transitions
CREATING --> ACTIVE : action-success
CREATING --> REMOVING : disable

state ACTIVE {
    [*] --> WAIT_STATUS

    WAIT_STATUS --> UP : port-up
    WAIT_STATUS --> DOWN : port-down
    WAIT_STATUS : enter / pull port status transition

    UP -r-> DOWN : port-down
    UP : enter / emit bfd-up

    DOWN -l-> UP : port-up
    DOWN : enter / emit bfd-down
}
ACTIVE : enter / save properties into DB
ACTIVE --> REMOVING : disable
ACTIVE : offline / emit bfd-kill
ACTIVE : offline / clean port status transitions
ACTIVE : exit / emit bfd-kill

REMOVING : enter / set tear down flag
REMOVING : enter / [isOnline] make BFD-remove action
REMOVING --> HOUSEKEEPING : action-success
REMOVING : online / make BFD-remove action
REMOVING : disable / [isOnline] make BFD-remove action
REMOVING : action-fail / report fail

HOUSEKEEPING : enter / release BFD-descriptor
HOUSEKEEPING --> [*] : next

            */

            /*
            // INIT_CHOICE
            builder.transition()
                    .from(State.INIT_CHOICE).to(State.IDLE).on(Event._INIT_CHOICE_CLEAN);
            builder.transition()
                    .from(State.INIT_CHOICE).to(State.INIT_REMOVE).on(Event._INIT_CHOICE_DIRTY);
            builder.onEntry(State.INIT_CHOICE)
                    .callMethod("handleInitChoice");

            // IDLE
            builder.transition()
                    .from(State.IDLE).to(State.INIT_SETUP).on(Event.ENABLE_UPDATE)
                    .callMethod(saveIslReferenceMethod);
            builder.transition()
                    .from(State.IDLE).to(State.UNOPERATIONAL).on(Event.OFFLINE);
            builder.onEntry(State.IDLE)
                    .callMethod("idleEnter");

            // UNOPERATIONAL
            builder.transition()
                    .from(State.UNOPERATIONAL).to(State.IDLE).on(Event.ONLINE);
            builder.transition()
                    .from(State.UNOPERATIONAL).to(State.PENDING).on(Event.ENABLE_UPDATE)
                    .callMethod(savePropertiesMethod);

            // PENDING
            builder.transition()
                    .from(State.PENDING).to(State.UNOPERATIONAL).on(Event.DISABLE);
            builder.transition()
                    .from(State.PENDING).to(State.INIT_SETUP).on(Event.ONLINE);
            builder.onEntry(State.PENDING)
                    .callMethod(saveIslReferenceMethod);

            // INIT_SETUP
            builder.transition()
                    .from(State.INIT_SETUP).to(State.IDLE).on(Event.FAIL);
            builder.transition()
                    .from(State.INIT_SETUP).to(State.DO_SETUP).on(Event.NEXT);
            builder.onEntry(State.INIT_SETUP)
                    .callMethod("doAllocateResources");

            // DO_SETUP
            builder.transition()
                    .from(State.DO_SETUP).to(State.ACTIVE).on(Event.ACTION_SUCCESS)
                    .callMethod("reportSetupSuccess");
            builder.transition()
                    .from(State.DO_SETUP).to(State.INIT_REMOVE).on(Event.DISABLE);
            builder.transition()
                    .from(State.DO_SETUP).to(State.SETUP_FAIL).on(Event.ACTION_FAIL);
            builder.transition()
                    .from(State.DO_SETUP).to(State.SETUP_INTERRUPT).on(Event.OFFLINE);
            builder.transition()
                    .from(State.DO_SETUP).to(State.INIT_CLEANUP).on(Event.KILL);
            builder.internalTransition().within(State.DO_SETUP).on(Event.SPEAKER_RESPONSE)
                    .callMethod(proxySpeakerResponseIntoActionMethod);
            builder.onEntry(State.DO_SETUP)
                    .callMethod("doSetupEnter");

            // SETUP_FAIL
            builder.transition()
                    .from(State.SETUP_FAIL).to(State.INIT_REMOVE).on(Event.DISABLE);
            builder.transition()
                    .from(State.SETUP_FAIL).to(State.SETUP_INTERRUPT).on(Event.OFFLINE);
            builder.transition()
                    .from(State.SETUP_FAIL).to(State.INIT_CLEANUP).on(Event.KILL);
            builder.transition()
                    .from(State.SETUP_FAIL).to(State.RESET).on(Event.ENABLE_UPDATE)
                    .callMethod(savePropertiesMethod);
            builder.onEntry(State.SETUP_FAIL)
                    .callMethod("setupFailEnter");

            // SETUP_INTERRUPT
            builder.transition()
                    .from(State.SETUP_INTERRUPT).to(State.RESET).on(Event.ONLINE);
            builder.transition()
                    .from(State.SETUP_INTERRUPT).to(State.REMOVE_INTERRUPT).on(Event.DISABLE);

            // RESET
            builder.transition()
                    .from(State.RESET).to(State.DO_SETUP).on(Event.ACTION_SUCCESS);
            builder.transition()
                    .from(State.RESET).to(State.SETUP_INTERRUPT).on(Event.OFFLINE);
            builder.transition()
                    .from(State.RESET).to(State.SETUP_FAIL).on(Event.ACTION_FAIL);
            builder.transition()
                    .from(State.RESET).to(State.DO_REMOVE).on(Event.DISABLE);
            builder.transition()
                    .from(State.RESET).to(State.DO_CLEANUP).on(Event.KILL);
            builder.internalTransition()
                    .within(State.RESET).on(Event.SPEAKER_RESPONSE)
                    .callMethod(proxySpeakerResponseIntoActionMethod);
            builder.onEntry(State.RESET)
                    .callMethod(makeBfdRemoveActionMethod);

            // ACTIVE
            builder.transition()
                    .from(State.ACTIVE).to(State.OFFLINE).on(Event.OFFLINE);
            builder.transition()
                    .from(State.ACTIVE).to(State.INIT_REMOVE).on(Event.DISABLE);
            builder.transition()
                    .from(State.ACTIVE).to(State.INIT_CLEANUP).on(Event.KILL);
            builder.transition()
                    .from(State.ACTIVE).to(State.RESET).on(Event.ENABLE_UPDATE)
                    .callMethod(savePropertiesMethod);
            builder.onEntry(State.ACTIVE)
                    .callMethod("activeEnter");
            builder.onExit(State.ACTIVE)
                    .callMethod("activeExit");
            builder.defineSequentialStatesOn(
                    State.ACTIVE,
                    State.WAIT_STATUS, State.UP, State.DOWN);

            // WAIT_STATUS
            builder.transition()
                    .from(State.WAIT_STATUS).to(State.UP).on(Event.PORT_UP);
            builder.transition()
                    .from(State.WAIT_STATUS).to(State.DOWN).on(Event.PORT_DOWN);
            builder.onEntry(State.WAIT_STATUS)
                    .callMethod("waitStatusEnter");

            // UP
            builder.transition()
                    .from(State.UP).to(State.DOWN).on(Event.PORT_DOWN);
            builder.onEntry(State.UP)
                    .callMethod("upEnter");

            // DOWN
            builder.transition()
                    .from(State.DOWN).to(State.UP).on(Event.PORT_UP);
            builder.onEntry(State.DOWN)
                    .callMethod("downEnter");

            // OFFLINE
            builder.transition()
                    .from(State.OFFLINE).to(State.ACTIVE).on(Event.ONLINE);
            builder.transition()
                    .from(State.OFFLINE).to(State.REMOVE_INTERRUPT).on(Event.DISABLE);

            // INIT_REMOVE
            builder.transition()
                    .from(State.INIT_REMOVE).to(State.DO_REMOVE).on(Event.NEXT);
            builder.onEntry(State.INIT_REMOVE)
                    .callMethod(makeBfdRemoveActionMethod);

            // DO_REMOVE
            builder.transition()
                    .from(State.DO_REMOVE).to(State.IDLE).on(Event.ACTION_SUCCESS)
                    .callMethod(doReleaseResourcesMethod);
            builder.transition()
                    .from(State.DO_REMOVE).to(State.REMOVE_FAIL).on(Event.ACTION_FAIL);
            builder.transition()
                    .from(State.DO_REMOVE).to(State.REMOVE_INTERRUPT).on(Event.OFFLINE);
            builder.transition()
                    .from(State.DO_REMOVE).to(State.DO_CLEANUP).on(Event.KILL);
            builder.transition()
                    .from(State.DO_REMOVE).to(State.CHARGED).on(Event.ENABLE_UPDATE)
                    .callMethod(saveIslReferenceMethod);
            builder.internalTransition().within(State.DO_REMOVE).on(Event.SPEAKER_RESPONSE)
                    .callMethod(proxySpeakerResponseIntoActionMethod);

            // REMOVE_FAIL
            builder.transition()
                    .from(State.REMOVE_FAIL).to(State.CHARGED_RESET).on(Event.ENABLE_UPDATE)
                    .callMethod(saveIslReferenceMethod);
            builder.transition()
                    .from(State.REMOVE_FAIL).to(State.REMOVE_INTERRUPT).on(Event.OFFLINE);
            builder.transition()
                    .from(State.REMOVE_FAIL).to(State.INIT_REMOVE).on(Event.DISABLE);
            builder.onEntry(State.REMOVE_FAIL)
                    .callMethod("removeFailEnter");

            // REMOVE_INTERRUPT
            builder.transition()
                    .from(State.REMOVE_INTERRUPT).to(State.INIT_REMOVE).on(Event.ONLINE);
            builder.transition()
                    .from(State.REMOVE_INTERRUPT).to(State.CHARGED_INTERRUPT).on(Event.ENABLE_UPDATE)
                    .callMethod(saveIslReferenceMethod);

            // CHARGED
            builder.transition()
                    .from(State.CHARGED).to(State.INIT_SETUP).on(Event.ACTION_SUCCESS)
                    .callMethod(doReleaseResourcesMethod);
            builder.transition()
                    .from(State.CHARGED).to(State.CHARGED_FAIL).on(Event.ACTION_FAIL);
            builder.transition()
                    .from(State.CHARGED).to(State.DO_REMOVE).on(Event.DISABLE);
            builder.transition()
                    .from(State.CHARGED).to(State.CHARGED_INTERRUPT).on(Event.OFFLINE);
            builder.transition()
                    .from(State.CHARGED).to(State.DO_CLEANUP).on(Event.KILL);
            builder.internalTransition()
                    .within(State.CHARGED).on(Event.SPEAKER_RESPONSE)
                    .callMethod(proxySpeakerResponseIntoActionMethod);
            builder.internalTransition()
                    .within(State.CHARGED).on(Event.ENABLE_UPDATE)
                    .callMethod(saveIslReferenceMethod);

            // CHARGED_FAIL
            builder.transition()
                    .from(State.CHARGED_FAIL).to(State.CHARGED_INTERRUPT).on(Event.OFFLINE);
            builder.transition()
                    .from(State.CHARGED_FAIL).to(State.REMOVE_FAIL).on(Event.DISABLE);
            builder.transition()
                    .from(State.CHARGED_FAIL).to(State.CHARGED_RESET).on(Event.ENABLE_UPDATE)
                    .callMethod(saveIslReferenceMethod);
            builder.onEntry(State.CHARGED_FAIL)
                    .callMethod("chargedFailEnter");

            // CHARGED_INTERRUPT
            builder.transition()
                    .from(State.CHARGED_INTERRUPT).to(State.CHARGED_RESET).on(Event.ONLINE);
            builder.transition()
                    .from(State.CHARGED_INTERRUPT).to(State.REMOVE_INTERRUPT).on(Event.DISABLE);

            // CHARGED_RESET
            builder.transition()
                    .from(State.CHARGED_RESET).to(State.CHARGED).on(Event.NEXT);
            builder.onEntry(State.CHARGED_RESET)
                    .callMethod(makeBfdRemoveActionMethod);

            // INIT_CLEANUP
            builder.transition()
                    .from(State.INIT_CLEANUP).to(State.DO_CLEANUP).on(Event.NEXT);
            builder.onEntry(State.INIT_CLEANUP)
                    .callMethod(makeBfdRemoveActionMethod);

            // DO_CLEANUP
            builder.transition()
                    .from(State.DO_CLEANUP).to(State.STOP).on(Event.ACTION_SUCCESS)
                    .callMethod(doReleaseResourcesMethod);
            builder.transition()
                    .from(State.DO_CLEANUP).to(State.STOP).on(Event.ACTION_FAIL);
            builder.internalTransition()
                    .within(State.DO_CLEANUP).on(Event.SPEAKER_RESPONSE)
                    .callMethod(proxySpeakerResponseIntoActionMethod);
*/
            // STOP
            builder.defineFinalState(State.STOP);
        }

        public BfdSessionFsm produce(IslReference islReference, Endpoint logical, Integer physicalPortNumber) {
            BfdSessionFsm entity = builder.newStateMachine(
                    State.ENTER, persistenceManager, switchOnlineStatusMonitor, endpointStatusMonitor, carrier,
                    islReference, logical, physicalPortNumber);
            // FIXME - DEBUG!
            new StateMachineLogger(entity).startLogging();
            // FIXME - DEBUG!
            return entity;
        }
    }

    @Value
    @Builder
    public static class BfdSessionFsmContext {
        private IslReference islReference;
        private BfdProperties properties;

        private String requestKey;
        private BfdSessionResponse speakerResponse;
        }

    public enum Event {
        NEXT,
        ENABLE, DISABLE,

        // FIXME
        KILL, FAIL,

        HISTORY,
        ONLINE, OFFLINE,
        PORT_UP, PORT_DOWN,

        SPEAKER_RESPONSE,
        ACTION_SUCCESS, ACTION_FAIL,

        _INIT_CHOICE_CLEAN, _INIT_CHOICE_DIRTY
    }

    public enum State {
        ENTER,
        PREPARE,
        REMOVING,
        STOP
    }
}
