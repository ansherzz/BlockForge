package com.blockforge;

import com.blockforge.api.BlockchainServer;
import com.blockforge.chain.Block;
import com.blockforge.chain.Blockchain;
import com.blockforge.cli.BlockForgeCLI;
import com.blockforge.mempool.Mempool;
import com.blockforge.mining.Miner;
import com.blockforge.network.Node;
import com.blockforge.network.PeerNetwork;
import com.blockforge.transaction.Transaction;
import com.blockforge.transaction.TransactionOutput;
import com.blockforge.wallet.Wallet;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.Security;
import java.util.ArrayList;
import java.util.Scanner;

/**
 * BlockForge entry point.
 *
 * Usage via run.sh:
 *   ./run.sh demo      — automated walkthrough of all features (default)
 *   ./run.sh cli       — interactive CLI wallet and block explorer
 *   ./run.sh api       — REST API server on port 4567
 *   ./run.sh p2p       — P2P multi-node consensus demonstration
 *   ./run.sh mining    — mempool + miner demonstration
 */
public class BlockForge {

    public static final int   DIFFICULTY = 3;
    public static final float MINIMUM_TX = 0.1f;
    public static final int   API_PORT   = 4567;

    public static void main(String[] args) throws Exception {
        Security.addProvider(new BouncyCastleProvider());

        String mode = (args.length > 0) ? args[0].toLowerCase() : "demo";

        Wallet     coinbase    = new Wallet();
        Blockchain blockchain  = bootstrapGenesis(coinbase);
        Mempool    mempool     = new Mempool();
        PeerNetwork network    = new PeerNetwork(DIFFICULTY);

        switch (mode) {
            case "cli":    runCLI(blockchain, mempool, network, coinbase);   break;
            case "api":    runAPI(blockchain, coinbase, mempool);            break;
            case "p2p":    runP2PDemo(blockchain, network);                  break;
            case "mining": runMiningDemo(blockchain, mempool, coinbase);     break;
            default:       runDemo(blockchain, mempool, network, coinbase);  break;
        }
    }

    // ── Mode runners ──────────────────────────────────────────────────────────

    private static void runDemo(Blockchain blockchain, Mempool mempool,
                                 PeerNetwork network, Wallet coinbase) throws Exception {
        header("BlockForge — Full Feature Demo");

        // A. Wallets & Transactions
        section("A  |  Wallet Creation & Funding");
        Wallet alice = new Wallet();
        Wallet bob   = new Wallet();

        System.out.println("  Alice address : " + alice.getAddress());
        System.out.println("  Bob   address : " + bob.getAddress());

        // Fund Alice via coinbase
        Transaction fund = coinbase.sendFunds(alice.getPublicKey(), 150f, blockchain);
        Block b1 = new Block(blockchain.getLatestBlock().hash, blockchain.size());
        b1.addTransaction(fund, blockchain);
        blockchain.addBlock(b1);

        printBalances(blockchain, coinbase, alice, bob);

        // B. Mempool + Mining
        section("B  |  Transactions → Mempool → Mining");
        Miner miner = new Miner("Alice", alice, blockchain, mempool);

        System.out.println("  Alice submits a 40-coin transfer to Bob → mempool...");
        Transaction t1 = alice.sendFunds(bob.getPublicKey(), 40f, blockchain);
        mempool.add(t1);
        System.out.printf("  Mempool now holds %d transaction(s). Mining...%n%n", mempool.size());
        miner.mine();
        printBalances(blockchain, coinbase, alice, bob);

        System.out.println("  Alice submits a 25-coin transfer to Bob → mempool...");
        Transaction t2 = alice.sendFunds(bob.getPublicKey(), 25f, blockchain);
        mempool.add(t2);
        System.out.printf("  Mempool: %d pending. Mining...%n%n", mempool.size());
        miner.mine();
        printBalances(blockchain, coinbase, alice, bob);

        System.out.println("  Alice attempts to send 9999 coins (should be rejected)...");
        Transaction t3 = alice.sendFunds(bob.getPublicKey(), 9999f, blockchain);
        if (t3 == null) System.out.println("  Correctly rejected: insufficient funds.\n");

        System.out.println("  Bob sends 10 coins back to Alice...");
        Transaction t4 = bob.sendFunds(alice.getPublicKey(), 10f, blockchain);
        mempool.add(t4);
        miner.mine();
        printBalances(blockchain, coinbase, alice, bob);

        // C. Chain Validation
        section("C  |  Chain Validation");
        System.out.println("  Chain length : " + blockchain.size() + " blocks");
        boolean valid = blockchain.isChainValid();
        System.out.println("  Chain valid  : " + valid);
        System.out.println();

        // D. Tamper Detection
        section("D  |  Tamper Detection");
        System.out.println("  Modifying block #1 data (simulating attack)...");
        Block victim = blockchain.getBlock(1);
        victim.merkleRoot = "0000000000000000000000000000000000000000000000000000000000000000";
        System.out.println("  Validating chain after tampering...");
        boolean stillValid = blockchain.isChainValid();
        System.out.println("  Chain valid  : " + stillValid + " (expected: false)\n");

        // Restore for remaining demos
        victim.merkleRoot = null; // reset so display isn't broken

        // E. P2P Network
        section("E  |  P2P Network — Longest-Chain Consensus");
        runP2PDemo(blockchain, network);

        // F. Mining reward halving info
        section("F  |  Mining Economics");
        System.out.printf("  Block reward at height     0 : %.2f BFC%n", Miner.blockReward(0));
        System.out.printf("  Block reward at height 210k  : %.2f BFC%n", Miner.blockReward(210_000));
        System.out.printf("  Block reward at height 420k  : %.2f BFC%n", Miner.blockReward(420_000));
        System.out.printf("  Fee rate                      : %.1f%% (min %.2f BFC)%n",
            Miner.FEE_RATE * 100, Miner.MINIMUM_FEE);

        // G. REST API
        section("G  |  REST API Server — press Enter to stop");
        runAPI(blockchain, coinbase, mempool);

        footer("Demo Complete");
    }

