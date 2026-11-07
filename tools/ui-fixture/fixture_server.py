#!/usr/bin/env python3
"""Local Oxype-protocol test fixture (NOT a real Oxype server).

Serves the small subset of HTTP routes the Android client calls, over the Kalmia
`data` envelope, so the group chat / message bubble UI can be exercised in an
emulator. State lives in memory only: restart the process to reset it.

Run:  python -I fixture_server.py
"""

import base64
import json
import threading
import time
import zlib
import struct
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, unquote

HOST = "127.0.0.1"
PORT = 18080

FIXTURE_USER_ID = 1
FIXTURE_PASSWORD = "testpass1"
FIXTURE_TOKEN = "fixture-token-user1"

SESSION_ID = 42
AVATAR_PATH = "/avatars/alex.png"
SESSION_AVATAR_PATH = "/avatars/session-42.png"
BASE_AVATAR_URL = "http://127.0.0.1:18080"

# 64x64 RGBA PNG, drawn inline so the fixture has no binary asset dependency.
AVATAR_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHeAAAAv0lEQVR42u3bwRHCMBAEwc2ETAmASPlSBAAZgGxsg5Z+KIDpl0q6y/V+e/zzCQAAAAAcdU6X89CpARgN/iZIfjn8CIjMEL4nRGYK3wMis8ZvhZBZw7eCSEP8JwhpiV+LkKb4NQgA2uKXIqQxfgkCgNb4UQQAzfEjCADa498hAAAAoD/+FQIAAAAAAAAAAAAAV2EAAAB4EgPgWRyArzEAvscNSAAwJGVMzqCkUVnD0sblLUxYmbE0ZW0OAAAA3QBPqkb5YGxSXDgAAAAASUVORK5CYII="
)


def _solid_png(size, rgba):
    """A single-colour PNG, generated so the session avatar is visually distinct."""
    def chunk(tag, data):
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    raw = b"".join(b"\x00" + bytes(rgba) * size for _ in range(size))
    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


# The group's own avatar: distinct from any member's, so the info page header is
# recognisable at a glance.
SESSION_AVATAR_PNG = _solid_png(64, (0x3E, 0x6B, 0x57, 0xFF))

USERS = {
    1: {"userid": 1, "username": "舟舟"},
    2: {"userid": 2, "username": "林晓"},
    3: {"userid": 3, "username": "Alex", "avatarUrl": BASE_AVATAR_URL + AVATAR_PATH},
    # Deliberately unreachable: exercises the client's initial-letter fallback.
    4: {"userid": 4, "username": "🌿青", "avatarUrl": BASE_AVATAR_URL + "/avatars/missing.png"},
}

SESSIONS = {
    SESSION_ID: {
        "sessionId": SESSION_ID,
        "sessionName": "周末徒步小队",
        "description": "UI fixture group",
        "owner": 1,
        "members": [1, 2, 3, 4],
        # Two admins so the info page shows both a 群主 and a 管理员 row.
        "admins": [1, 2],
        "avatarUrl": BASE_AVATAR_URL + SESSION_AVATAR_PATH,
    }
}

BASE_TS = 1767225600000  # 2026-01-01T00:00:00Z


def _msg(seq, sender, text):
    return {
        "id": seq,
        "sessionId": SESSION_ID,
        "sender": sender,
        "timestamp": BASE_TS + seq * 60000,
        "text": text,
        # Server-written snapshot of the quoted message; `None` for a plain message.
        "reply": None,
    }


# Sequence numbers are per-session and survive deletions as holes.
INITIAL_MESSAGES = [
    _msg(1, 2, "好"),
    _msg(2, 3, "你好"),
    _msg(3, 4, "Hello there"),
    _msg(
        4,
        2,
        "今天走了差不多十二公里，从北门上山，沿着水库绕了一圈，最后在观景台看日落，"
        "风很大但是天特别好，下次想早点出发，中午还能在山顶吃个热乎的饭。",
    ),
    _msg(5, 3, "第一行\n第二行\n第三行"),
    _msg(6, 4, "😀🎉👍"),
    _msg(7, 1, "收到"),
    _msg(
        8,
        1,
        "我这边已经把水和干粮都准备好了，周六早上七点半在地铁站集合，"
        "记得带外套，山顶比市区冷不少。",
    ),
    _msg(9, 3, "收到，我准时到"),
]

