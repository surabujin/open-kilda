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

package org.openkilda.wfm.topology.nbworker.services;

import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertTrue;

import org.openkilda.messaging.model.SwitchPropertiesDto;
import org.openkilda.model.FlowEncapsulationType;
import org.openkilda.model.PortProperties;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.model.SwitchStatus;
import org.openkilda.persistence.Neo4jBasedTest;
import org.openkilda.persistence.repositories.PortPropertiesRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.error.IllegalSwitchPropertiesException;
import org.openkilda.wfm.error.SwitchNotFoundException;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Collections;

public class SwitchOperationsServiceTest extends Neo4jBasedTest {
    private static SwitchRepository switchRepository;
    private static SwitchPropertiesRepository switchPropertiesRepository;
    private static PortPropertiesRepository portPropertiesRepository;
    private static SwitchOperationsService switchOperationsService;

    private static final SwitchId TEST_SWITCH_ID = new SwitchId(1);

    @BeforeClass
    public static void setUpOnce() {
        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        switchRepository = repositoryFactory.createSwitchRepository();
        switchPropertiesRepository = repositoryFactory.createSwitchPropertiesRepository();
        portPropertiesRepository = repositoryFactory.createPortPropertiesRepository();

        SwitchOperationsServiceCarrier carrier = new SwitchOperationsServiceCarrier() {
            @Override
            public void requestSwitchSync(SwitchId switchId) {
            }
        };
        switchOperationsService = new SwitchOperationsService(persistenceManager.getRepositoryFactory(),
                persistenceManager.getTransactionManager(), carrier);
    }

    @Test
    public void shouldUpdateLinkUnderMaintenanceFlag() throws SwitchNotFoundException {
        Switch sw = Switch.builder().switchId(TEST_SWITCH_ID).status(SwitchStatus.ACTIVE).build();
        switchRepository.createOrUpdate(sw);

        switchOperationsService.updateSwitchUnderMaintenanceFlag(TEST_SWITCH_ID, true);
        sw = switchRepository.findById(TEST_SWITCH_ID).get();
        assertTrue(sw.isUnderMaintenance());

        switchOperationsService.updateSwitchUnderMaintenanceFlag(TEST_SWITCH_ID, false);
        sw = switchRepository.findById(TEST_SWITCH_ID).get();
        assertFalse(sw.isUnderMaintenance());
    }

    @Test
    public void shouldDeletePortPropertiesWhenDeletingSwitch() throws SwitchNotFoundException {
        Switch sw = Switch.builder().switchId(TEST_SWITCH_ID).status(SwitchStatus.ACTIVE).build();
        switchRepository.createOrUpdate(sw);
        PortProperties portProperties = PortProperties.builder().switchObj(sw).port(7).discoveryEnabled(false).build();
        portPropertiesRepository.createOrUpdate(portProperties);

        switchOperationsService.deleteSwitch(TEST_SWITCH_ID, false);
        assertFalse(switchRepository.findById(TEST_SWITCH_ID).isPresent());
        assertTrue(portPropertiesRepository.findAll().isEmpty());
    }

    @Test(expected = IllegalSwitchPropertiesException.class)
    public void shouldValidateSupportedEncapsulationTypeWhenUpdatingSwitchProperties() {
        Switch sw = Switch.builder().switchId(TEST_SWITCH_ID).status(SwitchStatus.ACTIVE).build();
        switchRepository.createOrUpdate(sw);
        SwitchProperties switchProperties = SwitchProperties.builder()
                .switchObj(sw)
                .supportedTransitEncapsulation(Collections.singleton(FlowEncapsulationType.TRANSIT_VLAN))
                .multiTable(false)
                .build();
        switchPropertiesRepository.createOrUpdate(switchProperties);

        switchOperationsService.updateSwitchProperties(TEST_SWITCH_ID, new SwitchPropertiesDto());
    }
}
