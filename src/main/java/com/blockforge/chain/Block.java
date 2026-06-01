package com.blockforge.chain;

import com.blockforge.crypto.HashUtil;
import com.blockforge.transaction.Transaction;
import com.blockforge.transaction.TransactionOutput;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * A single block in the chain.
 *
 * Structure:
 *   - Header: hash, previousHash, merkleRoot, timestamp, nonce, height
 *   - Body:   ordered list of transactions (coinbase always first)
 *
 * The header hash covers the Merkle root of all transactions, so altering any
 * transaction invalidates the block's hash and breaks the chain.
 */
public class Block {

    public String hash;
    public String previousHash;
    public String merkleRoot;
    public long   timeStamp;
    public int    nonce;
    public int    height;

    public ArrayList<Transaction> transactions = new ArrayList<>();

    public Block(String previousHash, int height) {
        this.previousHash = previousHash;
        this.height       = height;
        this.timeStamp    = new Date().getTime();
        this.hash         = calculateHash();
    }

    // ── Hashing ───────────────────────────────────────────────────────────────

    public String calculateHash() {
        return HashUtil.sha256(
            previousHash +
            Long.toString(timeStamp) +
            Integer.toString(nonce) +
            Integer.toString(height) +
            (merkleRoot != null ? merkleRoot : "")
        );
    }

    // ── Mining ────────────────────────────────────────────────────────────────

    public void mineBlock(int difficulty) {
        List<String> txIds = new ArrayList<>();
        for (Transaction t : transactions) txIds.add(t.transactionId);
        merkleRoot = HashUtil.merkleRoot(txIds);

        String target  = HashUtil.miningTarget(difficulty);
        long   startMs = System.currentTimeMillis();

        while (!hash.substring(0, difficulty).equals(target)) {
            nonce++;
            hash = calculateHash();
        }

        double elapsed = (System.currentTimeMillis() - startMs) / 1000.0;
        System.out.printf("  Block #%-4d  hash=%s  nonce=%-8d  txs=%-3d  %.2fs%n",
            height, hash.substring(0, 20) + "...", nonce, transactions.size(), elapsed);
    }

    // ── Transaction management ────────────────────────────────────────────────

    /**
     * Adds the coinbase (mining reward) transaction. Must be the first transaction
     * in every block. Skips processTransaction() — coinbase has no inputs to verify.
     */
    public void addCoinbaseTransaction(Transaction coinbase, Blockchain chain) {
        transactions.add(0, coinbase);
        for (TransactionOutput out : coinbase.outputs) {
            chain.getUTXOs().put(out.id, out);
        }
    }

    /**
     * Validates and adds a user transaction. Returns false and discards the
     * transaction if it fails processing (bad signature, insufficient funds, etc.).
     */
    public boolean addTransaction(Transaction tx, Blockchain chain) {
        if (tx == null) return false;
        if (!tx.processTransaction(chain)) return false;
        transactions.add(tx);
        return true;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public int   getTransactionCount() { return transactions.size(); }

    /** Sum of fees from all non-coinbase transactions (fee paid to miner). */
    public float getTotalFees() {
        float total = 0;
        for (int i = 1; i < transactions.size(); i++) total += transactions.get(i).fee;
        return total;
    }
}
