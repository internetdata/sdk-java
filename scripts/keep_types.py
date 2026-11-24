"""Writes the copy of the pinned spec the generator reads, with the edits that keep a public type.

A re-pin that would move a member's Java type (a `format: date` added to a string the client has
always returned as a String) is generated from this copy, so the type moves only at a major. The
pin itself stays byte-equal to the published spec. Each edit targets one member of one schema and
fails when it matches nothing, so a spec that drops or moves the member stops the generation rather
than leaving a dead edit behind.

Usage: keep_types.py <pinned spec> <edited copy>
"""

import re
import sys

# The seen days a lookup answer has carried as plain strings since 2.8.0, typed `format: date` by
# the spec from 2026.11.23. RequiredMembers.DAYS checks each holds RFC 3339's full-date.
STRING_DAYS = [
    ("ClassDetail", "last_seen"),
    ("ProxyDetail", "first_seen"),
    ("ProxyDetail", "last_seen"),
    ("PubproxyDetail", "last_seen"),
    ("BotDetail", "first_seen"),
    ("BotDetail", "last_seen"),
    ("SshDetail", "first_seen"),
    ("SshDetail", "last_seen"),
    ("SmtpDetail", "first_seen"),
    ("SmtpDetail", "last_seen"),
    ("WebDetail", "first_seen"),
    ("WebDetail", "last_seen"),
    ("DnsResolverDetail", "first_seen"),
    ("DnsResolverDetail", "last_seen"),
    ("BitTorrentDetail", "first_seen"),
    ("BitTorrentDetail", "last_seen"),
    ("AsnPrefix", "first_seen"),
    ("DnsAddressRecord", "last_seen"),
    ("DnsMxRecord", "last_seen"),
    ("DnsNsRecord", "last_seen"),
    ("DnsCnameRecord", "last_seen"),
    ("DisposableDetail", "first_seen"),
    ("DisposableDetail", "last_seen"),
]


def block(lines, start, indent):
    """The index past the last line of the block opened at `start`, whose children sit deeper than `indent`."""
    end = start + 1
    while end < len(lines):
        line = lines[end]
        if line.strip() and len(line) - len(line.lstrip(" ")) <= indent:
            break
        end += 1
    return end


def find(lines, lo, hi, pattern):
    for i in range(lo, hi):
        if re.fullmatch(pattern, lines[i]):
            return i
    return None


def strip_date(lines, schema, member):
    schemas = find(lines, 0, len(lines), r"  schemas:")
    if schemas is None:
        sys.exit("keep_types: the spec has no components.schemas")
    at = find(lines, schemas + 1, block(lines, schemas, 2), r"    " + re.escape(schema) + r":")
    if at is None:
        sys.exit(f"keep_types: no schema {schema}")
    props = find(lines, at + 1, block(lines, at, 4), r"      properties:")
    if props is None:
        sys.exit(f"keep_types: {schema} has no properties")
    prop = find(lines, props + 1, block(lines, props, 6), r"        " + re.escape(member) + r":")
    if prop is None:
        sys.exit(f"keep_types: {schema} has no {member}")
    fmt = find(lines, prop + 1, block(lines, prop, 8), r"          format: date")
    if fmt is None:
        sys.exit(f"keep_types: {schema}.{member} is no longer format: date; drop its edit")
    del lines[fmt]


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    with open(sys.argv[1], encoding="utf-8") as f:
        lines = f.read().split("\n")
    for schema, member in STRING_DAYS:
        strip_date(lines, schema, member)
    with open(sys.argv[2], "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


if __name__ == "__main__":
    main()
