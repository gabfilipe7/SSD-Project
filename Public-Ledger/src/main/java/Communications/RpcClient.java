package Communications;

import Blockchain.Blockchain;
import Blockchain.Transaction;
import Identity.Reputation;
import Utils.Utils;
import Utils.InstantAdapter;
import Utils.PublicKeyAdapter;
import Utils.StoreValue;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.protobuf.ByteString;
import com.kademlia.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import Kademlia.Node;
import io.grpc.StatusRuntimeException;

import java.security.PublicKey;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import Blockchain.Block;
import java.math.BigInteger;
import java.util.*;

import static Utils.Utils.sha256;
import static java.lang.Math.min;

public class RpcClient {

    private static final long TIMEOUT_SECONDS = 10 ;
    private final ManagedChannel channel;
    private final KademliaServiceGrpc.KademliaServiceBlockingStub stub;
    private ExecutorService executorService;
    private final Node localNode;
    private final Blockchain blockchain;


    Gson gson = new GsonBuilder()
            .registerTypeAdapter(Instant.class, new InstantAdapter())
            .registerTypeHierarchyAdapter(PublicKey.class, new PublicKeyAdapter())
            .create();

    public RpcClient(Node localNode,Blockchain blockchain) {
        this.localNode = localNode;
        this.blockchain = blockchain;
        this.channel = ManagedChannelBuilder
                .forAddress(localNode.getIpAddress(), localNode.getPort())
                .usePlaintext()
                .build();
        this.executorService = Executors.newFixedThreadPool(10);

        this.stub = KademliaServiceGrpc.newBlockingStub(channel);
    }

    public boolean ping(Node peer, Node localNode) {
        ManagedChannel channel = null;
        try {
            channel = ManagedChannelBuilder
                    .forAddress(peer.getIpAddress(), peer.getPort())
                    .usePlaintext()
                    .build();


            KademliaServiceGrpc.KademliaServiceBlockingStub stub = KademliaServiceGrpc.newBlockingStub(channel);

            NodeInfo nodeInfo = NodeInfo.newBuilder()
                    .setId(localNode.getId().toString())
                    .setIp(localNode.getIpAddress())
                    .setPort(localNode.getPort())
                    .build();


            PingRequest request = PingRequest.newBuilder()
                    .setNode(nodeInfo)
                    .build();

            PingResponse response = stub.ping(request);

            if(response.getIsAlive()){

                Reputation rep = localNode.reputationMap.get(peer.getId());

                if(rep != null){
                    double newScore = min(rep.getScore() + 0.005,1);
                    rep.setScore(newScore);
                    rep.setLastUpdated(Instant.now());
                    localNode.reputationMap.put(peer.getId(),rep);

                    byte[] signature = rep.signReputation(localNode.getPrivateKey(),peer.getId());
                    CompletableFuture.runAsync(() -> {
                        System.out.println("BATATAS3");
                        gossipReputation(rep, peer.getId(), signature, localNode);
                    });
                }
                else{
                    Reputation reputation = new Reputation(0.3,Instant.now());
                    localNode.reputationMap.put(peer.getId(),reputation);
                }

                return true;
            }
            else{
                return false;
            }


        } catch (Exception e) {
        // System.err.println("Ping failed to " + peer.getId() + ": " + e.getMessage());
            return false;
        } finally {
            if (channel != null) {
                channel.shutdown();
            }
        }
    }

    public CompletableFuture<List<Node>> findNode(BigInteger targetId) {
        List<Node> initialPeers = this.localNode.findClosestNodes(targetId, 3);
        if (initialPeers == null) {
            initialPeers = new ArrayList<>();
        }
        Set<Node> alreadyChecked = new HashSet<>();
        Set<Node> discovered = new HashSet<>(initialPeers);

        return findNodeRecursive(targetId, initialPeers, alreadyChecked, discovered, 20, 2);
    }

