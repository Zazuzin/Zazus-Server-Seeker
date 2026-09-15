#!/usr/bin/env python3
"""Check compiled mod bytecode against the real Loom compile classpath.

The audit catches the failure class that caused RC.1's Server Notes crash:
a Methodref targeting an interface (or InterfaceMethodref targeting a class).
It also checks that referenced Minecraft/Fabric fields and methods exist in the
resolved 26.2 compile-time API, following superclass/interface inheritance.
"""
from __future__ import annotations

import argparse
import os
import struct
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, Optional, Set, Tuple

ACC_INTERFACE = 0x0200
CHECK_PREFIXES = ("net/minecraft/", "net/fabricmc/")
SKIP_PREFIXES = ("dev/zazuzin/", "dev/zazu/")

@dataclass
class ClassInfo:
    name: str
    access: int
    super_name: Optional[str]
    interfaces: tuple[str, ...]
    methods: Set[Tuple[str, str]]
    fields: Set[Tuple[str, str]]

@dataclass
class ParsedClass:
    info: ClassInfo
    method_refs: list[Tuple[int, str, str, str]]
    field_refs: list[Tuple[str, str, str]]


def u1(data, p): return data[p], p + 1
def u2(data, p): return struct.unpack_from(">H", data, p)[0], p + 2
def u4(data, p): return struct.unpack_from(">I", data, p)[0], p + 4


def parse_class(data: bytes) -> ParsedClass:
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")
    p = 8
    cp_count, p = u2(data, p)
    cp = [None] * cp_count
    i = 1
    while i < cp_count:
        tag, p = u1(data, p)
        if tag == 1:
            n, p = u2(data, p)
            cp[i] = (tag, data[p:p+n].decode("utf-8", "replace")); p += n
        elif tag in (3, 4): cp[i] = (tag,); p += 4
        elif tag in (5, 6): cp[i] = (tag,); p += 8; i += 1
        elif tag in (7, 8, 16, 19, 20):
            v, p = u2(data, p); cp[i] = (tag, v)
        elif tag in (9, 10, 11, 12, 17, 18):
            a, p = u2(data, p); b, p = u2(data, p); cp[i] = (tag, a, b)
        elif tag == 15:
            kind, p = u1(data, p); idx, p = u2(data, p); cp[i] = (tag, kind, idx)
        else:
            raise ValueError(f"unsupported constant-pool tag {tag}")
        i += 1

    def utf(idx): return cp[idx][1]
    def cls(idx): return utf(cp[idx][1])
    def nt(idx):
        item = cp[idx]
        return utf(item[1]), utf(item[2])

    access, p = u2(data, p)
    this_idx, p = u2(data, p)
    super_idx, p = u2(data, p)
    iface_count, p = u2(data, p)
    ifaces = []
    for _ in range(iface_count):
        x, p = u2(data, p); ifaces.append(cls(x))

    def skip_attrs(p, count):
        for _ in range(count):
            _, p = u2(data, p)
            n, p = u4(data, p)
            p += n
        return p

    field_count, p = u2(data, p)
    fields = set()
    for _ in range(field_count):
        _, p = u2(data, p)
        name_idx, p = u2(data, p); desc_idx, p = u2(data, p)
        fields.add((utf(name_idx), utf(desc_idx)))
        ac, p = u2(data, p); p = skip_attrs(p, ac)

    method_count, p = u2(data, p)
    methods = set()
    for _ in range(method_count):
        _, p = u2(data, p)
        name_idx, p = u2(data, p); desc_idx, p = u2(data, p)
        methods.add((utf(name_idx), utf(desc_idx)))
        ac, p = u2(data, p); p = skip_attrs(p, ac)

    method_refs = []
    field_refs = []
    for item in cp:
        if not item: continue
        if item[0] in (10, 11):
            name, desc = nt(item[2])
            method_refs.append((item[0], cls(item[1]), name, desc))
        elif item[0] == 9:
            name, desc = nt(item[2])
            field_refs.append((cls(item[1]), name, desc))

    info = ClassInfo(cls(this_idx), access, cls(super_idx) if super_idx else None,
                     tuple(ifaces), methods, fields)
    return ParsedClass(info, method_refs, field_refs)


