"""Writes the copy of the pinned spec the generator reads, with the edits that keep a public type.

A re-pin that would move a member's Java type (a `format: date` added to a string the client has
always returned as a String) or its requirement (a member the models have always marked required,
which the spec lets a plan leave out) is generated from this copy, so it moves only at a major. The
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


# Members the spec lists as required through 2026.11.25 and no longer from 2026.11.26, since a plan
# without them leaves them out. The models keep them required, so RequiredMembers.PLAN_MEMBERS
# lists them: the decoder accepts an answer without one, and a test holds the two tables together.
PLAN_MEMBERS = [
    ("LookupResponse", "is_bot"),
    ("LookupResponse", "is_carrier"),
    ("LookupResponse", "is_satellite"),
    ("LookupResponse", "is_ssh"),
    ("LookupResponse", "is_smtp"),
    ("LookupResponse", "is_web"),
    ("LookupResponse", "is_dns_resolver"),
    ("LookupResponse", "is_bittorrent"),
    ("Asn", "type"),
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


def member_at(lines, schema, member):
    """The schema's start and the index of the line opening its property `member`."""
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
    return at, prop


def strip_date(lines, schema, member):
    _, prop = member_at(lines, schema, member)
    fmt = find(lines, prop + 1, block(lines, prop, 8), r"          format: date")
    if fmt is None:
        sys.exit(f"keep_types: {schema}.{member} is no longer format: date; drop its edit")
    del lines[fmt]


def restore_required(lines, schema, member):
    at, _ = member_at(lines, schema, member)
    end = block(lines, at, 4)
    required = find(lines, at + 1, end, r"      required:")
    if required is None:
        sys.exit(f"keep_types: {schema} has no required list")
    last = required
    while last + 1 < end and re.fullmatch(r"        - .*", lines[last + 1]):
        last += 1
        if lines[last] == f"        - {member}":
            sys.exit(f"keep_types: {schema}.{member} is required again; drop its edit")
    lines.insert(last + 1, f"        - {member}")


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    with open(sys.argv[1], encoding="utf-8") as f:
        lines = f.read().split("\n")
    for schema, member in STRING_DAYS:
        strip_date(lines, schema, member)
    for schema, member in PLAN_MEMBERS:
        restore_required(lines, schema, member)
    with open(sys.argv[2], "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


if __name__ == "__main__":
    main()
