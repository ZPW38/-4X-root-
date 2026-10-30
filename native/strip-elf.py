#!/usr/bin/env python3
"""Remove debug information and the symbol table from a 64-bit ELF file.

`zig objcopy --strip-all` is not implemented by the zig shipped through pip, and
there is no NDK (hence no llvm-strip) on the build machine, so this script does
the only thing that is actually needed for the payload: drop every section that
is not part of the loaded image.

What is removed
---------------
  * every `.debug*` section
  * `.comment`
  * `SHT_SYMTAB` and non-allocated `SHT_STRTAB` sections

The loaded image is untouched: all `SHF_ALLOC` sections keep their original
file offset, and the program headers are copied verbatim, so the resulting file
runs exactly like the input. Only the section header table at the end of the
file is rebuilt.

Usage:
    python strip-elf.py <in> <out>
"""

from __future__ import annotations

import struct
import sys

# ELF constants we care about.
SHT_NULL = 0
SHT_SYMTAB = 2
SHT_STRTAB = 3
SHT_NOBITS = 8
SHF_ALLOC = 0x2

EHDR64 = struct.Struct("<16sHHIQQQIHHHHHH")
SHDR64 = struct.Struct("<IIQQQQIIQQ")

DROP_PREFIXES = (".debug",)
DROP_NAMES = {".comment"}

# One past the last byte occupied by a kept section (used to truncate the file).
NOBITS_KINDS = {SHT_NOBITS}


class StripError(Exception):
    pass


def read_sections(blob: bytes) -> tuple[bytes, list[dict]]:
    _ident, _type, _machine, _version, _entry, _phoff, shoff, _flags, _ehsize, \
        _phentsize, _phnum, shentsize, shnum, shstrndx = EHDR64.unpack_from(blob, 0)

    if shoff == 0 or shnum == 0:
        raise StripError("no section header table")
    if shstrndx >= shnum:
        raise StripError("bad e_shstrndx")

    sections = []
    for index in range(shnum):
        base = shoff + index * shentsize
        fields = SHDR64.unpack_from(blob, base)
        sections.append({
            "index": index,
            "sh_name": fields[0],
            "sh_type": fields[1],
            "sh_flags": fields[2],
            "sh_addr": fields[3],
            "sh_offset": fields[4],
            "sh_size": fields[5],
            "sh_link": fields[6],
            "sh_info": fields[7],
            "sh_addralign": fields[8],
            "sh_entsize": fields[9],
        })

    strtab = sections[shstrndx]
    names = blob[strtab["sh_offset"]:strtab["sh_offset"] + strtab["sh_size"]]
    for section in sections:
        start = section["sh_name"]
        end = names.find(b"\0", start)
        if end < 0:
            end = len(names)
        section["name"] = names[start:end].decode("utf-8", "replace")

    return blob, sections


def should_drop(section: dict) -> bool:
    name = section["name"]
    sections_type = section["sh_type"]

    # The null section must stay at index 0.
    if sections_type == SHT_NULL:
        return False
    if name in DROP_NAMES or name.startswith(DROP_PREFIXES):
        return True
    if sections_type == SHT_SYMTAB:
        return True
    if sections_type == SHT_STRTAB and not section["sh_flags"] & SHF_ALLOC:
        return True
    return False


def strip(blob: bytes) -> bytes:
    blob, sections = read_sections(blob)

    kept = [s for s in sections if not should_drop(s)]
    dropped = {s["index"] for s in sections if should_drop(s)}

    # Truncate after the last byte that a kept section still reads from.
    end = 0
    for section in kept:
        if section["sh_type"] in NOBITS_KINDS:
            continue
        end = max(end, section["sh_offset"] + section["sh_size"])
    if end == 0 or end > len(blob):
        raise StripError("could not determine truncation point")

    out = bytearray(blob[:end])

    # Rebuild the section name table with the surviving names only.
    name_offsets: dict[str, int] = {"": 0}
    new_strtab = bytearray(b"\0")
    for section in kept:
        name = section["name"]
        if name in name_offsets:
            continue
        name_offsets[name] = len(new_strtab)
        new_strtab += name.encode() + b"\0"

    shstrtab_name = len(new_strtab)
    new_strtab += b".shstrtab\0"

    # The name table needs the same alignment the original one had (usually 1).
    align = next(
        (s["sh_addralign"] for s in sections if s["name"] == ".shstrtab"), 1
    ) or 1
    while len(out) % align:
        out.append(0)

    strtab_offset = len(out)
    out += new_strtab
    while len(out) % 8:
        out.append(0)

    # New header table: the null section, every kept section, then the new name table.
    shoff = len(out)
    headers = [bytes(SHDR64.size)]  # section 0 is all zeroes

    new_index: dict[int, int] = {0: 0}
    for position, section in enumerate(kept):
        if section["index"] == 0:
            continue
        new_index[section["index"]] = position + 1
        headers.append(bytes(SHDR64.size))  # placeholder, patched below

    strtab_index = len(headers)
    headers.append(bytes(SHDR64.size))

    def patch(slot: int, section: dict | None, *, name: int = 0,
              offset: int | None = None, size: int | None = None) -> None:
        if section is None:
            payload = SHDR64.pack(name, SHT_STRTAB, 0, 0, strtab_offset, len(new_strtab),
                                  0, 0, 1, 0)
            headers[slot] = payload
            return
        # A link to a dropped section becomes 0; indices otherwise shift.
        link = section["sh_link"]
        link = new_index.get(link, 0) if link else 0
        headers[slot] = SHDR64.pack(
            name,
            section["sh_type"],
            section["sh_flags"],
            section["sh_addr"],
            section["sh_offset"] if offset is None else offset,
            section["sh_size"] if size is None else size,
            link,
            section["sh_info"],
            section["sh_addralign"],
            section["sh_entsize"],
        )

    slot = 1
    for section in kept:
        if section["index"] == 0:
            continue
        patch(slot, section, name=name_offsets[section["name"]])
        slot += 1

    patch(strtab_index, None, name=shstrtab_name)

    for header in headers:
        out += header

    # Locate the ELF header fields that describe the section header table.
    (shoff_field, shnum_field, shstrndx_field) = (0x28, 0x3C, 0x3E)
    struct.pack_into("<Q", out, shoff_field, shoff)
    struct.pack_into("<H", out, shnum_field, len(headers))
    struct.pack_into("<H", out, shstrndx_field, strtab_index)

    return bytes(out)


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        print("usage: strip-elf.py <in> <out>", file=sys.stderr)
        return 2

    with open(argv[1], "rb") as source:
        blob = source.read()
    if blob[:4] != b"\x7fELF":
        print(f"error: {argv[1]} is not an ELF file", file=sys.stderr)
        return 1
    if blob[4] != 2:
        print("error: only 64-bit ELF files are supported", file=sys.stderr)
        return 1

    result = strip(blob)
    with open(argv[2], "wb") as target:
        target.write(result)

    print(f"{argv[1]}: {len(blob)} -> {len(result)} bytes "
          f"({100 * len(result) / len(blob):.1f}%)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
