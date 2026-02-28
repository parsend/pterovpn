# Protocol compatibility report: Go client vs Java server

## 1. Handshake format

### Go WriteHandshake (internal/protocol/protocol.go)

| Offset | Field      | Size | Value                             |
|--------|------------|------|-----------------------------------|
| 0      | magic      | 5    | "PTVPN" (fixed bytes)             |
| 5      | version    | 1    | 1                                 |
| 6      | role       | 1    | 1=UDP, 2=TCP                      |
| 7      | tokenLen   | 2    | big-endian uint16                 |
| 9      | token      | N    | tokenLen bytes                    |
| 9+N    | channelId  | 1    | only if role==UDP                 |

### Java read (current design)

| Source | Expected format |
|--------|-----------------|
| readMessage | magic = SHA256("ptevpn:"+token)[0:5], then type byte (TYPE_HANDSHAKE=0), then readHandshakeBody |
| readHandshakeBody | version, role, tokenLen, token, [channelId if UDP] |

### Mismatches

| Aspect | Go | Java | Result |
|--------|-----|------|--------|
| Magic | Fixed "PTVPN" (5 bytes) | SHA256("ptevpn:"+token)[0:5] | Incompatible |
| After magic | version | type byte (0) | Go sends version, Java expects type 0 |
| Type byte | Not sent | Required | Misaligned parsing |
| Token in handshake | Sent in body | Same | Compatible once parsing fixed |

### Java missing methods

- `readHandshake(InputStream)` — ConnectionHandler calls it, but only `readMessage(expectedMagic)` and `readHandshakeBody` exist. No standalone `readHandshake(in)`.

---

## 2. UDP frame format

### Go WriteUDPFrame / ReadUDPFrame (internal/protocol/protocol.go)

| Offset | Field   | Size | Value                             |
|--------|---------|------|-----------------------------------|
| 0      | flen    | 4    | big-endian uint32 (payload length)|
| 4      | msgType | 1    | msgUDP = 1                        |
| 5      | addrType| 1    | 4=IPv4, 6=IPv6                    |
| 6      | srcPort | 2    | big-endian uint16                 |
| 8      | dstIP   | 4/16 | IPv4 or IPv6                      |
| 8+ipLen| dstPort | 2    | big-endian uint16                 |
| 10+ipLen| payload| rest | raw bytes                         |

`flen` = 1 + 1 + 2 + ipLen + 2 + len(payload) (msgType + addrType + srcPort + dstIP + dstPort + payload).

### Java readUdpFrameBody (current)

| Offset | Field    | Size | Value                             |
|--------|----------|------|-----------------------------------|
| 0      | frameLen | 4    | readU32                           |
| 4      | buf      | frameLen bytes                    |
| buf[0] | addrType | 1    | Treats first byte as addrType     |
| buf[1:3]| srcPort | 2    | big-endian                        |
| ...    | dstIP    | 4/16 |                                   |
| ...    | dstPort  | 2    |                                   |
| rest   | payload  | ...  |                                   |

### Mismatches

| Aspect | Go | Java | Result |
|--------|-----|------|--------|
| Msg type in body | buf[0] = 1 (msgUDP) | buf[0] = addrType | Java interprets 1 as addrType (invalid) |
| Body layout | [type, addrType, srcPort, dstIP, dstPort, payload] | [addrType, srcPort, dstIP, dstPort, payload] | Off by 1 byte |
| Type constant | msgUDP = 1 | TYPE_UDP_FRAME = 2 | Different numeric values |

### Java missing methods

- `readUdpFrame(InputStream)` — ConnectionHandler calls it. `readUdpFrameBody` exists but expects body without type byte and is used via `readMessageAfterHandshake`.
- `writeUdpFrame(OutputStream, UdpFrame)` — UdpSessions calls it, not implemented in Protocol.

---

## 3. TCP connect format

### Go WriteTcpConnect (internal/protocol/protocol.go)

Sent immediately after handshake, no length prefix, no message type:

