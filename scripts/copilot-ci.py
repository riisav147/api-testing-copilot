#!/usr/bin/env python3
"""Run explicitly reviewed saved test revisions against a local Copilot backend. No dependencies."""
import argparse
import json
from pathlib import Path
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ACTIVE = {"QUEUED", "RUNNING", "CANCELLING"}
SANDBOX = "bundled localhost sandbox"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Client:
    def __init__(self, base):
        url = urllib.parse.urlsplit(base)
        if (url.scheme != "http" or url.hostname != "127.0.0.1" or url.username or url.password
                or url.query or url.fragment or url.path not in ("", "/") or not url.port or not 1024 <= url.port <= 65535):
            raise ValueError("Backend must be an explicit http://127.0.0.1:port URL without credentials or a path")
        self.base = base.rstrip("/")
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(self, path, payload=None, timeout=15, raw=False):
        data = None if payload is None else json.dumps(payload).encode()
        req = urllib.request.Request(self.base + "/api" + path, data=data, headers={"Content-Type": "application/json"})
        try:
            with self.opener.open(req, timeout=timeout) as response:
                # Reports are bounded by 12 cases and the existing request/response capture limits.
                body = response.read(8 * 1024 * 1024 + 1)
                if len(body) > 8 * 1024 * 1024:
                    raise ValueError("Backend response exceeded the CLI capture limit")
                return body.decode("utf-8") if raw else json.loads(body)
        except urllib.error.HTTPError as error:
            # Avoid printing bodies, provider messages, request data or credentials in CI logs.
            raise RuntimeError(f"Backend returned HTTP {error.code} for {path.split('?')[0]}") from None
        except urllib.error.URLError:
            raise RuntimeError("Backend connection failed; no execution retry was made") from None


def load_config(path):
    config = json.loads(Path(path).read_text())
    if set(config) != {"planId", "environmentId", "cases", "parallelism", "independent"}:
        raise ValueError("Config must contain planId, environmentId, cases, parallelism and independent only")
    uuid.UUID(config["planId"])
    if config["environmentId"] is not None:
        uuid.UUID(config["environmentId"])
    cases = config["cases"]
    if not isinstance(cases, list) or not 1 <= len(cases) <= 12:
        raise ValueError("Select between 1 and 12 explicit revisions")
    seen = set()
    for case in cases:
        if (set(case) != {"scenarioIndex", "expectedRevision"}
                or type(case["scenarioIndex"]) is not int or case["scenarioIndex"] < 0
                or type(case["expectedRevision"]) is not int or case["expectedRevision"] < 1
                or case["scenarioIndex"] in seen):
            raise ValueError("Cases must have unique nonnegative scenarioIndex and positive expectedRevision integers")
        seen.add(case["scenarioIndex"])
    if type(config["parallelism"]) is not int or config["parallelism"] not in (1, 2) or type(config["independent"]) is not bool:
        raise ValueError("parallelism must be 1 or 2 and independent must be a boolean")
    return config


def reviewed_payload(client, config, authorized):
    plan = client.request("/plans/" + config["planId"])
    for case in config["cases"]:
        i = case["scenarioIndex"]
        if i >= len(plan["scenarioRevisions"]) or plan["scenarioRevisions"][i] != case["expectedRevision"]:
            raise ValueError("A selected revision changed; review the saved configuration again")
        if config["parallelism"] == 2 and (not config["independent"] or plan["scenarios"][i]["method"] not in ("GET", "HEAD")):
            raise ValueError("Parallel execution requires declared independent GET/HEAD cases")
    target, revision = SANDBOX, 0
    if config["environmentId"]:
        workspace = client.request("/specifications/" + plan["specificationId"] + "/workspace")
        environments = client.request("/projects/" + workspace["project"]["id"] + "/environments")
        env = next((e for e in environments if e["id"] == config["environmentId"]), None)
        if not env:
            raise ValueError("Environment does not belong to this plan's project")
        target, revision = env["baseUrl"], env["revision"]
    if (SANDBOX if authorized == "sandbox" else authorized) != target:
        raise ValueError("--authorize-target must equal sandbox or the exact saved environment base URL")
    return {**config, "expectedTarget": target, "expectedEnvironmentRevision": revision, "approved": True}


