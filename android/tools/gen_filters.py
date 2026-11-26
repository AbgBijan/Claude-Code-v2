#!/usr/bin/env python3
"""Convert uBlock Origin Lite's network rulesets into the app's compact filters.txt.

Usage: gen_filters.py <uBOL chromium/rulesets dir> <output filters.txt>

uBOL ships its default filter lists (uBlock filters, EasyList, EasyPrivacy,
Peter Lowe, uBlock badware, URLhaus) pre-compiled as declarativeNetRequest JSON.
This keeps the network rules a WebView browser can enforce and writes them as
tab-separated lines:

  d  <domain>                                    block domain + subdomains
  b|a|p  <urlFilter> <reqDomains> <exReqDomains> <initDomains> <exInitDomains> <party>
         b = block, a = allow (exception), p = allow everything on matching pages
         party: 0 any, 1 first-party only, 3 third-party only
"""
import json
import os
import sys

KEEP = {"urlFilter", "requestDomains", "excludedRequestDomains", "initiatorDomains",
        "excludedInitiatorDomains", "domainType", "resourceTypes", "excludedResourceTypes"}


def main(src, out):
    details = json.load(open(os.path.join(src, "ruleset-details.json"), encoding="utf-8"))
    ids = [d["id"] for d in details if d.get("enabled")]
    domains, lines, skipped = set(), [], 0
    for rid in ids:
        path = os.path.join(src, "main", rid + ".json")
        if not os.path.exists(path):
            continue
        for r in json.load(open(path, encoding="utf-8")):
            act, cond = r["action"]["type"], r["condition"]
            types = cond.get("resourceTypes")
            if act in ("block", "redirect"):
                kind = "b"
            elif act == "allow":
                kind = "a"
            elif act == "allowAllRequests":
                kind = "p"
            else:
                skipped += 1
                continue
            if set(cond) - KEEP or (cond.get("urlFilter") is None and not cond.get("requestDomains")):
                skipped += 1
                continue
            # The app never blocks top-level navigations, so drop rules that only target them.
            if kind != "p" and types and set(types) <= {"main_frame"}:
                skipped += 1
                continue
            party = {"firstParty": "1", "thirdParty": "3"}.get(cond.get("domainType"), "0")
            only_domains = set(cond) - {"resourceTypes"} == {"requestDomains"}
            if kind == "b" and only_domains:
                domains.update(d.lower() for d in cond["requestDomains"])
                continue
            uf = cond.get("urlFilter", "")
            if kind == "b" and party == "0" and set(cond) - {"resourceTypes"} == {"urlFilter"} \
                    and uf.startswith("||") and uf.rstrip("^")[2:].replace(".", "").replace("-", "").isalnum() \
                    and uf.count("^") <= 1 and (uf.endswith("^") or "^" not in uf) and "/" not in uf:
                domains.add(uf[2:].rstrip("^").lower())
                continue
            j = lambda k: ",".join(cond.get(k, []))
            lines.append("\t".join([kind, uf, j("requestDomains"), j("excludedRequestDomains"),
                                    j("initiatorDomains"), j("excludedInitiatorDomains"), party]))
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write("# Generated from uBlock Origin Lite rulesets: %s\n" % ", ".join(ids))
        for d in sorted(domains):
            f.write("d\t" + d + "\n")
        for line in sorted(set(lines)):
            f.write(line + "\n")
    print(f"{len(domains)} domains, {len(set(lines))} pattern rules, {skipped} skipped -> {out}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
