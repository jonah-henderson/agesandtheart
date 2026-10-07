"""The one NBT file a release writes: the multiplayer list, `servers.dat`, which Minecraft reads uncompressed."""

import struct

TAG_END = 0
TAG_BYTE = 1
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10


def _name(text: str) -> bytes:
    encoded = text.encode("utf-8")
    return struct.pack(">H", len(encoded)) + encoded


def _string_field(key: str, value: str) -> bytes:
    return bytes([TAG_STRING]) + _name(key) + _name(value)


def _byte_field(key: str, value: int) -> bytes:
    return bytes([TAG_BYTE]) + _name(key) + struct.pack(">b", value)


def servers_dat(servers: list[tuple[str, str]]) -> bytes:
    """A multiplayer list of (name, address) pairs, in order, each set to accept the server's resource pack."""
    entries = b"".join(
        _string_field("name", name) + _string_field("ip", address) + _byte_field("acceptTextures", 1) + bytes([TAG_END])
        for name, address in servers
    )
    server_list = bytes([TAG_LIST]) + _name("servers") + bytes([TAG_COMPOUND]) + struct.pack(">i", len(servers)) + entries
    return bytes([TAG_COMPOUND]) + _name("") + server_list + bytes([TAG_END])
