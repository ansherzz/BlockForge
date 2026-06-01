# BlockForge — Architecture Deep Dive

This document explains the design decisions and data flows in each part of BlockForge. It is intended to be read alongside the source code.

---

## Part 1 — Blockchain Foundations

### Goal
Prove that a linked list of hashed blocks, combined with proof-of-work mining, creates a data structure that is computationally expensive to tamper with.

### Data Flow

```
NoobChain.main()
  │
  ├── addBlock(new Block("Genesis", "0"))
  │     Block.mineBlock(difficulty=4)
  │       loop: nonce++ until hash starts with "0000"
  │       SHA-256(previousHash + timestamp + nonce + data)
  │
  ├── addBlock(new Block("Sent £100 to Bob", genesis.hash))
  │     ... same mining loop
  │
  ├── isChainValid()
  │     for each block:
  │       recalculate hash → compare to stored hash (tamper check)
  │       compare previousHash → compare to previous block's hash (chain check)
  │       check hash starts with "0000" (proof-of-work check)
  │
  └── DEMO: mutate block[1].data → re-run isChainValid() → fails
```

### Why Proof of Work?

Without mining cost, an attacker could silently recalculate hashes for a tampered chain in milliseconds. Requiring the hash to start with N zeros means ~16^N hash attempts on average — at difficulty 4, that's ~65 000 attempts per block. The attacker must redo all subsequent blocks faster than the honest network adds new ones, which becomes exponentially harder as the chain grows.

### SHA-256 Properties Used

| Property | How BlockForge uses it |
|---|---|
| Deterministic | Same block content always produces the same hash |
| Avalanche effect | Changing one byte in `data` changes every bit in the hash |
| One-way | Cannot reverse-engineer block content from its hash |
| Collision-resistant | Practically impossible to find two different blocks with the same hash |

---

## Part 2 — Wallets & Transactions

### Goal
Replace the plain `data` string in each block with cryptographically-verified coin transfers between wallet addresses. Prevent double-spending via the UTXO model.

### ECDSA Key Pairs

```
Wallet generation:
  KeyPairGenerator(ECDSA, prime192v1 curve, BouncyCastle provider)
    → privateKey   (secret — never share; used to sign transactions)
    → publicKey    (your "address" — share freely; used to verify signatures)
```

**Why prime192v1?** It is the same elliptic curve family Bitcoin uses (secp256k1 for Bitcoin, prime192v1 here). Elliptic curve cryptography gives the same security level as RSA with a much smaller key size — important for a resource-constrained environment like a blockchain node.

### Transaction Lifecycle

```
1. wallet.sendFunds(recipient, 40.0)
      │
      ├── getBalance() — scan UTXOs map, find outputs addressed to this publicKey
      ├── collect UTXOs until sum >= 40.0
      ├── create Transaction(from, to, 40.0, inputs)
      └── tx.generateSignature(privateKey)
              ECDSA.sign(privateKey, senderKey + recipientKey + "40.0")
              → signature bytes stored on the transaction

2. block.addTransaction(tx)
      │
      └── tx.processTransaction()
              ├── verifySignature()         proves sender owns the private key
              ├── resolve inputs            look up each input UTXO in global map
              ├── check total >= minimum    prevents dust attacks
              ├── create outputs:
              │     output[0] → recipient, value=40.0
              │     output[1] → sender,    value=(inputs_total - 40.0)  ← change
              ├── add outputs to global UTXOs map
              └── remove consumed inputs from global UTXOs map
```

### UTXO Model vs Account Model

Ethereum uses an **account model**: each address has a balance counter.

Bitcoin (and BlockForge Part 2+) uses the **UTXO model**:

- There are no balances. Coins exist as discrete "unspent transaction outputs."
- Your "balance" is the sum of all UTXOs at your public key.
- Spending a UTXO destroys it and creates new UTXOs.

**Advantage:** Each UTXO can only be spent once (enforced by removing it from the map). Double-spending is impossible without breaking the signature and the chain.

### Merkle Tree

```
Transactions: [T1, T2, T3, T4]

Layer 0:  [T1]       [T2]       [T3]       [T4]
                 ↘ ↙                   ↘ ↙
Layer 1:    H(T1+T2)              H(T3+T4)
                       ↘ ↙
Layer 2:          H(H12 + H34)   ← Merkle Root (stored in block header)
```

