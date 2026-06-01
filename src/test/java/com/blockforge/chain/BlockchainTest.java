package com.blockforge.chain;

import com.blockforge.BlockForge;
import com.blockforge.transaction.Transaction;
import com.blockforge.transaction.TransactionOutput;
import com.blockforge.wallet.Wallet;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.Security;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class BlockchainTest {

    @BeforeAll
    static void setupCrypto() {
        Security.addProvider(new BouncyCastleProvider());
    }

    @Test
    void genesisBlock_isMinedWithCorrectHash() {
        Wallet     coinbase    = new Wallet();
        Blockchain blockchain  = BlockForge.bootstrapGenesis(coinbase);

        assertEquals(1, blockchain.size());
        Block genesis = blockchain.getBlock(0);
        assertNotNull(genesis);
        assertEquals("0", genesis.previousHash);
        assertTrue(genesis.hash.startsWith(
            com.blockforge.crypto.HashUtil.miningTarget(BlockForge.DIFFICULTY)),
            "Genesis hash must satisfy proof-of-work");
    }

    @Test
    void chainIsValid_afterMiningMultipleBlocks() {
        Wallet     coinbase   = new Wallet();
        Blockchain blockchain = BlockForge.bootstrapGenesis(coinbase);

        Wallet alice = new Wallet();
        Transaction fund = coinbase.sendFunds(alice.getPublicKey(), 50f, blockchain);
        Block b = new Block(blockchain.getLatestBlock().hash, blockchain.size());
        b.addTransaction(fund, blockchain);
        blockchain.addBlock(b);

        assertTrue(blockchain.isChainValid(), "Chain must be valid after legitimate block");
    }

    @Test
    void tamperedBlock_failsValidation() {
        Wallet     coinbase   = new Wallet();
        Blockchain blockchain = BlockForge.bootstrapGenesis(coinbase);

        // Mine a block
        Block b = new Block(blockchain.getLatestBlock().hash, blockchain.size());
        b.transactions.add(dummyCoinbaseTx(coinbase));
        blockchain.addBlock(b);

        // Tamper: change the hash directly
        blockchain.getBlock(1).hash = "0000tampered000000000000000000000000000000000000000000000000000000";

        assertFalse(blockchain.isChainValid(), "Tampered chain must fail validation");
    }

    @Test
    void brokenChainLink_failsValidation() {
        Wallet     coinbase   = new Wallet();
        Blockchain blockchain = BlockForge.bootstrapGenesis(coinbase);

        Block b = new Block(blockchain.getLatestBlock().hash, blockchain.size());
        b.transactions.add(dummyCoinbaseTx(coinbase));
        blockchain.addBlock(b);

        // Break the chain link
        blockchain.getBlock(1).previousHash = "0000000000000000000000000000000000000000000000000000000000000000";

        assertFalse(blockchain.isChainValid(), "Broken chain link must fail validation");
    }

    @Test
    void addValidatedBlock_acceptsValidPeerBlock() {
        Wallet     coinbase = new Wallet();
        Blockchain chain1   = BlockForge.bootstrapGenesis(coinbase);

        // chain2 starts from the SAME genesis state as chain1
        Blockchain chain2 = new Blockchain(BlockForge.DIFFICULTY, BlockForge.MINIMUM_TX);
        chain2.replaceChain(chain1.getChain(), chain1.getUTXOs());

        // chain2 mines a block
        Block mined = new Block(chain2.getLatestBlock().hash, chain2.size());
        chain2.addBlock(mined);

        // chain1 accepts the block (it correctly extends chain1's genesis)
        boolean accepted = chain1.addValidatedBlock(mined);
        assertTrue(accepted, "Valid peer block should be accepted");
    }

    @Test
    void emptyChain_isAlwaysValid() {
        Blockchain blockchain = new Blockchain(3);
        assertTrue(blockchain.isChainValid());
    }

    // Creates a simple coinbase transaction for use in tests.
    private static Transaction dummyCoinbaseTx(Wallet wallet) {
        Transaction cb = new Transaction(null, wallet.getPublicKey(), 50f, 0f, new ArrayList<>());
        cb.transactionId = "test-coinbase";
        TransactionOutput out = new TransactionOutput(wallet.getPublicKey(), 50f, "test-coinbase");
        cb.outputs.add(out);
        return cb;
    }
}