    private static void runCLI(Blockchain blockchain, Mempool mempool,
                                 PeerNetwork network, Wallet coinbase) {
        new BlockForgeCLI(blockchain, mempool, network, coinbase).run();
    }

    private static void runAPI(Blockchain blockchain, Wallet coinbase,
                                Mempool mempool) throws Exception {
        BlockchainServer server = new BlockchainServer(blockchain, coinbase, mempool, API_PORT);
        server.start();
        printCurlExamples();
        System.out.println("  Press Enter to stop...");
        new Scanner(System.in).nextLine();
        server.stop();
        System.out.println("  Server stopped.");
    }

    private static void runP2PDemo(Blockchain blockchain, PeerNetwork network) {
        Node alpha = network.addNode("Alpha");
        Node beta  = network.addNode("Beta");
        Node gamma = network.addNode("Gamma");
        network.seedAll(blockchain);
        network.printStatus();

        String[] names  = {"Alpha", "Beta", "Gamma"};
        Node[]   nodes  = {alpha, beta, gamma};

        for (int i = 0; i < nodes.length; i++) {
            System.out.printf("%n  %s mines a block and broadcasts...%n", names[i]);
            Block b = new Block(nodes[i].getBlockchain().getLatestBlock().hash,
                                nodes[i].getBlockchain().size());
            nodes[i].mineAndBroadcast(b);
            pause(300);
        }
        pause(200);
        network.printStatus();

        System.out.println(network.isInSync()
            ? "\n  Consensus reached — all nodes on the same chain tip.\n"
            : "\n  Nodes still syncing...\n");
    }

