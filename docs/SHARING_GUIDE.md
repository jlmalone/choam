# CHOAM Sharing & Access Control Guide

> How to share content between machines and with trusted peers.

## Quick Reference

```bash
# Create your identity
choam house init --name my-house

# Add a trusted peer
choam house add-peer friend-house --id <their-house-id> --ip <their-ip>

# Share a library with them
choam share grant film --with <peer-id> --access read

# See everything in one view
choam federation
```

---

## Part 1: Your House Identity

Every CHOAM user has a **House** — your identity in the federation. Think of it as your personal domain.

### Create Your House

```bash
choam house init --name my-house
```

This generates a cryptographic keypair and derives your **House ID** — a unique fingerprint that identifies you to peers. The private key is stored at `~/.choam/house_key` (owner-only permissions).

### View Your Identity

```bash
choam house status
```

Output:
```
House: my-house
  ID:          a1b2c3d4e5f6...
  Created:     2026-03-04
  Peers:       0
```

**Your House ID is public.** Share it freely — it's like a public key. Only you have the private key.

---

## Part 2: Adding Peers

A **peer** is another CHOAM user you trust. Federation is explicit — no auto-discovery.

### Step 1: Exchange House IDs

Your friend runs `choam house status` and shares their House ID with you (text, email, QR code, etc.).

### Step 2: Add the Peer

```bash
choam house add-peer friend-house --id <their-house-id> --ip <their-tailscale-ip>
```

Optional: add SSH user if different from default:
```bash
choam house add-peer friend-house --id <their-house-id> --ip <their-ip> --user theiruser
```

### Step 3: They Add You

Your friend does the same thing on their machine, adding YOUR House ID and IP.

Trust is mutual but independent — adding a peer doesn't give them access to anything. It just means you recognize their identity.

### List Your Peers

```bash
choam house peers
```

---

## Part 3: Sharing Repositories

Once you have peers, you can **share repositories** with them at different access levels.

### Access Levels

| Level | What They Can Do | Use Case |
|-------|-----------------|----------|
| **STORE** | Hold encrypted blobs. Cannot read content. | Offsite disaster recovery. Friend stores your backup, can't see your files. |
| **READ** | Pull (download) content. Can browse and stream. | Share your movie library. Friend can watch your films. |
| **WRITE** | Push (upload) and pull. Full collaboration. | Shared project. Both of you can add and modify files. |

### Grant Access

```bash
# Let a friend stream your movie library
choam share grant film --with <peer-id> --access read

# Let a friend store encrypted backups for you
choam share grant backup --with <peer-id> --access store

# Collaborate on a shared project
choam share grant project --with <peer-id> --access write
```

### View Active Shares

```bash
choam share list
```

### Revoke Access

```bash
choam share revoke film --from <peer-id>
```

**Important:** Revoking access stops future transfers. It does NOT delete data the peer already has. If you shared your movie library and they downloaded files, those files remain on their machine.

---

## Part 4: Mutual Backup

Two Houses can agree to store each other's encrypted data. This is how CHOAM provides offsite backup without cloud services.

### The Flow

1. **You offer storage:**
   ```bash
   choam backup offer --to <peer-id> --size 2TB
   ```
   "I'll store up to 2TB of your encrypted data."

2. **They accept and counter-offer:**
   ```bash
   choam backup accept --from <your-id> --their-size 1TB
   ```
   "Deal. I'll store up to 1TB of yours."

3. **Agreement is active.** Data starts flowing according to replication policies.

### View Agreements

```bash
choam backup list
```

### Agreement Lifecycle

| Status | Meaning |
|--------|---------|
| PROPOSED | You offered, waiting for acceptance |
| ACCEPTED | Both sides agreed |
| ACTIVE | Data is flowing |
| SUSPENDED | Temporarily paused (maintenance, travel, etc.) |
| TERMINATED | Permanently ended |

### Suspend or Terminate

```bash
choam backup suspend <peer-id>     # Pause temporarily
choam backup terminate <peer-id>   # End permanently (requires typing TERMINATE)
```

---

## Part 5: The Bandwidth Economy

CHOAM tracks how much data flows between you and each peer. This is reciprocity, not payment.

```bash
choam gossip economy
```

| Metric | Meaning |
|--------|---------|
| **Uploaded** | How much you've sent to this peer |
| **Downloaded** | How much you've received from this peer |
| **Balance** | Uploaded minus downloaded. Positive = you're a net contributor |
| **Priority** | HIGH (net contributor), NORMAL (balanced), LOW (net consumer), THROTTLED (heavy consumer) |

Peers who contribute more get faster transfers when they need something. No cryptocurrency — just keeping score.

---

## Part 6: Security Model

### What's Protected

- **Network layer:** Tailscale provides encrypted, authenticated tunnels between machines. CHOAM never sends data over the open internet.
- **Content integrity:** Every file has a CID (content hash). You can verify that what you received matches what was sent.
- **STORE encryption:** Files shared at STORE level are encrypted with AES-256-GCM before transfer. The peer holds opaque blobs.
- **Audit trail:** Every federation action (share, revoke, backup offer/accept) is logged with timestamps.

### What's NOT Protected

- **Compromised peer machine:** If a peer's machine is hacked, READ-level content is exposed. STORE-level content remains encrypted.
- **Physical access:** If someone has physical access to your machine, they have your data.
- **Rogue peer:** A peer with WRITE access can push malicious content. Only grant WRITE to people you deeply trust.

### Recommendations

1. Use **READ** for media sharing (movies, music)
2. Use **STORE** for backups (encryption at rest)
3. Use **WRITE** only for active collaboration with trusted partners
4. Regularly review your shares: `choam share list`
5. Back up your House key (`~/.choam/house_key`) — if you lose it, you lose your federation identity

---

## Part 7: All-In-One View

```bash
choam federation
```

Shows everything in one command: your House identity, all peers, all shares, all backup agreements. This is the quickest way to see your complete federation state.

On the web dashboard: navigate to `/federation` for the same view with interactive tables and action buttons.
