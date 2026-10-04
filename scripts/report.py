"""Produce a standalone English report from measured CSVs; never invent a missing run."""
import csv
import hashlib
import html
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

source = Path(sys.argv[1]).resolve()
files = sorted(source.glob('fork-*.csv'))
if len(files) < 3:
    raise SystemExit('At least three complete JVM forks required')
groups = defaultdict(list)
rows = []
for f in files:
    data = list(csv.DictReader(f.open(encoding='utf-8')))
    if len(data) != 72:
        raise SystemExit(f'Incomplete fork: {f.name}; expected 72 rows, got {len(data)}')
    seen = set()
    for row in data:
        key = (row['mode'], row['implementation'], row['round'])
        if key in seen:
            raise SystemExit('Duplicate result row')
        seen.add(key)
        row['fork'] = f.stem
        groups[(row['mode'], row['implementation'])].append(row)
        rows.append(row)
env = json.loads((source/'environment.json').read_text(encoding='utf-8-sig'))
summary = []
for (mode, impl), samples in sorted(groups.items()):
    def values(key):
        return [float(s[key]) for s in samples]
    summary.append(dict(mode=mode, implementation=impl, runs=len(samples),
        p50=statistics.median(values('p50_ns')), p95=statistics.median(values('p95_ns')),
        p99=statistics.median(values('p99_ns')), p99_min=min(values('p99_ns')), p99_max=max(values('p99_ns')),
        maximum=max(values('max_ns')), throughput=statistics.median(values('events_per_second')),
        allocation=statistics.median(values('consumer_bytes_per_event')),
        gc=sum(values('gc_collections')), blocked=sum(values('backpressure_events'))))
service = {s['implementation']: s for s in summary if s['mode']=='service'}
ratio = service['reference']['p99']/service['array']['p99']
esc = lambda v: html.escape(str(v))
fmt = lambda v: f'{v:,.2f}'
result_dir=source.parent.parent
result_dir.mkdir(exist_ok=True)
data = dict(environment=env, input_hashes={f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in files},
            summary=summary, measurements=rows)
(result_dir/'results.json').write_text(json.dumps(data,indent=2),encoding='utf-8')

def table(items):
    return '<div class="scroll"><table><thead><tr><th>Implementation</th><th>P50 µs</th><th>P95 µs</th><th>P99 µs</th><th>P99 range µs</th><th>Worst sample µs</th><th>Events/sec</th><th>Consumer bytes/event</th></tr></thead><tbody>'+''.join(
        f'<tr><th>{esc(s["implementation"])}</th><td>{fmt(s["p50"]/1000)}</td><td>{fmt(s["p95"]/1000)}</td><td>{fmt(s["p99"]/1000)}</td><td>{fmt(s["p99_min"]/1000)}–{fmt(s["p99_max"]/1000)}</td><td>{fmt(s["maximum"]/1000)}</td><td>{s["throughput"]:,.0f}</td><td>{format(s["allocation"], ".3f") if s["allocation"]>=0 else "Not sampled"}</td></tr>' for s in items)+'</tbody></table></div>'

sections=''
for mode in sorted({s['mode'] for s in summary}):
    selected=[s for s in summary if s['mode']==mode]
    sections+=f'<section class="measurement" data-mode="{esc(mode)}"><h2>{esc(mode.replace("_"," "))}</h2>{table(selected)}</section>'
