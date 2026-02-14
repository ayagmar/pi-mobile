# Spike: RPC transport and cwd assumptions

Date: 2026-02-14

## Goal

Validate two assumptions before implementing bridge/client logic:

1. `pi --mode rpc` uses JSON Lines over stdio and responses/events can interleave.
2. `switch_session` changes session context, but tool execution cwd remains tied to process cwd.

---

## Environment

- `pi` binary: `0.52.12`
- Host OS: Linux
- Command path: `/home/ayagmar/.fnm/aliases/default/bin/pi`

---

## Experiment 1: JSONL behavior + response correlation

### Command

```bash
python3 - <<'PY'
import json, subprocess

proc = subprocess.Popen(
    ['pi', '--mode', 'rpc', '--no-session'],
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    text=True,
    bufsize=1,
)

expected = {'one': None, 'two': None, 'parse': None}
observed = []

try:
    proc.stdin.write(json.dumps({'id': 'one', 'type': 'get_state'}) + '\n')
    proc.stdin.write('{ this-is-not-json\n')
    proc.stdin.write(json.dumps({'id': 'two', 'type': 'get_messages'}) + '\n')
    proc.stdin.flush()

    while any(value is None for value in expected.values()):
        line = proc.stdout.readline()
        if not line:
            raise RuntimeError('EOF while waiting for responses')
        obj = json.loads(line)
        observed.append(obj)
        if obj.get('type') != 'response':
            continue
        if obj.get('command') == 'parse':
            expected['parse'] = obj
        elif obj.get('id') == 'one':
            expected['one'] = obj
        elif obj.get('id') == 'two':
            expected['two'] = obj

    summary = {
        'observedTypesInOrder': [f"{o.get('type')}:{o.get('command', o.get('method', ''))}" for o in observed],
        'responseOneCommand': expected['one']['command'],
        'responseTwoCommand': expected['two']['command'],
        'parseErrorContains': 'Failed to parse command' in expected['parse']['error'],
        'responseOneHasSameId': expected['one']['id'] == 'one',
        'responseTwoHasSameId': expected['two']['id'] == 'two',
    }
    print(json.dumps(summary, indent=2))
finally:
    proc.terminate()
    try:
        proc.wait(timeout=2)
    except subprocess.TimeoutExpired:
        proc.kill()
PY
```

### Observed output

```json
{
  "observedTypesInOrder": [
    "extension_ui_request:setStatus",
    "response:parse",
    "response:get_state",
    "response:get_messages"
  ],
  "responseOneCommand": "get_state",
  "responseTwoCommand": "get_messages",
  "parseErrorContains": true,
  "responseOneHasSameId": true,
  "responseTwoHasSameId": true
}
```

### Conclusion

- Transport is JSON Lines over stdio (one JSON object per line).
- Non-response events (e.g. `extension_ui_request`) can appear between command responses.
- Clients must correlate responses by `id` (not by "next line" ordering).

---

## Experiment 2: `switch_session` vs process cwd

### Command

