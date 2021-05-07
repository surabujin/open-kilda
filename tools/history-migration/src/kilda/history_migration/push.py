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

import collections
import datetime
import json

import ndjson

from kilda.history_migration import mapper
from kilda.history_migration import model


def ndjson_to_mysql(mysql_client, stream_in):
    cursor = mysql_client.cursor()
    stream = _ndjson_stream(stream_in)
    for entry in stream:
        record = entry.handler.decode(entry.entry)
        entry.handler.handle(cursor, record)
        mysql_client.commit()


def _ndjson_stream(stream_in):
    handlers_map = {
        model.FLOW_EVENT_TAG.tag: _FlowEventHandler(model.FLOW_EVENT_TAG),
        model.PORT_EVENT_TAG.tag: _PortEventHandler(model.PORT_EVENT_TAG)
    }

    reader = ndjson.reader(stream_in)
    handler = _DummyHandler()
    for entry in reader:
        if len(entry) == 1:  # possible type tag
            try:
                type_tag = mapper.decode_type_tag(entry)
                handler = handlers_map.get(
                    type_tag.tag, _DummyHandler(type_tag))
                continue
            except ValueError:
                pass
        yield _StreamEntry(handler, entry)


class _HandlerBase:
    def __init__(self, type_tag=None):
        self.type_tag = type_tag

    def decode(self, raw):
        raise NotImplementedError

    def handle(self, cursor, record):
        raise NotImplementedError


class _DummyHandler(_HandlerBase):
    def decode(self, raw):
        self._force_error('decode({!r})'.format(raw))

    def handle(self, cursor, record):
        self._force_error('handle({!r}, {!r})'.format(cursor, record))

    def _force_error(self, method):
        raise ValueError(
            'Dummy handler method {}.{} have been called'.format(
                type(self).__name__, method))


class _FlowEventHandler(_HandlerBase):
    def decode(self, raw):
        return mapper.decode_flow_event(raw)

    def handle(self, cursor, flow_record):
        unstructured = flow_record.event.copy()
        args = (
            _decode_datetime(unstructured.pop('timestamp')),
            unstructured.pop('flow_id'),
            flow_record.task_id,
            unstructured.pop('action'),
            json.dumps(unstructured))
        cursor.execute(
            'INSERT INTO flow_events_history '
            '(event_time, flow_id, task_id, action, unstructured) '
            'VALUES (%s, %s, %s, %s, %s)', params=args)

        record_id = cursor.lastrowid
        self._handle_actions(cursor, record_id, flow_record.actions)
        self._handle_dumps(cursor, record_id, flow_record.dumps)

    @staticmethod
    def _handle_actions(cursor, record_id, actions):
        for entry in actions:
            args = (
                record_id, _decode_datetime(entry['timestamp']),
                entry['action'], entry.get('details'))
            cursor.execute(
                'INSERT INTO flow_event_actions '
                '(flow_event_id, event_time, `action`, details) '
                'VALUES (%s, %s, %s, %s)', params=args)

    @staticmethod
    def _handle_dumps(cursor, record_id, dumps):
        for entry in dumps:
            unstructured = entry.copy()
            args = (
                record_id, unstructured.pop('type'), json.dumps(unstructured))
            cursor.execute(
                'INSERT INTO flow_event_dumps '
                '(flow_event_id, kind, unstructured) '
                'VALUES (%s, %s, %s)', params=args)


class _PortEventHandler(_HandlerBase):
    def decode(self, raw):
        return mapper.decode_port_event(raw)

    def handle(self, cursor, port_record):
        unstructured = port_record.event.copy()
        args = (
            unstructured.pop('id'),
            _decode_datetime(unstructured.pop('time')),
            unstructured.pop('switch_id'), unstructured.pop('port_number'),
            unstructured.pop('event'), json.dumps(unstructured))
        cursor.execute(
            'INSERT INTO port_events_history '
            '(id, event_time, switch_id, port_number, `event`, unstructured) '
            'VALUES (%s, %s, %s, %s, %s, %s)', params=args)


def _decode_datetime(origin):
    try:
        numeric_value = int(origin)
    except ValueError:
        naive = _decode_string_datetime(origin)
    else:
        naive = _decode_numeric_datetime(numeric_value)
    return naive.replace(tzinfo=datetime.timezone.utc)


def _decode_numeric_datetime(origin):
    seconds = origin // 1000
    result = datetime.datetime.fromtimestamp(seconds, datetime.timezone.utc)
    millis = origin % 1000
    return result.replace(microsecond=millis * 1000)


def _decode_string_datetime(origin):
    time_format_templates = ("%Y-%m-%dT%H:%M:%S.%fZ", "%Y-%m-%dT%H:%M:%SZ")
    for template in time_format_templates:
        try:
            return datetime.datetime.strptime(origin, template)
        except ValueError:
            continue
    raise ValueError(
        'Unable to parse time string {!r} using formats: "{}"'.format(
            origin, '", "'.join(time_format_templates)))


_StreamEntry = collections.namedtuple('_StreamEntry', ('handler', 'entry'))