| Offset | Field   | Size | Value              |
|--------|---------|------|--------------------|
| 0      | addrType| 1    | 4=IPv4, 6=IPv6     |
| 1      | ip      | 4/16 | IP bytes           |
| 1+ipLen| port    | 2    | big-endian uint16  |

### Java readTcpConnectBody (current)

| Offset | Field    | Size | Value              |
|--------|----------|------|--------------------|
| 0      | addrType | 1    | readU8             |
| 1      | ip       | 4/16 | readAddr           |
| 1+ipLen| port     | 2    | readU16            |

### Mismatches

- Format matches.
- Java `readTcpConnect(InputStream)` is called by ConnectionHandler but does not exist; only `readTcpConnectBody` exists.

---

## 4. Flow comparison

### Go client

1. TCP: handshake (PTVPN...) → TcpConnect (addrType, ip, port) → raw tunnel
2. UDP: handshake (PTVPN...) → UDP frames [length, 1, addrType, srcPort, dstIP, dstPort, payload]...

### Java server (intended)

1. readHandshake(in) — missing
2. UDP: readUdpFrame(in) — missing; readUdpFrameBody assumes no type byte
3. TCP: readTcpConnect(in) — missing; only readTcpConnectBody exists
4. writeUdpFrame(out, f) — missing

---

## 5. Minimal fix steps

### Step 1: Java handshake (align with Go)

1. Use fixed magic `"PTVPN"` (5 bytes), not `magicFromToken`.
2. Add `readHandshake(InputStream in)`:
   - read 5-byte magic, verify = "PTVPN"
   - read version (1 byte), check == 1
   - read role (1 byte)
   - read tokenLen (2 bytes, big-endian)
   - read token (tokenLen bytes)
   - if role == ROLE_UDP: read channelId (1 byte); else channelId = -1
   - return `new Handshake(role, channelId, token)`
3. Remove or stop using `readMessage` / `magicFromToken` for this flow.

### Step 2: Java TCP connect

Add:

```java
static TcpConnect readTcpConnect(InputStream in) throws IOException {
  return readTcpConnectBody(in);
}
```

### Step 3: Java UDP frame read (align with Go)

1. Add `readUdpFrame(InputStream in)` that:
   - reads frameLen (4 bytes, big-endian)
   - reads frameLen bytes into buf
   - treats buf[0] as msg type (accept 1 or 2 as UDP)
   - treats buf[1] as addrType
   - parses srcPort at buf[2:4], dstIP, dstPort, payload with off=2
2. Ensure body layout matches Go: [type, addrType, srcPort, dstIP, dstPort, payload].

### Step 4: Java UDP frame write (align with Go)

Add `writeUdpFrame(OutputStream out, UdpFrame f)`:

1. Write frame length (4 bytes, big-endian): 1 + 1 + 2 + ipLen + 2 + payload.length
2. Write type byte: 1 (msgUDP)
3. Write addrType (1 byte)
4. Write srcPort (2 bytes, big-endian)
5. Write dst IP bytes (4 or 16)
6. Write dstPort (2 bytes, big-endian)
7. Write payload
8. flush

### Step 5: ConnectionHandler

- Replace `Protocol.readHandshake(in)` with the new implementation (no API change if method is added).
- Replace `Protocol.readUdpFrame(in)` and `Protocol.readTcpConnect(in)` with the new methods.

---

## 6. Summary table

| Component     | Go sends / expects | Java expects / sends | Fix location      |
|---------------|--------------------|----------------------|-------------------|
| Handshake     | PTVPN, version, role, tokenLen, token, [channelId] | SHA256 magic, type 0, body | Java: use PTVPN, add readHandshake |
| TCP connect   | addrType, ip, port | (readTcpConnect missing) | Java: add readTcpConnect = readTcpConnectBody |
| UDP frame     | len, 1, addrType, srcPort, dstIP, dstPort, payload | len, addrType, ... (no type) | Java: add type handling, add writeUdpFrame |
| writeUdpFrame | —                  | (missing)            | Java: implement   |
