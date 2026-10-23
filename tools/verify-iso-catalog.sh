#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
CATALOG="${ROOT_DIR}/app/src/main/assets/iso-catalog.json"
CHECK_NETWORK=0

usage() {
    cat <<'EOF'
Usage: tools/verify-iso-catalog.sh [--catalog PATH] [--network]

Validate the bundled Linux ISO catalog structurally. With --network, also request
every distribution and variant URL using a browser-like user agent. Some official
hosts block automated requests with 403; these are reported as warnings.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --catalog)
            CATALOG="$2"
            shift 2
            ;;
        --network)
            CHECK_NETWORK=1
            shift
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

python3 - "$CATALOG" "$CHECK_NETWORK" <<'PY'
import json
import re
import socket
import sys
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

catalog_path = sys.argv[1]
check_network = sys.argv[2] == "1"

try:
    with open(catalog_path, encoding="utf-8") as f:
        catalog = json.load(f)
except (OSError, json.JSONDecodeError) as error:
    raise SystemExit(f"Failed to load catalog: {error}")

if catalog.get("schemaVersion") != 2:
    raise SystemExit("schemaVersion must be 2")
categories = catalog.get("categories")
distributions = catalog.get("distributions")
if not isinstance(categories, list) or not categories:
    raise SystemExit("categories must be a non-empty array")
if not isinstance(distributions, list) or not distributions:
    raise SystemExit("distributions must be a non-empty array")

category_ids = set()
for category in categories:
    if not isinstance(category, dict) or not (category.get("id") or "").strip():
        raise SystemExit("Every category needs a non-empty id")
    category_ids.add(category["id"])
    for field in ("name", "description"):
        if not isinstance(category.get(field), str) or not category[field].strip():
            raise SystemExit(f"Category {category.get('id')} is missing {field}")

distribution_ids = set()
variant_ids = set()
distribution_count = 0
variant_count = 0

def safe_url(url, require_iso=False):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https":
        return False
    if parsed.username or parsed.password:
        return False
    if parsed.port not in (None, 443):
        return False
    if not parsed.hostname or not parsed.hostname.isascii():
        return False
    if require_iso and not parsed.path.lower().endswith(".iso"):
        return False
    return True

def host(url):
    return (urllib.parse.urlsplit(url).hostname or "").lower()

