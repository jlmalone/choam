# CHOAM Vision - Unified Data Storage Protocol

*Distilled from conversation 2026-02-13*

## The Vision
**Build a unified data storage protocol that works for everything from private messages to 4K movie streaming, with graduated trust and censorship resistance.**

---

## Core Principles

### 1. Multi-Tier Availability (Privacy Through Preference)
Not pure P2P, but **biased towards trusted nodes**:

- **Tier 1**: Own devices (Tailscale private mesh)
- **Tier 2**: Friend nodes (semi-trusted)
- **Tier 3**: Firebase/Cloud (convenient, encrypted)
- **Tier 4**: Public P2P/IPFS (deniability layer)

> *"Like Freenet but biased towards own nodes and friend nodes. Open enough for deniability, but biased towards one's own tribe."*

**Key insight**: Observer can't tell which tier provided the data. Government sees HTTPS traffic but can't determine source or destination within the mesh.

### 2. Graduated Trust (Not Binary)
The breakthrough insight - trust is **contextual and layered**:

```
Storage ≠ View Key ≠ Social Contract
```

**Real-world examples:**

#### Calendar with Best Friend
- Give them the view key
- Request they don't reshare
- Accept they might anyway
- *"Whatever, his classmates now know I have a dentist appointment."*
- **Acceptable risk** - not the end of the world

#### Banking Details
- Give them encrypted storage
- DON'T give them the key
- *"I want him to provide a safe, but I don't give him the key to open it."*
- **Cryptographic guarantee** - they literally cannot read it

**Three distinct capabilities:**
1. **STORE** - "Hold this encrypted blob for me" (no view key)
2. **VIEW** - "Here's the decryption key, you can read it" (with view key)
3. **RESHARE** - Social contract, not cryptographically enforced, acceptable risk

### 3. Economy of Data
Different content requires different strategies - the protocol should **automatically balance**:

| Factor | What It Means |
|--------|---------------|
| **Duplication** | Hot content → many copies, cold content → few copies |
| **Decentralization** | Private → own devices only, public → wide distribution |
| **Availability** | Critical data → high-availability nodes, archives → single copy |
| **Encryption** | Sensitive → encrypted with selective key sharing, public → cleartext |
| **Device Matching** | Movies → servers with bandwidth, calendar → phones for sync |

**Content profiling determines strategy automatically:**
- 4K movie you're watching → replicate to fast servers, streaming capability required
- Old archived movie → single copy on NAS, no streaming needed
- Private calendar → replicate to phone/desktop/server, encrypted, no view keys widely distributed
- Banking backup → encrypted, limited replication, storage-only (no view keys)
- Shared party photos → encrypted, friends can have view keys, social trust not to reshare widely
- Public blog post → unencrypted, replicate aggressively, anyone can host

### 4. Real-World Requirements

**Async-first design:**
- Don't care about real-time
- Slow sync is acceptable
- Eventual consistency is fine

**Jellyfin-compatible:**
- Must work with standard media servers
- HTTP streaming with range requests
- Standard content APIs

**Heterogeneous nodes:**
- Phones (limited storage, battery, intermittent)
- Desktops (medium storage, good when awake)
- Servers (24/7, large storage, high bandwidth)
- NAS (bulk archives, slower access)
- Cloud (unlimited, expensive, encrypted blobs only)

**Pragmatic, not dogmatic:**
- Firebase as first pass is fine
- Not purist about decentralization
- Use centralized services where it makes sense
- Fall back through tiers gracefully

### 5. Censorship Resistance (Iran Use Case)

> *"What would work well in Iran dealing with authoritarian government"*

**Key properties:**
- All traffic looks like normal HTTPS
- Can't distinguish which tier provided data
- No single point of failure to block
- Multiple fallback layers (own devices → friends → cloud → public P2P)
- Deniability built-in ("I just downloaded from public network")
- Works on local mesh networks if internet is cut
- Degrades gracefully (slower, not broken)

**What government sees:**
- Encrypted traffic to various servers
- Can't determine content (end-to-end encrypted)
- Can't identify sources within mesh
- Can't block all tiers simultaneously without blocking entire internet

**What dissidents get:**
- Private documents: encrypted backup on friend nodes (storage-only, no view keys)
- Meeting schedules: encrypted, shared with activist network (view keys + social trust)
- Public propaganda: encrypted but key is public (deniability + distribution)

---

## The Unified Protocol Vision

> **"Should all be smooth under the same architecture and protocol."**

One protocol that handles all content types:

