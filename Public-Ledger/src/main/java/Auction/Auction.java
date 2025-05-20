package Auction;

import com.google.gson.*;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;

public class Auction {
    private UUID auctionId;
    private String itemDescription;
    private BigInteger owner;
    private Instant startTime;
    private Instant endTime;
    private boolean isClosed;
    private List<Bid> bids = new ArrayList<>();

    public Auction(UUID auctionId, String itemDescription, BigInteger owner, Instant startTime) {
        this.auctionId = auctionId;
        this.itemDescription = itemDescription;
        this.owner = owner;
        this.startTime = startTime;
        this.isClosed = false;
    }

    public void placeBid(BigInteger bidder, double amount) {
        if (!isClosed /*&& Instant.now().isBefore(endTime)*/) {
            bids.add(new Bid(this.getAuctionId(), bidder, amount, Instant.now()));
        }
    }

    public void placeBid(Bid bid) {
        if (!isClosed /*&& Instant.now().isBefore(endTime)*/) {
            bids.add(bid);
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

    public BigInteger getWinner() {
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
    public BigInteger getOwner() { return owner; }
    public List<Bid> getBids() { return bids; }
}
