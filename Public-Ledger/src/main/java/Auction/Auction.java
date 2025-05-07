package Auction;

import com.google.gson.*;
import org.checkerframework.common.returnsreceiver.qual.This;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
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

    public Auction(UUID auctionId, String itemDescription, PublicKey owner, Instant startTime) {
        this.auctionId = auctionId;
        this.itemDescription = itemDescription;
        this.owner = owner;
        this.startTime = startTime;
        this.endTime = startTime.plusSeconds(48 *3600); // Auction lasts for 1 hour
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

    public String toJson() {
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(PublicKey.class, (JsonSerializer<PublicKey>) (src, typeOfSrc, context) -> {
                    String base64 = Base64.getEncoder().encodeToString(src.getEncoded());
                    return new JsonPrimitive(base64);
                })
                .create();
        return gson.toJson(this);
    }

    public static Auction fromJson(String json) {
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(PublicKey.class, (JsonDeserializer<PublicKey>) (jsonElement, typeOfT, context) -> {
                    try {
                        byte[] bytes = Base64.getDecoder().decode(jsonElement.getAsString());
                        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                        return keyFactory.generatePublic(new X509EncodedKeySpec(bytes));
                    } catch (Exception e) {
                        throw new JsonParseException(e);
                    }
                })
                .create();
        return gson.fromJson(json, Auction.class);
    }

    public PublicKey getWinner() {
        if (bids.isEmpty()) {
            return null;
        }

        Bid winningBid = bids.stream()
                .max(Comparator.comparingDouble(Bid::getAmount))
                .orElse(null);

        return winningBid.getBidder();
    }

    public String getItem() { return itemDescription; }
    public Instant getStartTime() {return startTime; }
    public UUID getAuctionId() { return auctionId; }
    public boolean isClosed() { return isClosed; }
    public PublicKey getOwner() { return owner; }
    public List<Bid> getBids() { return bids; }
}
