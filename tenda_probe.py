#!/usr/bin/env python3
"""
Probe for the HTTP API of a Tenda MF6 modem (MI-FI MF3 firmware).

Scheme recovered from a HAR capture of the web interface:

  POST /login/Auth               {"userName":"admin","password":"<MD5(password).upper()>"}
                                 -> {"errCode":0}, Set-Cookie: password=<MD5><6 chars>
  GET  /goform/getModules?modules=a,b,c&rand=<float>
                                 -> {"a":{...},"b":{...}}
  POST /goform/setModules?modules=viewSms   {"viewSms":{"id":["3","4"]}}
                                 -> {"errCode":"0"}   (marks messages as read)

  errCode == 1000 in any response means "session is not authorized".

The modem also answers 302 -> /login.html instead of JSON. That does not always
mean the session is gone, so redirects are retried rather than treated as fatal.

Usage:
  python3 tenda_probe.py status            # battery / signal / network / SMS / traffic
  python3 tenda_probe.py sms               # message list (does NOT mark as read)
  python3 tenda_probe.py mark-read 3 4     # explicitly mark messages as read
  python3 tenda_probe.py raw batteryInfo,networkStatus
  python3 tenda_probe.py watch --interval 5

Password: --password, the TENDA_PASSWORD environment variable, or a prompt.
"""

from __future__ import annotations

import argparse
import getpass
import hashlib
import json
import os
import random
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from http.cookiejar import CookieJar

DEFAULT_HOST = "192.168.0.1"
TIMEOUT = 10

# errCode the firmware returns instead of data when the session is not authorized.
ERR_NOT_LOGGED_IN = 1000


class TendaError(Exception):
    pass


