package Blockchain;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public class Transaction {

    public enum TransactionType {
        AuctionPayment,
        BlockMinedPayment
    }

    private UUID TransactionId;
    private Instant Timestamp;
    private PublicKey Sender;
    private BigInteger AuctionOwnerId;
    private TransactionType Type;
    private byte[] Signature;
    private Double Amount;
    private UUID AuctionId;

    public Transaction(TransactionType type, PublicKey sender,BigInteger auctionOwnerId, Double amount) {
        this.TransactionId = UUID.randomUUID();
        this.Timestamp = Instant.now();
        this.Sender = sender;
        this.AuctionOwnerId = auctionOwnerId;
        this.Type = type;
        this.Amount = amount;
    }

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public double validateTransaction() {
        try {

            if (this.getTransactionId() == null) {
                return 0.05;
            }

            if (this.getTimestamp() == null || this.getTimestamp().isAfter(java.time.Instant.now())) {
                return 0.1;
            }

            if (this.getType() == null) {
                return 0.05;
            }

            Security.addProvider(new BouncyCastleProvider());
            String data =
                    (TransactionId != null ? TransactionId.toString() : "") +
                            (Timestamp != null ? Timestamp.toString() : "") +
                            (Sender != null ? Base64.getEncoder().encodeToString(Sender.getEncoded()) : "") +
                            (Type != null ? Type.toString() : "") +
                            (AuctionId != null ? AuctionId.toString() : "") +
                            (Amount != null ? Amount.toString() : "");


            byte[] message = data.getBytes(StandardCharsets.UTF_8);

            Signature verifier = java.security.Signature.getInstance("SHA256withRSA", "BC");
            verifier.initVerify(Sender);
            verifier.update(message);

            var secure = verifier.verify(this.Signature);

            if(!secure){
                return 0.2;
            }
            else{
                return 1;
            }

        } catch (Exception e) {
            return 0;
        }
    }


    public void signTransaction(PrivateKey privateKey) {
        try {
            Security.addProvider(new BouncyCastleProvider());

            String data =
                    (TransactionId != null ? TransactionId.toString() : "") +
                            (Timestamp != null ? Timestamp.toString() : "") +
                            (Sender != null ? Base64.getEncoder().encodeToString(Sender.getEncoded()) : "") +
                            (Type != null ? Type.toString() : "") +
                            (AuctionId != null ? AuctionId.toString() : "") +
                            (Amount != null ? Amount.toString() : "");

            byte[] message = data.getBytes(StandardCharsets.UTF_8);

            Signature signature = java.security.Signature.getInstance("SHA256withRSA", "BC");
            signature.initSign(privateKey);
            signature.update(message);

            this.Signature = signature.sign();
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign transaction", e);
        }
    }

    public void setSignature(byte[] signature) {
        this.Signature = signature;
    }

    public byte[] getSignature() {
        return this.Signature;
    }

    public UUID getTransactionId() { return TransactionId; }

    public Instant getTimestamp() { return Timestamp; }

    public PublicKey getSender() { return Sender; }

    public BigInteger getAuctionOwnerId() { return AuctionOwnerId; }

    public TransactionType getType() { return Type; }

    public UUID getAuctionId() { return AuctionId; }

    public Double getAmount() { return Amount; }

    public void setType(TransactionType type) {
        this.Type = type;
    }

    public void setAuctionId(UUID auctionId) {
        this.AuctionId = auctionId;
    }

    @Override
    public String toString() {
        return "Transaction{" +
                "transactionId=" + (TransactionId != null ? TransactionId.toString() : "null") +
                ", type=" + (Type != null ? Type.name() : "null") +
                ", timestamp=" + (Timestamp != null ? Timestamp.toString() : "null") +
                ", senderPublicKey=" + (Sender != null ? Base64.getEncoder().encodeToString(Sender.getEncoded()) : "null") +
                ", auctionId='" + (AuctionId != null ? AuctionId : "null") + '\'' +
                ", bidAmount='" + (Amount != null ? Amount : "null") + '\'' +
                '}';
    }
}

