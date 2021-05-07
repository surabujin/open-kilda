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

import click
import mysql.connector
import pyorient

from kilda.history_migration import pull
from kilda.history_migration import push


@click.group()
def cli():
    pass


@cli.command('pull')
@click.option('--orient-db-host', default='localhost')
@click.option('--orient-db-port', type=int, default=2424)
@click.option('--orient-db-login', default='kilda')
@click.option('--orient-db-password', default='')
@click.argument('database')
@click.argument('dump', type=click.types.File(mode='wt', lazy=True))
def history_pull(database, dump, **options):
    auth = _LoginPassword(
        options['orient_db_login'], options['orient_db_password'])
    orient_client = _make_orientdb_client(
        options['orient_db_host'], options['orient_db_port'], database, auth)
    pull.orient_to_ndjson(orient_client, dump)


@cli.command('push')
@click.option('--mysql-db-host', default='localhost')
@click.option('--mysql-db-port', type=int, default=3306)
@click.option('--mysql-db-login', default='kilda')
@click.option('--mysql-db-password', default='')
@click.argument('dump', type=click.types.File(mode='rt', lazy=False))
@click.argument('database')
def history_push(dump, database, **options):
    auth = _LoginPassword(
        options['mysql_db_login'], options['mysql_db_password'])
    mysql_client = _make_mysql_client(
        options['mysql_db_host'], options['mysql_db_port'], database, auth)
    push.ndjson_to_mysql(mysql_client, dump)


def main():
    """
    Entry point defined in setup.py
    """
    cli()


def _make_orientdb_client(hostname, port, database, auth):
    connect = {
        'host': hostname,
        'port': port}
    connect = {k: v for k, v in connect.items() if v is not None}
    client = pyorient.OrientDB(**connect)
    client.db_open(database, *auth)
    return client


def _make_mysql_client(hostname, port, database, auth):
    return mysql.connector.connect(
        host=hostname, port=port,
        user=auth.login, password=auth.password,
        database=database)


_LoginPassword = collections.namedtuple('_LoginPassword', ('login', 'password'))
