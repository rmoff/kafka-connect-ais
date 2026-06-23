#!/usr/bin/env python3
"""
AIS feed baseline instrument — ground truth, deliberately dumb.

Single vantage (this host / this source IP). See
docs/feed-baseline-and-ingest-efficacy-test-plan.md for the methodology and the
SINGLE-IP CAVEAT: everything here is conditioned on one egress IP and must be
reported as "observed from this IP", not "the feed does X".

What it does and nothing more:
  - opens raw TCP connections to the feed
  - reads BYTES with recv(); counts bytes and newline ('\\n') frames
  - distinguishes a genuine remote EOF (recv == b'') from a read timeout
  - logs every lifecycle event as append-only JSONL (re-derivable, auditable)

No BufferedReader/decoder over the socket (the very thing we were suspicious of
in the connector — excluded so it can't be blamed). No AIS parsing (a parser
can't drop transport frames).

Fleet (low concurrency on purpose — a heavy fleet would itself perturb a per-IP
limit, which is the H6 confounder; keep the baseline gentle and test H6
separately):
  L : one long-lived connection, reconnects forever  (feed-emitting reference;
      tests established-connection starvation)
  F : a fresh connection every FRESH_PERIOD_S, held FRESH_HOLD_S, staggered so
      windows overlap                                  (tests fresh-conn starvation)
  B : a burst of BURST_K connections opened within ~100ms every BURST_PERIOD_S,
      held BURST_HOLD_S                                (tests per-connection
      randomness with time held constant)

The decisive analysis is cross-connection at a shared clock: L streaming while
F/B starve => fresh starvation; everything silent => feed-wide drop-out; L silent
while a fresh conn streams => established-connection starvation.

Usage:  python3 baseline_probe.py [vantage_label] [logfile]
Stop cleanly with SIGINT/SIGTERM (Ctrl-C) — flushes and closes.
"""
import json
import os
import signal
import socket
import sys
import threading
import time
import urllib.request

HOST = os.environ.get("AIS_HOST", "153.44.253.27")
PORT = int(os.environ.get("AIS_PORT", "5631"))

CONNECT_TIMEOUT_S = 10.0
RECV_TIMEOUT_S = 2.0          # read timeout != close; we just record "no data this slice"
STARVE_THRESHOLD_S = 10.0     # fresh conn with 0 bytes in first N s -> starved

FRESH_PERIOD_S = int(os.environ.get("FRESH_PERIOD_S", "60"))    # spawn a fresh conn this often
FRESH_HOLD_S = int(os.environ.get("FRESH_HOLD_S", "60"))        # hold each fresh conn (overlaps next)
BURST_PERIOD_S = int(os.environ.get("BURST_PERIOD_S", "600"))   # fire a burst this often
BURST_K = int(os.environ.get("BURST_K", "5"))                   # connections per burst
BURST_HOLD_S = int(os.environ.get("BURST_HOLD_S", "30"))

# Alternate SOLO (only the single long-lived L connection from this IP) and FLEET
# (L + F + bursts). Comparing L's drop-rate across the two phases disentangles
# "the feed/IP cycles connections" from "our own concurrent connections cause it"
# (the H6 per-IP-concurrency confounder). Every event is tagged with the phase.
PHASE_PERIOD_S = int(os.environ.get("PHASE_PERIOD_S", "1800"))   # 30 min per phase

VANTAGE = sys.argv[1] if len(sys.argv) > 1 else "local"
LOGPATH = sys.argv[2] if len(sys.argv) > 2 else f"probe-{VANTAGE}.jsonl"

_log_lock = threading.Lock()
_logf = open(LOGPATH, "a", buffering=1)            # line-buffered
_stop = threading.Event()
_conn_seq = 0
_seq_lock = threading.Lock()
_phase = "solo"          # start SOLO so we get a clean single-connection control first


