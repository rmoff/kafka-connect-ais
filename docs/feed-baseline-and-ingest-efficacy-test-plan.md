# AIS feed baseline & ingest-efficacy test plan

**Status:** draft for review · **Author:** drafted with Claude Code · **Date:** 2026-06

## Why this exists

We keep judging the connector's behaviour against *assumptions* about the
Norwegian Coastal Administration (NCA) AIS feed (`153.44.253.27:5631`). We have
**no absolute baseline** for the feed itself. Two open questions we cannot
currently answer with evidence:

1. **Temporal characteristics** — does the feed's message rate genuinely vary by
   time of day? Are there real feed-wide drop-outs, and how long/often?
2. **Connectional behaviour** — does the feed starve *fresh* connections? Does it
   ever starve *established* connections? Is starvation random per-connection? Are
   there per-source-IP connection limits? Does connection *age* matter?

Without ground truth on (1) and (2) we cannot honestly state the connector's
**ingest efficacy** (what fraction of available data it actually lands), nor
attribute any shortfall to the connector vs the feed. This plan builds that
ground truth and then measures the connector against it — **simultaneously and
at a matched vantage point**, which is the discipline we failed at before
(comparing a *fresh* connector against a *long-lived* incumbent led to two wrong
conclusions).

## The three components

| # | What | Role |
|---|------|------|
| (1) | Connector run **locally** | Device-under-test A |
| (2) | Connector **test instance on Confluent Cloud** | Device-under-test B |
| (3) | **Independent raw-socket baseline instrument** | Ground truth |

The efficacy of (1) and (2) is only meaningful relative to (3), measured over the
**same wall-clock window at the same vantage point**.

---

## Component (3): the baseline instrument — design for defensibility

(3) is the load-bearing part. Its credibility rests on five tenets.

### Tenet 1 — Minimalism = credibility

The instrument must be too simple to be blamed for any artifact:

- Raw TCP `socket` + `recv()` of **bytes**. Count **bytes** and **newline (`\n`)
  frames**. Nothing else on the hot path.
- **No `BufferedReader`/decoder over the socket** — that is precisely the
  component whose behaviour around `SO_TIMEOUT` we were (wrongly) suspicious of in
  v0.3.0. By counting raw bytes we remove that entire class of argument.
- **No AIS payload parsing.** A parser bug can drop "messages"; counting
  transport-level `\n`-delimited frames cannot. (Message-level rates, if wanted,
  are derived offline and labelled separately.)
- Append-only **per-event JSONL logs**, not just summaries, so every statistic is
  re-derivable and auditable by a skeptic.

### Tenet 2 — Deterministic measurement

- `time.monotonic()` for durations/gaps; UTC wall-clock (`time.time()`) for
  absolute alignment.
- Record the **NTP offset** at start (`ntpdate -q` / `chronyc tracking`) so
  timestamps are trustworthy and cross-vantage alignment is valid.
- Log every lifecycle event with both clocks.

### Tenet 3 — Concurrent, synchronized connections to separate *feed-wide* from *per-connection* effects

This is the core experimental design. From each vantage, run a small fleet whose
logs share a clock:

- **L — one long-lived connection.** Opened once at start, never deliberately
  closed (auto-reopen only if dropped). Answers *"does the feed keep/established
  connections fed?"* and serves as the **"is the feed emitting right now"
  reference**.
- **F — fresh connections on a fixed cadence.** Open a new connection every
  *p* seconds, hold *h* seconds, close, repeat — staggered so windows overlap.
  Answers *"does the feed starve fresh connections, and how often?"*
- **B — burst cohort.** Every *m* minutes, open *k* connections within the same
  ~100 ms and watch whether they diverge. This is the cleanest test of *random
  per-connection* behaviour because time is held constant.

**The decisive read** is the cross-connection comparison at each timestamp *T*:

