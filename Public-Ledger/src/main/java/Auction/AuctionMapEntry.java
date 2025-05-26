package Auction;


import java.math.BigInteger;
import java.util.UUID;

public class AuctionMapEntry {
    private UUID auctionId;
    private String itemName;
    private BigInteger ownerNode;
    public AuctionMapEntry(UUID auctionId, String itemName, BigInteger ownerNode) {
        this.auctionId = auctionId;
        this.itemName = itemName;
        this.ownerNode = ownerNode;
    }

    @Override
    public String toString() {
        return "Auction ID: " + auctionId.toString() +
                ", Item: " + itemName +
                ", Owner Node: " + ownerNode.toString();
    }


    public UUID getAuctionId() {
        return auctionId;
    }

    public String getItemName() {
        return itemName;
    }

    public BigInteger getOwnerNode() {
        return ownerNode;
    }

}