# Two seeded replies: one answering somebody else, one answering the logged-in user.
# They make the quote block and the jump-back reachable without sending anything first.
_SEEDED_REPLIES = [(7, 4), (9, 8)]


def _reply_snapshot(target_id):
    """The frozen copy the server would have written when the reply was sent."""
    target = next((m for m in INITIAL_MESSAGES if m["id"] == target_id), None)
    if target is None:
        return None
    return {
        "messageId": target["id"],
        "sender": target["sender"],
        "senderName": USERS.get(target["sender"], {}).get("username", ""),
        "text": target["text"],
    }


for _reply_seq, _target_id in _SEEDED_REPLIES:
    for _message in INITIAL_MESSAGES:
        if _message["id"] == _reply_seq:
            _message["reply"] = _reply_snapshot(_target_id)

_lock = threading.Lock()
_messages = [dict(m) for m in INITIAL_MESSAGES]
_max_seq = max(m["id"] for m in _messages)
# Pre-seeded so a fixture restart does not sign the app out; a token it never issued
# (or one that was logged out) is still rejected with 401.
_tokens = {FIXTURE_TOKEN: FIXTURE_USER_ID}


def _now_millis():
    return int(time.time() * 1000)


def _json_bytes(value):
    return json.dumps(value, ensure_ascii=False).encode("utf-8")


def _is_member(user_id, session_id):
    session = SESSIONS.get(session_id)
    return session is not None and user_id in session["members"]


def _member_payload(user_id):
    user = USERS[user_id]
    entry = {"userid": user["userid"], "username": user["username"]}
    if user.get("avatarUrl"):
        entry["avatarUrl"] = user["avatarUrl"]
    return entry


def _session_summary(session):
    return {
        "sessionId": session["sessionId"],
        "sessionName": session["sessionName"],
        "description": session["description"],
        "owner": session["owner"],
        "members": list(session["members"]),
        "admins": list(session["admins"]),
    }


def _session_detail(session):
    detail = {
        "sessionId": session["sessionId"],
        "sessionName": session["sessionName"],
        "description": session["description"],
        "owner": session["owner"],
        "admins": list(session["admins"]),
        "isOwner": session["owner"] == FIXTURE_USER_ID,
        "isAdmin": FIXTURE_USER_ID in session["admins"],
        "users": [_member_payload(uid) for uid in session["members"]],
    }
    if session.get("avatarUrl"):
        detail["avatarUrl"] = session["avatarUrl"]
    return detail


