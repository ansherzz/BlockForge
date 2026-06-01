package com.blockforge.mining;

import com.blockforge.chain.Block;
import com.blockforge.chain.Blockchain;
import com.blockforge.mempool.Mempool;
import com.blockforge.transaction.Transaction;
import com.blockforge.transaction.TransactionOutput;
import com.blockforge.wallet.Wallet;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles and mines the next block.
 *
 * Each block contains:
 *   1. A coinbase transaction paying the miner the block reward plus all fees.
 *   2. Up to MAX_TX_PER_BLOCK user transactions drawn from the mempool, ordered
 *      by fee so the miner maximises revenue.
 *
 * Block reward halving: the base reward halves every HALVING_INTERVAL blocks,
 * mirroring Bitcoin's supply-cap mechanism. With INITIAL_BLOCK_REWARD=50 and
 * HALVING_INTERVAL=210 000, total supply converges to 21 000 000 coins.
 */
public class Miner {

    public static final float INITIAL_BLOCK_REWARD = 50f;
    public static final int   HALVING_INTERVAL     = 210_000;
    public static final int   MAX_TX_PER_BLOCK     = 10;
    public static final float FEE_RATE             = 0.005f;  // 0.5 % of tx value
    public static final float MINIMUM_FEE          = 0.01f;

    private final String     minerId;
    private final Wallet     rewardWallet;
    private final Blockchain blockchain;
    private final Mempool    mempool;

    public Miner(String minerId, Wallet rewardWallet, Blockchain blockchain, Mempool mempool) {
        this.minerId      = minerId;
        this.rewardWallet = rewardWallet;
        this.blockchain   = blockchain;
        this.mempool      = mempool;
    }

    // ── Mining ────────────────────────────────────────────────────────────────

    /**
     * Mines the next block and appends it to the chain.
     *
     * Algorithm:
     *   1. Grab the highest-fee pending transactions from the mempool.
     *   2. Process each transaction — any that fail are returned to the pool.
     *   3. Compute coinbase amount = base reward + total fees collected.
     *   4. Prepend coinbase to the block and run proof-of-work.
     */
    public Block mine() {
        Block prev   = blockchain.getLatestBlock();
        int   height = (prev != null) ? prev.height + 1 : 0;
        Block block  = new Block(prev != null ? prev.hash : "0", height);

        // Include pending transactions (highest fee first)
        List<Transaction> candidates = mempool.drain(MAX_TX_PER_BLOCK - 1);
        List<Transaction> rejected   = new ArrayList<>();

        for (Transaction tx : candidates) {
            if (!block.addTransaction(tx, blockchain)) {
                rejected.add(tx); // invalid at current state — put back
            }
        }
        if (!rejected.isEmpty()) mempool.requeue(rejected);

        // Coinbase: base reward + fees from all successfully included transactions
        float reward   = blockReward(height) + block.getTotalFees();
        Transaction cb = buildCoinbase(height, reward);
        block.addCoinbaseTransaction(cb, blockchain);

        blockchain.addBlock(block);
        return block;
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    /**
     * Block reward at a given height.
     * Halves every HALVING_INTERVAL blocks; returns 0 after 64 halvings.
     */
    public static float blockReward(int height) {
        int halvings = height / HALVING_INTERVAL;
        if (halvings >= 64) return 0f;
        return INITIAL_BLOCK_REWARD / (float) (1L << halvings);
    }

    /** Suggested fee for a transaction of the given value. */
    public static float suggestedFee(float value) {
        return Math.max(MINIMUM_FEE, value * FEE_RATE);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private Transaction buildCoinbase(int height, float reward) {
        Transaction cb = new Transaction(null, rewardWallet.getPublicKey(), reward, 0f, new ArrayList<>());
        cb.transactionId = "coinbase-" + height;

        TransactionOutput output = new TransactionOutput(rewardWallet.getPublicKey(), reward, cb.transactionId);
        cb.outputs.add(output);
        // UTXOs are registered in Block.addCoinbaseTransaction()

        return cb;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String getId()             { return minerId; }
    public Wallet getRewardWallet()   { return rewardWallet; }
}
