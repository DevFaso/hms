# HMS HL7 v2 MLLP listener

> P0.2 of the Epic-alignment workstream (see [`claude/finding-gaps.md`](../claude/finding-gaps.md)).
>
> **Goal:** accept inbound HL7 v2 messages from real lab analyzers
> (Mindray, Sysmex, Roche Cobas) and ADT feeds over the
> Minimum Lower Layer Protocol — the wire protocol every commodity LIS
> speaks. The existing REST `Hl7InboundController` will keep working as
> a manual / test path; production analyzers will route here.

## Wire protocol

HL7 v2 over TCP frames each message as:

```
<VT=0x0B>  HL7 v2 message body  <FS=0x1C><CR=0x0D>
```

`MllpFrameCodec` reads/writes that framing. `MllpTcpServer` runs a single
accept thread + a cached worker pool — one connection per worker, many
frames per connection.

## What gets handled today (P0.2)

| Inbound                       | Action                                                  | ACK |
| ----------------------------- | ------------------------------------------------------- | --- |
| `ORU^R01` (lab results)       | Parsed via the existing `Hl7v2MessageBuilder.parseOruR01`. Logged with sender + OBX content. *Persistence wired in P1.* | AA |
| `ORU^R01` with no OBX         | Logged as a parse failure                               | AE |
| `ADT^A01 / A04 / A08`         | Logged with MSH routing data. *Encounter projection in P1.* | AA |
| Anything else (`ORM`, `MDM`, …) | Logged as unsupported                                  | AR |
| Malformed body (no `MSH`)     | Logged                                                  | AR |

The listener never silently drops a message — every inbound frame gets
a framed reply.

## Why ack-without-persist?

The alternative is sending NAK on every result we don't yet know how to
project into a `LabResult` row. Real analyzers respond to NAK by
retrying forever, which floods our log + the LIS network. Acking + logging
gives us:

- **Operational visibility** — DevOps can see traffic the moment a hospital
  cuts its analyzer over to the new endpoint.
- **No retry storms** — analyzers move on after a successful ACK.
- **A clear P1 hand-off** — the persistence + EMPI matching work has a
  scoped seam (the dispatcher) and a known input shape (the parsed ORU body).

## Configuration

Defaults — the listener is **off** everywhere out of the box.

| Property                    | Env var                       | Default     | Notes |
| --------------------------- | ----------------------------- | ----------- | ----- |
| `app.hl7.mllp.enabled`      | `APP_HL7_MLLP_ENABLED`        | `false`     | Master switch. The bean is not created at all when false. |
| `app.hl7.mllp.port`         | `APP_HL7_MLLP_PORT`           | `2575`      | IANA-registered HL7 port. Use `0` for an ephemeral port (tests). |
| `app.hl7.mllp.bindAddress`  | `APP_HL7_MLLP_BIND_ADDRESS`   | `0.0.0.0`   | Pin to a private interface in production. |
| `app.hl7.mllp.charset`      | `APP_HL7_MLLP_CHARSET`        | `UTF-8`     | HL7 v2 traditionally uses ISO-8859-1 — agree with the analyzer. |
| `app.hl7.mllp.maxFrameBytes`| `APP_HL7_MLLP_MAX_FRAME_BYTES`| `1048576`   | 1 MB cap defends against junk traffic. |
| `app.hl7.mllp.readTimeoutMs`| `APP_HL7_MLLP_READ_TIMEOUT_MS`| `60000`     | Per-socket idle timeout. |

To enable on Railway / dev:
```
APP_HL7_MLLP_ENABLED=true
APP_HL7_MLLP_PORT=2575
APP_HL7_MLLP_BIND_ADDRESS=0.0.0.0
```

## Smoke test

```bash
# 1. Boot with the listener on
./gradlew :hospital-core:bootRun -Pargs='--spring.profiles.active=local-h2 \
    --app.hl7.mllp.enabled=true --app.hl7.mllp.port=2575'

# 2. From another shell, fire a framed ORU^R01
python3 - <<'PY'
import socket
msg = (
    "MSH|^~\\&|MINDRAY|LAB1|HMS|HOSP1|20260428073000||ORU^R01|MSG-1|P|2.5.1\r"
    "PID|1||p-uuid\r"
    "OBR|1||ord-1|GLU^Glucose|||20260428073000\r"
    "OBX|1|NM|GLU^Glucose||5.6|mmol/L|||N\r"
)
frame = b"\x0b" + msg.encode() + b"\x1c\x0d"
s = socket.create_connection(("127.0.0.1", 2575), timeout=5)
s.sendall(frame)
buf = b""
while True:
    chunk = s.recv(1024)
    if not chunk: break
    buf += chunk
    if b"\x1c\x0d" in buf: break
print(buf.replace(b"\x0b", b"<VT>").replace(b"\x1c\x0d", b"<FS><CR>").decode())
PY
```

Expected reply (one frame):
```
<VT>MSH|^~\&|HMS|HOSP1|MINDRAY|LAB1|...||ACK|...|P|2.5.1
MSA|AA|MSG-1<FS><CR>
```

## Operational notes

- **Pin the port.** The listener binds *before* Spring's web container is
  ready to serve health checks. If the port is in use the application fails
  fast with a clear `Address already in use` error.
- **mTLS / firewall.** The HMS process speaks plain MLLP; production
  deployments should put nginx / stunnel in front for TLS, and restrict the
  source IPs at the firewall layer.
- **Multi-tenancy.** This first cut accepts messages from any sender. P1 will
  reject senders that do not match a registered facility on
  `Hospital.organization`.

## Next steps (P1)

- Persist `ORU^R01` results as `LabResult` rows once OBR-3 → `LabOrder.id`
  resolution and the integration-service assignment context are wired.
- Project `ADT^A01/A04/A08` into `Patient` + `Encounter` via the EMPI service.
- Add per-facility allowlist (sending facility → hospital).
- Optionally swap the hand-rolled inspector for HAPI HL7v2 once we add
  the ASTM bridge for serial-only analyzers (Mindray BC-3000+, BS-120).
