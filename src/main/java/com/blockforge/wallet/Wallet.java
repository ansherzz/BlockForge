package com.blockforge.wallet;

import com.blockforge.chain.Blockchain;
import com.blockforge.crypto.CryptoUtil;
import com.blockforge.mining.Miner;
import com.blockforge.transaction.Transaction;
import com.blockforge.transaction.TransactionInput;
import com.blockforge.transaction.TransactionOutput;

import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * An ECDSA key pair that can send and receive BlockForge coins.
 *
 * The public key is your wallet address — share it freely so others can send you coins.
 * The private key is your spending password — never share it.
 *
 * Keys are generated on the prime192v1 elliptic curve using the BouncyCastle provider,
 * which must be registered before creating any wallets (via Security.addProvider).
 */
public class Wallet {

    private final PublicKey  publicKey;
    private final PrivateKey privateKey;

    // Local UTXO cache: refreshed by getBalance(); avoids rescanning the full pool each time.
    private final HashMap<String, TransactionOutput> UTXOs = new HashMap<>();

    public Wallet() {
        KeyPair pair = generateKeyPair();
        this.publicKey  = pair.getPublic();
        this.privateKey = pair.getPrivate();
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator   gen    = KeyPairGenerator.getInstance("ECDSA", "BC");
            SecureRandom       random = SecureRandom.getInstance("SHA1PRNG");
            ECGenParameterSpec spec   = new ECGenParameterSpec("prime192v1");
            gen.initialize(spec, random);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("Key pair generation failed", e);
        }
    }

    // ── Balance ───────────────────────────────────────────────────────────────

    /** Scans the chain's UTXO pool and returns the sum of outputs owned by this wallet. */
    public float getBalance(Blockchain chain) {
        float total = 0;
        UTXOs.clear();
        for (Map.Entry<String, TransactionOutput> e : chain.getUTXOs().entrySet()) {
            TransactionOutput utxo = e.getValue();
            if (utxo.isMine(publicKey)) {
                UTXOs.put(utxo.id, utxo);
                total += utxo.value;
            }
        }
        return total;
    }

    // ── Sending funds ─────────────────────────────────────────────────────────

    /**
     * Creates and signs a transaction paying `value` coins to `recipient`.
     *
     * Automatically computes the fee via {@link Miner#suggestedFee}.
     * Returns null if the wallet has insufficient funds (value + fee).
     */
    public Transaction sendFunds(PublicKey recipient, float value, Blockchain chain) {
        float fee    = Miner.suggestedFee(value);
        float needed = value + fee;

        if (getBalance(chain) < needed) {
            System.out.printf("  [Wallet] Insufficient funds: have %.2f, need %.2f (incl. fee %.2f)%n",
                getBalance(chain), needed, fee);
            return null;
        }

        ArrayList<TransactionInput> inputs    = new ArrayList<>();
        float                       collected = 0;

        for (Map.Entry<String, TransactionOutput> e : UTXOs.entrySet()) {
            TransactionOutput utxo = e.getValue();
            collected += utxo.value;
            inputs.add(new TransactionInput(utxo.id));
            if (collected >= needed) break;
        }

        Transaction tx = new Transaction(publicKey, recipient, value, fee, inputs);
        tx.generateSignature(privateKey);

        for (TransactionInput in : inputs) UTXOs.remove(in.transactionOutputId);

        return tx;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public PublicKey  getPublicKey()  { return publicKey; }
    public PrivateKey getPrivateKey() { return privateKey; }

    /** Returns the first 16 characters of the Base64-encoded public key — useful for display. */
    public String getAddress() {
        return CryptoUtil.encodeKey(publicKey).substring(0, 16) + "...";
    }

    /** Same as getAddress() but returns the full key string. */
    public String getFullAddress() {
        return CryptoUtil.encodeKey(publicKey);
    }
}
