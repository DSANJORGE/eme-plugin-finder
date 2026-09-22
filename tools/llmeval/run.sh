#!/bin/sh
# Runs every golden prompt against each named aiserver through services/llm/evalcall.json (no failover).
# Usage: tools/llmeval/run.sh <golden.jsonl> <aiserver-id>...
#   EME_BASE (default http://localhost:8080/site/mediadb), EME_ADMIN_PW (default admin), DRYRUN=1 renders only,
#   RUN_DELAY=<seconds> pause between calls (rate-limited providers).
# Output: <golden dir>/results/<YYYYmmdd-HHMM>-<aiserver>.jsonl
set -eu
G=$1; shift
[ $# -ge 1 ] || { echo "usage: run.sh golden.jsonl aiserver..."; exit 2; }
B=${EME_BASE:-http://localhost:8080/site/mediadb}
J=$(mktemp); trap 'rm -f "$J"' EXIT
curl -sf -c "$J" -o /dev/null "$B/services/authentication/login.json" -H 'Content-Type: application/json' -d "{\"id\":\"admin\",\"password\":\"${EME_ADMIN_PW:-admin}\"}"
OUTDIR=$(dirname "$G")/results; mkdir -p "$OUTDIR"; STAMP=$(date +%Y%m%d-%H%M)
for S in "$@"; do
  OUT="$OUTDIR/$STAMP-$S.jsonl"
  python3 - "$G" "$S" "$B" "$J" "${DRYRUN:-0}" > "$OUT" <<'PY'
import json, os, sys, time, urllib.request, urllib.parse
golden, server, base, jar, dry = sys.argv[1:6]
cookie = '; '.join(f"{p[5]}={p[6]}" for p in (l.rstrip('\n').split('\t') for l in open(jar)) if len(p) == 7)
for line in open(golden):
    line = line.strip()
    if not line: continue
    g = json.loads(line)
    form = {'function': g['function'], 'aiserver': server, 'input': json.dumps(g['input'])}
    if dry == '1': form['dryrun'] = 'true'
    time.sleep(float(os.environ.get('RUN_DELAY', '0')))
    req = urllib.request.Request(f"{base}/services/llm/evalcall.json", data=urllib.parse.urlencode(form).encode(), headers={'Cookie': cookie})
    try:
        d = json.load(urllib.request.urlopen(req, timeout=600))
    except Exception as ex:
        d = {'ok': False, 'error': str(ex), 'ms': None}
    # question/reference/rubric are optional top-level golden keys; they are what the judge grades against.
    d.update(id=g['id'], expected=g.get('expected', {}), question=g.get('question') or g['input'].get('query', ''),
             reference=g.get('reference', ''), rubric=g.get('rubric', ''))
    print(json.dumps(d, ensure_ascii=False)); sys.stdout.flush()
PY
  echo "wrote $OUT ($(wc -l < "$OUT") rows)"
done
