package Kademlia;

import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.*;
import java.security.Security;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import Blockchain.Blockchain;
import Blockchain.Block;
import Blockchain.Transaction;
import Identity.Authentication;
import Identity.Reputation;
import Auction.Auction;
import Utils.Utils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;


public class Node {

    private BigInteger NodeId;
    private String IpAddress;
    private int K;
    private int Port;
    private ArrayList<KBucket> RoutingTable;
    private boolean IsMiner;
    private double Balance = 100;
    private final Map<String, Set<String>> Map = new ConcurrentHashMap<>();
    private Map<UUID, Auction> Auctions = new HashMap<>();
    private KeyPair KeyPair;
    private boolean IsBootstrap = false;
    public final Map<BigInteger, Reputation> ReputationMap = new HashMap<>();

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    public Node(String ipAddress, int port, int k, boolean isBootstrap, KeyPair keys) {
        this.IpAddress = ipAddress;
        this.Port = port;
        this.RoutingTable = new ArrayList<>(256);
        for (int i = 0; i < 256; i++) {
            this.RoutingTable.add(new KBucket(20));
        }
        this.IsBootstrap = isBootstrap;

        try{
            if (keys == null) {
                keys = generateKeys();
                this.KeyPair = keys;
                this.NodeId = new BigInteger(Utils.sha256(keys.getPublic().getEncoded()),16);
                try {
                    Authentication.saveKeyPair(keys);
                    System.out.println("Generated new keys and saved to file.");
                } catch (IOException e) {
                    System.err.println("Failed to save keys: " + e.getMessage());
                }
            }
            else{
                this.KeyPair = keys;
                this.NodeId = new BigInteger(Utils.sha256(keys.getPublic().getEncoded()),16);
            }

           if(port == 5000){
               this.NodeId = new BigInteger("114463119885993250460859498894823303590894975338136063695510593414907458792199");
                return;
            }
            if(port == 5001){
                this.NodeId = new BigInteger("12345678901234565747082763456095068247603666210263000000526401266846914547799");
            }
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Node(BigInteger nodeId, String ipAddress, int port, int k, boolean isBootstrap) {
        this.IpAddress = ipAddress;
        this.Port = port;
        this.RoutingTable = new ArrayList<>(256);
        for (int i = 0; i < 256; i++) {
            this.RoutingTable.add(new KBucket(20));
        }
        this.IsBootstrap = isBootstrap;
        this.KeyPair = generateKeys();
        this.NodeId = nodeId;
    }

    public Node(BigInteger nodeId, String ipAddress, int port){
        this.IpAddress = ipAddress;
        this.Port = port;
        this.NodeId = nodeId;
    }

    public BigInteger xorDistance(BigInteger other) {
        return this.NodeId.xor(other);
    }

    public int getTargetBucketIndex(BigInteger nodeID) {
        BigInteger distance = this.xorDistance(nodeID);
        int index = distance.bitLength() - 1;
        return (index < 0) ? 0 : (index > 255) ? 255 : index;
    }

    public boolean addNode(Node node) {
        int index = getTargetBucketIndex(node.getId());
        KBucket bucket = this.RoutingTable.get(index);
        boolean success = bucket.addNode(node);

        Reputation rep = ReputationMap.get(node.getId());
        if (rep == null) {
            rep = new Reputation(0.3,Instant.now());
            rep.generateId();
            ReputationMap.put(node.getId(), rep);
        }
        return success;
    }

    public BigInteger getId() {
        return this.NodeId;
    }

    public List<Node> getAllNeighbours() {
        List<Node> allNeighbours = new ArrayList<>();
        for (KBucket bucket : RoutingTable) {
            allNeighbours.addAll(bucket.getNodes());
        }
        return allNeighbours;
    }

    public void printAllNeighbours() {
        List<Node> allNeighbours = getAllNeighbours();
        System.out.println("All Neighbours:");
        for (Node node : allNeighbours) {
            System.out.println("Node ID: " + node.getId());
        }
    }

    public boolean containsNode(BigInteger nodeId) {
        for (KBucket bucket : RoutingTable) {
            for (Node node : bucket.getNodes()) {
                if (node.getId().equals(nodeId)) {
                    return true;
                }
            }
        }
        return false;
    }

    public List<Node> findClosestNodes(BigInteger targetNodeId, int sizeNumber) {
        List<Node> closestNodes = new ArrayList<>();

        int targetBucketIndex = getTargetBucketIndex(targetNodeId);

        int i = 0;
        int j = 1;
        Utils nodeComparator = new Utils(targetNodeId);

        for (int it = 0; it < this.RoutingTable.size(); it++) {

            if (targetBucketIndex + i < this.RoutingTable.size()) {
                KBucket bucket = this.RoutingTable.get(targetBucketIndex + i);
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
                KBucket bucket = this.RoutingTable.get(targetBucketIndex - j);
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

    private static KeyPair generateKeys() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA", "BC");
            keyPairGenerator.initialize(2048);
            KeyPair keys = keyPairGenerator.generateKeyPair();
            return keys;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isMiner(){
        return this.IsMiner;
    }

    public void setIsMiner(boolean isMiner){
        this.IsMiner = isMiner;
    }

    public void addKey(String key, String value) {
        Map.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(value);
    }

    public void addKeyWithReplace(String key, String value) {
        Map.put(key, new HashSet<>(Set.of(value)));
    }

    public void addKeyWithReplace(String key, Set<String> value) {
        Map.put(key, value);
    }

    public Set<String> getValues(String key) {
        return Map.getOrDefault(key, new HashSet<>());
    }

    public Auction createAuction(String itemDescription, Instant startTime) {
        Auction newAuction = new Auction(UUID.randomUUID(), itemDescription, this.getId(), startTime);
        Auctions.put(newAuction.getAuctionId(), newAuction);
        return newAuction;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Node node = (Node) o;

        return  Objects.equals(NodeId, node.NodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(NodeId);
    }

    public int getK(){
        return this.K;
    }

    public void setK(int k){
        this.K = k;
    }

    public String getIpAddress(){
        return this.IpAddress;
    }

    public int getPort(){
        return this.Port;
    }

    public PublicKey getPublicKey() {
        return KeyPair.getPublic();
    }

    public PrivateKey getPrivateKey() {
        return KeyPair.getPrivate();
    }

    public void addAuctionToAuctions(Auction a) {
        this.Auctions.put(a.getAuctionId(),a);
    }


    public boolean removeAuction(UUID auctionId) {
        if (Auctions.containsKey(auctionId)) {
            Auctions.remove(auctionId);
            return true;
        }
        return false;
    }

    public double getBalance() {
        return Balance;
    }

    public void updateBalance(double value) {
        this.Balance = this.Balance + value;
    }

    public ArrayList<KBucket> getRoutingTable(){
        return RoutingTable;
    }

    public long calculateLocalNodeBalance(Blockchain blockchain) {
        long balance = 100;

        for (Block block : blockchain.getChain()) {
            for (Transaction tx : block.getTransactions()) {

                if (tx.getSender().equals(getPublicKey())) {
                    balance -= tx.getAmount();
                }

                if (tx.getAuctionOwnerId().equals(NodeId)) {
                    balance += tx.getAmount();
                }
            }
        }
        this.Balance = balance;
        return balance;
    }

}