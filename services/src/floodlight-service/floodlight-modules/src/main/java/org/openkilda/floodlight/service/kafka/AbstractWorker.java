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

package org.openkilda.floodlight.service.kafka;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

abstract class AbstractWorker {
    protected final Producer<String, String> kafkaProducer;

    AbstractWorker(AbstractWorker other) {
        this(other.kafkaProducer);
    }

    AbstractWorker(Producer<String, String> kafkaProducer) {
        this.kafkaProducer = kafkaProducer;
    }

    /**
     * Serialize and send message into kafka topic.
     */
    abstract SendStatus send(ProducerRecord<String, String> record, Callback callback);

    void deactivate(long transitionPeriod) {}

    boolean isActive() {
        return true;
    }
}
