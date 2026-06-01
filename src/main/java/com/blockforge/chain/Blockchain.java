package com.blockforge.chain;

import com.blockforge.crypto.HashUtil;
import com.blockforge.transaction.Transaction;
import com.blockforge.transaction.TransactionInput;
import com.blockforge.transaction.TransactionOutput;
import com.google.gson.GsonBuilder;

import java.util.*;

/**
 * Thread-safe blockchain engine.
 *
 * Owns the chain of blocks and the global UTXO pool. Multiple Node instances
 * can each hold an independent Blockchain copy — enabling the P2P simulation.
 *
 * Difficulty adjustment: every ADJUSTMENT_INTERVAL blocks, the difficulty is
 * raised or lowered by 1 to keep average block time near TARGET_BLOCK_MS.
 */
public class Blockchain {

    public static final int  ADJUSTMENT_INTERVAL = 10;
    public static final long TARGET_BLOCK_MS     = 5_000; // 5s target per block

    private final List<Block>                    chain   = new ArrayList<>();
    private final Map<String, TransactionOutput> UTXOs   = new HashMap<>();

    private int   difficulty;
    private final float minimumTransaction;

    public Blockchain(int initialDifficulty) {
        this(initialDifficulty, 0.1f);
    }

    public Blockchain(int initialDifficulty, float minimumTransaction) {
        this.difficulty         = initialDifficulty;
        this.minimumTransaction = minimumTransaction;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public int   getDifficulty()          { return difficulty; }
    public float getMinimumTransaction()  { return minimumTransaction; }
    public Map<String, TransactionOutput> getUTXOs() { return UTXOs; }

    public synchronized Block getLatestBlock() {
        return chain.isEmpty() ? null : chain.get(chain.size() - 1);
    }

    public synchronized Block getBlock(int index) {
        return (index >= 0 && index < chain.size()) ? chain.get(index) : null;
    }

    public synchronized int size() { return chain.size(); }

    public synchronized List<Block> getChain() {
        return Collections.unmodifiableList(chain);
    }

    // ── Block addition ────────────────────────────────────────────────────────

    /** Mine then append a block (used when this node is the miner). */
    public synchronized void addBlock(Block block) {
        block.mineBlock(difficulty);
        chain.add(block);
        adjustDifficulty();
    }

    /**
     * Validate then append an already-mined block received from a peer.
     * Returns false without modifying state if the block fails any check.
     */
    public synchronized boolean addValidatedBlock(Block block) {
        if (!block.hash.equals(block.calculateHash())) return false;
        if (!block.hash.startsWith(HashUtil.miningTarget(difficulty))) return false;
        Block latest = getLatestBlock();
        if (latest != null && !block.previousHash.equals(latest.hash)) return false;
        chain.add(block);
        adjustDifficulty();
        return true;
    }

    /** Replace the entire chain (longest-chain consensus). */
    public synchronized void replaceChain(List<Block> newChain,
                                           Map<String, TransactionOutput> newUTXOs) {
        chain.clear();
        chain.addAll(newChain);
        UTXOs.clear();
        UTXOs.putAll(newUTXOs);
    }

    // ── Validation ────────────────────────────────────────────────────────────

    public synchronized boolean isChainValid() {
        if (chain.isEmpty()) return true;

        String hashTarget = HashUtil.miningTarget(difficulty);
        Map<String, TransactionOutput> tempUTXOs = new HashMap<>();

        // Seed from genesis block outputs (genesis has no inputs to verify)
        Block genesis = chain.get(0);
        for (Transaction t : genesis.transactions) {
            for (TransactionOutput out : t.outputs) tempUTXOs.put(out.id, out);
        }

        for (int i = 1; i < chain.size(); i++) {
            Block current  = chain.get(i);
            Block previous = chain.get(i - 1);

            if (!current.hash.equals(current.calculateHash())) {
                System.out.println("  [INVALID] Block " + i + ": hash mismatch — data tampered");
                return false;
            }
            if (!previous.hash.equals(current.previousHash)) {
                System.out.println("  [INVALID] Block " + i + ": broken chain link");
                return false;
            }
            if (!current.hash.startsWith(hashTarget)) {
                System.out.println("  [INVALID] Block " + i + ": proof-of-work not satisfied");
                return false;
            }

            // Coinbase (index 0) — add outputs, skip signature/input checks
            if (!current.transactions.isEmpty()) {
                for (TransactionOutput out : current.transactions.get(0).outputs) {
                    tempUTXOs.put(out.id, out);
                }
            }

            // Normal transactions (index 1+)
            for (int j = 1; j < current.transactions.size(); j++) {
                Transaction t = current.transactions.get(j);

                if (!t.verifySignature()) {
                    System.out.println("  [INVALID] Block " + i + " tx #" + j + ": invalid signature");
                    return false;
                }
                for (TransactionInput in : t.inputs) {
                    in.UTXO = tempUTXOs.get(in.transactionOutputId);
                    if (in.UTXO == null) {
                        System.out.println("  [INVALID] Block " + i + ": UTXO not found (double-spend?)");
                        return false;
                    }
                }
                // inputs = outputs + fee  (fee goes to miner's coinbase, not in outputs)
                float diff = t.getInputsValue() - t.getOutputsValue() - t.fee;
                if (Math.abs(diff) > 0.0001f) {
                    System.out.printf("  [INVALID] Block %d tx #%d: inputs != outputs + fee (diff=%.4f)%n",
                        i, j, diff);
                    return false;
                }
                for (TransactionOutput out : t.outputs) tempUTXOs.put(out.id, out);
                for (TransactionInput  in  : t.inputs)  if (in.UTXO != null) tempUTXOs.remove(in.UTXO.id);
            }
        }
        return true;
    }

    // ── Difficulty adjustment ─────────────────────────────────────────────────

    private void adjustDifficulty() {
        int size = chain.size();
        if (size < ADJUSTMENT_INTERVAL || size % ADJUSTMENT_INTERVAL != 0) return;

        Block oldest  = chain.get(size - ADJUSTMENT_INTERVAL);
        Block newest  = chain.get(size - 1);
        long  elapsed = newest.timeStamp - oldest.timeStamp;
        long  target  = TARGET_BLOCK_MS * ADJUSTMENT_INTERVAL;

        if (elapsed < target / 2 && difficulty < 8) {
            difficulty++;
            System.out.printf("  [Difficulty] Blocks too fast (%.1fs avg) — increased to %d%n",
                (elapsed / (double) ADJUSTMENT_INTERVAL) / 1000.0, difficulty);
        } else if (elapsed > target * 2 && difficulty > 1) {
            difficulty--;
            System.out.printf("  [Difficulty] Blocks too slow (%.1fs avg) — decreased to %d%n",
                (elapsed / (double) ADJUSTMENT_INTERVAL) / 1000.0, difficulty);
        }
    }

    // ── Serialisation ─────────────────────────────────────────────────────────

    public String toJson() {
        return new GsonBuilder().setPrettyPrinting().create().toJson(chain);
    }
}
