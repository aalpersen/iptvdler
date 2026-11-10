"""A tiny fake Xtream Codes server for testing iptvdler without a real provider account.

    python docs/tools/fake_xtream_server.py [port]          # default 8001

Login: username "demo", password "demo". In iptvdler add a playlist, choose Xtream, and paste
    http://127.0.0.1:8001/get.php?username=demo&password=demo&type=m3u_plus&output=ts
(on the Android emulator run `adb reverse tcp:8001 tcp:8001` first).

It answers player_api.php (account, categories, live/VOD/series lists, series info) and get.php (M3U),
and redirects every stream URL to a public test stream (Mux, Apple, Unified Streaming), so playback,
downloads and recording all work. No real channels or provider links are involved.
"""
import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

USER, PASSWORD = "demo", "demo"

# Stream id -> public test stream the fake server redirects to.
LIVE = {
    "101": ("Unified Streaming live demo", "1", "https://demo.unified-streaming.com/k8s/live/stable/live.isml/.m3u8"),
}
MOVIES = {
    "201": ("Big Buck Bunny (Mux)", "10", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"),
    "202": ("Apple BipBop with subtitles", "10", "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"),
}
EPISODES = {  # episode id -> (season, number, title, target)
    "801": (1, 1, "Pilot", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"),
    "802": (1, 2, "Second", "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"),
    "901": (2, 1, "Next season", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"),
}


def api(action, query, host):
    if action is None:
        return {
            "user_info": {
                "username": USER, "auth": 1, "status": "Active",
                "exp_date": str(int(time.time()) + 365 * 24 * 3600), "max_connections": "1",
                # m3u8 only, so live streams are HLS (the test stream is HLS).
                "allowed_output_formats": ["m3u8"],
            },
            "server_info": {"url": host.split(":")[0], "port": host.split(":")[-1], "server_protocol": "http"},
        }
    if action == "get_live_categories":
        return [{"category_id": "1", "category_name": "Test live", "parent_id": 0}]
    if action == "get_live_streams":
        return [{"num": i + 1, "name": name, "stream_type": "live", "stream_id": int(sid), "stream_icon": "",
                 "epg_channel_id": None, "category_id": cat} for i, (sid, (name, cat, _)) in enumerate(LIVE.items())]
    if action == "get_vod_categories":
        return [{"category_id": "10", "category_name": "Test movies", "parent_id": 0}]
    if action == "get_vod_streams":
        return [{"num": i + 1, "name": name, "stream_type": "movie", "stream_id": int(sid), "stream_icon": "",
                 "category_id": cat, "container_extension": "m3u8"} for i, (sid, (name, cat, _)) in enumerate(MOVIES.items())]
    if action == "get_series_categories":
        return [{"category_id": "20", "category_name": "Test series", "parent_id": 0}]
    if action == "get_series":
        return [{"num": 1, "name": "Test Show", "series_id": 301, "cover": "", "category_id": "20"}]
    if action == "get_series_info":
        seasons = {}
        for eid, (season, num, title, _) in EPISODES.items():
            seasons.setdefault(str(season), []).append({
                "id": eid, "episode_num": num, "title": f"Test Show - S{season:02d}E{num:02d} - {title}",
                "container_extension": "m3u8", "season": season, "info": {"duration_secs": 600},
            })
        return {"info": {"name": "Test Show"}, "episodes": seasons}
    return []


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        url = urlparse(self.path)
        query = {k: v[0] for k, v in parse_qs(url.query).items()}
        parts = [p for p in url.path.split("/") if p]
        host = self.headers.get("Host", "127.0.0.1")

        if url.path.endswith(("player_api.php", "get.php")):
            if query.get("username") != USER or query.get("password") != PASSWORD:
                return self.send_json({"user_info": {"auth": 0}})
            if url.path.endswith("get.php"):
                return self.send_m3u(host)
            return self.send_json(api(query.get("action"), query, host))

        # /live|movie|series/<user>/<pass>/<id>.<ext>
        if len(parts) == 4 and parts[0] in ("live", "movie", "series") and parts[1:3] == [USER, PASSWORD]:
            sid = parts[3].split(".")[0]
            table = {"live": LIVE, "movie": MOVIES, "series": EPISODES}[parts[0]]
            if sid in table:
                target = table[sid][-1]
                self.send_response(302)
                self.send_header("Location", target)
                self.end_headers()
                return
        self.send_error(404)

    do_HEAD = do_GET  # some players probe with HEAD first

    def send_json(self, value):
        body = json.dumps(value).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_m3u(self, host):
        base = f"http://{host}"
        lines = ["#EXTM3U"]
        for sid, (name, _, _) in LIVE.items():
            lines += [f'#EXTINF:-1 group-title="Test live",{name}', f"{base}/live/{USER}/{PASSWORD}/{sid}.m3u8"]
        for sid, (name, _, _) in MOVIES.items():
            lines += [f'#EXTINF:-1 group-title="Test movies",{name}', f"{base}/movie/{USER}/{PASSWORD}/{sid}.m3u8"]
        body = ("\n".join(lines) + "\n").encode()
        self.send_response(200)
        self.send_header("Content-Type", "audio/x-mpegurl")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8001
    print(f"Fake Xtream server on http://127.0.0.1:{port} (login demo / demo)")
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
