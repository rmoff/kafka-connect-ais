#!/usr/bin/env python3
"""
Analyse baseline_probe.py JSONL. Safe to run against a still-growing log.

Reports, each tied to a pre-registered hypothesis in the test plan:
  - summary counts per role (connect ok/fail, starved, remote_eof)
  - L (long-lived): reconnects, total connected time, longest silence gap   [H5]
  - per-UTC-minute frame throughput across all connections                  [H1]
  - feed-wide silence windows: minutes where EVERY active conn saw 0 frames [H2]
  - fresh-connection starvation rate                                        [H3]
  - burst divergence: spread of frames within each burst cohort             [H4]

SINGLE-IP CAVEAT: read every result as "from this one egress IP", not
"the feed does X". See the test plan.

Usage: python3 analyze.py probe-local.jsonl
"""
import collections
import datetime as dt
import json
import sys
import statistics


def load(path):
    evs = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                evs.append(json.loads(line))
            except json.JSONDecodeError:
                pass            # tolerate a torn last line on a live file
    return evs


def utc_min(ts):
    return dt.datetime.fromtimestamp(ts, dt.timezone.utc).strftime("%Y-%m-%d %H:%M")


def main(path):
    evs = load(path)
    if not evs:
        print("no events"); return
    start = min(e["t_wall"] for e in evs)
    end = max(e["t_wall"] for e in evs)
    dur_h = (end - start) / 3600
    print(f"== {path} ==")
    print(f"window: {utc_min(start)}Z .. {utc_min(end)}Z  ({dur_h:.1f} h, {len(evs)} events)")
    starts = [e for e in evs if e["ev"] == "probe_start"]
    if starts:
        s = starts[-1]
        print(f"egress_ip: {s.get('egress_ip')}   (SINGLE-IP CAVEAT applies)")

    # ---- counts per role
    by_role = collections.defaultdict(collections.Counter)
    for e in evs:
        r = e.get("role")
        if r:
            by_role[r][e["ev"]] += 1
    print("\n-- lifecycle counts per role --")
    print(f"{'role':<5}{'conn_ok':>9}{'conn_fail':>10}{'starved':>9}{'remote_eof':>11}{'read_err':>9}")
    for r in sorted(by_role):
        c = by_role[r]
        print(f"{r:<5}{c['connect_ok']:>9}{c['connect_fail']:>10}{c['starved']:>9}"
              f"{c['remote_eof']:>11}{c['read_error']:>9}")

    # ---- remote_eof split: productive (frames>0) vs unproductive (0)  [crux]
    # This is the distinction the whole connector investigation turned on: did a
    # closed connection deliver data first? Productive-short-close is the live churn
    # the reconnect-backoff does NOT address; unproductive-close is what it targets.
    eofs = [e for e in evs if e["ev"] == "remote_eof"]
    if eofs:
        prod = [e for e in eofs if e.get("frames", 0) > 0]
        unprod = [e for e in eofs if e.get("frames", 0) == 0]
        durs_p = [e.get("dur_s", 0) for e in prod]
        frames_p = [e.get("frames", 0) for e in prod]
        print("\n-- remote closes (EOF) split [the productive-vs-unproductive crux] --")
        print(f"  total remote_eof={len(eofs)}  productive(frames>0)={len(prod)}  "
              f"unproductive(0 data)={len(unprod)}")
        if prod:
            print(f"  productive closes: median frames/conn={statistics.median(frames_p):.0f}  "
                  f"median lifetime={statistics.median(durs_p):.1f}s")
            print(f"    -> if MOST closes are productive-short, the live churn is NOT "
                  f"addressed by backoff (it resets on data).")
        if unprod:
            print(f"  unproductive closes: {len(unprod)} (these ARE what the backoff targets)")

    # ---- L: reconnects, connected time, worst silence  [H5]
    L_eof = sum(1 for e in evs if e.get("role") == "L" and e["ev"] in ("remote_eof", "read_error"))
    L_ok = sum(1 for e in evs if e.get("role") == "L" and e["ev"] == "connect_ok")
    L_gaps = [e.get("max_gap_s", 0) for e in evs
              if e.get("role") == "L" and e["ev"] in ("remote_eof", "read_error", "client_close")]
    L_dur = sum(e.get("dur_s", 0) for e in evs
                if e.get("role") == "L" and e["ev"] in ("remote_eof", "read_error", "client_close"))
    print("\n-- L (long-lived) [H5 established-conn starvation] --")
    print(f"connect_ok={L_ok}  drops(eof/err)={L_eof}  total_connected={L_dur/3600:.2f} h"
          f"  worst_in-conn_silence={max(L_gaps) if L_gaps else 0:.1f}s")
    if L_eof > 3:
        print(f"  NOTE: L dropped {L_eof}x — a stable feed should keep an established conn; "
              f"frequent drops here are themselves a finding.")

    # ---- phase control: is L's cycling our own concurrency, or the feed/IP?  [confounder]
    TERMINAL = ("remote_eof", "read_error", "client_close", "starved")
    L_term = [e for e in evs if e.get("role") == "L" and e["ev"] in TERMINAL]
    by_phase = collections.defaultdict(lambda: {"drops": 0, "dur": 0.0})
    for e in L_term:
        p = e.get("phase", "n/a")
        by_phase[p]["drops"] += 1
        by_phase[p]["dur"] += e.get("dur_s", 0)
    if len(by_phase) >= 1 and any(p in by_phase for p in ("solo", "fleet")):
        print("\n-- phase control [confounder: is L's cycling OUR concurrency or the feed?] --")
        for p in ("solo", "fleet"):
            if p in by_phase:
                d = by_phase[p]
                rate = d["drops"] / (d["dur"] / 3600) if d["dur"] > 0 else 0
                print(f"  {p:<5}  L drops={d['drops']:>4}  connected={d['dur']/3600:.2f} h"
                      f"  => {rate:.1f} drops/hour")
        if "solo" in by_phase and "fleet" in by_phase:
            print("  FLEET >> SOLO  => cycling is induced by our own concurrent connections "
                  "(per-IP concurrency limit).")
            print("  SOLO ~= FLEET  => cycling is intrinsic to the feed / this IP.")

    # ---- per-minute throughput across all conns  [H1]
    buckets = [e for e in evs if e["ev"] == "bucket"]
    per_min = collections.defaultdict(int)
    for b in buckets:
        per_min[utc_min(b["sec"])] += b["frames"]
    if per_min:
        rates = list(per_min.values())
        print("\n-- throughput (frames/min, all conns) [H1 diurnal] --")
        print(f"minutes={len(per_min)}  mean={statistics.mean(rates):.0f}  "
              f"median={statistics.median(rates):.0f}  min={min(rates)}  max={max(rates)}")
        # hour-of-day profile (needs many hours to be meaningful)
        by_hour = collections.defaultdict(list)
        for m, v in per_min.items():
            by_hour[m[11:13]].append(v)
        if len(by_hour) >= 6:
            print("  hour-of-day mean frames/min (UTC):")
            for h in sorted(by_hour):
                print(f"    {h}:00  {statistics.mean(by_hour[h]):>7.0f}  (n={len(by_hour[h])})")

    # ---- feed-wide silence: minutes with active conns but 0 frames  [H2]
    #   "active" = a connect_ok exists at/before the minute and no terminal after.
    #   Approximation: a minute is silent-while-connected if there are >=1 conns
    #   that produced buckets in adjacent minutes but 0 frames this minute.
    silent_min = sorted(m for m, v in per_min.items() if v == 0)
    if silent_min:
        print(f"\n-- feed-wide silence candidates [H2]: {len(silent_min)} min with 0 frames "
              f"despite active probe --")
        for m in silent_min[:10]:
            print(f"    {m}Z")
        if len(silent_min) > 10:
            print(f"    ... +{len(silent_min)-10} more")

    # ---- starvation rate per role  [H3]
    # Starved = ZERO frames over the whole connection life, however it ended. The old
    # "0 bytes for the full hold" definition missed the common case where the feed
    # closes the connection (remote_eof) before the hold elapses — so it under-reported.
    for role in ("F", "B"):
        started = sum(1 for e in evs if e.get("role") == role and e["ev"] == "connect_ok")
        zero = sum(1 for e in evs if e.get("role") == role and e["ev"] in TERMINAL
                   and e.get("frames", 0) == 0)
        if started:
            print(f"\n-- {role}-conn starvation [H3] (zero-frame conns / connected) --")
            print(f"  connected={started}  zero-data={zero}  rate={100*zero/started:.1f}%")

    # ---- burst divergence  [H4]
    #   group B terminal events by their ~burst time (60s bins) and show frame spread
    B_term = [e for e in evs if e.get("role") == "B"
              and e["ev"] in ("client_close", "starved", "remote_eof")]
    groups = collections.defaultdict(list)
    for e in B_term:
        groups[int(e["t_wall"] // 120)].append(e.get("frames", 0))
    diverging = [(k, g) for k, g in groups.items() if len(g) >= 2 and (max(g) - min(g)) > 0]
    if groups:
        print(f"\n-- burst divergence [H4 per-conn randomness] --")
        print(f"  burst cohorts={len(groups)}  cohorts with divergent outcomes "
              f"(min!=max frames)={len(diverging)}")
        for k, g in sorted(diverging)[:5]:
            print(f"    cohort@{utc_min(k*120)}Z  frames per conn: {sorted(g)}")
        if diverging:
            print("  -> same-instant connections diverging is direct evidence of "
                  "per-connection (not feed-wide) behaviour.")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "probe-local.jsonl")
