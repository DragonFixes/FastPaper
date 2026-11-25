"""FAWE benchmark driver: boots a server jar, drives FAWE from the console, times each op.

usage: python bench.py <jar> <label> [--reps N] [--profile] [--jfr] [--jvm "-Dx=y ..."]
                       [--only <regex>] [--no-viewers] [--server-dir <dir>]
See README.md in this folder for setup (FAWE, ViaVersion, server.properties).
"""
import psutil, argparse, json, os, queue, re, shutil, statistics, subprocess, threading, time

ap = argparse.ArgumentParser()
ap.add_argument("jar"); ap.add_argument("label")
ap.add_argument("--reps", type=int, default=5)
ap.add_argument("--profile", action="store_true")
ap.add_argument("--jfr", action="store_true")
ap.add_argument("--jvm", default="")
ap.add_argument("--only", default="")
ap.add_argument("--no-viewers", action="store_true")
ap.add_argument("--server-dir", default=None)
a = ap.parse_args()

here = os.path.dirname(os.path.abspath(__file__))
jar = os.path.abspath(a.jar)
os.chdir(a.server_dir or os.path.join(here, "..", "..", "run-bench", "fawe"))
for d in ("world", "world_nether", "world_the_end"):
    shutil.rmtree(d, ignore_errors=True)
os.makedirs("results", exist_ok=True)

jvm = ["-Xms3G", "-Xmx3G"] + a.jvm.split()
if a.jfr:
    jvm.append(f"-XX:StartFlightRecording=filename=results/{a.label}.jfr,settings=profile")