The root is included in the block hash calculation. Changing any transaction changes the root, which changes the block hash, which invalidates every subsequent block — exactly the same tamper-detection guarantee as Part 1, but now covering the whole transaction set efficiently.

---

## Part 3 — Full-Stack Application

### Architectural Change: Breaking Static State

Parts 1 and 2 store the UTXO pool and difficulty in static fields on the main class (`NoobCoin.UTXOs`). This works for a single-chain simulation but makes it impossible to have multiple independent chain instances in the same JVM — which is exactly what P2P simulation needs.

Part 3 introduces `Blockchain` as a proper context object:

```java
// Before (Part 2):
NoobCoin.UTXOs.get(id)           // static reference

// After (Part 3):
chain.UTXOs.get(id)              // instance reference, passed explicitly
transaction.processTransaction(chain)
block.addTransaction(tx, chain)
wallet.getBalance(chain)
```

Every `Node` in the P2P network holds its own `Blockchain` instance, and all operations are scoped to that instance.

### Thread Safety

`Blockchain` uses `synchronized` on all public methods. When the P2P network propagates a block (`Node.receiveBlock()` runs on a separate executor thread), it calls `blockchain.addValidatedBlock()` which acquires the lock. This prevents race conditions when multiple peers broadcast simultaneously.

### REST API Design

```
Request path parsing:
  /api/block/3
    └── parts = path.split("/") → ["", "api", "block", "3"]
               parts[3] = "3" → int index

Route registration (order matters — longer prefixes first):
  /api/chain/validate   → registered before /api/chain
  /api/chain/save       → registered before /api/chain
  /api/chain            → catch-all for /api/chain
  /api/block/           → catches /api/block/{index}
  /api/wallet/new       → registered before /api/wallet/
  /api/wallet/          → catches /api/wallet/{id}/balance
```

Serialization: `PublicKey` and `PrivateKey` objects are not directly JSON-serializable. The API converts them to Base64 strings (`StringUtil.getStringFromKey`) for display. The raw cryptographic objects never leave the server.

### P2P Consensus — Longest Chain Rule

```
Node A mines block #5 and broadcasts:
  │
  ├── Node B receives block #5
  │     B.latest.hash == block.previousHash?
  │       YES → addValidatedBlock(block)   [accept, re-broadcast]
  │       NO  → B.size < A.size?
  │               YES → requestChainSync(A)  [replace chain if valid]
  │               NO  → ignore (stale/orphan)
  │
  └── Node C receives block #5 (from B)
        ... same logic
```

In this simulation, nodes are in the same JVM and communication is synchronous method calls. In a real network, blocks travel over TCP and nodes must handle network latency, fork resolution, and Byzantine faults — but the consensus rule is the same.

### Persistence Strategy

```
ChainStore.save()
  Loop over chain:
    Build view map (crypto keys → truncated Base64, no private data)
    Gson serialise to JSON
  Write to data/chain-exports/chain-{timestamp}.json

ChainStore.exportSummary()
  Write human-readable block explorer table
  to data/exports/summary-{timestamp}.txt
```

**Full re-loading:** To restore a chain from JSON, you would need to also restore the wallet private keys (to verify existing signatures and create new transactions). This requires a keystore — encrypted storage for private keys, like PKCS#12. BlockForge intentionally omits this to keep the codebase focused on blockchain mechanics rather than key management infrastructure.

---

## Key Design Principles

**Progressive complexity.** Each part builds on the previous. You can read them in order and understand exactly what each addition enables.

**Minimal dependencies.** The only libraries are Gson (JSON) and BouncyCastle (ECDSA). The REST API uses Java's built-in `HttpServer` — no Spring, no Spark, no framework overhead.

**Educational comments.** Comments explain *why*, not *what*. The code is structured so that the class/method names describe the what, and inline comments explain non-obvious invariants.

**No mutable shared state.** Part 3's core engine has no static fields. All state is owned by either a `Blockchain` instance or a `Wallet` instance, making the code testable and the P2P simulation possible.
