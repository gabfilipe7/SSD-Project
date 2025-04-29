package Blockchain;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;


public class Transaction {

    public enum TransactionType {
        CREATE_AUCTION,
        PLACE_BID,
        CLOSE_AUCTION
    }

    private UUID transactionId;
    private Instant timestamp;
    private PublicKey sender;
    private TransactionType type;
    private byte[] signature;

    private UUID auctionId;
    private String itemDescription;
    private Instant startTime;
    private Instant endTime;
    private Double bidAmount;

    public Transaction(TransactionType type, PublicKey sender) {
        this.transactionId = UUID.randomUUID();
        this.timestamp = Instant.now();
        this.sender = sender;
        this.type = type;
    }
    public Transaction(UUID transactionId, TransactionType type,Instant timestamp, PublicKey sender) {
        this.transactionId = transactionId;
        this.timestamp = timestamp;
        this.sender = sender;
        this.type = type;
    }


    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }
    public Transaction setAuctionId(UUID auctionId) {
        this.auctionId = auctionId;
        return this;
    }

    public Transaction setItemDescription(String itemDescription) {
        this.itemDescription = itemDescription;
        return this;
    }

    public Transaction setStartTime(Instant startTime) {
        this.startTime = startTime;
        return this;
    }

    public Transaction setEndTime(Instant endTime) {
        this.endTime = endTime;
        return this;
    }

    public Transaction setBidAmount(Double bidAmount) {
        this.bidAmount = bidAmount;
        return this;
    }

    public boolean validateTransaction() {
        try {
            if (this.getTransactionId() == null) {
                return false;
            }

            if (this.getTimestamp() == null || this.getTimestamp().isAfter(java.time.Instant.now())) {
                return false;
            }

            if (this.getType() == null) {
                return false;
            }

            Security.addProvider(new BouncyCastleProvider());

            String data = transactionId.toString()
                    + timestamp.toString()
                    + Base64.getEncoder().encodeToString(sender.getEncoded())
                    + type.toString()
                    + auctionId.toString()
                    + itemDescription
                    + startTime.toString()
                    + endTime.toString()
                    + bidAmount.toString();

            byte[] message = data.getBytes(StandardCharsets.UTF_8);

            Signature verifier = Signature.getInstance("SHA256withRSA", "BC");
            verifier.initVerify(sender);
            verifier.update(message);

            return verifier.verify(this.signature);
        } catch (Exception e) {
            return false;
        }
    }


    public void signTransaction(PrivateKey privateKey) {
        try {
            Security.addProvider(new BouncyCastleProvider());

            String data = transactionId.toString()
                    + timestamp.toString()
                    + Base64.getEncoder().encodeToString(sender.getEncoded())
                    + type.toString()
                    + auctionId.toString()
                    + itemDescription
                    + startTime.toString()
                    + endTime.toString()
                    + bidAmount.toString();

            byte[] message = data.getBytes(StandardCharsets.UTF_8);

            Signature signature = Signature.getInstance("SHA256withRSA", "BC");
            signature.initSign(privateKey);
            signature.update(message);

            this.signature = signature.sign();
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign transaction", e);
        }
    }

    public void setSignature(byte[] signature) {
        this.signature = signature;
    }

    public UUID getTransactionId() { return transactionId; }
    public Instant getTimestamp() { return timestamp; }
    public PublicKey getSender() { return sender; }
    public TransactionType getType() { return type; }
    public UUID getAuctionId() { return auctionId; }
    public String getItemDescription() { return itemDescription; }
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }
    public Double getBidAmount() { return bidAmount; }
}

