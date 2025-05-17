package Auction;


import java.math.BigInteger;
import java.util.UUID;

public class AuctionMapEntry {
    private UUID auctionId;
    private String itemName;
    private BigInteger ownerNode;
    private boolean active;

    public AuctionMapEntry(UUID auctionId, String itemName, BigInteger ownerNode, boolean active) {
        this.auctionId = auctionId;
        this.itemName = itemName;
        this.ownerNode = ownerNode;
        this.active = active;
    }

    public static AuctionMapEntry fromString(String raw) {
        String[] parts = raw.split(":");
        if (parts.length != 4) {
            throw new IllegalArgumentException("Invalid auction entry format: " + raw);
        }

        UUID auctionId = UUID.fromString(parts[0]);
        String itemName = parts[1];
        BigInteger ownerNode = new BigInteger(parts[2]);
        boolean active = Boolean.parseBoolean(parts[3]);
        return new AuctionMapEntry(auctionId, itemName, ownerNode, active);
    }

    @Override
    public String toString() {
        return "Auction ID: " + auctionId.toString() +
                ", Item: " + itemName +
                ", Owner Node: " + ownerNode.toString() +
                ", Active: " + active;
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

    public boolean getActive() {
        return active;
    }
}
