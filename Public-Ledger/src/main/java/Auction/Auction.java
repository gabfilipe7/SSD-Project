package Auction;

import java.security.PublicKey;
import java.time.Instant;
import java.util.*;

public class Auction {
    private UUID auctionId;
    private String itemDescription;
    private PublicKey owner;
    private Instant startTime;
    private Instant endTime;
    private boolean isClosed;
    private List<Bid> bids = new ArrayList<>();

    public Auction(UUID auctionId, String itemDescription, PublicKey owner, Instant startTime, Instant endTime) {
        this.auctionId = auctionId;
        this.itemDescription = itemDescription;
        this.owner = owner;
        this.startTime = startTime;
        this.endTime = endTime;
        this.isClosed = false;
    }

    public void placeBid(PublicKey bidder, double amount) {
        if (!isClosed && Instant.now().isBefore(endTime)) {
            bids.add(new Bid(bidder, amount, Instant.now()));
        }
    }

    public void closeAuction() {
        this.isClosed = true;
    }

    public Optional<Bid> getWinningBid() {
        return bids.stream().max(Comparator.comparingDouble(Bid::getAmount));
    }
    
    public UUID getAuctionId() { return auctionId; }
    public boolean isClosed() { return isClosed; }
    public PublicKey getOwner() { return owner; }
    public List<Bid> getBids() { return bids; }
}
