package com.blockforge.transaction;

import com.blockforge.BlockForge;
import com.blockforge.chain.Block;
import com.blockforge.chain.Blockchain;
import com.blockforge.wallet.Wallet;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Security;

import static org.junit.jupiter.api.Assertions.*;

class TransactionTest {

    private Blockchain blockchain;
    private Wallet     coinbase;
    private Wallet     alice;
    private Wallet     bob;

    @BeforeAll
    static void setupCrypto() {
        Security.addProvider(new BouncyCastleProvider());
    }

    @BeforeEach
    void setup() {
        coinbase   = new Wallet();
        blockchain = BlockForge.bootstrapGenesis(coinbase);
        alice      = new Wallet();
        bob        = new Wallet();

        // Fund Alice with 100 coins
        Transaction fund = coinbase.sendFunds(alice.getPublicKey(), 100f, blockchain);
        assertNotNull(fund, "Coinbase must be able to fund Alice");
        Block b = new Block(blockchain.getLatestBlock().hash, blockchain.size());
        b.addTransaction(fund, blockchain);
        blockchain.addBlock(b);
    }

    @Test
    void signature_isVerifiable() {
        Transaction tx = alice.sendFunds(bob.getPublicKey(), 10f, blockchain);
        assertNotNull(tx, "Transaction creation must succeed");
        assertTrue(tx.verifySignature(), "Signature must be valid immediately after creation");
    }

    @Test
    void tamperedTransaction_failsSignatureCheck() {
        Transaction tx = alice.sendFunds(bob.getPublicKey(), 10f, blockchain);
        assertNotNull(tx);
        tx.value = 9999f; // tamper
        assertFalse(tx.verifySignature(), "Tampered transaction must fail signature check");
    }

    @Test
    void validTransaction_transfersFunds() {
        float aliceBefore = alice.getBalance(blockchain);
        float bobBefore   = bob.getBalance(blockchain);

        Transaction tx = alice.sendFunds(bob.getPublicKey(), 30f, blockchain);
        assertNotNull(tx);

        Block block = new Block(blockchain.getLatestBlock().hash, blockchain.size());
        block.addTransaction(tx, blockchain);
        blockchain.addBlock(block);

        float aliceAfter = alice.getBalance(blockchain);
        float bobAfter   = bob.getBalance(blockchain);

        assertTrue(aliceAfter < aliceBefore, "Alice balance must decrease after sending");
        assertEquals(bobBefore + 30f, bobAfter, 0.001f, "Bob balance must increase by 30");
    }

    @Test
    void insufficientFunds_returnsNull() {
        Transaction tx = alice.sendFunds(bob.getPublicKey(), 99999f, blockchain);
        assertNull(tx, "sendFunds must return null when funds are insufficient");
    }

    @Test
    void feeIsNonNegative() {
        Transaction tx = alice.sendFunds(bob.getPublicKey(), 50f, blockchain);
        assertNotNull(tx);
        assertTrue(tx.fee >= 0, "Fee must be non-negative");
        assertTrue(tx.fee >= 0.01f, "Fee must meet minimum");
    }

    @Test
    void inputsEqualOutputsPlusFee() {
        Transaction tx = alice.sendFunds(bob.getPublicKey(), 40f, blockchain);
        assertNotNull(tx);
        // processTransaction is called inside block.addTransaction
        Block block = new Block(blockchain.getLatestBlock().hash, blockchain.size());
        block.addTransaction(tx, blockchain);
        blockchain.addBlock(block);

        // After processing, inputs = outputs + fee
        float inputTotal  = tx.getInputsValue();
        float outputTotal = tx.getOutputsValue();
        assertEquals(inputTotal, outputTotal + tx.fee, 0.001f,
            "inputs must equal outputs + fee");
    }

    @Test
    void chainRemainsValidAfterMultipleTransactions() {
        Transaction t1 = alice.sendFunds(bob.getPublicKey(), 20f, blockchain);
        Block b1 = new Block(blockchain.getLatestBlock().hash, blockchain.size());
        b1.addTransaction(t1, blockchain);
        blockchain.addBlock(b1);

        Transaction t2 = bob.sendFunds(alice.getPublicKey(), 5f, blockchain);
        Block b2 = new Block(blockchain.getLatestBlock().hash, blockchain.size());
        b2.addTransaction(t2, blockchain);
        blockchain.addBlock(b2);

        assertTrue(blockchain.isChainValid(), "Chain must remain valid after multiple transactions");
    }
}
