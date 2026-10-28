#!/usr/bin/env python3
"""Explicit live smoke check. Uses synthetic sample data and incurs provider usage."""
import argparse
import json
from pathlib import Path
import sys
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--report", default=".tools/live-ai-report.json")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    report = {"checks": []}

    def request(path, body=None, expected=200):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(args.base_url + path, data=data,
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=180) as response:
                status, result = response.status, json.load(response)
        except urllib.error.HTTPError as error:
            status, result = error.code, json.load(error)
        if status != expected:
            raise RuntimeError(f"{path}: expected HTTP {expected}, observed {status}: {result.get('detail', 'request failed')}")
        return result

    try:
        info = request("/api/info")
        if info["mode"] != "ai":
            raise RuntimeError("Start the backend in AI mode before running this check")
        report["checks"].append("ai-profile")
        spec = request("/api/specifications", {
            "name": "Live smoke catalog",
            "content": (root / "src/main/resources/static/examples/catalog.openapi.yaml").read_text(),
            "documentation": "Product 1 exists. Product 999999 does not exist. Price zero is permitted by the contract.",
        })
        report["checks"].append("real-embedding-ingestion")
        plan = request("/api/plans", {"specificationId": spec["id"], "endpointKey": "POST /products"})
        if plan["mode"] != "ai" or not plan["scenarios"]:
            raise RuntimeError("No AI scenarios returned")
        if not any(not source["id"].startswith("contract:") for source in plan["evidence"]):
            raise RuntimeError("No evidence returned from pgvector")
        report["checks"].append("real-retrieval-and-validated-generation")
        report["scenarioCount"] = len(plan["scenarios"])
        report["evidenceCount"] = len(plan["evidence"])
        request("/api/executions", {"planId": plan["id"], "scenarioIndex": 0, "approved": False}, expected=400)
        report["checks"].append("unapproved-execution-rejected")
        index = next((i for i, s in enumerate(plan["scenarios"]) if s["category"] == "POSITIVE"), 0)
        execution = request("/api/executions", {"planId": plan["id"], "scenarioIndex": index, "approved": True})
        if execution["planId"] != plan["id"] or execution["scenarioIndex"] != index:
            raise RuntimeError("Execution is not associated with the approved scenario")
        if execution["passed"] != (execution["actualStatus"] == execution["expectedStatus"]):
            raise RuntimeError("Status comparison is inconsistent")
        report["checks"].append("live-model-tool-execution")
        report["execution"] = {key: execution[key] for key in ("expectedStatus", "actualStatus", "passed")}
        analysis = request(f"/api/executions/{execution['id']}/analysis", {})
        if analysis["mode"] != "ai" or not analysis["explanation"]:
            raise RuntimeError("No AI analysis returned")
        report["checks"].append("live-model-analysis")
        report["success"] = True
    except Exception as error:
        report["success"] = False
        report["error"] = str(error)
    path = Path(args.report)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    return 0 if report["success"] else 1


if __name__ == "__main__":
    sys.exit(main())
