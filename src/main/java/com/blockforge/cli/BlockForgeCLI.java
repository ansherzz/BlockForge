package com.blockforge.cli;

import com.blockforge.chain.Block;
import com.blockforge.chain.Blockchain;
import com.blockforge.mempool.Mempool;
import com.blockforge.mining.Miner;
import com.blockforge.network.Node;
import com.blockforge.network.PeerNetwork;
import com.blockforge.persistence.ChainStore;
import com.blockforge.transaction.Transaction;
import com.blockforge.wallet.Wallet;

import java.util.*;

/**
 * Interactive command-line wallet and block explorer.
 *
 * Lets you create wallets, check balances, send coins, submit transactions to the
 * mempool, mine blocks, inspect the chain, run a P2P demo, and save state to disk —
 * all without writing a line of code.
 */
public class BlockForgeCLI {

    // ANSI colour codes — empty string disables colour for terminals that don't support it
    private static final String RST  = "[0m";
    private static final String BOLD = "[1m";
    private static final String CYAN = "[36m";
    private static final String GRN  = "[32m";
    private static final String YLW  = "[33m";
    private static final String RED  = "[31m";

    private final Blockchain          blockchain;
    private final Mempool             mempool;
    private final PeerNetwork         network;
    private final Wallet              coinbase;
    private final Map<String, Wallet> wallets = new LinkedHashMap<>();
    private final Scanner             scanner = new Scanner(System.in);
    private       Miner               miner;

