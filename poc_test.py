#!/usr/bin/env python3
"""AutoPoC Test Script for Auteur"""
import json, os, sys, time, urllib.request, urllib.error

BACKEND_URL = os.environ.get("BACKEND_URL", "http://auteur-backend.poc-auteur.svc.cluster.local:8082")
FRONTEND_URL = os.environ.get("FRONTEND_URL", "http://auteur-frontend.poc-auteur.svc.cluster.local:8080")
MAX_RETRIES = 5
RETRY_DELAY = 15
results = []

def test_scenario(name, description, base_url, method, path, body=None,
                  expected_status=200, expected_content=None, timeout=30):
    url = f"{base_url.rstrip('/')}{path}"
    start = time.time()
    for attempt in range(MAX_RETRIES):
        try:
            if body:
                data = json.dumps(body).encode() if isinstance(body, dict) else body.encode()
                req = urllib.request.Request(url, data=data, method=method)
                req.add_header("Content-Type", "application/json")
            else:
                req = urllib.request.Request(url, method=method)
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                status = resp.status
                response_body = resp.read().decode()
                if status == expected_status:
                    if expected_content and expected_content not in response_body:
                        r = {"scenario_name": name, "status": "fail",
                             "output": response_body[:2000],
                             "error_message": f"Expected '{expected_content}' not in response",
                             "duration_seconds": round(time.time()-start, 2)}
                    else:
                        r = {"scenario_name": name, "status": "pass",
                             "output": response_body[:2000], "error_message": None,
                             "duration_seconds": round(time.time()-start, 2)}
                    results.append(r); return r
                elif attempt < MAX_RETRIES - 1:
                    time.sleep(RETRY_DELAY); continue
                else:
                    r = {"scenario_name": name, "status": "fail",
                         "output": response_body[:2000],
                         "error_message": f"Expected {expected_status}, got {status}",
                         "duration_seconds": round(time.time()-start, 2)}
                    results.append(r); return r
        except urllib.error.HTTPError as e:
            if e.code == expected_status:
                response_body = e.read().decode() if e.fp else ""
                r = {"scenario_name": name, "status": "pass",
                     "output": response_body[:2000], "error_message": None,
                     "duration_seconds": round(time.time()-start, 2)}
                results.append(r); return r
            if attempt < MAX_RETRIES - 1:
                print(f"  Retry {attempt+1}/{MAX_RETRIES}: HTTP {e.code}", file=sys.stderr)
                time.sleep(RETRY_DELAY)
            else:
                r = {"scenario_name": name, "status": "fail", "output": "",
                     "error_message": f"HTTP {e.code} after {MAX_RETRIES} attempts",
                     "duration_seconds": round(time.time()-start, 2)}
                results.append(r); return r
        except urllib.error.URLError as e:
            if attempt < MAX_RETRIES - 1:
                print(f"  Retry {attempt+1}/{MAX_RETRIES}: {e}", file=sys.stderr)
                time.sleep(RETRY_DELAY)
            else:
                r = {"scenario_name": name, "status": "error", "output": "",
                     "error_message": f"Unreachable after {MAX_RETRIES} attempts: {e}",
                     "duration_seconds": round(time.time()-start, 2)}
                results.append(r); return r
        except Exception as e:
            r = {"scenario_name": name, "status": "error", "output": "",
                 "error_message": str(e),
                 "duration_seconds": round(time.time()-start, 2)}
            results.append(r); return r

# === SCENARIOS ===

# Scenario 1: Backend health check
print("Testing backend-health...", file=sys.stderr)
test_scenario(
    name="backend-health",
    description="Verify Spring Boot Actuator health endpoint",
    base_url=BACKEND_URL,
    method="GET",
    path="/actuator/health",
    expected_status=200,
    expected_content="UP",
    timeout=60
)

# Scenario 2: Frontend loads
print("Testing frontend-load...", file=sys.stderr)
test_scenario(
    name="frontend-load",
    description="Verify Vue 3 SPA loads via nginx",
    base_url=FRONTEND_URL,
    method="GET",
    path="/",
    expected_status=200,
    expected_content="<html"
)

# Scenario 3: API proxy through frontend
print("Testing api-proxy...", file=sys.stderr)
test_scenario(
    name="api-proxy",
    description="Verify nginx proxies /api to backend",
    base_url=FRONTEND_URL,
    method="GET",
    path="/api/actuator/health",
    expected_status=200,
    expected_content="UP"
)

# === END SCENARIOS ===

print(json.dumps({"results": results}, indent=2))
sys.exit(1 if any(r["status"] in ("fail", "error") for r in results) else 0)