| L | F / B | Interpretation |
|---|-------|----------------|
| streaming | starved | **fresh-connection starvation** |
| silent | silent | **feed-wide drop-out** (genuine) |
| silent | a just-opened conn streaming | **established-connection starvation** (the scary one) |

You cannot reach these conclusions from a single connection observed at different
times — only from concurrent connections sharing a clock.

### Tenet 4 — Control the per-IP / connection-count confounder

Opening many sockets from one IP could *itself* trigger feed-side limits and
masquerade as starvation. So:

- A **single-connection control phase** (just L) for part of the run. If L behaves
  identically alone vs in the fleet, connection-count is not confounding.
- **Fleet-size phases** (1 → 2 → 4 → 8 concurrent). A threshold where
  closes/starvation jump reveals a per-IP cap.
- Log each connection's source ephemeral port and the resolved egress IP.

### Tenet 5 — Vantage point (the local-vs-CC confounder)

> **SCOPE DECISION (2026-06): we are running (3) from the local laptop only — a
> single vantage / single egress IP.** The two-vantage design below is retained as
> the *ideal* but is explicitly **deferred**. Everything this run produces is
> therefore conditioned on **one source IP** and must carry that caveat (see the
> "Single-IP caveat" box).

The CC connector egresses from a **different IP** than a laptop, and the feed may
treat IPs differently. The defensible-ideal design runs (3) from **≥2 vantages
simultaneously**:

- **V_local** — the dev host (compare against component (1)). *← the only vantage
  we are running for now.*
- **V_cloud** — a small VM in the **same cloud/region as CC (AWS us-west-2)**, so
  its egress characteristics match the CC connector as closely as possible
  (compare against component (2)). **Deferred.**

> #### ⚠️ Single-IP caveat (in force for this run)
> With only V_local, we **cannot** distinguish *feed behaviour* from *behaviour
> specific to this one source IP*. Concretely:
> - Any starvation/drop-out we see **might be specific to this IP** (e.g. this IP
>   is rate-limited or de-prioritised) and not a general property of the feed.
> - We **cannot** fairly compare component (2) on CC against this baseline — CC
>   egresses from different IP(s). Component (2)'s efficacy number is therefore
>   **out of scope** until V_cloud exists; only component (1) (local, same IP) is a
>   valid like-for-like comparison for now.
> - Hypothesis **H7 (vantage dependence) is untestable** in this run and is
>   dropped from scope. **H6 (per-IP cap)** is only ever a statement about *this*
>   IP.
> All findings must be reported as *"observed from a single residential/office IP
> on \<dates\>"*, not as *"the feed does X."*

### Tenet 6 — Duration, coverage, pre-registration

- Run **continuously ≥72 h** to capture ≥3 diurnal cycles and a weekday/weekend
  boundary.
- **Pre-register hypotheses and thresholds below *before* looking at results** —
  precisely to avoid the post-hoc story-telling that produced two wrong calls
  earlier in this investigation.

### Metrics logged (per connection)

Lifecycle: `t_connect_attempt`, `t_connect_ok`/`t_connect_fail`(+errno),
`t_first_byte` (TTFB), `t_close`, `close_cause` ∈ {remote_EOF, read_error,
client_closed, open_at_end}, `duration`.
Throughput: bytes & `\n`-frames per 1 s and per 60 s bucket (UTC bucket start).
Liveness: inter-frame gap series → max, p50/p95/p99, counts of gaps >{1,5,30}s.
Starvation flag (F/B): 0 bytes within first *T* s of connect.
Context: vantage id, fleet-size phase, fleet role (L/F/B), source port, egress IP,
NTP offset.

### Pre-registered hypotheses & falsification criteria

- **H1 Diurnal pattern** — confirm if hourly mean frame-rate (≥3 days) differs
  across hours beyond within-hour noise *and* repeats across days; refute if flat.
- **H2 Feed-wide drop-outs** — confirm if windows exist where **all** connections
  at **both** vantages see 0 frames > 60 s simultaneously (both vantages rules out
  local network).
