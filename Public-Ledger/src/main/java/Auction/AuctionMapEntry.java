package Auction;

import java.math.BigInteger;
import java.util.UUID;

public class AuctionMapEntry {

    private final UUID AuctionId;
    private final String ItemName;
    private final BigInteger OwnerNode;

    public AuctionMapEntry(UUID auctionId, String itemName, BigInteger ownerNode) {
        this.AuctionId = auctionId;
        this.ItemName = itemName;
        this.OwnerNode = ownerNode;
    }

    @Override
    public String toString() {
        return "Auction ID: " + AuctionId.toString() +
                ", Item: " + ItemName +
                ", Owner Node: " + OwnerNode.toString();
    }

    public UUID getAuctionId() {
        return AuctionId;
    }

    public String getItemName() {
        return ItemName;
    }

    public BigInteger getOwnerNode() {
        return OwnerNode;
    }

}
