package com.blockforge.api;

import com.blockforge.chain.Block;
import com.blockforge.chain.Blockchain;
import com.blockforge.mempool.Mempool;
import com.blockforge.mining.Miner;
import com.blockforge.persistence.ChainStore;
import com.blockforge.transaction.Transaction;
import com.blockforge.transaction.TransactionOutput;
import com.blockforge.wallet.Wallet;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Lightweight REST API built on Java's built-in {@code HttpServer}.
 *
 * Endpoints:
 *   GET  /api/info                   — server info, endpoint listing
 *   GET  /api/chain                  — full chain as JSON
 *   GET  /api/chain/validate         — chain validity
 *   GET  /api/block/{index}          — single block
 *   POST /api/wallet/new             — create wallet → {id, address}
 *   GET  /api/wallet/{id}/balance    — wallet balance
 *   POST /api/coinbase/fund          — fund wallet from coinbase {to, amount}
 *   POST /api/transaction            — submit transaction {from, to, amount}
 *   GET  /api/mempool                — pending transactions
 *   POST /api/mine                   — mine the next block
 *   POST /api/chain/save             — export chain to disk
 */
public class BlockchainServer {

    private final Blockchain          blockchain;
    private final Wallet              coinbase;
    private final Mempool             mempool;
    private final Miner               miner;
    private final Map<String, Wallet> wallets = new ConcurrentHashMap<>();
    private final Gson                gson    = new GsonBuilder().setPrettyPrinting().create();
    private final HttpServer          server;
    private final int                 port;

    public BlockchainServer(Blockchain blockchain, Wallet coinbase, Mempool mempool,
                             int port) throws IOException {
        this.blockchain = blockchain;
        this.coinbase   = coinbase;
        this.mempool    = mempool;
        this.miner      = new Miner("api-node", coinbase, blockchain, mempool);
        this.port       = port;

        server = HttpServer.create(new InetSocketAddress(port), 32);
        server.setExecutor(Executors.newCachedThreadPool());

        // Route order matters — more-specific paths before catch-alls
        server.createContext("/api/chain/validate", ex -> handle(ex, this::validateChain));
        server.createContext("/api/chain/save",      ex -> handle(ex, this::saveChain));
        server.createContext("/api/chain",            ex -> handle(ex, this::getChain));
        server.createContext("/api/block/",           ex -> handle(ex, this::getBlock));
        server.createContext("/api/wallet/new",       ex -> handle(ex, this::createWallet));
        server.createContext("/api/wallet/",          ex -> handle(ex, this::walletBalance));
        server.createContext("/api/coinbase/fund",    ex -> handle(ex, this::fundFromCoinbase));
        server.createContext("/api/transaction",      ex -> handle(ex, this::submitTransaction));
        server.createContext("/api/mempool",          ex -> handle(ex, this::getMempoolStatus));
        server.createContext("/api/mine",             ex -> handle(ex, this::mineBlock));
        server.createContext("/api/info",             ex -> handle(ex, this::serverInfo));
    }

    public void start() {
        server.start();
        System.out.println();
        System.out.println("  ┌─ BlockForge REST API ─────────────────────────────────────┐");
        System.out.printf ("  │  Listening  http://localhost:%-4d                         │%n", port);
        System.out.println("  ├───────────────────────────────────────────────────────────┤");
        System.out.println("  │  GET  /api/info                 — endpoint listing         │");
        System.out.println("  │  GET  /api/chain                — full chain               │");
        System.out.println("  │  GET  /api/chain/validate       — validity check           │");
        System.out.println("  │  GET  /api/block/{n}            — block by index           │");
        System.out.println("  │  POST /api/wallet/new           — create wallet            │");
        System.out.println("  │  GET  /api/wallet/{id}/balance  — wallet balance           │");
        System.out.println("  │  POST /api/coinbase/fund        — fund wallet              │");
        System.out.println("  │  POST /api/transaction          — submit transaction       │");
        System.out.println("  │  GET  /api/mempool              — pending transactions     │");
        System.out.println("  │  POST /api/mine                 — mine next block          │");
        System.out.println("  │  POST /api/chain/save           — export to disk           │");
        System.out.println("  └───────────────────────────────────────────────────────────┘");
        System.out.println();
    }

    public void stop() { server.stop(1); }

    // ── Route handlers ────────────────────────────────────────────────────────

