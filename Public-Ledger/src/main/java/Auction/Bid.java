package Auction;

import java.security.PublicKey;
import java.time.Instant;

public class Bid {
    private PublicKey bidder;
    private double amount;
    private Instant timestamp;

    public Bid(PublicKey bidder, double amount, Instant timestamp) {
        this.bidder = bidder;
        this.amount = amount;
        this.timestamp = timestamp;
    }

    public PublicKey getBidder() { return bidder; }
    public double getAmount() { return amount; }
    public Instant getTimestamp() { return timestamp; }
}
