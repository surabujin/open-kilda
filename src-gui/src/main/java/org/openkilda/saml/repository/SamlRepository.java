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

package org.openkilda.saml.repository;

import org.openkilda.saml.entity.SamlConfig;

import org.springframework.context.annotation.ComponentScan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@ComponentScan
@Component
public interface SamlRepository extends JpaRepository<SamlConfig, Long> {

    SamlConfig findByEntityIdOrIdpNameEqualsIgnoreCase(String entityId, String idpName);

    SamlConfig findByIdpId(String idpId);

    SamlConfig findByEntityId(String entityId);

    SamlConfig findByIdpIdNotAndEntityIdOrIdpIdNotAndIdpNameEqualsIgnoreCase(String idpId, 
            String entityId, String idpId2, String name);

    List<SamlConfig> findAllByActiveStatus(boolean status);


}