    private String getChain(HttpExchange ex) throws IOException {
        requireMethod(ex, "GET");
        return gson.toJson(buildChainView());
    }

    private String validateChain(HttpExchange ex) throws IOException {
        requireMethod(ex, "GET");
        boolean valid = blockchain.isChainValid();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("valid",       valid);
        r.put("chainLength", blockchain.size());
        r.put("difficulty",  blockchain.getDifficulty());
        r.put("message",     valid ? "Chain is intact" : "Chain integrity violation detected!");
        return gson.toJson(r);
    }

    private String getBlock(HttpExchange ex) throws IOException {
        requireMethod(ex, "GET");
        String[] parts = ex.getRequestURI().getPath().split("/");
        if (parts.length < 4) throw new ApiException(400, "Usage: /api/block/{index}");
        int idx;
        try { idx = Integer.parseInt(parts[3]); }
        catch (NumberFormatException e) { throw new ApiException(400, "Index must be an integer"); }
        Block b = blockchain.getBlock(idx);
        if (b == null) throw new ApiException(404, "Block #" + idx + " not found");
        return gson.toJson(buildBlockView(b, idx));
    }

    private String createWallet(HttpExchange ex) throws IOException {
        requireMethod(ex, "POST");
        Wallet w  = new Wallet();
        String id = w.getFullAddress().substring(0, 16);
        wallets.put(id, w);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id",      id);
        r.put("address", w.getAddress());
        r.put("note",    "Save the id — use it in subsequent API calls");
        return gson.toJson(r);
    }

    private String walletBalance(HttpExchange ex) throws IOException {
        requireMethod(ex, "GET");
        String[] parts = ex.getRequestURI().getPath().split("/");
        if (parts.length < 4) throw new ApiException(400, "Usage: /api/wallet/{id}/balance");
        Wallet w = wallets.get(parts[3]);
        if (w == null) throw new ApiException(404, "Wallet not found: " + parts[3]);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id",      parts[3]);
        r.put("balance", w.getBalance(blockchain));
        return gson.toJson(r);
    }

    private String fundFromCoinbase(HttpExchange ex) throws IOException {
        requireMethod(ex, "POST");
        JsonObject body   = parseBody(ex);
        String     toId   = requireField(body, "to");
        float      amount = body.has("amount") ? body.get("amount").getAsFloat() : 50f;

        Wallet to = wallets.get(toId);
        if (to == null) throw new ApiException(404, "Wallet not found: " + toId);

        Transaction tx = coinbase.sendFunds(to.getPublicKey(), amount, blockchain);
        if (tx == null) throw new ApiException(400, "Coinbase has insufficient funds");

        Block block = new Block(blockchain.getLatestBlock().hash, blockchain.size());
        block.addTransaction(tx, blockchain);
        blockchain.addBlock(block);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success",    true);
        r.put("funded",     amount);
        r.put("blockIndex", blockchain.size() - 1);
        return gson.toJson(r);
    }

    private String submitTransaction(HttpExchange ex) throws IOException {
        requireMethod(ex, "POST");
        JsonObject body   = parseBody(ex);
        String     fromId = requireField(body, "from");
        String     toId   = requireField(body, "to");
        float      amount = body.has("amount") ? body.get("amount").getAsFloat() : 0f;

        if (amount <= 0) throw new ApiException(400, "amount must be > 0");
        Wallet from = wallets.get(fromId);
        Wallet to   = wallets.get(toId);
        if (from == null) throw new ApiException(404, "Sender wallet not found: " + fromId);
        if (to   == null) throw new ApiException(404, "Recipient wallet not found: " + toId);

        Transaction tx = from.sendFunds(to.getPublicKey(), amount, blockchain);
        if (tx == null) throw new ApiException(400, "Insufficient funds in wallet " + fromId);

        mempool.add(tx);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("transactionId", tx.transactionId);
        r.put("amount",        amount);
        r.put("fee",           tx.fee);
        r.put("mempool",       mempool.size());
        r.put("note",          "Transaction queued. Call POST /api/mine to include it in a block.");
        return gson.toJson(r);
    }