    private static void runMiningDemo(Blockchain blockchain, Mempool mempool,
                                       Wallet coinbase) {
        header("BlockForge — Mining & Mempool Demo");

        Wallet miner1 = new Wallet();
        Wallet miner2 = new Wallet();

        // Seed both miners
        Transaction f1 = coinbase.sendFunds(miner1.getPublicKey(), 200f, blockchain);
        Transaction f2 = coinbase.sendFunds(miner2.getPublicKey(), 200f, blockchain);
        Block seed = new Block(blockchain.getLatestBlock().hash, blockchain.size());
        seed.addTransaction(f1, blockchain);
        blockchain.addBlock(seed);
        Block seed2 = new Block(blockchain.getLatestBlock().hash, blockchain.size());
        seed2.addTransaction(f2, blockchain);
        blockchain.addBlock(seed2);

        Miner m1 = new Miner("Miner-1", miner1, blockchain, mempool);
        Miner m2 = new Miner("Miner-2", miner2, blockchain, mempool);

        System.out.println("  Submitting 5 transactions with varying fees to mempool...\n");
        float[] amounts = {10f, 20f, 5f, 50f, 15f};
        for (float a : amounts) {
            Transaction tx = miner1.sendFunds(miner2.getPublicKey(), a, blockchain);
            if (tx != null) mempool.add(tx);
        }

        System.out.println("\n  Miner-1 mines (takes highest-fee txs first)...");
        m1.mine();

        System.out.println("\n  Miner-2 mines remaining transactions...");
        m2.mine();

        System.out.printf("%n  Final balances:%n");
        System.out.printf("  Miner-1 : %.2f BFC%n", miner1.getBalance(blockchain));
        System.out.printf("  Miner-2 : %.2f BFC%n", miner2.getBalance(blockchain));
        System.out.printf("  Chain length: %d blocks%n", blockchain.size());

        footer("Mining Demo Complete");
    }

    // ── Bootstrap ─────────────────────────────────────────────────────────────

    /**
     * Creates the genesis block with a coinbase transaction that mints the initial
     * supply and assigns it to the provided coinbase wallet.
     *
     * Genesis transactions have no inputs — coins are created from nothing, the
     * equivalent of Bitcoin's block-reward on block 0.
     */
    public static Blockchain bootstrapGenesis(Wallet coinbase) {
        Blockchain chain = new Blockchain(DIFFICULTY, MINIMUM_TX);

        Transaction genesis = new Transaction(
            null, coinbase.getPublicKey(), 10_000f, 0f, new ArrayList<>());
        genesis.transactionId = "genesis";
        genesis.generateSignature(null);

        TransactionOutput genesisOut =
            new TransactionOutput(coinbase.getPublicKey(), 10_000f, "genesis");
        genesis.outputs.add(genesisOut);
        chain.getUTXOs().put(genesisOut.id, genesisOut);

        Block genesisBlock = new Block("0", 0);
        genesisBlock.transactions.add(genesis);

        System.out.println("Mining genesis block...");
        chain.addBlock(genesisBlock);
        return chain;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static void printBalances(Blockchain chain, Wallet coinbase,
                                       Wallet alice, Wallet bob) {
        System.out.printf("  Coinbase : %.2f BFC%n",  coinbase.getBalance(chain));
        System.out.printf("  Alice    : %.2f BFC%n",  alice.getBalance(chain));
        System.out.printf("  Bob      : %.2f BFC%n%n", bob.getBalance(chain));
    }

    private static void printCurlExamples() {
        System.out.println("  curl http://localhost:" + API_PORT + "/api/info");
        System.out.println("  curl http://localhost:" + API_PORT + "/api/chain");
        System.out.println("  curl http://localhost:" + API_PORT + "/api/chain/validate");
        System.out.println("  curl -X POST http://localhost:" + API_PORT + "/api/wallet/new");
        System.out.println("  curl -X POST http://localhost:" + API_PORT + "/api/coinbase/fund \\");
        System.out.println("       -H 'Content-Type: application/json' \\");
        System.out.println("       -d '{\"to\":\"<id>\",\"amount\":50}'");
        System.out.println("  curl -X POST http://localhost:" + API_PORT + "/api/mine");
        System.out.println();
    }

    private static void header(String title) {
        System.out.println("\n╔" + "═".repeat(62) + "╗");
        System.out.printf( "║  %-60s║%n", title);
        System.out.println("╚" + "═".repeat(62) + "╝\n");
    }

    private static void footer(String title) {
        System.out.println("\n╔" + "═".repeat(62) + "╗");
        System.out.printf( "║  %-60s║%n", title);
        System.out.println("╚" + "═".repeat(62) + "╝\n");
    }

    private static void section(String title) {
        System.out.println("┌─ " + title + " " + "─".repeat(Math.max(0, 59 - title.length())) + "┐");
    }

    private static void pause(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