    public BlockForgeCLI(Blockchain blockchain, Mempool mempool,
                          PeerNetwork network, Wallet coinbase) {
        this.blockchain = blockchain;
        this.mempool    = mempool;
        this.network    = network;
        this.coinbase   = coinbase;
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    public void run() {
        printBanner();
        boolean running = true;
        while (running) {
            printMenu();
            String choice = scanner.nextLine().trim();
            System.out.println();
            switch (choice) {
                case "1":  createWallet();    break;
                case "2":  listWallets();     break;
                case "3":  checkBalance();    break;
                case "4":  sendCoins();       break;
                case "5":  mineBlock();       break;
                case "6":  mempoolStatus();   break;
                case "7":  viewChain();       break;
                case "8":  inspectBlock();    break;
                case "9":  validateChain();   break;
                case "10": saveChain();       break;
                case "11": p2pDemo();         break;
                case "0":  running = false;   say("Goodbye!", CYAN); break;
                default:   say("Unknown option. Enter 0–11.", RED);
            }
        }
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    private void createWallet() {
        String alias = prompt("Alias (or Enter for auto-name): ").trim();
        if (alias.isEmpty()) alias = "wallet-" + (wallets.size() + 1);

        Wallet w = new Wallet();
        wallets.put(alias, w);
        // Spin up a Miner for this wallet if we don't have one yet
        if (miner == null) miner = new Miner(alias, w, blockchain, mempool);

        say("Wallet created!", GRN);
        sayf("  Alias  : %s%n", alias);
        sayf("  Address: %s%n", w.getAddress());

        // Seed 100 coins from coinbase
        Transaction tx = coinbase.sendFunds(w.getPublicKey(), 100f, blockchain);
        if (tx != null) {
            Block block = new Block(blockchain.getLatestBlock().hash, blockchain.size());
            block.addTransaction(tx, blockchain);
            blockchain.addBlock(block);
            sayf("  Seeded 100 coins from coinbase (block #%d)%n", blockchain.size() - 1);
        }
    }

    private void listWallets() {
        if (wallets.isEmpty()) { say("No wallets yet. Create one (option 1).", YLW); return; }
        sayf("%-20s  %-20s  %s%n", "Alias", "Address", "Balance");
        say("─".repeat(60), "");
        for (Map.Entry<String, Wallet> e : wallets.entrySet()) {
            sayf("%-20s  %-20s  %.2f%n",
                e.getKey(), e.getValue().getAddress(),
                e.getValue().getBalance(blockchain));
        }
    }

    private void checkBalance() {
        String alias = pickWallet("Check balance for");
        if (alias == null) return;
        float bal = wallets.get(alias).getBalance(blockchain);
        sayf("Balance: %.2f BFC%n", bal);
    }

    private void sendCoins() {
        String fromAlias = pickWallet("FROM");
        if (fromAlias == null) return;
        String toAlias   = pickWallet("TO");
        if (toAlias == null) return;
        if (fromAlias.equals(toAlias)) { say("Cannot send to yourself.", RED); return; }

        String amountStr = prompt("Amount: ").trim();
        float  amount;
        try { amount = Float.parseFloat(amountStr); }
        catch (NumberFormatException e) { say("Invalid amount.", RED); return; }

        Wallet from = wallets.get(fromAlias);
        Wallet to   = wallets.get(toAlias);

        Transaction tx = from.sendFunds(to.getPublicKey(), amount, blockchain);
        if (tx == null) { say("Transaction failed — insufficient funds.", RED); return; }

        boolean added = mempool.add(tx);
        if (added) {
            say("Transaction submitted to mempool. Mine to confirm (option 5).", GRN);
            sayf("  Amount : %.2f BFC  fee: %.2f BFC%n", amount, tx.fee);
            sayf("  Mempool: %d pending%n", mempool.size());
        } else {
            say("Mempool rejected transaction.", RED);
        }
    }

    private void mineBlock() {
        if (miner == null) {
            say("Create a wallet first (it becomes the miner's reward address).", YLW);
            return;
        }
        say("Mining...", YLW);
        Block block = miner.mine();
        sayf("Block #%d mined! Txs: %d  Reward: %.2f BFC%n",
            block.height, block.getTransactionCount(),
            Miner.blockReward(block.height) + block.getTotalFees());
    }

    private void mempoolStatus() {
        List<Transaction> txs = mempool.snapshot();
        sayf("Mempool: %d transaction(s)%n", txs.size());
        if (txs.isEmpty()) return;
        sayf("%-20s  %8s  %8s%n", "TX ID", "Value", "Fee");
        say("─".repeat(45), "");
        for (Transaction t : txs) {
            sayf("%-20s  %8.2f  %8.2f%n",
                t.transactionId.substring(0, Math.min(20, t.transactionId.length())),
                t.value, t.fee);
        }
    }

    private void viewChain() {
        sayf("Blockchain: %d block(s)  difficulty=%d%n",
            blockchain.size(), blockchain.getDifficulty());
        say(String.format("%-6s  %-24s  %-6s  %s", "Height", "Hash", "Txs", "Nonce"), BOLD);
        say("─".repeat(60), "");
        for (int i = 0; i < blockchain.size(); i++) {
            Block b = blockchain.getBlock(i);
            sayf("%-6d  %-24s  %-6d  %d%n",
                b.height, b.hash.substring(0, 20) + "...",
                b.getTransactionCount(), b.nonce);
        }
    }

    private void inspectBlock() {
        String idxStr = prompt("Block index: ").trim();
        int idx;
        try { idx = Integer.parseInt(idxStr); }
        catch (NumberFormatException e) { say("Invalid index.", RED); return; }

        Block b = blockchain.getBlock(idx);
        if (b == null) { say("Block not found.", RED); return; }

        say("Block #" + idx, BOLD);
        sayf("  Hash       : %s%n", b.hash);
        sayf("  PrevHash   : %s%n", b.previousHash);
        sayf("  MerkleRoot : %s%n", b.merkleRoot != null ? b.merkleRoot : "(none)");
        sayf("  Timestamp  : %s%n", new Date(b.timeStamp));
        sayf("  Nonce      : %d%n", b.nonce);
        sayf("  Height     : %d%n", b.height);
        sayf("  Tx count   : %d%n", b.getTransactionCount());
        sayf("  Total fees : %.4f BFC%n", b.getTotalFees());

        for (int j = 0; j < b.transactions.size(); j++) {
            Transaction t = b.transactions.get(j);
            sayf("  [%s] value=%.2f fee=%.2f id=%s%n",
                j == 0 ? "COINBASE" : "TRANSFER",
                t.value, t.fee,
                t.transactionId.substring(0, Math.min(20, t.transactionId.length())));
        }
    }

    private void validateChain() {
        say("Validating...", YLW);
        boolean valid = blockchain.isChainValid();
        if (valid) {
            say("Chain VALID — " + blockchain.size() + " blocks, all intact.", GRN);
        } else {
            say("Chain INVALID — tampering detected!", RED);
        }
    }

    private void saveChain() {
        try {
            String json    = ChainStore.save(blockchain, "data/exports");
            String summary = ChainStore.exportSummary(blockchain, "data/exports");
            say("Saved!", GRN);
            sayf("  JSON    : %s%n", json);
            sayf("  Summary : %s%n", summary);
        } catch (Exception e) {
            say("Save failed: " + e.getMessage(), RED);
        }
    }

    private void p2pDemo() {
        say("── P2P Network Demo ─────────────────────────────────────", CYAN);
        say("Creating 3-node mesh: Alpha, Beta, Gamma...", "");

        Node alpha = network.addNode("Alpha");
        Node beta  = network.addNode("Beta");
        Node gamma = network.addNode("Gamma");
        network.seedAll(blockchain);
        network.printStatus();

        say("Alpha mines → broadcasts → Beta and Gamma sync...", YLW);
        Block ba = new Block(alpha.getBlockchain().getLatestBlock().hash,
                              alpha.getBlockchain().size());
        alpha.mineAndBroadcast(ba);
        sleep(300);

        say("Beta mines → broadcasts...", YLW);
        Block bb = new Block(beta.getBlockchain().getLatestBlock().hash,
                              beta.getBlockchain().size());
        beta.mineAndBroadcast(bb);
        sleep(300);

        say("Gamma mines → broadcasts...", YLW);
        Block bc = new Block(gamma.getBlockchain().getLatestBlock().hash,
                              gamma.getBlockchain().size());
        gamma.mineAndBroadcast(bc);
        sleep(500);

        network.printStatus();
        say(network.isInSync() ? "All nodes in sync — consensus reached!" : "Syncing...", GRN);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String pickWallet(String prompt) {
        if (wallets.isEmpty()) { say("No wallets. Create one first (option 1).", YLW); return null; }
        List<String> aliases = new ArrayList<>(wallets.keySet());
        for (int i = 0; i < aliases.size(); i++) sayf("  %d. %s%n", i + 1, aliases.get(i));
        String s = prompt(prompt + " (number): ").trim();
        try {
            int idx = Integer.parseInt(s) - 1;
            if (idx < 0 || idx >= aliases.size()) throw new IndexOutOfBoundsException();
            return aliases.get(idx);
        } catch (Exception e) { say("Invalid selection.", RED); return null; }
    }

    private void printBanner() {
        System.out.println(CYAN + BOLD);
        System.out.println("  ╔══════════════════════════════════════════════════╗");
        System.out.println("  ║         B  L  O  C  K  F  O  R  G  E            ║");
        System.out.println("  ║         Java Blockchain — Interactive CLI        ║");
        System.out.println("  ║                                    v1.0          ║");
        System.out.println("  ╚══════════════════════════════════════════════════╝");
        System.out.println(RST);
    }

    private void printMenu() {
        System.out.println(CYAN + "\n  ── Commands " + "─".repeat(39) + RST);
        System.out.println("   1  Create wallet        7  View blockchain");
        System.out.println("   2  List wallets          8  Inspect block");
        System.out.println("   3  Check balance         9  Validate chain");
        System.out.println("   4  Send coins           10  Save chain to disk");
        System.out.println("   5  Mine block           11  P2P network demo");
        System.out.println("   6  Mempool status        0  Exit");
        System.out.print(CYAN + "  > " + RST);
    }

    private String prompt(String msg) { System.out.print("  " + msg); return scanner.nextLine(); }
    private void   say(String msg, String color)         { System.out.println(color + msg + RST); }
    private void   sayf(String fmt, Object... args)      { System.out.printf("  " + fmt, args); }
    private void   sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }
}
