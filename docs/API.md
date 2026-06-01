# BlockForge REST API Reference

Start the server with `./run.sh part3 api` (listens on port 4567).

Base URL: `http://localhost:4567`

All responses are `Content-Type: application/json`.

---

## Endpoints

### `GET /api/info`
Server status and full endpoint listing.

```bash
curl http://localhost:4567/api/info
```

```json
{
  "name": "BlockForge REST API",
  "version": "1.0",
  "chainLength": 1,
  "difficulty": 3,
  "wallets": 0,
  "endpoints": [...]
}
```

---

### `GET /api/chain`
Returns every block in the chain.

```bash
curl http://localhost:4567/api/chain
```

```json
[
  {
    "index": 0,
    "hash": "000a3f...",
    "previousHash": "0",
    "merkleRoot": "abc123...",
    "timestamp": 1700000000000,
    "nonce": 4271,
    "transactions": [
      { "id": "0", "value": 10000.0, "inputs": 0, "outputs": 1 }
    ]
  }
]
```

---

### `GET /api/chain/validate`
Validates the entire chain — checks hashes, chain links, proof-of-work, signatures, and UTXO integrity.

```bash
curl http://localhost:4567/api/chain/validate
```

```json
{
  "valid": true,
  "chainLength": 3,
  "message": "Chain is intact"
}
```

---

### `GET /api/block/{index}`
Returns a single block by zero-based index.

```bash
curl http://localhost:4567/api/block/0
```

**404** if index out of range. **400** if index is not an integer.

---

### `POST /api/wallet/new`
Generates a new ECDSA wallet (prime192v1 curve). Returns a short wallet ID and the public key.

```bash
curl -X POST http://localhost:4567/api/wallet/new
```

```json
{
  "id": "MFkwEwYHKoZIzj0C",
  "publicKey": "MFkwEwYHKoZIzj0CAQ...",
  "balance": 0.0,
  "note": "Save this id — it is your wallet handle for future requests"
}
```

> The `id` is the first 16 characters of the Base64-encoded public key. Save it — it is the handle for all subsequent requests.

---

### `GET /api/wallet/{id}/balance`
Returns the current confirmed balance for a wallet.

```bash
curl http://localhost:4567/api/wallet/MFkwEwYHKoZIzj0C/balance
```

```json
{
  "walletId": "MFkwEwYHKoZIzj0C",
  "balance": 50.0
}
```

**404** if wallet ID not found.

---

### `POST /api/coinbase/fund`
Sends coins from the server's coinbase wallet to a user wallet. Useful for seeding balances in demos.

```bash
curl -X POST http://localhost:4567/api/coinbase/fund \
  -H 'Content-Type: application/json' \
  -d '{"to": "MFkwEwYHKoZIzj0C", "amount": 50}'
```

```json
{
  "success": true,
  "funded": 50.0,
  "to": "MFkwEwYHKoZIzj0C",
  "blockIndex": 2
}
```

**Required fields:** `to` (wallet ID)
**Optional fields:** `amount` (default: 50)

---

### `POST /api/transaction`
Transfers coins between two wallets. Creates a new block containing the transaction.

```bash
curl -X POST http://localhost:4567/api/transaction \
  -H 'Content-Type: application/json' \
  -d '{"from": "WALLET_A_ID", "to": "WALLET_B_ID", "amount": 10}'
```

```json
{
  "success": true,
  "transactionId": "a4f8b2...",
  "blockIndex": 3,
  "amount": 10.0
}
```

**Required fields:** `from`, `to` (wallet IDs), `amount`

**400** if sender has insufficient funds or amount ≤ 0.
**404** if either wallet ID not found.

---

### `POST /api/chain/save`
Exports the chain to a JSON file in `data/chain-exports/` and returns the file path.

```bash
curl -X POST http://localhost:4567/api/chain/save
```

```json
{
  "saved": true,
  "file": "/home/user/blockforge/data/chain-exports/chain-1700000000000.json"
}
```

---

## Full Workflow Example

```bash
# 1. Check server
curl http://localhost:4567/api/info

# 2. Create two wallets
ALICE=$(curl -s -X POST http://localhost:4567/api/wallet/new | jq -r '.id')
BOB=$(curl -s -X POST http://localhost:4567/api/wallet/new | jq -r '.id')

# 3. Fund Alice from coinbase
curl -X POST http://localhost:4567/api/coinbase/fund \
  -H 'Content-Type: application/json' \
  -d "{\"to\": \"$ALICE\", \"amount\": 100}"

# 4. Check Alice's balance
curl http://localhost:4567/api/wallet/$ALICE/balance

# 5. Alice sends 30 coins to Bob
curl -X POST http://localhost:4567/api/transaction \
  -H 'Content-Type: application/json' \
  -d "{\"from\": \"$ALICE\", \"to\": \"$BOB\", \"amount\": 30}"

# 6. Validate the chain
curl http://localhost:4567/api/chain/validate

# 7. View the chain
curl http://localhost:4567/api/chain

# 8. Save chain to disk
curl -X POST http://localhost:4567/api/chain/save
```

---

## Error Responses

All errors return a JSON object with an `error` field.

```json
{ "error": "Wallet 'abc123' not found" }
```

| Status | Meaning |
|---|---|
| 400 | Bad request (missing field, invalid JSON, insufficient funds) |
| 404 | Resource not found (wallet ID, block index) |
| 405 | Wrong HTTP method |
| 500 | Internal server error |
