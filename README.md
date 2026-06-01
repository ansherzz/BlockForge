# BlockForge

A production-quality blockchain implementation built from scratch in Java. Every component — from the SHA-256 mining loop to the REST API — is written by hand with no blockchain framework, making it a clear demonstration of how distributed ledgers actually work.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                           BlockForge                                  │
│                                                                        │
│   crypto/          chain/              transaction/      wallet/      │
│   ┌───────────┐   ┌───────────────┐   ┌────────────┐   ┌──────────┐ │
│   │ HashUtil  │   │ Block         │   │ Transaction│   │ Wallet   │ │
│   │ (SHA-256, │   │ (PoW mining,  │   │ (ECDSA sig,│   │ (key pair│ │
│   │  Merkle)  │   │  Merkle root, │   │  UTXO I/O, │   │  UTXO    │ │
│   │ CryptoUtil│   │  height)      │   │  fee model)│   │  balance)│ │
│   │ (ECDSA)   │   │ Blockchain    │   │ TxInput    │   └──────────┘ │
│   └───────────┘   │ (chain +      │   │ TxOutput   │               │
│                   │  UTXOs,       │   └────────────┘               │
│                   │  sync,        │                                 │
│                   │  difficulty   │   mining/        mempool/       │
│                   │  adjustment)  │   ┌──────────┐  ┌───────────┐  │
│                   └───────────────┘   │ Miner    │  │ Mempool   │  │
│                                       │ (reward, │  │ (fee-     │  │
│                                       │  halving,│  │  ordered, │  │
│                                       │  mempool │  │  capacity │  │
│                                       │  drain)  │  │  bounded) │  │
│                                       └──────────┘  └───────────┘  │
│                                                                        │
│   network/              api/                 persistence/   cli/     │
│   ┌────────────────┐   ┌──────────────────┐  ┌──────────┐  ┌──────┐ │
│   │ Node           │   │ BlockchainServer  │  │ChainStore│  │ CLI  │ │
│   │ (independent   │   │ (built-in Java    │  │(JSON +   │  │      │ │
│   │  chain + peers,│   │  HttpServer, 11   │  │ summary  │  │      │ │
│   │  broadcast,    │   │  endpoints)       │  │ exports) │  │      │ │
│   │  consensus)    │   └──────────────────┘  └──────────┘  └──────┘ │
│   │ PeerNetwork    │                                                   │
│   │ (full-mesh)    │                                                   │
│   └────────────────┘                                                   │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Quick Start

**Requirements:** Java 11+, Maven 3.6+

```bash
git clone <repo>
cd blockforge

make demo      # Full automated walkthrough
make cli       # Interactive wallet & block explorer
make api       # REST API server → http://localhost:4567
make p2p       # Multi-node P2P consensus demo
make mining    # Mempool + miner reward demo
make test      # Run 21 unit tests
```

No `make`? Use `./run.sh` directly:

```bash
./run.sh demo | cli | api | p2p | mining | test
```

---

## What It Demonstrates

| Concept | Implementation |
|---|---|
| SHA-256 hashing | `HashUtil.sha256()` — fingerprints every block |
| Proof of Work | `Block.mineBlock()` — increments nonce until hash satisfies N leading zeros |
| Merkle tree | `HashUtil.merkleRoot()` — pair-hash transactions up a binary tree; changing any tx changes the root |
| Chain tamper detection | `Blockchain.isChainValid()` — recalculates every hash, verifies links and PoW |
| ECDSA wallets | `Wallet` — prime192v1 elliptic curve via BouncyCastle; spending requires private key signature |
| UTXO model | `Transaction` — coins are unspent outputs; balance = sum of UTXOs at your address |
| Transaction fees | `Miner.suggestedFee()` — 0.5% of value, minimum 0.01; implicit in inputs − outputs |
| Mining rewards | `Miner.blockReward()` — halves every 210 000 blocks; converges to 21M total supply |
| Mempool | `Mempool` — fee-ordered pending pool; miners drain highest-fee transactions first |
| Difficulty adjustment | `Blockchain.adjustDifficulty()` — every 10 blocks, targets 5s average |
| P2P consensus | `Node` + `PeerNetwork` — full-mesh broadcast; longest-chain rule; chain sync |
| REST API | `BlockchainServer` — 11 endpoints on Java's built-in `HttpServer` (no framework) |
| JSON persistence | `ChainStore` — timestamped JSON export + human-readable block-explorer summary |
| Unit tests | JUnit 5 — 21 tests across crypto, chain, and transaction layers |

