"""Inclusive sample counts per method on one thread, diffing two JFR files.
python jfrthread.py <thread-regex> <a.jfr> <b.jfr> [filter-regex]"""
import re, subprocess, sys, collections
trx = re.compile(sys.argv[1]); flt = re.compile(sys.argv[4]) if len(sys.argv) > 4 else re.compile(".")
def load(f):
    out = subprocess.run(["jfr", "print", "--events", "jdk.ExecutionSample", "--stack-depth", "64", f],
                         capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    inc = collections.Counter(); n = 0
    for ev in out.split("jdk.ExecutionSample")[1:]:
        m = re.search(r'sampledThread = "([^"]*)"', ev)
        if not m or not trx.search(m.group(1)): continue
        n += 1
        frames = {re.sub(r"\s+line:.*", "", l.strip()) for l in ev.split("stackTrace = [", 1)[-1].splitlines()[1:]
                  if l.strip() and not l.strip().startswith(("]", "..."))}
        for fr in frames:
            if flt.search(fr): inc[fr] += 1
    return n, inc
na, a = load(sys.argv[2]); nb, b = load(sys.argv[3])
print("samples", na, nb)
keys = sorted(set(a) | set(b), key=lambda k: -(a[k] + b[k]))[:45]
for k in keys: print(f"{a[k]:6} {b[k]:6} {a[k]-b[k]:+5}  {k[:170]}")
