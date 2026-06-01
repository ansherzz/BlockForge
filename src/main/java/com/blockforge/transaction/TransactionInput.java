package com.blockforge.transaction;

/** Points to a previous TransactionOutput that this transaction is consuming. */
public class TransactionInput {

    /** ID of the unspent output being spent. */
    public final String transactionOutputId;

    /** Resolved UTXO — populated during transaction processing; not serialised. */
    public transient TransactionOutput UTXO;

    public TransactionInput(String transactionOutputId) {
        this.transactionOutputId = transactionOutputId;
    }
}
