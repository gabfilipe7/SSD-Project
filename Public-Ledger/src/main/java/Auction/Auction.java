package Auction;

import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

public class Auction {

    private UUID AuctionId;
    private String ItemDescription;
    private BigInteger Owner;
    private Instant StartTime;
    private boolean IsClosed;
    private List<Bid> Bids = new ArrayList<>();

    public Auction(UUID auctionId, String itemDescription, BigInteger owner, Instant startTime) {
        this.AuctionId = auctionId;
        this.ItemDescription = itemDescription;
        this.Owner = owner;
        this.StartTime = startTime;
        this.IsClosed = false;
    }

    public void placeBid(Bid bid) {
        Bids.add(bid);
    }

    public void closeAuction() {
        this.IsClosed = true;
    }

    public Optional<Bid> getWinningBid() {
        return Bids.stream().max(Comparator.comparingDouble(Bid::getAmount));
    }

    public String getItem() { return ItemDescription; }

    public UUID getAuctionId() { return AuctionId; }

    public boolean isClosed() { return IsClosed; }

    public BigInteger getOwner() { return Owner; }

}
