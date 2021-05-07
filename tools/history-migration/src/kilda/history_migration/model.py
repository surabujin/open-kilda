#  Copyright 2021 Telstra Open Source
#
#    Licensed under the Apache License, Version 2.0 (the "License");
#    you may not use this file except in compliance with the License.
#    You may obtain a copy of the License at
#
#        http://www.apache.org/licenses/LICENSE-2.0
#
#    Unless required by applicable law or agreed to in writing, software
#    distributed under the License is distributed on an "AS IS" BASIS,
#    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#    See the License for the specific language governing permissions and
#    limitations under the License.


class FlowEvent:
    def __init__(self, task_id, event, actions, dumps):
        self.task_id = task_id
        self.event = event
        self.actions = actions
        self.dumps = dumps

    def _add_list_entry(self, target, orient_entry):
        if self.task_id != orient_entry.task_id:
            raise ValueError('Mismatch relations key {!r} != {!r}'.format(
                self.task_id, orient_entry.task_id))
        raw = orient_entry.oRecordData.copy()
        raw.pop('task_id')
        target.append(raw)


class PortEvent:
    def __init__(self, event):
        self.event = event


class TypeTag:
    def __init__(self, tag):
        self.tag = tag


FLOW_EVENT_TAG = TypeTag('flow-event')
PORT_EVENT_TAG = TypeTag('port-event')