def emit(ev):
    ev["t_wall"] = time.time()
    ev["phase"] = _phase
    line = json.dumps(ev, separators=(",", ":"))
    with _log_lock:
        _logf.write(line + "\n")


def next_id(prefix):
    global _conn_seq
    with _seq_lock:
        _conn_seq += 1
        return f"{prefix}{_conn_seq}"


def egress_ip():
    try:
        with urllib.request.urlopen("https://api.ipify.org", timeout=5) as r:
            return r.read().decode().strip()
    except Exception as e:
        return f"unknown ({e.__class__.__name__})"


def ntp_offset():
    """Best-effort: chrony or ntpdate; non-fatal."""
    for cmd in ("chronyc tracking 2>/dev/null", "ntpdate -q pool.ntp.org 2>/dev/null"):
        try:
            out = os.popen(cmd).read().strip()
            if out:
                return out.splitlines()[-1][:200]
        except Exception:
            pass
    return "unavailable"


def run_conn(conn_id, role, hold_s):
    """One connection's whole life. Raw recv, count bytes + '\\n' frames.

    Emits: connect_attempt, connect_ok|connect_fail, first_byte, per-second
    `bucket`s, and a terminal event (remote_eof|read_error|client_close|starved).
    Returns when the connection ends (EOF/error/hold elapsed/stop).
    """
    emit({"ev": "connect_attempt", "id": conn_id, "role": role, "vantage": VANTAGE})
    t_attempt = time.monotonic()
    try:
        s = socket.create_connection((HOST, PORT), timeout=CONNECT_TIMEOUT_S)
    except OSError as e:
        emit({"ev": "connect_fail", "id": conn_id, "role": role, "vantage": VANTAGE,
              "err": str(e), "connect_ms": (time.monotonic() - t_attempt) * 1000})
        return
    s.settimeout(RECV_TIMEOUT_S)
    m0 = time.monotonic()
    src_port = s.getsockname()[1]
    emit({"ev": "connect_ok", "id": conn_id, "role": role, "vantage": VANTAGE,
          "connect_ms": (m0 - t_attempt) * 1000, "src_port": src_port})

    bytes_n = frames_n = 0
    first_byte_mono = None
    last_frame_mono = m0
    max_gap_s = 0.0
    bucket_sec = int(time.time())
    b_bytes = b_frames = 0
    deadline = m0 + hold_s

    try:
        while not _stop.is_set() and time.monotonic() < deadline:
            # F/B exist only to populate the FLEET phase; drain them when it flips
            # to SOLO so SOLO is a pure single-connection control.
            if role in ("F", "B") and _phase != "fleet":
                emit({"ev": "drained_for_solo", "id": conn_id, "role": role,
                      "vantage": VANTAGE, "frames": frames_n})
                break
            try:
                d = s.recv(65536)
            except socket.timeout:
                # No data this slice. Track the silence gap; not a close.
                gap = time.monotonic() - last_frame_mono
                if gap > max_gap_s:
                    max_gap_s = gap
                continue
            except OSError as e:
                emit({"ev": "read_error", "id": conn_id, "role": role, "vantage": VANTAGE,
                      "err": str(e), "dur_s": time.monotonic() - m0,
                      "bytes": bytes_n, "frames": frames_n})
                return
            if d == b"":                          # genuine remote close
                emit({"ev": "remote_eof", "id": conn_id, "role": role, "vantage": VANTAGE,
                      "dur_s": time.monotonic() - m0, "bytes": bytes_n,
                      "frames": frames_n, "max_gap_s": round(max_gap_s, 3)})
                return
            now_mono = time.monotonic()
            if first_byte_mono is None:
                first_byte_mono = now_mono
                emit({"ev": "first_byte", "id": conn_id, "role": role,
                      "vantage": VANTAGE, "ttfb_ms": (now_mono - m0) * 1000})
            nl = d.count(b"\n")
            bytes_n += len(d)
            frames_n += nl
            if nl:
                gap = now_mono - last_frame_mono
                if gap > max_gap_s:
                    max_gap_s = gap
                last_frame_mono = now_mono
            # 1-second throughput buckets keyed by UTC second
            now_sec = int(time.time())
            if now_sec != bucket_sec:
                emit({"ev": "bucket", "id": conn_id, "role": role, "vantage": VANTAGE,
                      "sec": bucket_sec, "bytes": b_bytes, "frames": b_frames})
                bucket_sec = now_sec
                b_bytes = b_frames = 0
            b_bytes += len(d)
            b_frames += nl
    finally:
        try:
            s.close()
        except OSError:
            pass
    # Held the full duration (or stop): emit terminal summary.
    starved = first_byte_mono is None
    emit({"ev": "starved" if starved else "client_close", "id": conn_id, "role": role,
          "vantage": VANTAGE, "dur_s": time.monotonic() - m0, "bytes": bytes_n,
          "frames": frames_n, "max_gap_s": round(max_gap_s, 3), "starved": starved})


