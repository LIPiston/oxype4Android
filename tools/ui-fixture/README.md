# Oxype UI test fixture (protocol simulator)

This directory contains a **local mock of the Oxype HTTP protocol**, used only to
exercise the Android client's group-chat and message-bubble UI against a real device.

It is **not** the Oxype service, not a production component, and not connected to any
remote server. It stores everything in memory (nothing is written to disk) and resets
when the process exits. It lives under `tools/` and is not compiled into the APK.

## Run

```bash
cd tools/ui-fixture
python -I fixture_server.py
```

Listens on `127.0.0.1:18080`. For an emulator this must be reached through a reverse
forward of the same port, e.g.:

```bash
adb -s 127.0.0.1:16384 reverse tcp:18080 tcp:18080
```

## Fixture data

- Login: user id `1`, password `testpass1`.
- One group session, id `42`, "周末徒步小队" with 4 members:
  - `1` 舟舟 (the logged-in user)
  - `2` 林晓 — no avatar field (initial fallback)
  - `3` Alex — `http://127.0.0.1:18080/avatars/alex.png` (valid inline PNG)
  - `4` 🌿青 — `/avatars/missing.png` (404, exercises the fallback)
- Messages 1..8 cover a single character, `你好`, an English sentence, a long Chinese
  message, an explicit-multiline message, emoji, and the user's own short/long
  messages.

## Routes

`POST /login/{userid}` · `POST /isAlive/{userid}` · `POST /logout/{userid}` ·
`GET /getUser/{userid}` · `GET /getJoinedSessions/{userid}` · `POST /getSessions` ·
`POST /getSessionInfo/{sessionId}` · `GET /getSession/{sessionId}` ·
`GET /getSessionUsers/{sessionId}` · `POST /receivedMessageSeq/{userid}/{sessionId}` ·
`GET /lastMessage/{sessionId}` · `POST /getMessages/{sessionId}` ·
`POST /sendMessage/{sessionId}` · `POST /deleteMessage/{sessionId}` ·
`GET /avatars/alex.png` · `GET /health`

All answers use the Kalmia `data` envelope. GET carries `userid` + `token` in the query
string, POST carries them in the JSON body; a token that was not issued by `/login`
gets `401`.

`getMessages` takes an inclusive `start`..`end` range, deleted sequences are absent,
`sendMessage` really appends (new sequence numbers keep growing), and
`deleteMessage` removes the message for the rest of the process lifetime.

## Stop

Ctrl+C in the terminal that runs it, or kill the background process that was started.
