#!/bin/sh
# Deterministic checks, then an LLM judge (function llm_eval_judge on JUDGE aiserver, default anthropic).
# Usage: tools/llmeval/judge.sh results/<run>.jsonl   -> writes results/<run>.judged.jsonl
set -eu
R=$1; JUDGE=${JUDGE:-anthropic}; B=${EME_BASE:-http://localhost:8080/site/mediadb}
J=$(mktemp); trap 'rm -f "$J"' EXIT
curl -sf -c "$J" -o /dev/null "$B/services/authentication/login.json" -H 'Content-Type: application/json' -d "{\"id\":\"admin\",\"password\":\"${EME_ADMIN_PW:-admin}\"}"
OUT="${R%.jsonl}.judged.jsonl"
python3 - "$R" "$JUDGE" "$B" "$J" > "$OUT" <<'PY'
import json, os, re, sys, urllib.request, urllib.parse
path, judge, base, jar = sys.argv[1:5]
cookie = '; '.join(f"{p[5]}={p[6]}" for p in (l.rstrip('\n').split('\t') for l in open(jar)) if len(p) == 7)
def checks(reply, exp):
    failed = []
    body = re.sub(r'^[ \t]*>>.*$', '', reply, flags=re.M)
    follow = re.findall(r'^[ \t]*>>', reply, flags=re.M)
    words = len(re.sub(r'\[[^\]]*\]', '', body).split())
    for c in exp.get('must_cite', []):
        if c not in reply: failed.append('must_cite:' + c)
    for s in exp.get('must_not_say', []):
        if s.lower() in reply.lower(): failed.append('must_not_say:' + s)
    if 'must_start_with' in exp and not body.strip().startswith(exp['must_start_with']): failed.append('must_start_with')
    if 'max_words' in exp and words > exp['max_words']: failed.append(f'max_words:{words}')
    if 'followups_min' in exp and len(follow) < exp['followups_min']: failed.append('followups_min')
    if 'followups_max' in exp and len(follow) > exp['followups_max']: failed.append('followups_max')
    return failed
for line in open(path):
    d = json.loads(line)
    reply = (d.get('payload') or {}).get('message') or d.get('message') or ''
    d['reply'] = reply
    d['checks'] = checks(reply, d.get('expected', {})) if d.get('ok') else ['no_reply']
    if d.get('ok'):
        judgeinput = {'question': d.get('question', ''), 'reference': d.get('reference', ''), 'reply': reply,
                      'rubric': d.get('rubric') or os.environ.get('JUDGE_RUBRIC', '')}
        form = {'function': 'llm_eval_judge', 'aiserver': judge, 'input': json.dumps(judgeinput)}
        req = urllib.request.Request(f"{base}/services/llm/evalcall.json", data=urllib.parse.urlencode(form).encode(), headers={'Cookie': cookie})
        try:
            j = json.load(urllib.request.urlopen(req, timeout=300)).get('payload') or {}
            d['score'] = round((j['correctness'] + j['groundedness'] + j['tone']) / 3, 2); d['reason'] = j['reason']
        except Exception as ex:
            d['score'] = None; d['reason'] = 'judge failed: ' + str(ex)
    print(json.dumps(d, ensure_ascii=False))
PY
echo "wrote $OUT"
