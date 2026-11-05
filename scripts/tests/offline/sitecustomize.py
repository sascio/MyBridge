"""Block external DNS/TCP in script tests, while allowing loopback fixtures.

Loaded only through the test runner's PYTHONPATH; never used by the workflow's
publishing process. The preflight subprocess installs an even stricter mock.
"""
import ipaddress
import socket

_real_getaddrinfo = socket.getaddrinfo
_real_connect = socket.socket.connect
_real_connect_ex = socket.socket.connect_ex


def _local(host):
    if host in ("localhost", None):
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def _getaddrinfo(host, *args, **kwargs):
    if not _local(host):
        raise OSError("External DNS blocked by the offline test guard")
    return _real_getaddrinfo(host, *args, **kwargs)


def _connect(sock, address):
    if sock.family != socket.AF_UNIX and (not isinstance(address, tuple) or not _local(address[0])):
        raise OSError("External network blocked by the offline test guard")
    return _real_connect(sock, address)


def _connect_ex(sock, address):
    if sock.family != socket.AF_UNIX and (not isinstance(address, tuple) or not _local(address[0])):
        raise OSError("External network blocked by the offline test guard")
    return _real_connect_ex(sock, address)


socket.getaddrinfo = _getaddrinfo
socket.socket.connect = _connect
socket.socket.connect_ex = _connect_ex
