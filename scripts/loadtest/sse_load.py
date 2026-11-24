"""시세판 SSE 부하 측정 클라이언트(로컬 측정 스택 전용 — 운영에 쓰지 않는다).

사용자마다 로그인해 `/api/market/stream` 을 연다(사용자당 최대 3개 — 서버 상한). 접속마다 다른 X-Forwarded-For 를 붙여
IP 단위 rate limit 버킷을 나눈다 — 앱에 직접 붙는 로컬 측정에서만 쓴다(운영은 Caddy 가 이 헤더를 덮어쓴다).
일부는 수신 버퍼를 4KB 로 줄인 채 상태 줄만 읽고 더 읽지 않는 연결로 두어, 서버 쪽 쓰기가 막힌 연결이 다른 연결·요청에
주는 영향을 본다. 같은 앱의 `/actuator/health` 왕복을 함께 재서 다른 요청이 느려지는지 본다.

로그인을 마치면 --ready-file 을 만든다 — run_board_load.sh 는 그때부터 duration 동안 자원을 잰다(로그인의 BCrypt 부하를 빼려고).

usage: uv run --no-project --with aiohttp python scripts/loadtest/sse_load.py --base http://127.0.0.1:18090 \
           --streams 300 --users 100 --user-prefix load --password <pw> --duration 120 --slow-fraction 0.1 --ready-file <path>
출력: 마지막 줄에 JSON 한 줄(run_board_load.sh 가 표로 옮긴다).
"""
import argparse
import asyncio
import json
import pathlib
import socket
import statistics
import time
from urllib.parse import urlsplit

import aiohttp


def pct(values, p):
    if not values:
        return None
    s = sorted(values)
    return s[min(len(s) - 1, int(round(p / 100 * (len(s) - 1))))]


async def login(session, base, username, password, xff):
    async with session.post(f"{base}/api/auth/login", json={"username": username, "password": password},
                            headers={"X-Forwarded-For": xff}) as r:
        if r.status != 200:
            raise RuntimeError(f"login {username} -> {r.status}")
        return {k: v.value for k, v in r.cookies.items() if k == "token"}


async def stream(session, base, cookies, xff, stats, stop_at):
    """snapshot 을 받은 연결만 열린 것으로 센다 — 서버는 200 을 보낸 뒤 자리가 없으면 이벤트 없이 끝낼 수 있다."""
    try:
        async with session.get(f"{base}/api/market/stream", cookies=cookies,
                               headers={"Accept": "text/event-stream", "X-Forwarded-For": xff}) as r:
            code = str(r.status)
            stats["status"][code] = stats["status"].get(code, 0) + 1
            if r.status != 200:
                return
            event, last_arrival = None, None
            while time.time() < stop_at:
                line = await asyncio.wait_for(r.content.readline(), timeout=max(0.1, stop_at - time.time()))
                if not line:
                    stats["closed_by_server"] += 1
                    return
                line = line.decode().rstrip("\n")
                if line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:") and event == "snapshot":
                    stats["open"] += 1
                elif line.startswith("data:") and event == "delta":
                    now = time.time()
                    rows = json.loads(line[5:]).get("rows", [])
                    if rows:
                        stats["lag_ms"].append(now * 1000 - max(row["t"] for row in rows))
                    if last_arrival is not None:
                        stats["gap_ms"].append((now - last_arrival) * 1000)
                    last_arrival = now
                    stats["deltas"] += 1
    except asyncio.TimeoutError:
        return
    except (aiohttp.ClientError, ConnectionError, ValueError) as e:
        stats["errors"].append(type(e).__name__)


