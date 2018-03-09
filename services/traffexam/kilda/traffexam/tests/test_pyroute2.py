# Copyright 2017 Telstra Open Source
#
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.
#

import errno
import string
import random

import pytest
import pyroute2


class Abstract(object):
    def make_name(self, kind, uniq_length=None):
        uniq = self.make_uniq_str(length=uniq_length)
        return 'te.{}.{}'.format(kind, uniq)

    def make_uniq_str(self, length=8):
        alphabet = set(string.ascii_lowercase)
        alphabet.update(string.ascii_uppercase)
        alphabet.update(string.digits)
        return ''.join(random.sample(alphabet, length))

    def make_ns_name(self, ensure_missing=True):
        name = self.make_name('NS')

        if ensure_missing:
            try:
                pyroute2.netns.remove(name)
            except OSError as e:
                if e.errno != errno.ENOENT:
                    raise

        return name

    def make_veth_pair_name(self):
        base = self.make_name('VEth', uniq_length=4)
        return tuple('{}.{}'.format(base, tail) for tail in 'AB')

    def make_br_name(self):
        return self.make_name('BR', uniq_length=4)


class TestNameSpace(Abstract):
    def test_create_existing(self):
        name = self.make_ns_name()

        pyroute2.netns.create(name)
        try:
            with pytest.raises(OSError) as exc_info:
                pyroute2.netns.create(name)
            assert exc_info.value.errno == errno.EEXIST
        finally:
            pyroute2.netns.remove(name)


class TestVEthPair(Abstract):
    def test_create_delete(self):
        name = self.make_veth_pair_name()

        with pyroute2.IPDB() as ip:
            left, right = name
            with ip.create(kind='veth', ifname=left, peer=right):
                pass

            assert left in ip.interfaces
            assert right in ip.interfaces

            with ip.interfaces[left] as link:
                link.remove()

            assert left not in ip.interfaces
            assert right not in ip.interfaces


class TestBridge(Abstract):
    def test_create_delete(self):
        name = self.make_br_name()
        veth_pair = self.make_veth_pair_name()

        with pyroute2.IPDB() as ip:
            with ip.create(kind='veth', ifname=veth_pair[0], peer=veth_pair[1]):
                pass

            with ip.create(kind='bridge', ifname=name) as iface:
                iface.add_port(ip.interfaces[veth_pair[0]])

            with ip.interfaces[name].ro as iface:
                assert iface.kind == 'bridge'

            with ip.interfaces[name] as iface:
                iface.remove()

            with ip.interfaces[veth_pair[0]] as iface:
                iface.remove()


class TestIface(Abstract):
    def test_list(self):
        with pyroute2.IPDB() as ip:
            ifaces = tuple(ip.interfaces.keys())
            assert ifaces
            assert ifaces == [x for x in ifaces if isinstance(x, int)]

    def test_vlan_create(self):
        vlan_id = 127
        name = self.make_br_name()
        vlan_name = '{}.v{}'.format(name, vlan_id)

        with pyroute2.IPDB() as ip:
            with ip.create(kind='bridge', ifname=name):
                pass

            link = ip.interfaces[name].ro
            try:
                with ip.create(
                        kind='vlan', ifname=vlan_name, vlan_id=vlan_id,
                        link=link.index) as iface:
                    iface.add_ip('172.16.15.2/16')
            finally:
                with ip.interfaces[name] as iface:
                    iface.remove()


class TestIpAddress(Abstract):
    def test_create_delete(self):
        name = self.make_br_name()

        with pyroute2.IPDB() as ip:
            with ip.create(kind='bridge', ifname=name) as iface:
                iface.add_ip('172.16.15.1/16')
                iface.add_ip('fc00::2/64')

            try:
                with ip.interfaces[name] as iface:
                    for addr in tuple(iface.ipaddr):
                        iface.del_ip(*addr)
            finally:
                with ip.interfaces[name] as iface:
                    iface.remove()


class TestRoute(Abstract):
    def test_list(self):
        with pyroute2.IPDB() as ip:
            routes = list(ip.routes)
            print(routes)
