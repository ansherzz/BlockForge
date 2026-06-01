package com.blockforge.transaction;

import com.blockforge.crypto.CryptoUtil;
import com.blockforge.crypto.HashUtil;

import java.security.PublicKey;

/** An unspent coin at a specific address. The UTXO model's atomic unit of value. */
public class TransactionOutput {

    public final String    id;
    public final PublicKey recipient;
    public final float     value;
    public final String    parentTransactionId;

    public TransactionOutput(PublicKey recipient, float value, String parentTransactionId) {
        this.recipient           = recipient;
        this.value               = value;
        this.parentTransactionId = parentTransactionId;
        this.id = HashUtil.sha256(
            CryptoUtil.encodeKey(recipient) + value + parentTransactionId
        );
    }

    /** Returns true if this output is addressed to the given public key. */
    public boolean isMine(PublicKey publicKey) {
        return publicKey.equals(recipient);
    }
}
