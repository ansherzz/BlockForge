package com.blockforge.mempool;

import com.blockforge.transaction.Transaction;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Pool of pending (unconfirmed) transactions awaiting inclusion in the next block.
 *
 * Transactions are ordered by fee descending so miners maximise revenue per block.
 * The pool is bounded at MAX_CAPACITY to prevent unbounded memory growth.
 *
 * In a real network, the mempool would also:
 *   - Validate inputs against the current UTXO set (to reject double-spends early).
 *   - Evict low-fee transactions when full, rather than rejecting outright.
 *   - Expire transactions that haven't been mined within a time window.
 */
public class Mempool {

    private static final int MAX_CAPACITY = 500;

    private final Map<String, Transaction> pool = new LinkedHashMap<>();

    /**
     * Adds a signed transaction to the pool.
     *
     * Rejects transactions with invalid signatures or when the pool is full.
     * Returns true if the transaction was accepted.
     */
    public synchronized boolean add(Transaction tx) {
        if (tx == null) return false;
        if (pool.containsKey(tx.transactionId)) return false; // duplicate

        if (pool.size() >= MAX_CAPACITY) {
            System.out.println("  [Mempool] Pool full — transaction rejected");
            return false;
        }
        if (!tx.verifySignature()) {
            System.out.println("  [Mempool] Invalid signature — transaction rejected");
            return false;
        }

        pool.put(tx.transactionId, tx);
        System.out.printf("  [Mempool] +tx id=%-16s  fee=%.2f  pool=%d/%d%n",
            tx.transactionId.substring(0, Math.min(16, tx.transactionId.length())),
            tx.fee, pool.size(), MAX_CAPACITY);
        return true;
    }

    /**
     * Returns up to {@code max} transactions sorted by fee (highest first),
     * removing them from the pool. Called by the miner when building a block.
     */
    public synchronized List<Transaction> drain(int max) {
        List<Transaction> picked = pool.values().stream()
            .sorted(Comparator.comparingDouble((Transaction t) -> t.fee).reversed())
            .limit(max)
            .collect(Collectors.toList());
        picked.forEach(tx -> pool.remove(tx.transactionId));
        return picked;
    }

    /** Puts transactions back (e.g. when a block was rejected and its txs need re-queuing). */
    public synchronized void requeue(Collection<Transaction> txs) {
        txs.forEach(this::add);
    }

    public synchronized int     size()    { return pool.size(); }
    public synchronized boolean isEmpty() { return pool.isEmpty(); }

    /** Read-only snapshot of pending transactions (for display). */
    public synchronized List<Transaction> snapshot() {
        return new ArrayList<>(pool.values());
    }
}