class FixtureError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status
        self.message = message


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        print("%s %s" % (self.address_string(), fmt % args), flush=True)

    # ---- plumbing -------------------------------------------------------

    def _send(self, status, payload, content_type="application/json; charset=utf-8", raw=None):
        body = raw if raw is not None else _json_bytes(payload)
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _ok(self, data):
        self._send(200, {"data": data})

    def _error(self, status, message):
        self._send(status, {"error_message": message})

    def _read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        raw = self.rfile.read(length)
        if not raw.strip():
            return {}
        try:
            value = json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            raise FixtureError(400, "请求体不是合法 JSON")
        return value if isinstance(value, dict) else {}

    def _route(self):
        parsed = urlparse(self.path)
        path = unquote(parsed.path)
        return path, parsed.query

    def _query(self, query):
        params = {}
        for part in query.split("&") if query else []:
            if not part:
                continue
            name, _, value = part.partition("=")
            params[unquote(name)] = unquote(value.replace("+", " "))
        return params

    def _require_token(self, user_id, token):
        if not token:
            raise FixtureError(401, "缺少令牌")
        if _tokens.get(token) != user_id:
            raise FixtureError(401, "登录已失效，请重新登录")

    def _post_auth(self, body, user_id):
        body_user = body.get("userid", body.get("userId"))
        if body_user is not None and str(body_user) != str(user_id):
            raise FixtureError(403, "凭据与路径中的用户不一致")
        self._require_token(user_id, body.get("token"))

    def _get_auth(self, params, user_id):
        params_user = params.get("userid", params.get("userId"))
        if params_user is not None and str(params_user) != str(user_id):
            raise FixtureError(403, "凭据与路径中的用户不一致")
        self._require_token(user_id, params.get("token"))

    # ---- HTTP verbs -----------------------------------------------------

    def do_GET(self):
        self._dispatch("GET")

    def do_POST(self):
        self._dispatch("POST")

    def _dispatch(self, method):
        path, query = self._route()
        try:
            if method == "GET":
                self._handle_get(path, query)
            else:
                self._handle_post(path)
        except FixtureError as exc:
            self._error(exc.status, exc.message)
        except Exception as exc:  # keep the fixture alive, but say what broke
            print("fixture error: %r" % (exc,), flush=True)
            self._error(500, "fixture internal error")

    # ---- routes ---------------------------------------------------------

    def _handle_get(self, path, query):
        params = self._query(query)

        if path == "/health":
            with _lock:
                surviving = len(_messages)
            self._send(
                200,
                {
                    "status": "ok",
                    "fixture": "oxype-protocol-simulator",
                    "sessions": [SESSION_ID],
                    "messages": surviving,
                },
            )
            return

        if path == AVATAR_PATH:
            self._send(200, None, content_type="image/png", raw=AVATAR_PNG)
            return

        if path == SESSION_AVATAR_PATH:
            self._send(200, None, content_type="image/png", raw=SESSION_AVATAR_PNG)
            return

        if path == "/avatars/missing.png":
            raise FixtureError(404, "no such avatar")

        parts = [p for p in path.split("/") if p]
        if not parts:
            raise FixtureError(404, "unknown route")

        head = parts[0]
        if head == "getUser" and len(parts) == 2:
            user_id = self._int(parts[1], "用户编号")
            self._get_auth(params, user_id)
            if user_id not in USERS:
                raise FixtureError(404, "用户不存在")
            self._ok(_member_payload(user_id))
            return

        if head == "getJoinedSessions" and len(parts) == 2:
            user_id = self._int(parts[1], "用户编号")
            self._get_auth(params, user_id)
            joined = [sid for sid, s in SESSIONS.items() if user_id in s["members"]]
            self._ok({"sessions": joined})
            return

        if head == "getSession" and len(parts) == 2:
            session_id = self._int(parts[1], "会话编号")
            caller = self._query_user_id(params)
            self._get_auth(params, caller)
            self._require_session(session_id)
            if not _is_member(caller, session_id):
                raise FixtureError(403, "不是该会话的成员")
            self._ok(_session_detail(SESSIONS[session_id]))
            return

        if head == "getSessionUsers" and len(parts) == 2:
            session_id = self._int(parts[1], "会话编号")
            caller = self._query_user_id(params)
            self._get_auth(params, caller)
            self._require_session(session_id)
            if not _is_member(caller, session_id):
                raise FixtureError(403, "不是该会话的成员")
            self._ok({"users": [_member_payload(uid) for uid in SESSIONS[session_id]["members"]]})
            return

        if head == "lastMessage" and len(parts) == 2:
            session_id = self._int(parts[1], "会话编号")
            caller = self._query_user_id(params)
            self._get_auth(params, caller)
            self._require_session(session_id)
            if not _is_member(caller, session_id):
                raise FixtureError(403, "不是该会话的成员")
            with _lock:
                newest = _newest_message(session_id)
            if newest is None:
                self._ok({"hasMessage": False})
                return
            sender = USERS.get(newest["sender"], {})
            self._ok(
                {
                    "hasMessage": True,
                    "id": newest["id"],
                    "sender": newest["sender"],
                    "senderName": sender.get("username", ""),
                    "timestamp": newest["timestamp"],
                    "content": newest["text"],
                }
            )
            return

        raise FixtureError(404, "unknown route: %s" % path)

    def _handle_post(self, path):
        body = self._read_json()
        parts = [p for p in path.split("/") if p]
        if not parts:
            raise FixtureError(404, "unknown route")

        head = parts[0]

        if head == "login" and len(parts) == 2:
            user_id = self._int(parts[1], "用户编号")
            password = body.get("password")
            if user_id != FIXTURE_USER_ID or password != FIXTURE_PASSWORD:
                raise FixtureError(401, "用户编号或密码不正确")
            token = FIXTURE_TOKEN
            with _lock:
                _tokens[token] = user_id
            self._ok({"token": token})
            return

        if head == "logout" and len(parts) == 2:
            user_id = self._int(parts[1], "用户编号")
            self._post_auth(body, user_id)
            with _lock:
                _tokens.pop(body.get("token"), None)
            self._ok({"userid": user_id, "loggedOut": True})
            return

        if head == "isAlive" and len(parts) == 2:
            user_id = self._int(parts[1], "用户编号")
            self._post_auth(body, user_id)
            self._ok({"is_alive": True})
            return

        if head == "getSessions" and len(parts) == 1:
            caller = self._body_user_id(body)
            self._post_auth(body, caller)
            requested = body.get("sessions")
            if isinstance(requested, list):
                ids = [self._int(v, "会话编号") for v in requested]
            else:
                ids = list(SESSIONS.keys())
            rows = [_session_summary(SESSIONS[sid]) for sid in ids if sid in SESSIONS]
            self._ok({"sessions": rows})
            return

        if head == "getSessionInfo" and len(parts) == 2:
            session_id = self._int(parts[1], "会话编号")
            caller = self._body_user_id(body)
            self._post_auth(body, caller)
            self._require_session(session_id)
            if not _is_member(caller, session_id):
                raise FixtureError(403, "不是该会话的成员")
            self._ok(_session_detail(SESSIONS[session_id]))
            return

        if head == "receivedMessageSeq" and len(parts) == 3:
            user_id = self._int(parts[1], "用户编号")
            session_id = self._int(parts[2], "会话编号")
            self._post_auth(body, user_id)
            self._require_session(session_id)
            if not _is_member(user_id, session_id):
                self._ok({"seq": 0})
                return
            with _lock:
                newest = _newest_message(session_id)
            self._ok({"seq": newest["id"] if newest else 0})
            return

        if head == "getMessages" and len(parts) == 2:
            session_id = self._int(parts[1], "会话编号")
            caller = self._body_user_id(body)
            self._post_auth(body, caller)
            self._require_session(session_id)
            if not _is_member(caller, session_id):
                self._ok([])
                return
            start = self._int(body.get("start", 1), "起始序号")
            end = self._int(body.get("end", 0), "结束序号")
            with _lock:
                rows = [
                    _message_payload(m)
                    for m in sorted(_messages, key=lambda item: item["id"])
                    if m["sessionId"] == session_id and start <= m["id"] <= end
                ]
            self._ok(rows)
            return

        if head == "sendMessage" and len(parts) == 2:
            session_id = self._int(parts[1], "会话编号")
            caller = self._body_user_id(body)
            self._post_auth(body, caller)
            self._require_session(session_id)
            pieces = body.get("pieces")
            if not _is_member(caller, session_id):
                # Soft refusal, exactly like an empty message: the client shows its
                # existing "message not accepted" text and keeps the draft and the quote.
                self._ok({"seq": -1})
                return
            has_reply, reply_message_id = _reply_message_id(pieces)
            text = _pieces_text(pieces)
            if not text:
                raise FixtureError(400, "消息内容为空")
            global _max_seq
            with _lock:
                # Resolve the quote before appending: an unknown target must not leave
                # a half-written message behind.
                reply = (
                    _reply_snapshot_for(session_id, reply_message_id)
                    if has_reply
                    else None
                )
                _max_seq += 1
                seq = _max_seq
                _messages.append(
                    {
                        "id": seq,
                        "sessionId": session_id,
                        "sender": caller,
                        "timestamp": _now_millis(),
                        "text": text,
                        "reply": reply,
                    }
                )
            print("  appended session=%s seq=%s sender=%s" % (session_id, seq, caller), flush=True)
            self._ok({"seq": seq})
            return

        if head == "deleteMessage" and len(parts) == 2:
            session_id = self._int(parts[1], "会话编号")
            caller = self._body_user_id(body)
            self._post_auth(body, caller)
            self._require_session(session_id)
            message_id = self._int(body.get("messageId"), "消息编号")
            with _lock:
                target = next(
                    (
                        m
                        for m in _messages
                        if m["sessionId"] == session_id and m["id"] == message_id
                    ),
                    None,
                )
                if target is None:
                    self._ok({"success": False})
                    return
                owner = SESSIONS[session_id]["owner"]
                allowed = target["sender"] == caller or owner == caller
                if allowed:
                    _messages.remove(target)
            if not allowed:
                self._ok({"success": False})
                return
            self._ok({"success": True})
            return

        raise FixtureError(404, "unknown route: %s" % path)

    # ---- helpers --------------------------------------------------------

    def _int(self, value, label):
        try:
            return int(str(value).strip())
        except (TypeError, ValueError):
            raise FixtureError(400, "%s不正确" % label)

    def _body_user_id(self, body):
        raw = body.get("userid", body.get("userId"))
        if raw is None:
            raise FixtureError(401, "缺少用户凭据")
        return self._int(raw, "用户编号")

    def _query_user_id(self, params):
        raw = params.get("userid", params.get("userId"))
        if raw is None:
            raise FixtureError(401, "缺少用户凭据")
        return self._int(raw, "用户编号")

    def _require_session(self, session_id):
        if session_id not in SESSIONS:
            raise FixtureError(404, "会话不存在")