for distribution in distributions:
    if not isinstance(distribution, dict):
        raise SystemExit("Distribution entries must be objects")
    dist_id = distribution.get("id")
    if not isinstance(dist_id, str) or not dist_id.strip():
        raise SystemExit("Every distribution needs a non-empty id")
    if dist_id in distribution_ids:
        raise SystemExit(f"Duplicate distribution id: {dist_id}")
    distribution_ids.add(dist_id)
    for field in ("name", "categoryId", "description", "homepageUrl", "licenseUrl"):
        if not isinstance(distribution.get(field), str) or not distribution[field].strip():
            raise SystemExit(f"Distribution {dist_id} is missing {field}")
    if not isinstance(distribution.get("architectures"), list) or not distribution["architectures"]:
        raise SystemExit(f"Distribution {dist_id} has invalid architectures")
    if any(not isinstance(x, str) or not x for x in distribution["architectures"]):
        raise SystemExit(f"Distribution {dist_id} has an invalid architecture")
    if distribution["categoryId"] not in category_ids:
        raise SystemExit(f"Distribution {dist_id} references unknown category {distribution['categoryId']}")
    if not safe_url(distribution["homepageUrl"]):
        raise SystemExit(f"Distribution {dist_id} has an unsafe homepage URL")
    if not safe_url(distribution["licenseUrl"]):
        raise SystemExit(f"Distribution {dist_id} has an unsafe license URL")
    release_notes = distribution.get("releaseNotesUrl")
    if release_notes is not None and (
        not isinstance(release_notes, str)
        or not release_notes.strip()
        or not safe_url(release_notes)
    ):
        raise SystemExit(f"Distribution {dist_id} has an unsafe release-notes URL")
    hosts = distribution.get("hosts")
    if not isinstance(hosts, list) or not hosts:
        raise SystemExit(f"Distribution {dist_id} needs a hosts allowlist")
    hosts = [str(h).strip().lower() for h in hosts]
    if any(not h or "." not in h for h in hosts):
        raise SystemExit(f"Distribution {dist_id} has an invalid host")
    variants = distribution.get("variants")
    if not isinstance(variants, list) or not variants:
        raise SystemExit(f"Distribution {dist_id} has no variants")
    distribution_count += 1
    variant_count += len(variants)

    for variant in variants:
        if not isinstance(variant, dict):
            raise SystemExit(f"Variant in {dist_id} is not an object")
        variant_id = variant.get("id")
        if not isinstance(variant_id, str) or not variant_id.strip():
            raise SystemExit(f"Variant in {dist_id} is missing id")
        if variant_id in variant_ids:
            raise SystemExit(f"Duplicate variant id: {variant_id}")
        variant_ids.add(variant_id)
        for field in ("variantName", "description", "imageType", "release", "releaseChannel",
                      "arch", "fileName", "url", "checksumFormat", "approxSizeBytes"):
            if field not in variant:
                raise SystemExit(f"Variant {variant_id} is missing {field}")
        if not isinstance(variant.get("url"), str) or not safe_url(variant["url"], require_iso=True):
            raise SystemExit(f"Variant {variant_id} has an unsafe ISO URL")
        if host(variant["url"]) not in hosts:
            raise SystemExit(f"Variant {variant_id} uses unlisted host {host(variant['url'])}")
        checksum_url = variant.get("checksumUrl")
        if checksum_url is not None:
            if not isinstance(checksum_url, str):
                raise SystemExit(f"Variant {variant_id} has a bad checksum URL")
            checksum_url = checksum_url.strip()
            if checksum_url:
                if not safe_url(checksum_url) or host(checksum_url) not in hosts:
                    raise SystemExit(f"Variant {variant_id} has an unsafe or unlisted checksum URL")
        static_sha = variant.get("sha256")
        if static_sha is not None:
            if not isinstance(static_sha, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", static_sha.strip()):
                raise SystemExit(f"Variant {variant_id} has an invalid sha256")
        if not isinstance(variant["approxSizeBytes"], int) or variant["approxSizeBytes"] <= 0:
            raise SystemExit(f"Variant {variant_id} has no valid size")
        if not str(variant["fileName"]).lower().endswith(".iso"):
            raise SystemExit(f"Variant {variant_id} has a non-ISO fileName")
        persistence = variant.get("persistence")
        if not isinstance(persistence, dict):
            raise SystemExit(f"Variant {variant_id} is missing persistence")
        if persistence.get("support") not in ("app", "distro", "none"):
            raise SystemExit(f"Variant {variant_id} has invalid persistence support")
        if persistence["support"] == "app" and not (persistence.get("profiles") or []):
            raise SystemExit(f"Variant {variant_id} app persistence needs profiles")
        notes = persistence.get("notes")
        if not isinstance(notes, str) or not notes.strip():
            raise SystemExit(f"Variant {variant_id} is missing persistence notes")

print(f"Structural validation passed: {len(categories)} categories, "
      f"{distribution_count} distributions, {variant_count} variants.")

if check_network:
    urls = []
    for distribution in distributions:
        urls.append(("distribution", distribution["id"], distribution["homepageUrl"]))
        urls.append(("distribution", distribution["id"], distribution["licenseUrl"]))
        if distribution.get("releaseNotesUrl"):
            urls.append(("distribution", distribution["id"], distribution["releaseNotesUrl"]))
        for variant in distribution.get("variants", []):
            urls.append(("variant", variant["id"], variant["url"]))
            if variant.get("checksumUrl"):
                urls.append(("variant", variant["id"], variant["checksumUrl"].strip()))

    user_agent = ""

    def check(item):
        kind, item_id, url = item
        try:
            headers = {"Range": "bytes=0-0"}
            if user_agent:
                headers["User-Agent"] = user_agent
            request = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(request, timeout=20) as response:
                return kind, item_id, url, response.status
        except urllib.error.HTTPError as error:
            return kind, item_id, url, error.code
        except (urllib.error.URLError, TimeoutError, socket.timeout) as error:
            return kind, item_id, url, str(error)
        except Exception as error:
            return kind, item_id, url, str(error)

    with ThreadPoolExecutor(max_workers=6) as pool:
        results = list(pool.map(check, urls))

    failures = []
    warnings = []
    for kind, item_id, url, status in results:
        if isinstance(status, int) and (status == 403 or status == 429):
            warnings.append((kind, item_id, url, status))
        elif not (isinstance(status, int) and 200 <= status < 300):
            failures.append((kind, item_id, url, status))
    for kind, item_id, url, status in warnings:
        print(f"WARNING {status}: {kind}:{item_id} -> {url}")
    for kind, item_id, url, status in failures:
        print(f"FAILURE {status}: {kind}:{item_id} -> {url}")
    if failures:
        raise SystemExit(f"Network validation failed for {len(failures)} URL(s).")
    print(f"Network validation completed for {len(results)} URL(s) "
          f"({len(warnings)} warning(s)).")
PY
chmod +x tools/verify-iso-catalog.sh