package Auction;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

public class Bid {

    private final BigInteger Bidder;
    private final double Amount;
    private final Instant Timestamp;
    private final UUID AuctionId;

    public Bid(UUID auctionId, BigInteger bidder, double amount, Instant timestamp) {
        this.AuctionId = auctionId;
        this.Bidder = bidder;
        this.Amount = amount;
        this.Timestamp = timestamp;
    }

    public UUID getAuctionId() { return AuctionId; }

    public BigInteger getBidder() { return Bidder; }

    public double getAmount() { return Amount; }

}