all_rows=''.join(f'<tr><td>{esc(r["fork"])}</td><td>{esc(r["mode"])}</td><td>{esc(r["implementation"])}</td><td>{r["round"]}</td><td>{r["p99_ns"]}</td><td>{r["max_ns"]}</td><td>{r["backpressure_events"]}</td></tr>' for r in rows)
page='''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Event Ledger — Performance Evidence</title>
<style>:root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;background:#10151b;color:#e8edf2;font:16px/1.65 system-ui,sans-serif}main{max-width:1180px;margin:auto;padding:44px 26px}h1{font-size:clamp(32px,5vw,58px);line-height:1.1}h2{font-size:23px}p{max-width:900px;color:#c0cbd6}a{color:#83d8bd}.eyebrow{color:#83d8bd;letter-spacing:.12em;font-size:12px}.metrics{display:flex;gap:18px;flex-wrap:wrap;margin:32px 0}.metric{border-top:2px solid #83d8bd;padding:15px 24px 10px 0;flex:1;min-width:210px}.metric strong{display:block;font-size:32px}.metric span{font-size:13px;color:#b6c2ce}section{margin:38px 0;border-top:1px solid #34414d;padding-top:12px}.scroll{overflow-x:auto}table{border-collapse:collapse;width:100%;font-size:13px;text-align:left}th,td{padding:12px 10px;border-bottom:1px solid #34414d;white-space:nowrap}th{color:#83d8bd}pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px;background:#17212b;padding:20px}select{font:inherit;padding:8px;background:#17212b;color:white;border:1px solid #607586}summary{cursor:pointer;color:#83d8bd}li{margin:10px 0}footer{margin-top:40px;color:#98a9b8}</style>
<main><p class="eyebrow">JAVA 21 / REPRODUCIBLE SYSTEMS EXPERIMENT</p><h1>Event Ledger</h1><p><a href="https://github.com/BigFiiish/event-ledger">Public source on GitHub</a> · <a href="https://www.xingjiyan.com/work/event-ledger/">Engineering case study</a></p><p>Correct events. Explicit timing boundaries. Measured tradeoffs.</p><p>A single-instrument order-book prototype comparing a boxed map baseline with bounded arrays. This report contains measured synthetic workloads on one Windows machine. It is not exchange, network, or live-trading performance.</p>'''
page+=f'<div class="metrics"><div class="metric"><strong>{len(files)} JVM forks</strong><span>Four rounds per implementation per fork</span></div><div class="metric"><strong>{fmt(ratio)}×</strong><span>Reference / array median round P99, service only; descriptive ratio, not a confidence estimate</span></div><div class="metric"><strong>{fmt(service["array"]["p99"]/1000)} µs</strong><span>Array median round P99; apply + best prices + sink</span></div></div>'
page+='''<section><h2>Read the evidence</h2><ol><li><b>Correctness first.</b> Every measured replay checks its final order-state digest against the reference. JUnit separately checks semantics, state invariants, random streams and concurrent delivery.</li><li><b>Service is not end-to-end latency.</b> Scheduled-to-done includes producer scheduling lag, backpressure, queue wait and processing. Arrival deadlines never reset to hide backlog.</li><li><b>All rounds remain visible.</b> Tables show medians of per-round percentiles, their observed ranges and the worst individual sample. They are not pooled percentiles.</li><li><b>Allocation boundaries matter.</b> Consumer bytes exclude event generation, book initialization, producer envelopes and retained memory. A zero does not mean the whole application allocates nothing.</li></ol></section>
<label for="mode">Inspect a measurement </label><select id="mode"><option value="all">All measurements</option>'''
page+=''.join(f'<option value="{esc(m)}">{esc(m.replace("_"," "))}</option>' for m in sorted({s['mode'] for s in summary}))+'</select>'+sections
page+='''<section><h2>Tradeoffs and limits</h2><p>The array book requires dense IDs and a bounded tick range, trading fixed memory for lower per-event allocation. The reference implementation accepts the same bounds for a comparable experiment. Neither is a matching engine. The queue is a standard bounded ArrayBlockingQueue, not a custom lock-free structure.</p><p>Desktop scheduling, clock instrumentation and a volatile result sink affect the results. No pinned cores, Linux hardware counters, exchange feed, kernel bypass or wire-to-wire timestamps are included. Repeat on controlled hardware; do not put these figures on a resume without the workload and measurement boundary.</p></section>'''
page+=f'<section><h2>Environment and source provenance</h2><pre>{esc(json.dumps(env,indent=2))}</pre><p><a href="results.json">Download all measurements and provenance JSON</a></p></section>'
page+='<section><details><summary>Inspect every measured round</summary><div class="scroll"><table><thead><tr><th>Fork</th><th>Mode</th><th>Implementation</th><th>Round</th><th>P99 ns</th><th>Max ns</th><th>Backpressure events</th></tr></thead><tbody>'+all_rows+'</tbody></table></div></details></section>'
page+='''<footer>Generated from measured CSV files. Source hashes and all rounds are retained. No profitability or institutional deployment claim.</footer></main><script>document.querySelector('#mode').addEventListener('change',e=>document.querySelectorAll('.measurement').forEach(s=>s.hidden=e.target.value!=='all'&&s.dataset.mode!==e.target.value));</script></html>'''
(result_dir/'index.html').write_text(page,encoding='utf-8')
print(json.dumps({'report':str(result_dir/'index.html'),'service':service,'ratio':ratio},indent=2))
