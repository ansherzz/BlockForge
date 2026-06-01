package com.blockforge.transaction;

import com.blockforge.chain.Blockchain;
import com.blockforge.crypto.CryptoUtil;
import com.blockforge.crypto.HashUtil;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;

/**
 * A signed transfer of value from one wallet to another.
 *
 * Life-cycle:
 *   1. Wallet builds the transaction and calls {@link #generateSignature}.
 *   2. Transaction is broadcast to the mempool.
 *   3. A miner picks it up, calls {@link #processTransaction}, and includes it in a block.
 *
 * Fee model:
 *   inputs_total = value (to recipient) + change (back to sender) + fee (to miner)
 *   The fee is not represented as an output — it is implicit, claimed by the miner's
 *   coinbase transaction via {@link com.blockforge.mining.Miner}.
 */
public class Transaction {

    public String    transactionId;
    public PublicKey sender;
    public PublicKey recipient;
    public float     value;
    public float     fee;
    public byte[]    signature;

    public ArrayList<TransactionInput>  inputs  = new ArrayList<>();
    public ArrayList<TransactionOutput> outputs = new ArrayList<>();

    private static int sequence = 0;

    public Transaction(PublicKey from, PublicKey to, float value, float fee,
                       ArrayList<TransactionInput> inputs) {
        this.sender    = from;
        this.recipient = to;
        this.value     = value;
        this.fee       = fee;
        this.inputs    = inputs;
        this.transactionId = computeId();
    }

    // ── Signing & verification ────────────────────────────────────────────────

    /** Signs the transaction. Must be called before submitting to the mempool. */
    public void generateSignature(PrivateKey privateKey) {
        if (privateKey == null) return; // coinbase — no signature required
        signature = CryptoUtil.sign(privateKey, signatureData());
    }

    public boolean verifySignature() {
        if (sender == null) return true; // coinbase is implicitly valid
        if (signature == null) return false;
        return CryptoUtil.verify(sender, signatureData(), signature);
    }

    private String signatureData() {
        return CryptoUtil.encodeKey(sender)
             + CryptoUtil.encodeKey(recipient)
             + value + fee;
    }

    // ── Processing ────────────────────────────────────────────────────────────

    /**
     * Validates and executes this transaction against the given blockchain state.
     *
     * On success: resolves UTXOs, creates outputs, updates the UTXO pool.
     * On failure: returns false without modifying chain state.
     */
    public boolean processTransaction(Blockchain chain) {
        if (!verifySignature()) {
            System.out.println("  [TX] Invalid signature — rejected");
            return false;
        }

        for (TransactionInput i : inputs) {
            i.UTXO = chain.getUTXOs().get(i.transactionOutputId);
        }

        float inputTotal = getInputsValue();
        float needed     = value + fee;

        if (inputTotal < needed) {
            System.out.printf("  [TX] Inputs (%.2f) < value + fee (%.2f) — rejected%n",
                inputTotal, needed);
            return false;
        }
        if (value < chain.getMinimumTransaction()) {
            System.out.printf("  [TX] Value %.2f below minimum %.2f — rejected%n",
                value, chain.getMinimumTransaction());
            return false;
        }

        float change = inputTotal - needed;
        outputs.add(new TransactionOutput(recipient, value,  transactionId));
        outputs.add(new TransactionOutput(sender,    change, transactionId));

        for (TransactionOutput o : outputs) chain.getUTXOs().put(o.id, o);
        for (TransactionInput  i : inputs)  if (i.UTXO != null) chain.getUTXOs().remove(i.UTXO.id);

        return true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public float getInputsValue() {
        float total = 0;
        for (TransactionInput i : inputs) if (i.UTXO != null) total += i.UTXO.value;
        return total;
    }

    public float getOutputsValue() {
        float total = 0;
        for (TransactionOutput o : outputs) total += o.value;
        return total;
    }

    private String computeId() {
        String fromStr = (sender    != null) ? CryptoUtil.encodeKey(sender)    : "COINBASE";
        String toStr   = (recipient != null) ? CryptoUtil.encodeKey(recipient) : "NULL";
        return HashUtil.sha256(fromStr + toStr + value + fee + (++sequence));
    }
}