async def slow_stream(base, cookies, xff, stats, stop_at):
    """수신 버퍼를 줄인 소켓으로 요청만 보내고 상태 줄 뒤로는 읽지 않는다 — 서버 송신 버퍼가 차면 그 연결의 쓰기가 멈춘다."""
    url = urlsplit(base)
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
    sock.setblocking(False)
    writer = None
    try:
        await asyncio.get_running_loop().sock_connect(sock, (url.hostname, url.port))
        reader, writer = await asyncio.open_connection(sock=sock, limit=4096)
        cookie = "; ".join(f"{k}={v}" for k, v in cookies.items())
        writer.write((f"GET /api/market/stream HTTP/1.1\r\nHost: {url.netloc}\r\nAccept: text/event-stream\r\n"
                      f"Cookie: {cookie}\r\nX-Forwarded-For: {xff}\r\n\r\n").encode())
        await writer.drain()
        status_line = await asyncio.wait_for(reader.readline(), timeout=10)
        code = status_line.split()[1].decode() if len(status_line.split()) > 1 else "?"
        stats["status"][code] = stats["status"].get(code, 0) + 1
        if code == "200":
            stats["slow_open"] += 1
            await asyncio.sleep(max(0.0, stop_at - time.time()))
    except (OSError, asyncio.TimeoutError) as e:
        stats["errors"].append(type(e).__name__)
    finally:
        if writer is not None:
            writer.close()
        else:
            sock.close()


async def probe(session, base, stats, stop_at):
    while time.time() < stop_at:
        t0 = time.perf_counter()
        try:
            async with session.get(f"{base}/actuator/health") as r:
                await r.read()
                if r.status == 200:
                    stats["health_ms"].append((time.perf_counter() - t0) * 1000)
                else:
                    stats["health_fail"] += 1
        except aiohttp.ClientError:
            stats["health_fail"] += 1
        await asyncio.sleep(0.2)


async def main(a):
    stats = {"status": {}, "open": 0, "slow_open": 0, "deltas": 0, "closed_by_server": 0, "errors": [],
             "lag_ms": [], "gap_ms": [], "health_ms": [], "health_fail": 0}
    connector = aiohttp.TCPConnector(limit=0)
    timeout = aiohttp.ClientTimeout(total=None, sock_read=None)
    async with aiohttp.ClientSession(connector=connector, timeout=timeout) as session:
        users = max(1, a.users)
        cookies = []
        for i in range(users):
            cookies.append(await login(session, a.base, f"{a.user_prefix}{i}", a.password, f"10.1.{i // 250}.{i % 250 + 1}"))
        if a.ready_file:
            pathlib.Path(a.ready_file).touch()
        stop_at = time.time() + a.duration
        slow_every = int(round(1 / a.slow_fraction)) if a.slow_fraction > 0 else 0
        tasks = [asyncio.create_task(probe(session, a.base, stats, stop_at))]
        for n in range(a.streams):
            xff = f"10.2.{n // 250}.{n % 250 + 1}"
            if slow_every > 0 and n % slow_every == slow_every - 1:
                tasks.append(asyncio.create_task(slow_stream(a.base, cookies[n % users], xff, stats, stop_at)))
            else:
                tasks.append(asyncio.create_task(stream(session, a.base, cookies[n % users], xff, stats, stop_at)))
            if n % 50 == 49:
                await asyncio.sleep(0.2)
        await asyncio.gather(*tasks)
    result = {
        "streams": a.streams, "opened": stats["open"], "slow_opened": stats["slow_open"],
        "status": stats["status"], "deltas": stats["deltas"],
        "closed_by_server": stats["closed_by_server"], "errors": len(stats["errors"]),
        "lag_p50_ms": pct(stats["lag_ms"], 50), "lag_p99_ms": pct(stats["lag_ms"], 99),
        "gap_p50_ms": pct(stats["gap_ms"], 50), "gap_p99_ms": pct(stats["gap_ms"], 99),
        "health_p50_ms": pct(stats["health_ms"], 50), "health_p99_ms": pct(stats["health_ms"], 99),
        "health_mean_ms": statistics.mean(stats["health_ms"]) if stats["health_ms"] else None,
        "health_fail": stats["health_fail"],
    }
    print(json.dumps(result))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--streams", type=int, required=True)
    ap.add_argument("--users", type=int, required=True)
    ap.add_argument("--user-prefix", default="load")
    ap.add_argument("--password", required=True)
    ap.add_argument("--duration", type=int, default=120)
    ap.add_argument("--slow-fraction", type=float, default=0.1)
    ap.add_argument("--ready-file")
    asyncio.run(main(ap.parse_args()))
