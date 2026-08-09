#!/usr/bin/env python3
"""Client for the device lock service, used by the device-tests workflows.

This exists so the acquire/release logic lives in one testable place instead
of being duplicated as shell in every workflow. It also removes the need for
bash on the Windows runner: the steps used `defaults.run.shell: bash` purely
so one POSIX script could serve both jobs, which made Git for Windows a hard
dependency of CI. Python is already required on both guests, so this needs
strictly less than the shell version did.

Standard library only -- no pip install on a runner that holds a physical
device.

Usage:
    python ci/devicelock.py acquire --target windows --out lease.json
    python ci/devicelock.py release --lease lease.json

LOCKSERVICE_URL must be set. Everything else has a sensible default.
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

# /acquire blocks internally for ~25s before giving up with 202, so each
# attempt is already slow. 20 tries with a 10s gap is a little over 11 minutes
# of contention tolerance, comfortably more than one other repo's run.
MAX_ATTEMPTS = 20
RETRY_SLEEP_S = 10
HTTP_TIMEOUT_S = 60


def _url(path):
    base = os.environ.get("LOCKSERVICE_URL", "").rstrip("/")
    if not base:
        sys.exit("LOCKSERVICE_URL is not set. It should be an organization "
                 "variable, e.g. http://192.168.122.1:8246")
    return base + path


def _post(path, payload):
    """POST JSON. Returns (status, parsed_body_or_raw_text)."""
    req = urllib.request.Request(
        _url(path),
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT_S) as resp:
            raw = resp.read().decode()
            status = resp.status
    except urllib.error.HTTPError as e:
        # 4xx/5xx still carry a JSON body worth showing the user.
        raw = e.read().decode()
        status = e.code
    except urllib.error.URLError as e:
        sys.exit(f"cannot reach the lock service at {_url(path)}: {e.reason}")
    try:
        return status, json.loads(raw)
    except json.JSONDecodeError:
        return status, raw


def acquire(args):
    payload = {
        "target": args.target,
        "repo": os.environ.get("GITHUB_REPOSITORY", "unknown"),
        "run_id": os.environ.get("GITHUB_RUN_ID", "local"),
        "requested_by": args.requested_by,
        "lease_ttl_seconds": args.ttl,
    }

    for attempt in range(1, MAX_ATTEMPTS + 1):
        status, body = _post("/acquire", payload)

        if status == 200 and isinstance(body, dict) and body.get("lease_id"):
            with open(args.out, "w") as f:
                json.dump(body, f)
            print(f"acquired {body['lease_id']} for {body['target']}, "
                  f"expires {body.get('expires_at')}")
            return

        # 202 is backpressure, not an error: the device is held, most likely by
        # another repository. GitHub concurrency groups do not span repos, so
        # the lock service is the only cross-repo mutex and this is its normal
        # busy signal. Note 202 is a 2xx -- `curl -f` treated it as success,
        # which is how a job could once run with no device attached.
        if status == 202:
            print(f"device busy, attempt {attempt}/{MAX_ATTEMPTS}", flush=True)
            time.sleep(RETRY_SLEEP_S)
            continue

        # 503 means the lock was granted but the hotplug failed, and the
        # service has already handed the lock back. Retrying immediately would
        # just fail the same way, so surface it.
        sys.exit(f"acquire failed: HTTP {status}\n{json.dumps(body, indent=2)}")

    sys.exit(f"never acquired the device after {MAX_ATTEMPTS} attempts")


def release(args):
    # Called from `if: always()`, so it also runs when acquire failed and there
    # is nothing to hand back. Exiting 0 keeps the real failure visible instead
    # of burying it under an error from this step.
    if not os.path.exists(args.lease):
        print("no lease file, nothing to release")
        return

    with open(args.lease) as f:
        try:
            lease = json.load(f)
        except json.JSONDecodeError:
            print("lease file is not JSON, nothing to release")
            return

    lease_id = lease.get("lease_id")
    if not lease_id:
        print("no lease_id in lease file, nothing to release")
        return

    status, body = _post("/release", {"lease_id": lease_id})

    # /release deliberately returns 200 with a detach_error field when the
    # detach fails, because CI calls it from `if: always()` and a non-2xx here
    # would mask the real test failure. So a 200 is not automatically success:
    # the field has to be checked, and it is loud but non-fatal.
    if isinstance(body, dict) and body.get("detach_error"):
        print(f"::warning::released the lock but the detach FAILED: "
              f"{body['detach_error']}. The device may still be attached to a "
              f"VM; the next acquire will 503 until it is detached by hand.")
        return

    if status != 200:
        print(f"::warning::release returned HTTP {status}: {body}")
        return

    print(f"released {lease_id}")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest="cmd", required=True)

    a = sub.add_parser("acquire")
    a.add_argument("--target", required=True, choices=["windows", "linux", "macos"])
    a.add_argument("--ttl", type=int, default=900)
    a.add_argument("--out", default="lease.json")
    a.add_argument("--requested-by", default="device-tests")
    a.set_defaults(func=acquire)

    r = sub.add_parser("release")
    r.add_argument("--lease", default="lease.json")
    r.set_defaults(func=release)

    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
