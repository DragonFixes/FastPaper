# FastAsyncWorldEdit benchmark

Boots a server jar with FastAsyncWorldEdit, drives FAWE from the console and times each edit, with two
viewer bots connected over the edited areas so the server builds and sends chunk, light and block
updates the way it does for real players. Optional spark profile (`--profile`) and JFR recording
(`--jfr`).

```powershell
python gametests\fawe\bench.py deploy\<jar> fp --reps 5 --profile --jfr
python gametests\fawe\bench.py run-bench\fawe\paper-26.3-262.jar paper --reps 5   # upstream A/B
python gametests\fawe\bench.py deploy\<jar> fp --only "set|undo" --reps 10
python gametests\fawe\jfrthread.py "^Server thread$" run-bench\fawe\results\fp.jfr run-bench\fawe\results\paper.jfr
python gametests\fawe\jfrstacks.py run-bench\fawe\results\fp.jfr "SWMRNibbleArray.set" 8
```

## Setup (server folder `run-bench\fawe`, git-ignored)

- `plugins\`: FastAsyncWorldEdit-Paper for 26.3 (2.16.3 tested), ViaVersion and ViaBackwards 5.12.3+
  (the viewer bots are `minecraft-protocol` clients speaking 1.21.11).
- `npm install` in `gametests\bots` (the viewers reuse its `minecraft-protocol`); `pip install psutil`.
- `eula.txt` with `eula=true`; `server.properties`: `server-port=25599`, `online-mode=false`,
  `white-list=false`, `level-type=minecraft\:flat`, and explicit layers (an empty
  `generator-settings` logs `No key layers` and makes a default world):
  `generator-settings={"layers"\:[{"block"\:"minecraft\:bedrock","height"\:1},{"block"\:"minecraft\:dirt","height"\:2},{"block"\:"minecraft\:grass_block","height"\:1}],"biome"\:"minecraft\:the_void"}`

The world is deleted at every start. Results go to `run-bench\fawe\results\<label>.{json,log,jfr}`.

## Tests

| Test | Sizes | Timed command | Untimed setup |
|---|---|---|---|
| `set` | small 2k, medium 50k, big 200k, huge 1M | `//set stone` / `dirt` alternating | |
| `replace` | all four | `//replace stone dirt` | `//set stone` |
| `copy` | all four | `//copy` | `//set` |
| `paste` | all four | `//paste` 300 blocks away | `//set`, `//copy`, clear target |
| `set-random` | big, huge | `//set 50%stone,50%dirt` | |
| `undo` | big, huge | `//undo` | `//set`, 1.5 s for FAWE's async history write |
| `clear-air` | big, huge | `//set air` | `//set stone` |

Sizes are 20x5x20, 50x20x50, 100x20x100 and 100x100x100 from (0,-60,0). The console uses `//world world`
and `//pos1 x,y,z`; `//paste` places at pos1.

## Reading the numbers

Wall time runs from writing the command to FAWE's reply and moves in 50 ms steps: FAWE runs the edit
off the main thread and hands results back at tick boundaries, so `//set` replies after about 3 ticks
whatever its size. Latency only shows a server problem when it lands on a different step than Paper.
The cost that matters is in the profile: almost none on the main thread, and most server-side CPU is
the Starlight relight FAWE requests afterwards (`ThreadedLevelLightEngine.starlight$serverRelightChunks`
on `Paper Common Worker` threads). `cpu/rep` is process CPU per repetition including setup commands,
viewers' Via translation and the profiler itself; treat it as a coarse cross-check only.

## Results, 2026-11-25 (FastPaper 13ec1948 vs Paper 26.3 build 262, 3 GB heap)

Median wall time in ms, 5 repetitions with spark + JFR; `set`, `undo` and `clear-air` repeated in two
further rounds of 10 without the profiler.

| Test | small | medium | big | huge |
|---|---|---|---|---|
| set (FastPaper / Paper) | 148 / 148 | 149 / 147 | 149 / 149 | 149 / 149 |
| replace | 50 / 50 | 51 / 51 | 52 / 52 | 50 / 51 |
| copy | 5 / 5 | 6 / 5 | 11 / 12 | 33 / 39 |
| paste | 50 / 50 | 52 / 51 | 55 / 57 | 74 / 73 |
| set-random | | | 149 / 148 | 150 / 150 |
| undo | | | 150 / 150 | 150 / 150 |
| clear-air | | | 51 / 52 | 51 / 51 |

No difference beyond a tick of jitter. JFR samples by thread were within noise as well (main thread
369 vs 318 samples, mostly plugin loading and random ticks; light workers 983 vs 949). The only gap was
spark's own sampler, which walks FastPaper's extra threads.