def long_lived_loop():
    """L: keep one connection alive forever; each drop is itself data."""
    while not _stop.is_set():
        run_conn(next_id("L"), "L", hold_s=10 ** 9)   # effectively infinite; returns on EOF
        if _stop.is_set():
            break
        emit({"ev": "L_reconnecting", "vantage": VANTAGE})
        _stop.wait(1.0)


def spawn(role, hold_s):
    t = threading.Thread(target=run_conn, args=(next_id(role[0]), role, hold_s),
                         name=f"{role}-conn", daemon=True)
    t.start()
    return t


def main():
    def handle(_sig, _frm):
        _stop.set()
    signal.signal(signal.SIGINT, handle)
    signal.signal(signal.SIGTERM, handle)

    emit({"ev": "probe_start", "vantage": VANTAGE, "host": HOST, "port": PORT,
          "egress_ip": egress_ip(), "ntp": ntp_offset(), "pid": os.getpid(),
          "config": {"fresh_period_s": FRESH_PERIOD_S, "fresh_hold_s": FRESH_HOLD_S,
                     "burst_period_s": BURST_PERIOD_S, "burst_k": BURST_K,
                     "burst_hold_s": BURST_HOLD_S, "phase_period_s": PHASE_PERIOD_S,
                     "recv_timeout_s": RECV_TIMEOUT_S,
                     "starve_threshold_s": STARVE_THRESHOLD_S}})

    global _phase
    threading.Thread(target=long_lived_loop, name="L-supervisor", daemon=True).start()

    next_fresh = time.monotonic()
    next_burst = time.monotonic() + BURST_PERIOD_S    # first burst after one period
    phase_deadline = time.monotonic() + PHASE_PERIOD_S
    while not _stop.is_set():
        now = time.monotonic()
        if now >= phase_deadline:
            _phase = "fleet" if _phase == "solo" else "solo"
            emit({"ev": "phase_change", "vantage": VANTAGE})   # emit() stamps the new phase
            phase_deadline = now + PHASE_PERIOD_S
            if _phase == "fleet":
                next_fresh = now                       # spawn a fresh conn promptly on entry
                next_burst = now + BURST_PERIOD_S
        if _phase == "fleet":
            if now >= next_fresh:
                spawn("F", FRESH_HOLD_S)
                next_fresh = now + FRESH_PERIOD_S
            if now >= next_burst:
                emit({"ev": "burst_fire", "vantage": VANTAGE, "k": BURST_K})
                for _ in range(BURST_K):
                    spawn("B", BURST_HOLD_S)
                next_burst = now + BURST_PERIOD_S
        _stop.wait(0.5)

    emit({"ev": "probe_stop", "vantage": VANTAGE})
    time.sleep(1.0)          # let in-flight terminal events flush
    _logf.flush()
    _logf.close()


if __name__ == "__main__":
    main()