class RedirectedToLogin(TendaError):
    """The modem answered 302 -> /login.html instead of data.

    The firmware does this not only when the session is lost: the first request
    after a pause regularly gets such a redirect while an immediate retry goes
    through. So a redirect is handled by retrying, not as a fatal error.
    """


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """Without this urllib silently follows the 302 and returns the login page."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Tenda:
    def __init__(self, host: str = DEFAULT_HOST, password: str = "", verbose: bool = False):
        self.base = f"http://{host}"
        self.password = password
        self.verbose = verbose
        self._jar = CookieJar()
        self._opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self._jar), _NoRedirect()
        )
        self._logged_in = False

    def _log(self, msg: str) -> None:
        if self.verbose:
            print(f"  {msg}", file=sys.stderr)

    # --- transport -------------------------------------------------------

    def _raw(self, path: str, data: dict | None = None) -> str:
        """A single request. Raises RedirectedToLogin on a 302."""
        url = self.base + path
        body = json.dumps(data).encode() if data is not None else None
        headers = {
            "Accept": "application/json, text/plain, */*",
            "Content-Type": "application/json; charset=UTF-8",
            "Referer": self.base + "/index.html",
        }
        req = urllib.request.Request(url, data=body, headers=headers)
        self._log(f"-> {'POST' if body else 'GET '} {url}")
        try:
            with self._opener.open(req, timeout=TIMEOUT) as resp:
                return resp.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            if e.code in (301, 302, 303, 307, 308):
                loc = e.headers.get("Location", "")
                self._log(f"<- {e.code} redirect to {loc}")
                raise RedirectedToLogin(loc) from e
            raise TendaError(f"HTTP {e.code} from {path}") from e
        except urllib.error.URLError as e:
            raise TendaError(f"cannot reach {self.base}: {e.reason}") from e

    def _json(self, path: str, data: dict | None = None, tries: int = 3) -> dict:
        """Request with a redirect retry — cures the "first request after a pause" case."""
        last = None
        for attempt in range(tries):
            try:
                raw = self._raw(path, data)
                break
            except RedirectedToLogin as e:
                last = e
                self._log(f"attempt {attempt + 1}/{tries} hit a redirect, retrying")
                time.sleep(0.4)
        else:
            raise RedirectedToLogin(
                f"the modem redirected to login.html {tries} times in a row ({last}). "
                "Usually that means the session was refused: check the password "
                "or close spare web interface tabs (the modem allows 3 sessions)."
            )

        if not raw.strip():
            return {}
        try:
            return json.loads(raw)
        except json.JSONDecodeError as e:
            raise TendaError(f"non-JSON response from {path}: {raw[:200]!r}") from e

    # --- authentication --------------------------------------------------

    def login(self) -> None:
        # The browser always loads login.html and calls loginInfo before signing
        # in. This warm-up absorbs the "first request" redirect.
        try:
            info = self._json(f"/login/loginInfo?modules=loginInfo&rand={random.random()}")
            li = info.get("loginInfo", {})
            if li.get("isLimit"):
                raise TendaError(
                    f"the modem refuses to let us in: session limit reached ({li.get('maxLimit')}). "
                    "Close web interface tabs or wait for them to expire."
                )
        except RedirectedToLogin:
            pass

        digest = hashlib.md5(self.password.encode()).hexdigest().upper()
        res = self._json("/login/Auth", {"userName": "admin", "password": digest})
        code = int(res.get("errCode", -1))
        if code != 0:
            raise TendaError(
                f"login rejected (errCode={code}). "
                "Check the modem web interface password; after a few failures "
                "the firmware locks logins for a while."
            )
        self._logged_in = True
        self._log("logged in, cookie: " + "; ".join(f"{c.name}={c.value}" for c in self._jar))

    def _ensure_login(self) -> None:
        if not self._logged_in:
            self.login()

    # --- modules ---------------------------------------------------------

    def _call(self, path: str, data: dict | None = None) -> dict:
        """Request with re-login, both on errCode=1000 and on a persistent redirect."""
        self._ensure_login()
        try:
            res = self._json(path, data)
            if int(res.get("errCode", 0)) != ERR_NOT_LOGGED_IN:
                return res
            self._log("errCode=1000, logging in again")
        except RedirectedToLogin:
            self._log("persistent redirect, logging in again")

        self._logged_in = False
        self.login()
        res = self._json(path, data)
        if int(res.get("errCode", 0)) == ERR_NOT_LOGGED_IN:
            raise TendaError("session does not stick: errCode=1000 after re-login")
        return res

    def get(self, modules: str) -> dict:
        q = urllib.parse.urlencode({"rand": random.random(), "modules": modules})
        return self._call(f"/goform/getModules?{q}")

    def post(self, payload: dict) -> dict:
        """POST /goform/setModules — changes state on the modem."""
        modules = ",".join(payload.keys())
        return self._call(f"/goform/setModules?modules={modules}", payload)

    def keepalive(self) -> None:
        """What the web interface calls on navigation to keep the session alive."""
        self._json(f"/login/updateTime?rand={random.random()}")


# --- data handling -------------------------------------------------------

# The module set the widget needs: one request instead of five.
WIDGET_MODULES = "batteryInfo,networkStatus,simStatus,unreadMessage,usedFlow,flowData"

FLOW_MODE = {0: "no limit", 1: "limit set", 2: "statistics only"}


def decode_sms(content: str) -> str:
    """Message bodies arrive as a UTF-16BE hex string; pass anything else through."""
    s = content.strip()
    if len(s) >= 4 and len(s) % 4 == 0:
        try:
            return bytes.fromhex(s).decode("utf-16-be")
        except (ValueError, UnicodeDecodeError):
            pass
    return content


def human_bytes(n: float) -> str:
    n = float(n)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if abs(n) < 1024 or unit == "TB":
            return f"{n:.2f} {unit}" if unit not in ("B", "KB") else f"{n:.0f} {unit}"
        n /= 1024
    return f"{n:.2f} TB"


def signal_bar(level: int) -> str:
    level = max(0, min(3, int(level)))
    return "▮" * level + "▯" * (3 - level) + f" {level}/3"


def print_status(d: dict) -> None:
    bat = d.get("batteryInfo", {})
    net = d.get("networkStatus", {})
    sim = d.get("simStatus", {})
    msg = d.get("unreadMessage", {})
    used = d.get("usedFlow", {})
    flow = d.get("flowData", {})

    charging = " (charging)" if bat.get("isCharge") else ""
    online = int(net.get("status", -1)) == 0

    print(f"Battery   {bat.get('battery', '?')}%{charging}")
    print(f"Signal    {signal_bar(net.get('signal', 0))}")
    if online:
        print(f"Network   {net.get('mode', '?')} · {net.get('profileName', '')}")
    else:
        print(f"Network   no service (status={net.get('status')}, mode={net.get('mode')})")
    print(f"SIM       status={sim.get('status')} isMatchApn={sim.get('isMatchApn')}")
    if msg:
        print(f"SMS       {msg.get('count')} unread")

    if flow:
        mode = int(flow.get("mode", 0))
        u = float(used.get("usedData", 0) or 0)
        m = float(flow.get("monthData", 0) or 0)
        line = f"Traffic   used {human_bytes(u)}"
        if mode == 1 and m:
            line += f" of {human_bytes(m)} ({human_bytes(max(0, m - u))} left)"
        print(line + f"   [mode: {FLOW_MODE.get(mode, mode)}]")


def cmd_status(t: Tenda, args) -> None:
    d = t.get(WIDGET_MODULES)
    if args.json:
        print(json.dumps(d, ensure_ascii=False, indent=2))
    else:
        print_status(d)


def cmd_watch(t: Tenda, args) -> None:
    while True:
        try:
            d = t.get(WIDGET_MODULES)
            print(f"--- {time.strftime('%H:%M:%S')}")
            print_status(d)
        except TendaError as e:
            print(f"--- {time.strftime('%H:%M:%S')}  error: {e}")
        time.sleep(args.interval)


def cmd_sms(t: Tenda, args) -> None:
    d = t.get("smsList")
    threads = d.get("smsList", [])
    if args.json:
        for th in threads:
            for m in th.get("list", []):
                m["text"] = decode_sms(m.get("content", ""))
        print(json.dumps(threads, ensure_ascii=False, indent=2))
        return

    unread_ids = []
    for th in threads:
        msgs = sorted(th.get("list", []), key=lambda m: int(m.get("time", 0)))
        print(f"\n=== {th.get('phone')}  ({len(msgs)} msg)")
        for m in msgs:
            ts = time.strftime("%Y-%m-%d %H:%M", time.localtime(int(m.get("time", 0))))
            flag = "  " if m.get("isRead") else "●•"
            if not m.get("isRead"):
                unread_ids.append(m.get("id"))
            text = decode_sms(m.get("content", "")).replace("\n", " ")
            if not args.full and len(text) > 100:
                text = text[:100] + "…"
            print(f" {flag} [id={m.get('id')}] {ts}  {text}")
    if unread_ids:
        print(f"\nUnread ids: {' '.join(unread_ids)}")
        print("Mark them: python3 tenda_probe.py mark-read " + " ".join(unread_ids))
    else:
        print("\nNothing unread.")


def cmd_mark_read(t: Tenda, args) -> None:
    ids = [str(i) for i in args.ids]
    print(f"Marking as read: {', '.join(ids)}")
    res = t.post({"viewSms": {"id": ids}})
    print(f"Response: {json.dumps(res, ensure_ascii=False)}")


def cmd_raw(t: Tenda, args) -> None:
    print(json.dumps(t.get(args.modules), ensure_ascii=False, indent=2))


def main() -> int:
    # Shared flags live in a parent parser so they work both before and after the
    # subcommand. SUPPRESS keeps the subcommand from overwriting a value given
    # before it (otherwise `-v status` silently loses -v).
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--host", default=argparse.SUPPRESS)
    common.add_argument("--password", default=argparse.SUPPRESS,
                        help="web interface password (else TENDA_PASSWORD or a prompt)")
    common.add_argument("-v", "--verbose", action="store_true", default=argparse.SUPPRESS,
                        help="show requests, redirects and cookies")
    common.add_argument("--json", action="store_true", default=argparse.SUPPRESS,
                        help="print raw JSON")

    p = argparse.ArgumentParser(description="Tenda MF6 modem API probe", parents=[common])
    sub = p.add_subparsers(dest="cmd")

    sub.add_parser("status", help="widget summary",
                   parents=[common]).set_defaults(func=cmd_status)

    w = sub.add_parser("watch", help="poll in a loop", parents=[common])
    w.add_argument("--interval", type=float, default=5)
    w.set_defaults(func=cmd_watch)

    s = sub.add_parser("sms", help="message list (does not mark as read)", parents=[common])
    s.add_argument("--full", action="store_true", help="do not truncate text")
    s.set_defaults(func=cmd_sms)

    m = sub.add_parser("mark-read", parents=[common],
                       help="mark messages as read (changes state on the modem)")
    m.add_argument("ids", nargs="+")
    m.set_defaults(func=cmd_mark_read)

    r = sub.add_parser("raw", help="arbitrary comma-separated modules", parents=[common])
    r.add_argument("modules")
    r.set_defaults(func=cmd_raw)

    args = p.parse_args()
    args.host = getattr(args, "host", DEFAULT_HOST)
    args.json = getattr(args, "json", False)
    verbose = getattr(args, "verbose", False)
    if not args.cmd:
        args.cmd, args.func = "status", cmd_status

    password = getattr(args, "password", None) or os.environ.get("TENDA_PASSWORD")
    if not password:
        password = getpass.getpass(f"Web interface password for {args.host}: ")

    t = Tenda(args.host, password, verbose)
    try:
        args.func(t, args)
    except KeyboardInterrupt:
        return 130
    except TendaError as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
