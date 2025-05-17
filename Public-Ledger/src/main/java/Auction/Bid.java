package Auction;

import java.security.PublicKey;
import java.time.Instant;
import java.util.UUID;

public class Bid {
    private PublicKey bidder;
    private double amount;
    private Instant timestamp;
    private UUID auctionId;

    public Bid(UUID auctionId, PublicKey bidder, double amount, Instant timestamp) {
        this.auctionId = auctionId;
        this.bidder = bidder;
        this.amount = amount;
        this.timestamp = timestamp;
    }

    public UUID getAuctionId() { return auctionId; }
    public PublicKey getBidder() { return bidder; }
    public double getAmount() { return amount; }
    public Instant getTimestamp() { return timestamp; }
}