p = subprocess.Popen(["java", *jvm, "-jar", jar, "--nogui"], stdin=subprocess.PIPE,
                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
q = queue.Queue()
log = open(f"results/{a.label}.log", "w", encoding="utf-8")
def reader():
    for line in p.stdout:
        log.write(line); log.flush(); q.put(line.rstrip())
    q.put(None)
threading.Thread(target=reader, daemon=True).start()

def wait(rx, timeout=120):
    end = time.time() + timeout
    while time.time() < end:
        try: l = q.get(timeout=0.2)
        except queue.Empty: continue
        if l is None: raise SystemExit("server exited")
        if re.search(r"Exception|ERROR\]", l): print("  !", l)
        if re.search(rx, l): return l
    raise TimeoutError(rx)

def drain():
    while True:
        try: q.get_nowait()
        except queue.Empty: return

def cmd(c, rx=None, timeout=120):
    drain()
    t = time.perf_counter()
    p.stdin.write(c + "\n"); p.stdin.flush()
    line = wait(rx, timeout) if rx else None
    return (time.perf_counter() - t) * 1000, line

DONE = {"set": r"Operation completed", "replace": r"have been replaced", "copy": r"entities affected",
        "paste": r"has been pasted", "undo": r"(?i)undid|undo", "pos": r"[Pp]osition (already )?set",
        "stack": r"blocks changed|Operation completed|affected"}

def sel(x1, y1, z1, x2, y2, z2):
    cmd(f"//pos1 {x1},{y1},{z1}", DONE["pos"]); cmd(f"//pos2 {x2},{y2},{z2}", DONE["pos"])

SIZES = {  # name -> (dx, dy, dz)
    "small": (20, 5, 20),       # 2,000
    "medium": (50, 20, 50),     # 50,000
    "big": (100, 20, 100),      # 200,000
    "huge": (100, 100, 100),    # 1,000,000
}
SRC = (0, -60, 0)
DST = (300, -60, 0)

def region(size, origin=SRC):
    dx, dy, dz = SIZES[size]; x, y, z = origin
    return (x, y, z, x + dx - 1, y + dy - 1, z + dz - 1)

results = {}
viewers = None
def record(name, ms):
    results.setdefault(name, []).append(ms)

def bench_set(size, rep):
    sel(*region(size))
    pat = ["stone", "dirt"][rep % 2]
    return cmd(f"//set {pat}", DONE["set"])[0]

def bench_set_random(size, rep):
    sel(*region(size))
    pat = ["50%stone,50%dirt", "50%andesite,50%granite"][rep % 2]
    return cmd(f"//set {pat}", DONE["set"])[0]

def bench_replace(size, rep):
    sel(*region(size)); cmd("//set stone", DONE["set"])
    return cmd("//replace stone dirt", DONE["replace"])[0]

def bench_copy(size, rep):
    sel(*region(size)); cmd("//set " + ["stone", "dirt"][rep % 2], DONE["set"])
    return cmd("//copy", DONE["copy"])[0]

def bench_paste(size, rep):
    sel(*region(size)); cmd("//set " + ["stone", "dirt"][rep % 2], DONE["set"]); cmd("//copy", DONE["copy"])
    sel(*region(size, DST)); cmd("//set air", DONE["set"])
    cmd(f"//pos1 {DST[0]},{DST[1]},{DST[2]}", DONE["pos"])
    return cmd("//paste", DONE["paste"])[0]

def bench_undo(size, rep):
    sel(*region(size)); cmd("//set " + ["stone", "dirt"][rep % 2], DONE["set"])
    time.sleep(1.5)  # FAWE writes history to disk asynchronously; an immediate //undo can read a partial file
    return cmd("//undo", DONE["undo"])[0]

def bench_clear(size, rep):
    sel(*region(size)); cmd("//set stone", DONE["set"])
    return cmd("//set air", DONE["set"])[0]

TESTS = [("set", bench_set, ["small", "medium", "big", "huge"]),
         ("replace", bench_replace, ["small", "medium", "big", "huge"]),
         ("copy", bench_copy, ["small", "medium", "big", "huge"]),
         ("paste", bench_paste, ["small", "medium", "big", "huge"]),
         ("set-random", bench_set_random, ["big", "huge"]),
         ("undo", bench_undo, ["big", "huge"]),
         ("clear-air", bench_clear, ["big", "huge"])]

wait(r"Done \(", 300)
time.sleep(3)
proc = psutil.Process(p.pid)
viewers = None
if not a.no_viewers:
    viewers = subprocess.Popen(["node", os.path.join(here, "viewers.mjs"), "25599", "ViewA", "ViewB"], stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True)
    for l in viewers.stdout:
        print("  viewer:", l.strip(), flush=True)
        if "READY" in l: break
        if "KICKED" in l or "ERROR" in l: raise SystemExit("viewer failed")
    threading.Thread(target=lambda: [None for _ in viewers.stdout], daemon=True).start()
    for n, x in (("ViewA", 50), ("ViewB", 350)):
        cmd(f"gamemode spectator {n}"); cmd(f"tp {n} {x} -30 50")
    time.sleep(8)
cmd("//world world", r"world override")

# warm up: touch every chunk both areas use and JIT the paths
for _ in range(2):
    sel(*region("huge")); cmd("//set stone", DONE["set"]); cmd("//copy", DONE["copy"])
    cmd(f"//pos1 {DST[0]},{DST[1]},{DST[2]}", DONE["pos"]); cmd("//paste", DONE["paste"])
    sel(*region("huge")); cmd("//replace stone dirt", DONE["replace"]); cmd("//set air", DONE["set"])
time.sleep(2)

def run_all():
    if a.profile:
        cmd("spark profiler start --thread * --interval 2", r"[Pp]rofiler")
    for name, fn, sizes in TESTS:
        if a.only and not re.fullmatch(a.only, name): continue
        for size in sizes:
            cpu0 = sum(proc.cpu_times()[:2])
            for rep in range(a.reps):
                try:
                    ms = fn(size, rep)
                except TimeoutError as e:
                    print("  timeout", name, size, e, flush=True); ms = float("nan")
                record(f"{name}/{size}", ms)
            cpu = (sum(proc.cpu_times()[:2]) - cpu0) * 1000 / a.reps
            results[f"cpu:{name}/{size}"] = cpu
            runs = results[f"{name}/{size}"]
            med = statistics.median(runs)
            print(f"{a.label:>12} {name+'/'+size:<20} median {med:8.1f} ms  cpu/rep {cpu:8.0f} ms  runs {[round(x) if x == x else None for x in runs]}", flush=True)
    if a.profile:
        _, line = cmd("spark profiler stop", r"https://spark\.lucko\.me/\S+|saved", 180)
        print("spark:", line, flush=True)
        results["_spark"] = line

try:
    run_all()
    cmd("spark tps", r"Tick durations")
    time.sleep(0.5)
    lines = []
    while True:
        try: lines.append(q.get(timeout=0.5))
        except queue.Empty: break
    results["_tps"] = [l for l in lines if "[?]" in l][:3]
finally:
    json.dump(results, open(f"results/{a.label}.json", "w"), indent=1)
    p.stdin.write("stop\n"); p.stdin.flush()
    try: p.wait(120)
    except subprocess.TimeoutExpired: p.kill()
    if viewers: viewers.kill()
