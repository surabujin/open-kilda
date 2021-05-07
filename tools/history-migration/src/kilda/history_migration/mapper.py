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

from kilda.history_migration import model


def encode_flow_event(flow_event):
    return {
        'task_id': flow_event.task_id,
        'event': flow_event.event,
        'actions': flow_event.actions,
        'dumps': flow_event.dumps}


def decode_flow_event(raw):
    return model.FlowEvent(
        *_extract_fields(raw, 'task_id', 'event', 'actions', 'dumps'))


def encode_port_event(port_event):
    return {'event': port_event.event}


def decode_port_event(raw):
    return model.PortEvent(*_extract_fields(raw, 'event'))


def encode_type_tag(tag):
    return {'type-tag': tag.tag}


def decode_type_tag(raw):
    return model.TypeTag(*_extract_fields(raw, 'type-tag', allow_extra=False))


def _extract_fields(record, *fields, allow_extra=True):
    sequence = []
    missing = []
    for name in fields:
        try:
            value = record.pop(name)
        except KeyError:
            value = None
            missing.append(name)
        sequence.append(value)

    if missing:
        raise ValueError(
            'The record have no field(s): "{}"'.format('", "'.join(missing)))
    if not allow_extra and bool(record):
        raise ValueError('The record have extra fields: "{}"'.format(
            '", "'.join(record)))

    return sequence
