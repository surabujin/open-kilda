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

import ndjson

from kilda.history_migration import mapper
from kilda.history_migration import model


def orient_to_ndjson(orient_client, stream_out):
    writer = ndjson.writer(stream_out)
    _pull_flow_events(orient_client, writer)
    _pull_port_events(orient_client, writer)


def _pull_flow_events(orient_client, writer):
    stream = _flow_events_stream(orient_client)
    stream = _flow_events_relations_mixin(stream, orient_client)

    writer.writerow(mapper.encode_type_tag(model.FLOW_EVENT_TAG))
    for entry in stream:
        writer.writerow(mapper.encode_flow_event(entry))


def _pull_port_events(orient_client, writer):
    stream = _port_events_stream(orient_client)

    writer.writerow(mapper.encode_type_tag(model.PORT_EVENT_TAG))
    for entry in stream:
        writer.writerow(mapper.encode_port_event(entry))


def _flow_events_stream(orient_client, **kwargs):
    return _orient_stream(orient_client, 'flow_event', **kwargs)


def _flow_events_relations_mixin(stream, orient_client):
    for flow_event in stream:
        actions = []
        for entry in _query_flow_event_actions(
                orient_client, flow_event.task_id):
            actions.append(_orient_entry_raw_fields(entry, 'task_id'))

        dumps = []
        for entry in _query_flow_event_dumps(
                orient_client, flow_event.task_id):
            dumps.append(_orient_entry_raw_fields(entry, 'task_id'))

        yield model.FlowEvent(
            flow_event.task_id, _orient_entry_raw_fields(flow_event, 'task_id'),
            actions, dumps)


def _query_flow_event_actions(orient_client, task_id):
    q = 'SELECT * FROM flow_history WHERE task_id={!r}'.format(task_id)
    return orient_client.query(q, -1)


def _query_flow_event_dumps(orient_client, task_id):
    q = 'SELECT * FROM flow_dump WHERE task_id={!r}'.format(task_id)
    return orient_client.query(q, -1)


def _port_events_stream(orient_client, **kwargs):
    stream = _orient_stream(orient_client, 'port_history', **kwargs)
    for entry in stream:
        yield model.PortEvent(_orient_entry_raw_fields(entry))


def _orient_stream(orient_client, orient_class, limit=10):
    offset = 0
    while True:
        q = 'SELECT * FROM {} ORDER BY time SKIP {}'.format(
            orient_class, offset)

        origin = offset
        for entry in orient_client.query(q, limit):
            offset += 1
            yield entry
        if origin == offset:
            break


def _orient_entry_raw_fields(entry, exclude=()):
    raw = entry.oRecordData.copy()
    for field in exclude:
        raw.pop(field, None)
    return raw