def iter_classes(path: Path) -> Iterable[Tuple[str, bytes]]:
    if path.is_dir():
        for f in path.rglob("*.class"):
            yield f.relative_to(path).as_posix()[:-6], f.read_bytes()
    elif path.is_file() and path.suffix in (".jar", ".zip"):
        try:
            with zipfile.ZipFile(path) as z:
                for n in z.namelist():
                    if n.endswith(".class") and not n.startswith("META-INF/versions/"):
                        yield n[:-6], z.read(n)
        except zipfile.BadZipFile:
            return


def resolve_member(index: Dict[str, ClassInfo], owner: str, member: Tuple[str,str], kind: str, seen=None) -> bool:
    if seen is None: seen = set()
    if owner in seen: return False
    seen.add(owner)
    ci = index.get(owner)
    if ci is None: return False
    own = ci.methods if kind == "method" else ci.fields
    if member in own: return True
    if ci.super_name and resolve_member(index, ci.super_name, member, kind, seen): return True
    return any(resolve_member(index, itf, member, kind, seen) for itf in ci.interfaces)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--classes", action="append", required=True, help="compiled mod classes directory or jar")
    ap.add_argument("--classpath-file", required=True)
    args = ap.parse_args()

    cp_paths = []
    raw = Path(args.classpath_file).read_text().strip()
    # Gradle writes one path per line in this project. Also tolerate os.pathsep.
    for line in raw.splitlines():
        for part in line.split(os.pathsep):
            if part.strip(): cp_paths.append(Path(part.strip()))

    index: Dict[str, ClassInfo] = {}
    for path in cp_paths:
        for _, data in iter_classes(path):
            try:
                pc = parse_class(data)
            except Exception:
                continue
            index.setdefault(pc.info.name, pc.info)

    if not any(name.startswith("net/minecraft/") for name in index):
        print("API audit error: Loom compile classpath contains no net/minecraft classes", file=sys.stderr)
        return 2
    if not any(name.startswith("net/fabricmc/") for name in index):
        print("API audit error: Loom compile classpath contains no net/fabricmc classes", file=sys.stderr)
        return 2

    failures = []
    checked_methods = checked_fields = checked_classes = 0
    for mod_path in map(Path, args.classes):
        for logical_name, data in iter_classes(mod_path):
            try:
                pc = parse_class(data)
            except Exception as e:
                failures.append(f"{logical_name}: cannot parse class: {e}")
                continue
            if not pc.info.name.startswith(SKIP_PREFIXES):
                continue
            checked_classes += 1
            for tag, owner, name, desc in pc.method_refs:
                if not owner.startswith(CHECK_PREFIXES): continue
                target = index.get(owner)
                if target is None:
                    failures.append(f"{pc.info.name}: missing target class {owner} for {name}{desc}")
                    continue
                checked_methods += 1
                target_is_interface = bool(target.access & ACC_INTERFACE)
                if tag == 10 and target_is_interface:
                    failures.append(f"{pc.info.name}: Methodref targets interface {owner}.{name}{desc}")
                if tag == 11 and not target_is_interface:
                    failures.append(f"{pc.info.name}: InterfaceMethodref targets class {owner}.{name}{desc}")
                if name == "<init>":
                    if (name, desc) not in target.methods:
                        failures.append(f"{pc.info.name}: missing constructor {owner}.{name}{desc}")
                elif not resolve_member(index, owner, (name, desc), "method"):
                    failures.append(f"{pc.info.name}: missing method {owner}.{name}{desc}")
            for owner, name, desc in pc.field_refs:
                if not owner.startswith(CHECK_PREFIXES): continue
                target = index.get(owner)
                if target is None:
                    failures.append(f"{pc.info.name}: missing target class {owner} for field {name}:{desc}")
                    continue
                checked_fields += 1
                if not resolve_member(index, owner, (name, desc), "field"):
                    failures.append(f"{pc.info.name}: missing field {owner}.{name}:{desc}")

    if failures:
        print("Real-API bytecode audit FAILED:", file=sys.stderr)
        for f in failures:
            print(" - " + f, file=sys.stderr)
        return 1
    print(f"Real-API bytecode audit passed: {checked_classes} mod classes, {checked_methods} Minecraft/Fabric method refs, {checked_fields} field refs.")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