```bash
python3 - <<'PY'
import json, pathlib, subprocess, shutil

base = pathlib.Path('/tmp/pi-rpc-cwd-spike')
if base.exists():
    shutil.rmtree(base)
(base / 'a').mkdir(parents=True)
(base / 'b').mkdir(parents=True)
(base / 'sessions').mkdir(parents=True)
(base / 'a' / 'marker.txt').write_text('from-a\n', encoding='utf-8')
(base / 'b' / 'marker.txt').write_text('from-b\n', encoding='utf-8')


def start(cwd: pathlib.Path) -> subprocess.Popen:
    return subprocess.Popen(
        ['pi', '--mode', 'rpc', '--session-dir', str(base / 'sessions')],
        cwd=str(cwd),
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=1,
    )


def recv_response(proc: subprocess.Popen, request_id: str, event_log: list[dict]):
    while True:
        line = proc.stdout.readline()
        if not line:
            raise RuntimeError(f'EOF while waiting for response {request_id}')
        obj = json.loads(line)
        if obj.get('type') == 'response' and obj.get('id') == request_id:
            return obj
        event_log.append(obj)


def send(proc: subprocess.Popen, request: dict, event_log: list[dict]):
    proc.stdin.write(json.dumps(request) + '\n')
    proc.stdin.flush()
    return recv_response(proc, request['id'], event_log)


a_events = []
b_events = []
pa = start(base / 'a')
pb = start(base / 'b')

try:
    a_state = send(pa, {'id': 'a-state', 'type': 'get_state'}, a_events)
    b_state = send(pb, {'id': 'b-state', 'type': 'get_state'}, b_events)

    a_pwd_before = send(pa, {'id': 'a-pwd-before', 'type': 'bash', 'command': 'pwd'}, a_events)
    a_marker_before = send(pa, {'id': 'a-marker-before', 'type': 'bash', 'command': 'cat marker.txt'}, a_events)

    b_pwd = send(pb, {'id': 'b-pwd', 'type': 'bash', 'command': 'pwd'}, b_events)
    b_marker = send(pb, {'id': 'b-marker', 'type': 'bash', 'command': 'cat marker.txt'}, b_events)

    switch_resp = send(
        pa,
        {
            'id': 'switch',
            'type': 'switch_session',
            'sessionPath': b_state['data']['sessionFile'],
        },
        a_events,
    )
    a_state_after = send(pa, {'id': 'a-state-after', 'type': 'get_state'}, a_events)
    a_pwd_after = send(pa, {'id': 'a-pwd-after', 'type': 'bash', 'command': 'pwd'}, a_events)
    a_marker_after = send(pa, {'id': 'a-marker-after', 'type': 'bash', 'command': 'cat marker.txt'}, a_events)

    summary = {
        'sessionA': a_state['data']['sessionFile'],
        'sessionB': b_state['data']['sessionFile'],
        'aPwdBefore': a_pwd_before['data']['output'].strip(),
        'aMarkerBefore': a_marker_before['data']['output'].strip(),
        'bPwd': b_pwd['data']['output'].strip(),
        'bMarker': b_marker['data']['output'].strip(),
        'switchSuccess': switch_resp['success'],
        'stateAfterSessionFile': a_state_after['data']['sessionFile'],
        'aPwdAfterSwitch': a_pwd_after['data']['output'].strip(),
        'aMarkerAfterSwitch': a_marker_after['data']['output'].strip(),
        'aInterleavedEventCount': len(a_events),
        'bInterleavedEventCount': len(b_events),
    }

    print(json.dumps(summary, indent=2))
finally:
    for proc in (pa, pb):
        proc.terminate()
        try:
            proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            proc.kill()
PY
```

### Observed output

```json
{
  "sessionA": "/tmp/pi-rpc-cwd-spike/sessions/2026-02-14T18-32-52-533Z_2c98acaf-c37d-4678-bed7-bdac9eef1937.jsonl",
  "sessionB": "/tmp/pi-rpc-cwd-spike/sessions/2026-02-14T18-32-52-435Z_fe4f0bfc-3bf8-43f2-890a-9e360456c2a5.jsonl",
  "aPwdBefore": "/tmp/pi-rpc-cwd-spike/a",
  "aMarkerBefore": "from-a",
  "bPwd": "/tmp/pi-rpc-cwd-spike/b",
  "bMarker": "from-b",
  "switchSuccess": true,
  "stateAfterSessionFile": "/tmp/pi-rpc-cwd-spike/sessions/2026-02-14T18-32-52-435Z_fe4f0bfc-3bf8-43f2-890a-9e360456c2a5.jsonl",
  "aPwdAfterSwitch": "/tmp/pi-rpc-cwd-spike/a",
  "aMarkerAfterSwitch": "from-a",
  "aInterleavedEventCount": 1,
  "bInterleavedEventCount": 1
}
```

### Conclusion

- `switch_session` successfully changes the loaded session file.
- However, tool execution cwd (`bash pwd`, relative file reads) remains the process startup cwd.
- Therefore, multi-project correctness requires **one pi RPC process per cwd** on the bridge side.

---

## Final decision impact

Confirmed architecture constraints for implementation:

1. Bridge/client protocol handling must support interleaved events and id-based response matching.
2. Bridge must maintain per-cwd process management and route session operations through the process matching session cwd.