---

## Package Structure

```
src/
├── main/java/com/blockforge/
│   ├── BlockForge.java          Main entry point (demo/cli/api/p2p/mining modes)
│   ├── chain/
│   │   ├── Block.java           Block header + tx list + PoW + Merkle root
│   │   └── Blockchain.java      Thread-safe chain engine + UTXO pool + difficulty adjustment
│   ├── crypto/
│   │   ├── HashUtil.java        SHA-256, Merkle tree, mining target
│   │   └── CryptoUtil.java      ECDSA sign/verify, key encoding
│   ├── transaction/
│   │   ├── Transaction.java     Signed transfer with fee; processes inputs → outputs
│   │   ├── TransactionInput.java  References a UTXO being consumed
│   │   └── TransactionOutput.java  A coin at a public-key address
│   ├── wallet/
│   │   └── Wallet.java          ECDSA key pair, UTXO scanning, sendFunds()
│   ├── mining/
│   │   └── Miner.java           Block assembly: coinbase + mempool txs + PoW
│   ├── mempool/
│   │   └── Mempool.java         Fee-ordered pending tx pool (capacity 500)
│   ├── network/
│   │   ├── Node.java            Independent blockchain + peer list + consensus logic
│   │   └── PeerNetwork.java     Full-mesh topology manager
│   ├── api/
│   │   └── BlockchainServer.java  REST API (11 endpoints, JSON responses)
│   ├── persistence/
│   │   └── ChainStore.java      JSON + text export to disk
│   └── cli/
│       └── BlockForgeCLI.java   11-command interactive shell
└── test/java/com/blockforge/
    ├── crypto/HashUtilTest.java        8 tests
    ├── chain/BlockchainTest.java       6 tests
    └── transaction/TransactionTest.java  7 tests
```

---

## REST API

Start the server: `./run.sh api`

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/info` | Server status, endpoint listing |
| GET | `/api/chain` | Full blockchain as JSON |
| GET | `/api/chain/validate` | Chain integrity check |
| GET | `/api/block/{index}` | Single block |
| POST | `/api/wallet/new` | Create wallet → `{id, address}` |
| GET | `/api/wallet/{id}/balance` | Wallet balance |
| POST | `/api/coinbase/fund` | Fund wallet `{to, amount}` |
| POST | `/api/transaction` | Submit to mempool `{from, to, amount}` |
| GET | `/api/mempool` | Pending transactions |
| POST | `/api/mine` | Mine the next block |
| POST | `/api/chain/save` | Export chain to disk |

Full documentation → [docs/API.md](docs/API.md)

---

## Key Design Decisions

**No blockchain framework.** Every piece — hashing, mining, signatures, UTXO tracking, Merkle trees, P2P — is written from scratch. This is intentional: using a framework would hide the mechanics.

**No HTTP framework.** The REST API uses Java's built-in `com.sun.net.httpserver.HttpServer`. No Spring, no Spark, no Jetty setup. Demonstrates that you understand HTTP at the protocol level.

**Thread-safe by design.** `Blockchain` uses `synchronized` throughout. Multiple `Node` instances run in separate executor threads and safely share data through the consensus protocol, not through shared mutable state.

**Fee model matches Bitcoin.** `inputs = outputs + fee`. The fee is not an output — it's implicit. Miners collect it by including the fee total in their coinbase transaction. `isChainValid()` enforces `inputs − outputs − fee < ε` for every transaction.

**Wallet persistence is intentionally omitted.** Private keys must not be stored in plaintext. Production wallets use encrypted keystores (PKCS#12, BIP-38). BlockForge's wallets are session-only to keep the focus on chain mechanics, not key management infrastructure.

---

## Tech Stack

| | |
|---|---|
| Language | Java 11 |
| Build | Maven 3 |
| Cryptography | BouncyCastle 1.70 (ECDSA, prime192v1) |
| JSON | Google Gson 2.10 |
| HTTP | `com.sun.net.httpserver` (built-in) |
| Tests | JUnit Jupiter 5.10 |

---

## References

- [Bitcoin Whitepaper — Satoshi Nakamoto](https://bitcoin.org/bitcoin.pdf)
- [Programmers Blockchain: Java Tutorial Series](https://medium.com/programmers-blockchain/create-simple-blockchain-java-tutorial-from-scratch-6eeed3cb03fa)
- [Blockchain Development Mega Guide](https://medium.com/programmers-blockchain/blockchain-development-mega-guide-5a316e6d10df)
