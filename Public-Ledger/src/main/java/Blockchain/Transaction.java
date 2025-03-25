package Blockchain;

import java.security.*;

public class Transaction {

    private PublicKey buyerPublicKey;
    private PublicKey sellerPublicKey;
    private double amount;
    private byte[] signature;

    public Transaction(PublicKey buyerPublicKey, PublicKey sellerPublicKey, double amount) {
        this.buyerPublicKey = buyerPublicKey;
        this.sellerPublicKey = sellerPublicKey;
        this.amount = amount;
        this.signature = null;
    }

}