    private String getMempoolStatus(HttpExchange ex) throws IOException {
        requireMethod(ex, "GET");
        List<Transaction> txs = mempool.snapshot();
        List<Map<String, Object>> view = new ArrayList<>();
        for (Transaction t : txs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",    t.transactionId);
            m.put("value", t.value);
            m.put("fee",   t.fee);
            view.add(m);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("size",         mempool.size());
        r.put("transactions", view);
        return gson.toJson(r);
    }

    private String mineBlock(HttpExchange ex) throws IOException {
        requireMethod(ex, "POST");
        Block block = miner.mine();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("mined",      true);
        r.put("blockIndex", blockchain.size() - 1);
        r.put("hash",       block.hash);
        r.put("txCount",    block.getTransactionCount());
        r.put("reward",     Miner.blockReward(block.height));
        return gson.toJson(r);
    }

    private String saveChain(HttpExchange ex) throws IOException {
        requireMethod(ex, "POST");
        String json    = ChainStore.save(blockchain, "data/exports");
        String summary = ChainStore.exportSummary(blockchain, "data/exports");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("json",    json);
        r.put("summary", summary);
        return gson.toJson(r);
    }

    private String serverInfo(HttpExchange ex) throws IOException {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name",        "BlockForge REST API");
        r.put("version",     "1.0");
        r.put("chainLength", blockchain.size());
        r.put("difficulty",  blockchain.getDifficulty());
        r.put("mempool",     mempool.size());
        r.put("wallets",     wallets.size());
        r.put("endpoints", List.of(
            "GET  /api/info",
            "GET  /api/chain",
            "GET  /api/chain/validate",
            "GET  /api/block/{index}",
            "POST /api/wallet/new",
            "GET  /api/wallet/{id}/balance",
            "POST /api/coinbase/fund       {to, amount}",
            "POST /api/transaction         {from, to, amount}",
            "GET  /api/mempool",
            "POST /api/mine",
            "POST /api/chain/save"
        ));
        return gson.toJson(r);
    }

    // ── Request utilities ─────────────────────────────────────────────────────

    @FunctionalInterface
    private interface RouteHandler {
        String handle(HttpExchange ex) throws IOException;
    }

    private void handle(HttpExchange ex, RouteHandler handler) {
        try {
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            respond(ex, 200, handler.handle(ex));
        } catch (ApiException e) {
            try { respond(ex, e.status, errJson(e.getMessage())); } catch (IOException ignored) {}
        } catch (Exception e) {
            try { respond(ex, 500, errJson("Internal error: " + e.getMessage())); } catch (IOException ignored) {}
        }
    }

    private void respond(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }

    private void requireMethod(HttpExchange ex, String method) {
        if (!method.equalsIgnoreCase(ex.getRequestMethod()))
            throw new ApiException(405, "Expected " + method + ", got " + ex.getRequestMethod());
    }

    private JsonObject parseBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            if (body.isBlank()) throw new ApiException(400, "Request body is empty");
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(400, "Invalid JSON: " + e.getMessage());
        }
    }

    private String requireField(JsonObject obj, String field) {
        if (!obj.has(field)) throw new ApiException(400, "Missing required field: " + field);
        return obj.get(field).getAsString();
    }

    private String errJson(String msg) {
        return "{\"error\":" + gson.toJson(msg) + "}";
    }

    // ── View builders ─────────────────────────────────────────────────────────

    private List<Map<String, Object>> buildChainView() {
        List<Map<String, Object>> view = new ArrayList<>();
        for (int i = 0; i < blockchain.size(); i++) view.add(buildBlockView(blockchain.getBlock(i), i));
        return view;
    }

    private Map<String, Object> buildBlockView(Block b, int index) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index",        index);
        m.put("height",       b.height);
        m.put("hash",         b.hash);
        m.put("previousHash", b.previousHash);
        m.put("merkleRoot",   b.merkleRoot);
        m.put("timestamp",    b.timeStamp);
        m.put("nonce",        b.nonce);
        m.put("txCount",      b.getTransactionCount());
        m.put("totalFees",    b.getTotalFees());

        List<Map<String, Object>> txs = new ArrayList<>();
        for (int j = 0; j < b.transactions.size(); j++) {
            Transaction t = b.transactions.get(j);
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("type",   j == 0 ? "COINBASE" : "TRANSFER");
            tm.put("id",     t.transactionId);
            tm.put("value",  t.value);
            tm.put("fee",    t.fee);
            txs.add(tm);
        }
        m.put("transactions", txs);
        return m;
    }

    // ── Inner exception ───────────────────────────────────────────────────────

    private static class ApiException extends RuntimeException {
        final int status;
        ApiException(int status, String message) { super(message); this.status = status; }
    }
}