- **H3 Fresh-connection starvation** — confirm if, while L streams normally, a
  measurable fraction of freshly-opened connections get 0 bytes > *T* s; quantify
  the rate and whether it is stationary or time-varying.
- **H4 Per-connection randomness** — confirm if a burst cohort opened within
  ~100 ms diverges (some stream, some starve). Cleanest test; time held constant.
- **H5 Established-connection starvation** (user's specific concern) — confirm if L
  shows 0-frame gaps > *T* s **while a concurrently-open fresh connection receives
  data** (i.e. not feed-wide). Distinguished from H2 by the cross-connection check.
- **H6 Per-IP connection cap** — confirm if close/starvation rate rises with fleet
  size, or the Nth concurrent connection reliably fails.
- **H7 Vantage dependence** — confirm if V_local and V_cloud differ materially at
  the same wall-clock time.

### Reference skeleton for (3)

```python
# baseline_probe.py — ground-truth AIS feed instrument. Deliberately dumb.
# One process runs one fleet at one vantage; logs append-only JSONL per event.
import socket, time, json, sys, threading

HOST, PORT = "153.44.253.27", 5631
VANTAGE = sys.argv[1]                      # "local" | "cloud-usw2"
LOG = open(f"probe-{VANTAGE}.jsonl", "a", buffering=1)

def emit(ev): LOG.write(json.dumps(ev) + "\n")

def run_conn(conn_id, role, hold_s, starve_t=10.0):
    """role: 'L' (long-lived, hold_s=inf) | 'F'/'B' (fresh, finite hold)."""
    t_attempt = time.time()
    try:
        s = socket.create_connection((HOST, PORT), timeout=10)
    except OSError as e:
        emit({"ev":"connect_fail","id":conn_id,"role":role,"vantage":VANTAGE,
              "wall":t_attempt,"err":str(e)}); return
    s.settimeout(2.0)
    t_ok = time.time(); m0 = time.monotonic()
    src_port = s.getsockname()[1]
    emit({"ev":"connect_ok","id":conn_id,"role":role,"vantage":VANTAGE,
          "wall":t_ok,"connect_ms":(t_ok-t_attempt)*1000,"src_port":src_port})
    bytes_n=frames_n=0; first_byte=None; last_frame=m0; deadline=m0+hold_s
    bucket=int(t_ok); b_bytes=b_frames=0
    try:
        while time.monotonic() < deadline:
            try:
                d = s.recv(65536)
            except socket.timeout:
                continue                    # read timeout != close; just no data now
            if d == b"":                    # genuine remote EOF
                emit({"ev":"remote_eof","id":conn_id,"role":role,"vantage":VANTAGE,
                      "wall":time.time(),"dur_s":time.monotonic()-m0,
                      "bytes":bytes_n,"frames":frames_n}); return
            now=time.time(); mono=time.monotonic()
            if first_byte is None:
                first_byte=mono
                emit({"ev":"first_byte","id":conn_id,"role":role,"vantage":VANTAGE,
                      "wall":now,"ttfb_ms":(mono-m0)*1000})
            bytes_n+=len(d); nl=d.count(b"\n"); frames_n+=nl
            if nl: last_frame=mono
            # 1s throughput buckets
            if int(now)!=bucket:
                emit({"ev":"bucket","id":conn_id,"role":role,"vantage":VANTAGE,
                      "wall":bucket,"bytes":b_bytes,"frames":b_frames})
                bucket=int(now); b_bytes=b_frames=0
            b_bytes+=len(d); b_frames+=nl
    finally:
        try: s.close()
        except OSError: pass
    emit({"ev":"client_close" if first_byte else "starved","id":conn_id,
          "role":role,"vantage":VANTAGE,"wall":time.time(),
          "dur_s":time.monotonic()-m0,"bytes":bytes_n,"frames":frames_n,
          "starved": first_byte is None})
# A supervisor thread maintains L forever, spawns F on cadence, and fires B bursts;
# fleet-size phases are scheduled by a simple timetable. (Full harness TBD.)
```

Notes that make it stand up: raw `recv`, `\n` counting, EOF distinguished from
read-timeout, append-only evidence, vantage stamped, per-connection isolation. An
**optional second variant** uses the connector's *exact* read path (the
`TcpConnectionManager` char loop) so we can isolate any read-path effect — clearly
labelled as *not* the ground truth.

