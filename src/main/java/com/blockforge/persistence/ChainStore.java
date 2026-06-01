package com.blockforge.persistence;

import com.blockforge.chain.Block;
import com.blockforge.chain.Blockchain;
import com.blockforge.crypto.CryptoUtil;
import com.blockforge.transaction.Transaction;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saves blockchain state to the local filesystem.
 *
 * Two output formats:
 *   {@link #save}          — JSON array of blocks (auditable, machine-readable).
 *   {@link #exportSummary} — Human-readable block-explorer text file.
 *
 * A note on wallet persistence:
 *   Private keys are NOT saved. Storing them in plaintext defeats asymmetric
 *   cryptography. Production systems use encrypted keystores (PKCS#12, BIP-38).
 *   In BlockForge, wallets are session-only; only the immutable chain is persisted.
 */
public class ChainStore {

    private static final DateTimeFormatter TS_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
                         .withZone(ZoneId.of("UTC"));

    /** Exports the chain to a timestamped JSON file. Returns the file path. */
    public static String save(Blockchain chain, String directory) throws IOException {
        Path dir  = Paths.get(directory);
        Files.createDirectories(dir);
        Path file = dir.resolve("chain-" + System.currentTimeMillis() + ".json");

        String json = new GsonBuilder().setPrettyPrinting()
            .create()
            .toJson(buildChainView(chain));
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        return file.toAbsolutePath().toString();
    }

    /** Writes a human-readable block-explorer summary. Returns the file path. */
    public static String exportSummary(Blockchain chain, String directory) throws IOException {
        Path dir  = Paths.get(directory);
        Files.createDirectories(dir);
        Path file = dir.resolve("summary-" + System.currentTimeMillis() + ".txt");

        StringBuilder sb = new StringBuilder();
        sb.append("BlockForge — Chain Summary\n");
        sb.append("Generated : ").append(TS_FMT.format(Instant.now())).append("\n");
        sb.append("Blocks    : ").append(chain.size()).append("\n");
        sb.append("Difficulty: ").append(chain.getDifficulty()).append("\n");
        sb.append("Valid     : ").append(chain.isChainValid()).append("\n\n");

        for (int i = 0; i < chain.size(); i++) {
            Block b = chain.getBlock(i);
            sb.append("┌─ Block #").append(i).append(" ").append("─".repeat(54)).append("\n");
            sb.append("│  Hash       : ").append(b.hash).append("\n");
            sb.append("│  PrevHash   : ").append(b.previousHash).append("\n");
            sb.append("│  MerkleRoot : ").append(b.merkleRoot != null ? b.merkleRoot : "(none)").append("\n");
            sb.append("│  Timestamp  : ").append(TS_FMT.format(Instant.ofEpochMilli(b.timeStamp))).append("\n");
            sb.append("│  Nonce      : ").append(b.nonce).append("\n");
            sb.append("│  Transactions: ").append(b.transactions.size()).append("\n");

            for (int j = 0; j < b.transactions.size(); j++) {
                Transaction t = b.transactions.get(j);
                String type = (j == 0) ? "COINBASE" : "TRANSFER";
                sb.append(String.format("│    [%s] id=%s  value=%.4f  fee=%.4f%n",
                    type,
                    t.transactionId != null ? t.transactionId.substring(0, Math.min(20, t.transactionId.length())) : "?",
                    t.value, t.fee));
            }
            sb.append("└").append("─".repeat(64)).append("\n\n");
        }

        Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
        return file.toAbsolutePath().toString();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static List<Map<String, Object>> buildChainView(Blockchain chain) {
        List<Map<String, Object>> view = new ArrayList<>();
        for (int i = 0; i < chain.size(); i++) {
            Block b = chain.getBlock(i);
            Map<String, Object> bm = new LinkedHashMap<>();
            bm.put("index",        i);
            bm.put("height",       b.height);
            bm.put("hash",         b.hash);
            bm.put("previousHash", b.previousHash);
            bm.put("merkleRoot",   b.merkleRoot);
            bm.put("timestamp",    b.timeStamp);
            bm.put("nonce",        b.nonce);

            List<Map<String, Object>> txs = new ArrayList<>();
            for (int j = 0; j < b.transactions.size(); j++) {
                Transaction t = b.transactions.get(j);
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("type",    j == 0 ? "COINBASE" : "TRANSFER");
                tm.put("id",      t.transactionId);
                tm.put("value",   t.value);
                tm.put("fee",     t.fee);
                tm.put("from",    t.sender != null
                    ? CryptoUtil.encodeKey(t.sender).substring(0, 20) + "..." : "COINBASE");
                tm.put("to",      t.recipient != null
                    ? CryptoUtil.encodeKey(t.recipient).substring(0, 20) + "..." : "NULL");
                tm.put("inputs",  t.inputs.size());
                tm.put("outputs", t.outputs.size());
                txs.add(tm);
            }
            bm.put("transactions", txs);
            view.add(bm);
        }
        return view;
    }
}