    private CompletableFuture<List<Node>> findNodeRecursive(
            BigInteger targetId,
            List<Node> peers,
            Set<Node> alreadyChecked,
            Set<Node> discovered,
            int ttl,
            int k
    ) {
        if (ttl <= 0 || peers.isEmpty()) {
            List<Node> sorted = new ArrayList<>(discovered);
            sorted.sort(new Utils(targetId));
            return CompletableFuture.completedFuture(sorted.stream().limit(k).collect(Collectors.toList()));
        }

        Utils nodeComparator = new Utils(targetId);
        peers.sort(nodeComparator);

        List<CompletableFuture<List<Node>>> tasks = new ArrayList<>();

        for (Node peer : peers) {
            if (!alreadyChecked.contains(peer)) {
                alreadyChecked.add(peer);

                tasks.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        List<Node> response = findNode(peer, targetId);
                        synchronized (discovered) {
                            discovered.addAll(response);
                        }
                        return response;
                    } catch (Exception e) {
                        return List.of();
                    }
                }, executorService));
            }
        }

        return CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]))
                .thenCompose(v -> {
                    Set<Node> nextPeers = new HashSet<>();
                    for (CompletableFuture<List<Node>> task : tasks) {
                        List<Node> result = task.join();
                        for (Node node : result) {
                            if (!alreadyChecked.contains(node)) {
                                nextPeers.add(node);
                            }
                        }
                    }

                    return findNodeRecursive(
                            targetId,
                            new ArrayList<>(nextPeers),
                            alreadyChecked,
                            discovered,
                            ttl - 1,
                            k
                    );
                });
    }


    public List<Node> findNode(Node peer, BigInteger targetId) {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(peer.getIpAddress(), peer.getPort())
                .usePlaintext()
                .build();

        KademliaServiceGrpc.KademliaServiceBlockingStub stub =
                KademliaServiceGrpc.newBlockingStub(channel);

        FindNodeRequest request = FindNodeRequest.newBuilder()
                .setTargetId(targetId.toString())
                .build();

        FindNodeResponse response = stub.findNode(request);

        channel.shutdown();

        List<Node> nodes = response.getNodesList().stream()
                .map(nodeInfo -> new Node(
                        new BigInteger(nodeInfo.getId()),
                        nodeInfo.getIp(),
                        nodeInfo.getPort()
                ))
                .filter(node -> !node.getId().equals(localNode.getId()))
                .collect(Collectors.toList());

        for(Node node : nodes){
            if(!localNode.containsNode(node.getId()) && !node.getId().equals(localNode.getId())){
                localNode.addNode(node);
            }
        }

        return nodes;
    }

    public void shutdown() {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdown();
        }
    }

    public boolean store(String ip, int port, String key, String value) {
        ManagedChannel channel = null;
        try {
            channel = ManagedChannelBuilder.forAddress(ip, port)
                    .usePlaintext()
                    .build();

            KademliaServiceGrpc.KademliaServiceBlockingStub stub = KademliaServiceGrpc.newBlockingStub(channel);

            StoreRequest request = StoreRequest.newBuilder()
                    .setKey(key)
                    .setValue(value)
                    .setSrc(localNode.getId().toString())
                    .build();

            StoreResponse response = stub.store(request);

            return response.getResponseType() == StoreResponseType.LOCAL_STORE;

        } catch (Exception e) {
            if (channel != null) {
                channel.shutdown();
            }
            return false;
        } finally {
            if (channel != null) {
                channel.shutdown();
            }
        }
    }
