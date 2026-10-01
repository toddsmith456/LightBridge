#!/usr/bin/env python3
"""Dependency advisory + license gate for the shipped (release runtime) classpath.

* Resolves `:app:releaseRuntimeClasspath` with Gradle.
* Asks OSV (osv.dev) for known advisories for every exact Maven coordinate. An unreachable
  OSV is a FAILURE, never a silent pass.
* Reads each POM's declared license and fails on anything outside the allowlist.
* Writes dependency-inventory.md for the release record.

Advisories may be waived only in tools/release/osv-allowlist.txt, one `ID  reason` per line.
"""
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ALLOWED_LICENSE_WORDS = ("apache", "mit license", "the mit", "bsd")  # lowercase substrings
REPOS = ["https://dl.google.com/dl/android/maven2", "https://repo.maven.apache.org/maven2"]
COORD = re.compile(r"([\w.\-]+):([\w.\-]+):(?:[\w.\-\[\],()]+ -> )?([\w.\-]+)")


def http(url: str, data: bytes | None = None, retries: int = 4) -> bytes:
    last = None
    for i in range(retries):
        try:
            req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                raise
            last = e
        except Exception as e:  # noqa: BLE001
            last = e
        time.sleep(2 ** i)
    raise RuntimeError(f"{url}: {last}")


def coordinates() -> list[tuple[str, str, str]]:
    out = subprocess.run(
        ["./gradlew", "-q", ":app:dependencies", "--configuration", "releaseRuntimeClasspath"],
        cwd=ROOT, check=True, capture_output=True, text=True).stdout
    found = set()
    for line in out.splitlines():
        if not re.match(r"^[\s|+\\\-]*[+\\]--- ", line):
            continue
        body = line.split("--- ", 1)[1].strip()
        if body.startswith("project "):
            continue
        body = re.sub(r" \((\*|c|n)\)$", "", body)
        m = COORD.match(body)
        if m:
            found.add(m.groups())
    return sorted(found)


def pom(group: str, artifact: str, version: str) -> ET.Element | None:
    path = f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.pom"
    for repo in REPOS:
        try:
            xml = http(f"{repo}/{path}").decode()
            return ET.fromstring(re.sub(r'\sxmlns="[^"]+"', "", xml, count=1))
        except urllib.error.HTTPError:
            continue
    return None


def is_bom(group: str, artifact: str, version: str) -> bool:
    """A constraints-only BOM POM ships no code and (for androidx) declares no license."""
    root = pom(group, artifact, version)
    return (artifact.endswith("-bom") and root is not None
            and (root.findtext("packaging") or "").strip() == "pom" and root.find("dependencies") is None)


def licenses(group: str, artifact: str, version: str, depth: int = 0) -> list[str]:
    root = pom(group, artifact, version)
    if root is None:
        return []
    names = [n.text.strip() for n in root.findall("./licenses/license/name") if n.text]
    if names or depth > 4:
        return names
    parent = root.find("parent")
    if parent is not None:
        g, a, v = (parent.findtext(k, "").strip() for k in ("groupId", "artifactId", "version"))
        return licenses(g, a, v, depth + 1)
    return []


def main() -> int:
    deps = coordinates()
    if len(deps) < 20:
        print(f"::error::dependency audit: only {len(deps)} coordinates parsed — resolver output changed?")
        return 1
    print(f"{len(deps)} runtime dependencies")

    waived = {}
    allow = Path(__file__).with_name("osv-allowlist.txt")
    if allow.exists():
        for line in allow.read_text().splitlines():
            if line.strip() and not line.startswith("#"):
                key, _, why = line.strip().partition(" ")
                waived[key] = why.strip()

    queries = [{"package": {"ecosystem": "Maven", "name": f"{g}:{a}"}, "version": v} for g, a, v in deps]
    results = []
    for i in range(0, len(queries), 100):
        body = json.dumps({"queries": queries[i:i + 100]}).encode()
        results += json.loads(http("https://api.osv.dev/v1/querybatch", body))["results"]
    errors, inventory = [], ["| Artifact | Version | License | Advisories |", "|---|---|---|---|"]
    for (g, a, v), res in zip(deps, results):
        ids = [x["id"] for x in res.get("vulns", [])]
        open_ids = [x for x in ids if x not in waived]
        lic = ["n/a (BOM: version constraints only, no code)"] if is_bom(g, a, v) else licenses(g, a, v)
        lic_ok = bool(lic) and lic[0].startswith("n/a (BOM") or bool(lic) and all(any(w in n.lower() for w in ALLOWED_LICENSE_WORDS) for n in lic)
        inventory.append(f"| {g}:{a} | {v} | {'; '.join(lic) or 'UNKNOWN'} | {', '.join(ids) or 'none'} |")
        if open_ids:
            errors.append(f"{g}:{a}:{v} has advisories {open_ids}")
        if not lic_ok:
            found = pom(g, a, v) is not None
            errors.append(f"{g}:{a}:{v} license not on the allowlist: {lic or 'UNKNOWN'} (pom found: {found})")
    Path("dependency-inventory.md").write_text("\n".join(inventory) + "\n")
    print("\n".join(inventory))
    # Also surface the inventory as an annotation so it is readable without downloading artifacts.
    print("::notice title=dependency inventory::" + "%0A".join(inventory))
    for e in errors:
        print(f"::error::dependency audit: {e}")
    if not errors:
        print("dependency audit: OK (no unwaived advisories; all licenses allowed)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
