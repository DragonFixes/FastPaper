"""Aggregate JFR execution samples: python jfrstacks.py <file.jfr> <regex-top-frame> [depth]
Prints the most common call paths below frames matching the regex, plus per-thread totals."""
import re, subprocess, sys, collections
f, rx = sys.argv[1], re.compile(sys.argv[2]); depth = int(sys.argv[3]) if len(sys.argv) > 3 else 8
out = subprocess.run(["jfr", "print", "--events", "jdk.ExecutionSample", "--stack-depth", "40", f],
                     capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
paths = collections.Counter(); threads = collections.Counter(); total = 0
for ev in out.split("jdk.ExecutionSample")[1:]:
    m = re.search(r'sampledThread = "([^"]*)"', ev); th = m.group(1) if m else "?"
    th = re.sub(r"\d+", "#", th)
    frames = [l.strip() for l in ev.split("stackTrace = [", 1)[-1].splitlines()[1:] if l.strip() and not l.strip().startswith(("]", "..."))]
    frames = [re.sub(r"\s+line:.*", "", x) for x in frames]
    total += 1
    for i, fr in enumerate(frames):
        if rx.search(fr):
            threads[th] += 1
            paths[" <- ".join(frames[i:i + depth])] += 1
            break
print("total samples", total, "matching", sum(threads.values()))
for t, c in threads.most_common(8): print(f"  {c:6} {t}")
for p, c in paths.most_common(12): print(f"\n{c:6} {p}")