| Content | Size | Sensitivity | Replication Strategy |
|---------|------|-------------|---------------------|
| 4K Movie (watching now) | 15GB | Public | Server + Desktop, streaming, 2-4 replicas |
| Movie Archive | 15GB | Public | NAS only, single copy, no streaming |
| Private Calendar | 1KB | Private | Phone + Desktop + Server, encrypted, 3-5 replicas |
| Banking PDF | 100KB | Secret | Server + Cloud, encrypted, storage-only (no view keys) |
| Party Photos | 5MB | Semi-Private | Desktop + Friend nodes, encrypted, view keys to friends |
| Blog Post | 10KB | Public | Server + Cloud, unencrypted, aggressive replication |

**Properties of the protocol:**
- ✅ Content-addressed (immutable, deduplicated)
- ✅ Encryption-aware (sensitive data stays encrypted)
- ✅ Node-aware (right content on right devices)
- ✅ Resource-efficient (cold data on cheap storage, hot data on fast storage)
- ✅ Availability-optimized (critical data replicated more)
- ✅ Jellyfin-compatible (standard HTTP streaming)
- ✅ Censorship-resistant (multi-tier fallback)
- ✅ Privacy-preserving (social trust layers)

---

## What Makes This Different

### Most "Web3" Projects:
*"Just encrypt everything and put it on IPFS!"*

❌ Dogmatically decentralized
❌ Binary encrypted/unencrypted
❌ One-size-fits-all replication
❌ Ignores social trust
❌ Impractical about device constraints

### This Vision:
**Pragmatic, trust-aware, resource-conscious data layer that respects how humans actually share information.**

✅ Uses centralized services where appropriate (Firebase, cloud)
✅ Graduated trust (storage vs view keys vs social contracts)
✅ Content-aware replication (hot/warm/cold strategies)
✅ Respects social trust (not everything needs cryptographic enforcement)
✅ Realistic about devices (phones aren't servers)

**Sophisticated because it mirrors reality**: Some things need cryptographic guarantees, some things need social trust, and most things need both in different proportions.

---

## Architecture Sketch

```
┌─────────────────────────────────────────────┐
│ APPLICATION LAYER                           │
│ - Jellyfin (media streaming)                │
│ - Task Management                           │
│ - Calendar, Contacts, Messaging             │
└─────────────────────────────────────────────┘
           ↓ uses
┌─────────────────────────────────────────────┐
│ CONTENT LAYER (Content-Addressed)           │
│ - Hash-based addressing                     │
│ - Encryption (content-aware)                │
│ - Capability URLs (view/store)              │
│ - Metadata (type, size, sensitivity)        │
└─────────────────────────────────────────────┘
           ↓ stored on
┌─────────────────────────────────────────────┐
│ STORAGE LAYER (Heterogeneous Nodes)        │
│ - Phones (limited, transient)              │
│ - Desktops (medium, intermittent)          │
│ - Servers (large, persistent, 24/7)        │
│ - Cloud (unlimited, encrypted blobs)       │
└─────────────────────────────────────────────┘
           ↓ managed by
┌─────────────────────────────────────────────┐
│ REPLICATION LAYER (Economy of Data)        │
│ - Content profiling (hot/warm/cold)        │
│ - Node capability matching                  │
│ - Replication policies (automatic)          │
│ - Gossip protocol (coordination)            │
└─────────────────────────────────────────────┘
```

### Capability URLs

```
// Storage capability (no decryption)
content://store/sha256:abc123

// View capability (with key)
content://view/sha256:abc123?key=aes256:def456

// View + social policy hint
content://view/sha256:abc123?key=aes256:def456&policy=private
```

### Gossip Protocol

Nodes periodically announce:
- What content they have (inventory)
- What capabilities they offer (storage, streaming, transcoding)
- What content they need (under-replicated)
- What content they can share (with or without view keys)

Trusted peers coordinate replication automatically based on content profiles and node capabilities.

---

## Next Steps (When Ready)

1. **Extend task protocol** - Add multi-tier availability to existing protocol
2. **Tailscale mesh sync** - Content server on existing machines
3. **Content profiling** - Automatic replication policy determination
4. **Jellyfin adapter** - Streaming server with range requests
5. **Gossip protocol** - Peer coordination for replication
6. **Friend node whitelist** - Trust levels and capabilities

---

## Related Context

- **Task management systems**: Already have immutable signed nodes, local-first sync, Ed25519 signatures
- **Reputation/identity systems**: Blockchain identity layers could serve as trust/discovery foundation
- **Tailscale Mesh**: Private mesh networking across machines
- **Content repositories**: Media analysis pipelines and download managers that could benefit from this architecture
- **Jellyfin**: Media server that should integrate seamlessly

---

*This document captures the vision. Implementation is future work when the time is right.*
