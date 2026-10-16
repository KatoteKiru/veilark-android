"""Generate inactive Fairway route assets from a frozen exact RIPE IPv4 list.

No network, aggregation over foreign addresses, or runtime activation. The legacy
selection is a deterministic largest-prefix-first heuristic, not an optimum.
"""
from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
from pathlib import Path

EXPECTED_SHA = "ae3ce357e0cdaf40a70871c57c2318dded3f07a0c3bb3e1edca0b3f0a18b25e3"
BUDGET = 4096
DNS_RESERVE = 3


class Node:
    def __init__(self):
        self.children = {}
        self.direct = False


def insertion_cost(root, net):
    node = root
    for depth in range(net.prefixlen):
        bit = (int(net.network_address) >> (31 - depth)) & 1
        if bit not in node.children:
            # An already split parent has an implicit VPN child one level below.
            return net.prefixlen - depth - 1 - bool(node.children)
        node = node.children[bit]
    raise ValueError("overlapping or duplicate input")


def insert(root, net):
    node = root
    for depth in range(net.prefixlen):
        assert not node.direct
        bit = (int(net.network_address) >> (31 - depth)) & 1
        node = node.children.setdefault(bit, Node())
    assert not node.children and not node.direct
    node.direct = True


def complement(node, address=0, prefix=0):
    if node.direct:
        return []
    if not node.children:
        return [ipaddress.IPv4Network((address, prefix))]
    output = []
    for bit in (0, 1):
        child_address = address | (bit << (31 - prefix))
        child = node.children.get(bit)
        if child is None:
            output.append(ipaddress.IPv4Network((child_address, prefix + 1)))
        else:
            output.extend(complement(child, child_address, prefix + 1))
    return output


def encoded(networks):
    return ("\n".join(map(str, sorted(networks))) + "\n").encode("ascii")


def generate(source, output):
    raw = source.read_bytes()
    assert hashlib.sha256(raw).hexdigest() == EXPECTED_SHA, "unexpected frozen snapshot"
    exact = [ipaddress.IPv4Network(line) for line in raw.decode("ascii").splitlines()]
    assert len(exact) == 8662
    assert list(ipaddress.collapse_addresses(exact)) == sorted(exact)
    root, selected, route_count = Node(), [], 1
    for net in sorted(exact, key=lambda n: (n.prefixlen, int(n.network_address))):
        cost = insertion_cost(root, net)
        if route_count + cost <= BUDGET - DNS_RESERVE:
            insert(root, net)
            selected.append(net)
            route_count += cost
    vpn = complement(root)
    assert len(vpn) == route_count <= BUDGET - DNS_RESERVE
    # Exact partition: the canonical union covers all IPv4 with no overlap.
    partition = sorted(selected + vpn)
    cursor = 0
    for net in partition:
        assert int(net.network_address) == cursor
        cursor += net.num_addresses
    assert cursor == 1 << 32
    assert set(selected) <= set(exact)
    coverage = sum(n.num_addresses for n in selected)
    total = sum(n.num_addresses for n in exact)
    files = {"ru-ipv4-20261013.txt": encoded(exact),
             "ru-ipv4-legacy-20261013.txt": encoded(selected)}
    manifest = {
        "schema": "fairway-ru-route-assets-v1", "active": False,
        "snapshot_date": "2026-10-13", "source_sha256": EXPECTED_SHA,
        "algorithm": "largest-exact-prefix-first; skip-if-complement-exceeds-budget",
        "api33_exact_cidrs": len(exact), "exact_ru_ipv4": total,
        "legacy_direct_cidrs": len(selected), "legacy_vpn_cidrs": len(vpn),
        "legacy_direct_ru_ipv4": coverage, "legacy_residual_ru_ipv4": total - coverage,
        "legacy_coverage_percent": 100 * coverage / total,
        "legacy_route_budget": BUDGET, "reserved_dns_host_routes": DNS_RESERVE,
        "foreign_direct_ipv4": 0, "optimality_claimed": False,
        "files": {name: {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}
                  for name, data in files.items()},
    }
    output.mkdir(parents=True, exist_ok=True)
    for name, data in files.items():
        (output / name).write_bytes(data)
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] / "app/src/main/assets/fairway")
    args = parser.parse_args()
    print(json.dumps(generate(args.source, args.output), indent=2))
