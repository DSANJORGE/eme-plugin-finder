#!/bin/sh
# Markdown table over one or more *.judged.jsonl files.
# Usage: tools/llmeval/report.sh results/*.judged.jsonl
set -eu
python3 - "$(dirname "$0")/prices.json" "$@" <<'PY'
import json, statistics, sys
prices = json.load(open(sys.argv[1]))
print('| run | server | n | pass | median ms | p95 ms | judge | cost USD |'); print('|---|---|---|---|---|---|---|---|')
for path in sys.argv[2:]:
    rows = [json.loads(l) for l in open(path) if l.strip()]
    if not rows: continue
    server = rows[0].get('aiserver', '?')
    ok = [r for r in rows if r.get('ok') and not r['checks']]
    ms = sorted(r['ms'] for r in rows if r.get('ms') is not None)
    p95 = ms[min(len(ms) - 1, int(len(ms) * 0.95))] if ms else 0
    scores = [r['score'] for r in rows if r.get('score') is not None]
    p = prices.get(server, {'input': 0, 'output': 0})
    cost = sum(((r.get('usage') or {}).get('prompt_tokens') or 0) * p['input'] + ((r.get('usage') or {}).get('completion_tokens') or 0) * p['output'] for r in rows) / 1e6
    print(f"| {path.split('/')[-1]} | {server} | {len(rows)} | {len(ok)}/{len(rows)} | {int(statistics.median(ms)) if ms else '-'} | {int(p95)} | {round(statistics.mean(scores), 2) if scores else '-'} | {cost:.4f} |")
PY