def retained_submission(directory, payload):
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / "submission.json"
    request = {"id": str(uuid.uuid4()), **payload}
    try:
        with path.open("x") as stream:
            json.dump(request, stream, indent=2)
    except FileExistsError:
        saved = json.loads(path.read_text())
        if {k: v for k, v in saved.items() if k != "id"} != payload:
            raise ValueError("Output directory belongs to another reviewed submission. Use a new directory for a deliberate new run")
        uuid.UUID(saved["id"])
        request = saved
    return request


def run(client, args):
    config = load_config(args.config)
    payload = reviewed_payload(client, config, args.authorize_target)
    directory = Path(args.output)
    submission = retained_submission(directory, payload)
    print(f"Submission {submission['id']}; retries with this output directory reuse the same ID", flush=True)
    # Exactly one POST. A later deliberate invocation may resubmit the SAME idempotent job ID.
    job = client.request("/batches", submission)
    deadline = time.monotonic() + args.timeout
    timed_out = False
    while job["state"] in ACTIVE:
        if time.monotonic() >= deadline:
            timed_out = True
            job = client.request("/batches/" + job["id"] + "/cancel", {})
            break
        time.sleep(min(0.25, max(0, deadline - time.monotonic())))
        job = client.request("/batches/" + job["id"], timeout=min(15, max(0.1, deadline - time.monotonic())))
    report = client.request("/batches/" + job["id"] + "/export?format=json")
    junit = client.request("/batches/" + job["id"] + "/export?format=junit", raw=True)
    (directory / "results.json").write_text(json.dumps(report, indent=2) + "\n")
    (directory / "junit.xml").write_text(junit)
    counts = {}
    for case in report["cases"]:
        counts[case["state"]] = counts.get(case["state"], 0) + 1
    print(f"Batch {report['id']}: {report['state']} {counts}; artifacts: {directory}")
    if timed_out:
        print("Deadline reached: queued cancellation requested; claimed requests may finish. Inspect the saved batch before a new run.", file=sys.stderr)
    return 0 if report["success"] and not timed_out else 1


def prepare_demo(client, path):
    if client.request("/info")["mode"] != "demo":
        raise ValueError("prepare-demo requires demo mode and never calls a model provider")
    destination = Path(path)
    if destination.exists():
        raise ValueError("Config already exists; use it or choose a new path")
    root = Path(__file__).resolve().parents[1]
    spec = client.request("/specifications", {"name": "CI synthetic catalog", "content": (root / "src/main/resources/static/examples/catalog.openapi.yaml").read_text()})
    plan = client.request("/plans", {"specificationId": spec["id"], "endpointKey": "POST /products"})
    config = {"planId": plan["id"], "environmentId": None, "cases": [{"scenarioIndex": i, "expectedRevision": r} for i, r in enumerate(plan["scenarioRevisions"])], "parallelism": 1, "independent": False}
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(config, indent=2) + "\n")
    print(f"Synthetic saved config: {destination}. Cases 1/2 pass; boundary case 3 deliberately fails in the sandbox.")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-url", default="http://127.0.0.1:8080")
    commands = parser.add_subparsers(dest="command", required=True)
    prepare = commands.add_parser("prepare-demo", help="Save synthetic fixtures/config; does not execute tests")
    prepare.add_argument("--config", required=True)
    execute = commands.add_parser("run", help="Run reviewed saved revisions; every selected case must pass for exit 0")
    execute.add_argument("--config", required=True)
    execute.add_argument("--authorize-target", required=True, help="sandbox or exact environment base URL")
    execute.add_argument("--output", required=True, help="Unique artifact directory; reuse only to resume the SAME submission")
    execute.add_argument("--timeout", type=float, default=120, help="Polling deadline in seconds; cancels queued work on expiry")
    args = parser.parse_args(argv)
    try:
        client = Client(args.server_url)
        if args.command == "prepare-demo":
            return prepare_demo(client, args.config)
        if not 0 < args.timeout <= 3600:
            raise ValueError("Timeout must be between 0 and 3600 seconds")
        return run(client, args)
    except Exception as error:
        # Fixed validation messages are safe. Do not print untrusted transport/JSON data or local secrets.
        message = str(error) if type(error) in (ValueError, RuntimeError) else "Configuration, transport or artifact operation failed"
        print(f"Copilot CI: {message}. If POST may have reached the server, retain submission.json and inspect that batch; never switch directories to retry an uncertain write.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