public Optional<Set<String>> findValue(String key, int ttl) {
    int alpha = 3;

    Set<String> visitedNodeIds = new HashSet<>();
    Map<BigInteger, Node> closestNodes = new HashMap<>();
    PriorityQueue<Node> queue = new PriorityQueue<>(Comparator.comparing(n -> xorDistance(key, n.getId())));

    List<Node> initialNodes = localNode.findClosestNodes(new BigInteger(key,16),localNode.getK());
    queue.addAll(initialNodes);
    initialNodes.forEach(n -> closestNodes.put(n.getId(), n));

    Set<String> foundValues = new HashSet<>();
    AtomicReference<Boolean> valueFound = new AtomicReference<Boolean>();
    valueFound.set(false);

    while (!queue.isEmpty() && ttl-- > 0) {
        List<Node> alphaNodes = new ArrayList<>();

        while (!queue.isEmpty() && alphaNodes.size() < alpha) {
            Node n = queue.poll();
            if (!visitedNodeIds.contains(n.getId().toString())) {
                alphaNodes.add(n);
                visitedNodeIds.add(n.getId().toString());
            }
        }

        if (alphaNodes.isEmpty()) break;

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Node node : alphaNodes) {
            futures.add(CompletableFuture.runAsync(() -> {
                ManagedChannel channel = null;
                try {
                    channel = ManagedChannelBuilder
                            .forAddress(node.getIpAddress(), node.getPort())
                            .usePlaintext()
                            .build();

                    KademliaServiceGrpc.KademliaServiceBlockingStub stub = KademliaServiceGrpc.newBlockingStub(channel);

                    FindValueRequest request = FindValueRequest.newBuilder()
                            .setKey(key)
                            .build();

                    FindValueResponse response = stub.findValue(request);

                    if (response.getFound()) {
                        foundValues.addAll(response.getValueList());

                        // Update reputation
                        Reputation rep = this.localNode.reputationMap.get(node.getId());
                        if (rep != null) {
                            double newScore = min(rep.getScore() + 0.01,1);
                            rep.setScore(newScore);
                            rep.setLastUpdated(Instant.now());
                            this.localNode.reputationMap.put(node.getId(), rep);

                            byte[] signature = rep.signReputation(this.localNode.getPrivateKey(), node.getId());
                            gossipReputation(rep, node.getId(), signature, localNode);
                        } else {
                            Reputation newReputation = new Reputation(0.3, Instant.now());
                            newReputation.generateId();
                            this.localNode.reputationMap.put(node.getId(), newReputation);
                        }
                        valueFound.set(true);
                    } else {
                        // Merge returned nodes into queue
                        for (NodeInfo nodeInfo : response.getNodesList()) {
                            Node newNode = new Node(
                                    new BigInteger(nodeInfo.getId()),
                                    nodeInfo.getIp(),
                                    nodeInfo.getPort()
                            );
                            if (!visitedNodeIds.contains(newNode.getId().toString())) {
                                queue.add(newNode);
                                closestNodes.put(newNode.getId(), newNode);
                            }
                        }
                    }

                } catch (Exception e) {
                } finally {
                    if (channel != null) {
                        channel.shutdown();
                    }
                }
            }));
        }

        for (CompletableFuture<Void> f : futures) {
            try {
                f.get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
            }
        }

        if (valueFound.get()) {
            return Optional.of(foundValues);
        }
    }

    return foundValues.isEmpty() ? Optional.empty() : Optional.of(foundValues);
}

    // XOR Distance helper
    private BigInteger xorDistance(String key, BigInteger nodeId) {
        BigInteger keyHash = new BigInteger(key,16);
        return keyHash.xor(nodeId);
    }



    public BlockMessage gossipBlock(Block block, Node localNode) {
        BlockMessage blockMessage;

        try {
            List<String> transactions = new ArrayList<>();

            for(Transaction tr: block.getTransactions()){
                transactions.add(gson.toJson(tr));
            }

            com.kademlia.grpc.Block protoBlock = com.kademlia.grpc.Block.newBuilder()
                    .setBlockId(block.getIndex())
                    .setPreviousHash(block.getPreviousBlockHash())
                    .setTimestamp(block.getTimestamp())
                    .setNonce(block.getNonce())
                    .setHash(block.getBlockHash())
                    .addAllTransactions(transactions)
                    .build();



            blockMessage = BlockMessage.newBuilder()
                    .setBlockData(protoBlock)
                    .setSenderId(localNode.getId().toString())
                    .build();

        } catch (Exception e) {
            System.err.println("Failed to convert Block to Protobuf: " + e.getMessage());
            return null;
        }

        for (Node neighbor : localNode.getAllNeighbours()) {
            ManagedChannel channel = null;
            try {
                channel = ManagedChannelBuilder.forAddress(neighbor.getIpAddress(), neighbor.getPort())
                        .usePlaintext()
                        .build();

                KademliaServiceGrpc.KademliaServiceBlockingStub stub = KademliaServiceGrpc.newBlockingStub(channel);

                GossipResponse response = stub.withDeadlineAfter(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .gossipBlock(blockMessage);

                if (response.getSuccess()) {
                    System.out.println("Successfully gossiped block to " + neighbor.getId());
                } else {
                    System.out.println("Failed to gossip block to " + neighbor.getId());
                }

            } catch (StatusRuntimeException e) {
                System.err.println("gRPC error while gossiping to " + neighbor.getId() + ": " + e.getStatus().getDescription());
            } catch (Exception e) {
                System.err.println("Error while gossiping to " + neighbor.getId() + ": " + e.getMessage());
            } finally {
                if (channel != null) {
                    try {
                        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        System.err.println("Channel shutdown interrupted: " + e.getMessage());
                    }
                }
            }
        }

        return blockMessage;
    }

    public void gossipTransaction(Transaction transaction, byte[] signature, BigInteger senderNodeId) {
        TransactionMessage transactionMessage;

        try {

            String transactionJson = gson.toJson(transaction,Transaction.class);

            transactionMessage = TransactionMessage.newBuilder()
                    .setTransactionData(transactionJson)
                    .setSignature(ByteString.copyFrom(signature))
                    .setSenderNodeId(localNode.getId().toString())
                    .build();

        } catch (Exception e) {
            System.err.println("Failed to convert Transaction to Protobuf: " + e.getMessage());
            return;
        }
        localNode.printAllNeighbours();
        for (Node neighbor : localNode.getAllNeighbours()) {
            if(senderNodeId!= null){
                if(neighbor.getId().equals(senderNodeId)){
                    continue;
                }
            }
            ManagedChannel channel = null;
            try {
                channel = ManagedChannelBuilder.forAddress(neighbor.getIpAddress(), neighbor.getPort())
                        .usePlaintext()
                        .build();

                KademliaServiceGrpc.KademliaServiceBlockingStub stub = KademliaServiceGrpc.newBlockingStub(channel);

                GossipResponse response = stub.withDeadlineAfter(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .gossipTransaction(transactionMessage);

                if (response.getSuccess()) {
                    System.out.println("Successfully gossiped transaction to " + neighbor.getId());
                } else {
                    System.out.println("Failed to gossip transaction to " + neighbor.getId());
                }

            } catch (StatusRuntimeException e) {
                System.err.println("gRPC error while gossiping to " + neighbor.getId() + ": " + e.getStatus().getDescription());
            } catch (Exception e) {
                System.err.println("Error while gossiping to " + neighbor.getId() + ": " + e.getMessage());
            } finally {
                if (channel != null) {
                    try {
                        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        System.err.println("Channel shutdown interrupted: " + e.getMessage());
                    }
                }
            }
        }

    }

    public static void updateBlockChain(Node localnode, Blockchain blockchain,long startIndex) {
        List<Node> neighbors = localnode.getAllNeighbours();

        Map<String, List<Block>> chainsByHash = new HashMap<>();

        for (Node neighbor : neighbors) {
            try {
                List<Block> neighborBlocks = RpcClient.requestBlocksFrom(neighbor, startIndex);

                if (neighborBlocks.isEmpty()) {
                    continue;
                }

                Blockchain blockChain = new Blockchain(neighborBlocks);

                if(!blockChain.validateBlockChain()){
                    continue;
                }

                String chainHash = Utils.calculateChainHash(blockChain);
                chainsByHash.computeIfAbsent(chainHash, k -> new ArrayList<>()).addAll(neighborBlocks);

            } catch (Exception e) {
                System.err.println("Failed to get blocks from neighbor " + neighbor.getId() + ": " + e.getMessage());
            }
        }

        if (chainsByHash.isEmpty()) {
            System.out.println("No valid chains received.");
            return;
        }

        String mostCommonChainHash = chainsByHash.entrySet()
                .stream()
                .max(Comparator.comparingInt(e -> e.getValue().size()))
                .map(Map.Entry::getKey)
                .orElse(null);

        if (mostCommonChainHash != null) {
            List<Block> bestChain = chainsByHash.get(mostCommonChainHash);

            blockchain.replaceFromIndex(startIndex, bestChain);
            System.out.println("Blockchain synchronized with majority chain.");
        }
    }

    public static List<Block> requestBlocksFrom(Node peer, long startIndex) {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(peer.getIpAddress(), peer.getPort())
                .usePlaintext()
                .build();
        KademliaServiceGrpc.KademliaServiceBlockingStub stub = KademliaServiceGrpc.newBlockingStub(channel);

        GetBlocksRequest request = GetBlocksRequest.newBuilder()
                .setStartIndex(startIndex)
                .build();

        GetBlocksResponse response = stub.getBlocksFrom(request);
        channel.shutdown();

        List<Block> blocks = new ArrayList<>();

        for (BlockMessage blockMessage : response.getBlocksList()) {
            blocks.add(Utils.convertResponseToBlock(blockMessage));
        }

        return blocks;
    }

   /* public List<AuctionMapEntry> getAuctionListFromNetwork() {
        String key = Utils.sha256("auction_index");
        Set<String> auctionEntries = new HashSet<>();
        List<AuctionMapEntry> result = new ArrayList<>();

        findValue(key, localNode, 10).ifPresent(auctionEntries::addAll);

        for(String entry : new ArrayList<>(auctionEntries)){
            result.add(AuctionMapEntry.fromString(entry));
        }

        Set<String> localAuctions = localNode.getValues(key);
        for(String entry : localAuctions){
            result.add(AuctionMapEntry.fromString(entry));
        }

        return result.stream()
                .filter(AuctionMapEntry::getActive)
                .collect(Collectors.toList());
    }
*/
    public void PublishAuctionBid(UUID auctionId, String key, String payload) {
        String auctionKey = sha256("auction-subs:" + auctionId);
        Set<String> subscribers = localNode.getValues(auctionKey);

        StoreValue value = new StoreValue(StoreValue.Type.BID,payload);
        String payloadJson = gson.toJson(value);
        for (String nodeId : subscribers) {
            findNode(new BigInteger(nodeId)).thenAccept(closeNodes -> {
                for(Node node : closeNodes){
                    if(Objects.equals(node.getId().toString(), nodeId)){
                        store(node.getIpAddress(),node.getPort(),key, payloadJson);
                    }
                }
            });
        }
    }

    public void PublishAuctionClose(String key, String payload) {
        String auctionKey = sha256("auction-subs:" + payload);
        Set<String> subscribers = localNode.getValues(auctionKey);

        StoreValue value = new StoreValue(StoreValue.Type.CLOSE,payload);
        String payloadJson = gson.toJson(value);
        for (String nodeId : subscribers) {
            findNode(new BigInteger(nodeId)).thenAccept(closeNodes -> {
                for(Node node : closeNodes){
                    if(Objects.equals(node.getId().toString(), nodeId)){
                        store(node.getIpAddress(),node.getPort(),key, payloadJson);
                    }
                }
            });
        }
    }

    public void sendPaymentRequest(Node winner, double amount, UUID auctionId) {
        try {
            PaymentRequest request = PaymentRequest.newBuilder()
                    .setAuctionId(auctionId.toString())
                    .setAuctionOwnerId(this.localNode.getId().toString())
                    .setAuctionWinnerId(winner.getId().toString())
                    .setAmount(amount)
                    .build();

            ManagedChannel channel = ManagedChannelBuilder.forAddress(winner.getIpAddress(),winner.getPort())
                    .usePlaintext()
                    .build();

            KademliaServiceGrpc.KademliaServiceBlockingStub stub = KademliaServiceGrpc.newBlockingStub(channel);

            PaymentRequestResponse response = stub.sendPaymentRequest(request);

            if (response.getSuccess()) {
                System.out.println("Payment request sent to winner: " + winner.getId());
            } else {
                System.out.println("Payment request rejected or failed: " + winner.getId());
            }
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("Failed to send payment request to " + winner.getId() + ": " + e.getMessage());
        }
    }

    public void gossipReputation(Reputation reputation, BigInteger targetNodeId, byte[] signature, Node localNode) {
        GossipReputationRequest request;
        try {
            request = GossipReputationRequest.newBuilder()
                    .setReputationMessageId(reputation.getReputationId().toString())
                    .setSenderId(localNode.getId().toString())
                    .setSenderPublicKey(ByteString.copyFrom(localNode.getPublicKey().getEncoded()))
                    .setNodeId(targetNodeId.toString())
                    .setScore(reputation.getScore())
                    .setLastUpdated(reputation.getLastUpdated().toEpochMilli())
                    .setSignature(ByteString.copyFrom(signature))
                    .build();
        } catch (Exception e) {
            System.err.println("Failed to build GossipReputationRequest: " + e);
            return;
        }

        for (Node neighbor : localNode.getAllNeighbours()) {

            if(neighbor.getId().equals(targetNodeId) || neighbor.getId().equals(localNode.getId())){
                continue;
            }

            ManagedChannel channel = null;
            try {
                channel = ManagedChannelBuilder.forAddress(neighbor.getIpAddress(), neighbor.getPort())
                        .usePlaintext()
                        .build();

                KademliaServiceGrpc.KademliaServiceBlockingStub stub = KademliaServiceGrpc.newBlockingStub(channel);

                GossipReputationResponse response = stub
                        .withDeadlineAfter(5, TimeUnit.SECONDS)
                        .gossipReputation(request);

                if (response.getAccepted()) {
                    System.out.println("Successfully gossiped reputation to " + neighbor.getId());
                } else {
                    System.out.println("Gossip rejected by " + neighbor.getId());
                }

            } catch (StatusRuntimeException e) {
                System.err.println("gRPC error while gossiping to " + neighbor.getId() + ": " + e.getStatus().getDescription());
            } catch (Exception e) {
                System.err.println("Error while gossiping to " + neighbor.getId() + ": " + e.getMessage());
            } finally {
                if (channel != null) {
                    try {
                        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        System.err.println("Channel shutdown interrupted: " + e.getMessage());
                    }
                }
            }
        }
    }

    public void findNodeAndUpdateRoutingTable(BigInteger targetId) {
        final int MAX_NEW_NODES = 5;
        int addedCount = 0;

        List<Node> closest = this.localNode.findClosestNodes(targetId, 3);

        for (Node candidate : closest) {
            if (addedCount >= MAX_NEW_NODES) break;

            try {
                List<Node> nodesReturned = findNode(candidate, targetId);

                for (Node newNode : nodesReturned) {
                    if (addedCount >= MAX_NEW_NODES) break;
                    if (!localNode.getId().equals(newNode.getId())
                            && !localNode.containsNode(newNode.getId())) {
                        localNode.addNode(newNode);
                        addedCount++;
                    }
                }

            } catch (Exception e) {
                System.err.println("Failed to contact node " + candidate.getId() + ": " + e.getMessage());
            }
        }
    }

    public CompletableFuture<Boolean> pay(Transaction transaction, byte[] signature) {
        CompletableFuture<Boolean> futureResult = new CompletableFuture<>();

        TransactionMessage transactionMessage;
        try {
            String transactionJson = gson.toJson(transaction, Transaction.class);

            transactionMessage = TransactionMessage.newBuilder()
                    .setTransactionData(transactionJson)
                    .setSignature(ByteString.copyFrom(signature))
                    .setSenderNodeId(localNode.getId().toString())
                    .build();
        } catch (Exception e) {
            System.err.println("Failed to convert Transaction to Protobuf: " + e.getMessage());
            futureResult.complete(false);
            return futureResult;
        }

        findNode(transaction.getAuctionOwnerId()).thenAccept(closeToWinner -> {
            boolean success = false;

            for (Node node : closeToWinner) {
                if (node.getId().equals(transaction.getAuctionOwnerId())) {
                    ManagedChannel channel = null;
                    try {
                        channel = ManagedChannelBuilder.forAddress(node.getIpAddress(), node.getPort())
                                .usePlaintext()
                                .build();

                        KademliaServiceGrpc.KademliaServiceBlockingStub stub = KademliaServiceGrpc.newBlockingStub(channel);

                        GossipResponse response = stub.withDeadlineAfter(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                                .pay(transactionMessage);

                        if (response.getSuccess()) {
                            success = true;
                            System.out.println("Successfully paid to " + node.getId());
                        } else {
                            System.out.println("Failed to pay to " + node.getId());
                        }
                    } catch (StatusRuntimeException e) {
                        System.err.println("gRPC error while paying to " + node.getId() + ": " + e.getStatus().getDescription());
                    } catch (Exception e) {
                        System.err.println("Error while paying to " + node.getId() + ": " + e.getMessage());
                    } finally {
                        if (channel != null) {
                            try {
                                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                System.err.println("Channel shutdown interrupted: " + e.getMessage());
                            }
                        }
                    }
                }
            }

            futureResult.complete(success);

        }).exceptionally(ex -> {
            System.err.println("Error during findNode: " + ex.getMessage());
            futureResult.complete(false);
            return null;
        });

        return futureResult;
    }


}