---

## Components (1) and (2): measuring connector efficacy against (3)

Everything below runs in **overlapping windows** with (3), compared against the
**same-vantage** baseline and **time-aligned per minute**.

### (1) Local connector
- Run the connector (or the `TcpConnectionManager`+`AisSourceTask` harness) on
  **V_local**, same host as the V_local probe, same window.
- Capture connector ingest: frames/records per second, reconnect count, close
  count + cause, per-connection productivity.
- `Efficacy_local(minute) = connector_frames / baseline_frames(V_local)`.

### (2) CC test instance
- Deploy the connector on CC (us-west-2) — fresh instance, throwaway topic, **does
  not touch live `ais-source`/`ais`**.
- Measure ingest via `confluent kafka topic consume` (lag-free) and/or the
  `received_records` metric; pull close/reconnect counts from `clcc-*-app-logs`.
- Compare against the **V_cloud** baseline (matched region/egress), same window.
- `Efficacy_cc(minute) = connector_records / baseline_frames(V_cloud)`.

### Definitions (stated up front, not after the fact)
- **Ingest efficacy** = fraction of baseline-available `\n`-frames the connector
  landed in Kafka over the same aligned UTC minute at the same vantage.
- **Connector-attributable overhead** = connector close/reconnect rate *minus* the
  dumb probe's close rate at the same vantage/window. If the connector closes more
  than a raw client does, that excess is on the connector.

### The apples-to-apples rules (the lesson learned)
1. (1), (2), (3) run **simultaneously**.
2. Compare each connector against the **same-vantage** baseline (CC ↔ V_cloud).
3. Where connection age matters, compare **fresh-vs-fresh**; never a fresh
   connector against a long-lived incumbent.
4. Align on UTC minute; report with confidence intervals; publish raw JSONL.

---

## Anticipated objections → how the plan answers them

| Objection | Answer |
|-----------|--------|
| "Different IP, can't compare local vs CC" | Two vantages; CC compared to V_cloud (matched region/egress). |
| "Your reader behaves differently than the connector" | Raw `recv` + `\n` counting is the ground truth; optional connector-read-path variant isolates read effects, clearly labelled. |
| "Your fleet self-interferes / hits a per-IP cap" | Single-connection control phase + fleet-size phases expose any cap. |
| "One run is a fluke / you cherry-picked" | ≥72 h, pre-registered hypotheses+thresholds, raw logs published, analysis scripted & re-runnable. |
| "Clock skew invalidates alignment" | Monotonic clock for gaps; NTP offset logged; UTC alignment. |
| "Feed changed between your runs" | Simultaneity; all comparisons time-aligned within one run. |
| "NMEA multi-sentence framing distorts counts" | Count transport `\n`-frames, not decoded messages; message rates derived & labelled separately. |
| "Starvation is just the feed being quiet" | The L-vs-F/B cross-check separates feed-wide silence (H2) from per-connection starvation (H3/H5). |

## Deliverables
1. `baseline_probe.py` (+ supervisor & timetable) and a scheduled deploy on
   V_local and V_cloud.
2. Raw JSONL evidence logs from both vantages, ≥72 h.
3. An analysis script producing: hourly throughput profile, drop-out catalogue,
   fresh-/established-starvation rates, per-IP cap finding, vantage comparison, and
   the (1)/(2) efficacy curves — each tied to a pre-registered hypothesis.
4. A short findings memo with confidence intervals and the raw logs attached.
