package com.blockforge.network;

import com.blockforge.chain.Block;
import com.blockforge.chain.Blockchain;
import com.blockforge.transaction.TransactionOutput;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A single peer in the BlockForge P2P network.
 *
 * Each node maintains an independent Blockchain instance. When it mines a block
 * it propagates it to all connected peers. Peers apply the longest-chain rule:
 *   - If the incoming block extends their chain → accept and re-propagate.
 *   - If the sender's chain is longer → request a full chain sync.
 *   - Otherwise → ignore (stale or orphan).
 *
 * Communication here is synchronous in-process method calls. In a production
 * network, blocks travel over TCP and nodes must handle latency and Byzantine faults
 * — but the consensus logic is identical.
 */
public class Node {

    private final String          id;
    private       Blockchain      blockchain;
    private final List<Node>      peers;
    private final ExecutorService executor;

    public Node(String id, int difficulty) {
        this.id         = id;
        this.blockchain = new Blockchain(difficulty);
        this.peers      = new CopyOnWriteArrayList<>();
        this.executor   = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "node-" + id);
            t.setDaemon(true);
            return t;
        });
    }

    // ── Peer management ───────────────────────────────────────────────────────

    public void connectPeer(Node peer) {
        if (peer != this && !peers.contains(peer)) peers.add(peer);
    }

    // ── Mining ────────────────────────────────────────────────────────────────

    /** Mines a block and broadcasts it to all peers. */
    public synchronized void mineAndBroadcast(Block block) {
        blockchain.addBlock(block);
        System.out.printf("  [Node %-8s] Mined block #%d  hash=%s%n",
            id, blockchain.size() - 1, blockchain.getLatestBlock().hash.substring(0, 16) + "...");
        broadcastToAll(blockchain.getLatestBlock(), null);
    }

    // ── Receiving blocks ──────────────────────────────────────────────────────

    public void receiveBlock(Block block, Node sender) {
        executor.submit(() -> handleIncoming(block, sender));
    }

    private synchronized void handleIncoming(Block block, Node sender) {
        Block latest = blockchain.getLatestBlock();

        if (latest == null) {
            if (blockchain.addValidatedBlock(block)) {
                System.out.printf("  [Node %-8s] Accepted genesis block from %s%n", id, sender.id);
                broadcastToAll(block, sender);
            }
            return;
        }

        if (block.previousHash.equals(latest.hash)) {
            if (blockchain.addValidatedBlock(block)) {
                System.out.printf("  [Node %-8s] Accepted block #%d from %s%n",
                    id, blockchain.size() - 1, sender.id);
                broadcastToAll(block, sender);
            }
        } else if (sender.blockchain.size() > blockchain.size()) {
            System.out.printf("  [Node %-8s] Behind %s (%d vs %d) — syncing chain%n",
                id, sender.id, blockchain.size(), sender.blockchain.size());
            syncFrom(sender);
        }
    }

    private void syncFrom(Node peer) {
        List<Block> peerChain   = peer.blockchain.getChain();
        Map<String, TransactionOutput> peerUTXOs = peer.blockchain.getUTXOs();

        Blockchain candidate = new Blockchain(blockchain.getDifficulty());
        candidate.replaceChain(peerChain, peerUTXOs);

        if (candidate.isChainValid()) {
            blockchain.replaceChain(peerChain, peerUTXOs);
            System.out.printf("  [Node %-8s] Chain replaced from %s. New length: %d%n",
                id, peer.id, blockchain.size());
        } else {
            System.out.printf("  [Node %-8s] Rejected invalid chain from %s%n", id, peer.id);
        }
    }

    private void broadcastToAll(Block block, Node except) {
        for (Node peer : peers) {
            if (!peer.equals(except)) peer.receiveBlock(block, this);
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String     getId()         { return id; }
    public Blockchain getBlockchain() { return blockchain; }

    /** Installs a pre-built chain (e.g. genesis state). Call before connecting to peers. */
    public synchronized void installChain(Blockchain source) {
        this.blockchain = new Blockchain(source.getDifficulty());
        this.blockchain.replaceChain(source.getChain(), source.getUTXOs());
    }
}
