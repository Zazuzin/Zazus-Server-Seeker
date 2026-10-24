#!/usr/bin/env python3
"""Check the actual installed Minecraft APIs used by the status sample hook."""
from pathlib import Path
import os
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
classpath = os.pathsep.join((root / 'build/client-compile-classpath.txt').read_text().splitlines())

def javap(name):
    return subprocess.run(['javap', '-p', '-s', '-classpath', classpath, name],
                          check=True, capture_output=True, text=True).stdout

packet = javap('net.minecraft.network.protocol.status.ClientboundStatusResponsePacket')
assert '(Lnet/minecraft/network/protocol/status/ClientStatusPacketListener;)V' in packet
assert ' status();' in packet
status = javap('net.minecraft.network.protocol.status.ServerStatus')
players = javap('net.minecraft.network.protocol.status.ServerStatus$Players')
assert ' players();' in status and ' sample();' in players
listeners = set()
for entry in classpath.split(os.pathsep):
    path = Path(entry)
    if path.suffix != '.jar' or not path.exists():
        continue
    with zipfile.ZipFile(path) as archive:
        listeners.update(name[:-6].replace('/', '.') for name in archive.namelist()
                         if name.startswith('net/minecraft/client/multiplayer/ServerStatusPinger$')
                         and name.endswith('.class'))
captures = [name for name in sorted(listeners) if 'net.minecraft.client.multiplayer.ServerData ' in javap(name)]
assert captures, 'Native status listener does not capture ServerData; inspect the real pinger before release'
print('Real status packet, sample accessors and ServerData listener capture verified:', ', '.join(captures))
