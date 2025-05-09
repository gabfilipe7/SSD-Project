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

    public Transaction() {}

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
            String data =
                    (transactionId != null ? transactionId.toString() : "") +
                            (timestamp != null ? timestamp.toString() : "") +
                            (sender != null ? Base64.getEncoder().encodeToString(sender.getEncoded()) : "") +
                            (type != null ? type.toString() : "") +
                            (auctionId != null ? auctionId.toString() : "") +
                            (itemDescription != null ? itemDescription : "") +
                            (startTime != null ? startTime.toString() : "") +
                            (endTime != null ? endTime.toString() : "") +
                            (bidAmount != null ? bidAmount.toString() : "");


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

            String data =
                    (transactionId != null ? transactionId.toString() : "") +
                            (timestamp != null ? timestamp.toString() : "") +
                            (sender != null ? Base64.getEncoder().encodeToString(sender.getEncoded()) : "") +
                            (type != null ? type.toString() : "") +
                            (auctionId != null ? auctionId.toString() : "") +
                            (itemDescription != null ? itemDescription : "") +
                            (startTime != null ? startTime.toString() : "") +
                            (endTime != null ? endTime.toString() : "") +
                            (bidAmount != null ? bidAmount.toString() : "");

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

    public byte[] getSignature() {
        return this.signature;
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
    public void setTransactionId(UUID transactionId) {
        this.transactionId = transactionId;
    }

    public void setType(TransactionType type) {
        this.type = type;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public void setSender(PublicKey sender) {
        this.sender = sender;
    }

    public void setAuctionId(UUID auctionId) {
        this.auctionId = auctionId;
    }

    public void setItemDescription(String itemDescription) {
        this.itemDescription = itemDescription;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public void setBidAmount(Double bidAmount) {
        this.bidAmount = bidAmount;
    }

    @Override
    public String toString() {
        return "Transaction{" +
                "transactionId=" + (transactionId != null ? transactionId.toString() : "null") +
                ", type=" + (type != null ? type.name() : "null") +
                ", timestamp=" + (timestamp != null ? timestamp.toString() : "null") +
                ", senderPublicKey=" + (sender != null ? Base64.getEncoder().encodeToString(sender.getEncoded()) : "null") +
                ", auctionId='" + (auctionId != null ? auctionId : "null") + '\'' +
                ", itemDescription='" + (itemDescription != null ? itemDescription : "null") + '\'' +
                ", startTime='" + (startTime != null ? startTime : "null") + '\'' +
                ", endTime='" + (endTime != null ? endTime : "null") + '\'' +
                ", bidAmount='" + (bidAmount != null ? bidAmount : "null") + '\'' +
                '}';
    }


}

