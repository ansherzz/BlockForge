package com.blockforge.network;

import com.blockforge.chain.Blockchain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages a set of Node instances connected in a full-mesh P2P topology.
 *
 * Each node added via {@link #addNode} is automatically connected to all
 * existing nodes (bi-directional). Call {@link #seedAll} to distribute a
 * genesis chain to every node before mining begins.
 */
public class PeerNetwork {

    private final List<Node> nodes;
    private final int        difficulty;

    public PeerNetwork(int difficulty) {
        this.difficulty = difficulty;
        this.nodes      = new ArrayList<>();
    }

    // ── Node management ───────────────────────────────────────────────────────

    public Node addNode(String id) {
        Node node = new Node(id, difficulty);
        for (Node existing : nodes) {
            node.connectPeer(existing);
            existing.connectPeer(node);
        }
        nodes.add(node);
        System.out.printf("  [Network] %-10s joined  peers=%-3d  total nodes=%d%n",
            id, nodes.size() - 1, nodes.size());
        return node;
    }

    /** Copies the genesis chain state into every node. */
    public void seedAll(Blockchain genesis) {
        for (Node node : nodes) node.installChain(genesis);
        System.out.printf("  [Network] Genesis chain (length %d) seeded to %d node(s)%n",
            genesis.size(), nodes.size());
    }

    // ── Status ────────────────────────────────────────────────────────────────

    public void printStatus() {
        System.out.println("\n┌─ Network Status " + "─".repeat(46) + "┐");
        for (Node node : nodes) {
            Blockchain c = node.getBlockchain();
            String tip   = (c.getLatestBlock() != null)
                ? c.getLatestBlock().hash.substring(0, 20) + "..."
                : "(empty)";
            System.out.printf("│  %-10s  blocks=%-4d  tip=%s%n",
                node.getId(), c.size(), tip);
        }
        System.out.printf("│%n│  In sync: %s%n", isInSync() ? "YES ✓" : "NO  ✗");
        System.out.println("└" + "─".repeat(63) + "┘");
    }

    public boolean isInSync() {
        if (nodes.size() <= 1) return true;
        String reference = latestHash(nodes.get(0));
        return nodes.stream().allMatch(n -> reference.equals(latestHash(n)));
    }

    private String latestHash(Node n) {
        Blockchain c = n.getBlockchain();
        return (c.getLatestBlock() != null) ? c.getLatestBlock().hash : "";
    }

    public List<Node> getNodes() { return Collections.unmodifiableList(nodes); }
}
