package Kademlia;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.Security;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;

import Auction.Auction;
import Utils.Utils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

public class Node {

    private BigInteger nodeId;
    private String ipAddress;
    private int K;
    private int port;
    private ArrayList<KBucket> routingTable;
    private ExecutorService executorService;
    private boolean isMiner;
    private Map<String, String> map;
    private Map<UUID, Auction> auctions = new HashMap<>();
    private KeyPair keyPair;


    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    public Node(String ipAddress, int port, int k) {
        this.ipAddress = ipAddress;
        this.port = port;
        this.map = new HashMap<>();
        this.routingTable = new ArrayList<>(256);
        for (int i = 0; i < 256; i++) {
            this.routingTable.add(new KBucket(k));
        }

        try{
            KeyPair keys = generateKeys();
            this.keyPair = keys;
            byte[] encodedPK = keys.getPublic().getEncoded();

            MessageDigest digest = MessageDigest.getInstance("SHA-256", "BC");
            byte[] hashBytes = digest.digest(encodedPK);


            this.nodeId = new BigInteger(1, hashBytes);
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Node(BigInteger nodeId, String ipAddress, int port){
        this.ipAddress = ipAddress;
        this.port = port;
        this.nodeId = nodeId;
    }

    public BigInteger xorDistance(BigInteger other) {
        return this.nodeId.xor(other);
    }

    public int getTargetBucketIndex(BigInteger nodeID) {
        BigInteger distance = this.xorDistance(nodeID);
        int index = distance.bitLength() - 1;
        return (index < 0) ? 0 : (index > 255) ? 255 : index;
    }

    public boolean addNode(Node node) {
        int index = getTargetBucketIndex(node.getId());
        KBucket bucket = this.routingTable.get(index);
        boolean success = bucket.addNode(node);
        return success;
    }

    public BigInteger getId() {
        return this.nodeId;
    }

    public boolean ping(Node node) {
        System.out.println("Pinging node: " + node.getId());
        return true;
    }

    public List<Node> getAllNeighbours() {
        List<Node> allNeighbours = new ArrayList<>();
        for (KBucket bucket : routingTable) {
            allNeighbours.addAll(bucket.getNodes());
        }
        return allNeighbours;
    }

    public List<Node> findClosestNodes(BigInteger targetNodeId, int sizeNumber) {
        List<Node> closestNodes = new ArrayList<>();

        int targetBucketIndex = getTargetBucketIndex(targetNodeId);

        int i = 0;
        int j = 1;
        Utils nodeComparator = new Utils(this);

        for (int it = 0; it < this.routingTable.size(); it++) {

            if (targetBucketIndex + i < this.routingTable.size()) {
                KBucket bucket = this.routingTable.get(targetBucketIndex + i);
                if (bucket != null && !bucket.getNodes().isEmpty()) {
                    for (Node node : bucket.getNodes()) {
                        if (closestNodes.size() < sizeNumber) {
                            closestNodes.add(node);
                        } else {
                            closestNodes.sort(nodeComparator);
                            return closestNodes.subList(0, sizeNumber);
                        }
                    }
                }
                i++;
            }

            if (targetBucketIndex - j >= 0) {
                KBucket bucket = this.routingTable.get(targetBucketIndex - j);
                if (bucket != null && !bucket.getNodes().isEmpty()) {
                    for (Node node : bucket.getNodes()) {
                        if (closestNodes.size() < sizeNumber) {
                            closestNodes.add(node);
                        } else {
                            closestNodes.sort(nodeComparator);
                            return closestNodes.subList(0, sizeNumber);
                        }
                    }
                }
                j++;
            }
        }

        if (closestNodes.isEmpty()) {
            return null;
        }

        closestNodes.sort(nodeComparator);

        return closestNodes.subList(0, Math.min(sizeNumber, closestNodes.size()));
    }

    private static KeyPair generateKeys()  {

        try{

        ECGenParameterSpec parameters = new ECGenParameterSpec("secp256r1");

        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC", "BC");
        keyPairGenerator.initialize(parameters);

        KeyPair keys = keyPairGenerator.generateKeyPair();

        return keys;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isMiner(){
        return this.isMiner;
    }

    public void addKey(String key, String value) {
        map.put(key, value);
    }

    public String getValue(String key) {
        return map.get(key);
    }

    public boolean removeKey(String key) {
        if (map.containsKey(key)) {
            map.remove(key);
            return true;
        }
        return false;
    }

    public Auction createAuction(String itemDescription, Instant startTime) {
        Auction newAuction = new Auction(UUID.randomUUID(), itemDescription, this.getPublicKey(), startTime);
        auctions.put(newAuction.getAuctionId(), newAuction);
        return newAuction;
    }

    public boolean closeAuction(UUID auctionId) {
        Auction auction = auctions.get(auctionId);
        if (auction != null && auction.getOwner().equals(this.getPublicKey()))  {
            auction.closeAuction();
            return true;
        }
        return false;
    }

    public boolean placeBid(UUID auctionId, PublicKey bidder, double bidAmount) {
        Auction auction = auctions.get(auctionId);
        if (auction != null && !auction.isClosed() && Instant.now().isAfter(auction.getStartTime())) {
            auction.placeBid(bidder, bidAmount);
            return true;
        }
        return false;
    }

    public Auction getAuction(UUID auctionId) {
        return auctions.get(auctionId);
    }

    public int getK(){
        return this.K;
    }
    public String getIpAddress(){
        return this.ipAddress;
    }
    public int getPort(){
        return this.port;
    }
    public PublicKey getPublicKey() {
        return keyPair.getPublic();
    }

    public PrivateKey getPrivateKey() {
        return keyPair.getPrivate();
    }

    public List<Auction> GetListedAuctions(){
        return new ArrayList<>(auctions.values());
    }
}