def _newest_message(session_id):
    candidates = [m for m in _messages if m["sessionId"] == session_id and m["id"] >= 1]
    return max(candidates, key=lambda m: m["id"]) if candidates else None


def _message_payload(message):
    pieces = []
    if message.get("reply"):
        # The server writes the snapshot, and it leads the piece list: the client sends
        # only `messageId` and never the author or the text.
        reply = message["reply"]
        pieces.append(
            {
                "type": "reply",
                "messageId": reply["messageId"],
                "sender": reply["sender"],
                "senderName": reply["senderName"],
                "text": reply["text"],
            }
        )
    pieces.append({"type": "text", "text": message["text"]})
    return {
        "id": message["id"],
        "sender": message["sender"],
        "timestamp": message["timestamp"],
        "pieces": pieces,
    }


def _pieces_text(pieces):
    if not isinstance(pieces, list):
        return ""
    texts = []
    for piece in pieces:
        if not isinstance(piece, dict):
            continue
        if piece.get("type") not in (None, "text", "markdown", "md"):
            continue
        value = piece.get("text")
        if isinstance(value, str) and value:
            texts.append(value)
    return "".join(texts)


def _reply_message_id(pieces):
    """Returns `(present, message_id)` for a reply piece anywhere in the list."""
    if not isinstance(pieces, list):
        return False, None
    for index, piece in enumerate(pieces):
        if not isinstance(piece, dict) or piece.get("type") != "reply":
            continue
        if index != 0:
            raise FixtureError(400, "reply 片段必须在首位")
        try:
            return True, int(str(piece.get("messageId")).strip())
        except (TypeError, ValueError):
            raise FixtureError(400, "reply 片段缺少 messageId")
    return False, None


def _reply_snapshot_for(session_id, message_id):
    """Freezes the quoted message the way the real server would at send time."""
    target = next(
        (m for m in _messages if m["sessionId"] == session_id and m["id"] == message_id),
        None,
    )
    if target is None:
        raise FixtureError(404, "Message %s to reply to was not found" % message_id)
    return {
        "messageId": target["id"],
        "sender": target["sender"],
        "senderName": USERS.get(target["sender"], {}).get("username", ""),
        "text": target["text"],
    }


def main():
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    server.daemon_threads = True
    print("oxype ui fixture listening on http://%s:%d" % (HOST, PORT), flush=True)
    print("fixture user %d / password %s" % (FIXTURE_USER_ID, FIXTURE_PASSWORD), flush=True)
    print("session %d members %s" % (SESSION_ID, [USERS[u]["username"] for u in SESSIONS[SESSION_ID]["members"]]), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
