package Communications;

import Auction.Auction;
import Auction.Bid;
import Auction.AuctionMapEntry;
import Blockchain.Blockchain;
import Blockchain.Transaction;
import Identity.Reputation;
import Utils.Utils;
import Utils.StoreValue;
import com.google.gson.Gson;
import com.google.protobuf.ByteString;
import com.kademlia.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import Kademlia.Node;
import io.grpc.StatusRuntimeException;

import java.time.Instant;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import Blockchain.Block;
import java.math.BigInteger;
import java.util.*;

import static Utils.Utils.sha256;

public class RpcClient {

    private static final long TIMEOUT_SECONDS = 10 ;
    private final ManagedChannel channel;
    private final KademliaServiceGrpc.KademliaServiceBlockingStub stub;
    private ExecutorService executorService;
    private final Node localNode;
    private final Blockchain blockchain;



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

    public static boolean ping(Node peer, Node localNode) {
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

            return response.getIsAlive();

        } catch (Exception e) {
            System.err.println("Ping failed to " + peer.getId() + ": " + e.getMessage());
            return false;
        } finally {
            if (channel != null) {
                channel.shutdown();
            }
        }
    }

    public CompletableFuture<List<Node>> findNode(BigInteger targetId) {
        List<Node> initialPeers = this.localNode.findClosestNodes(targetId, 3);
        Set<Node> alreadyChecked = new HashSet<>();
        Set<Node> discovered = new HashSet<>(initialPeers);

        return findNodeRecursive(targetId, initialPeers, alreadyChecked, discovered, 20, localNode.getK());
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
            sorted.sort(new Utils(this.localNode));
            return CompletableFuture.completedFuture(sorted.stream().limit(k).collect(Collectors.toList()));
        }

        Utils nodeComparator = new Utils(this.localNode);
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
                .collect(Collectors.toList());

        for(Node node : nodes){
            if(!localNode.containsNode(node.getId())){
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

    public static boolean store(String ip, int port, String key, String value) {
        ManagedChannel channel = null;
        try {
            channel = ManagedChannelBuilder.forAddress(ip, port)
                    .usePlaintext()
                    .build();

            KademliaServiceGrpc.KademliaServiceBlockingStub stub = KademliaServiceGrpc.newBlockingStub(channel);

            StoreRequest request = StoreRequest.newBuilder()
                    .setKey(key)
                    .setValue(value)
                    .build();

            StoreResponse response = stub.store(request);

            return response.getResponseType() == StoreResponseType.LOCAL_STORE;

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            if (channel != null) {
                channel.shutdown();
            }
        }
    }

    public static Optional<Set<String>> findValue(String key, Node node, int ttl) {
        return findValue(key, node, ttl, new HashSet<>());
    }

    private static Optional<Set<String>> findValue(String key, Node node, int ttl, Set<String> visitedNodeIds) {
        if (ttl <= 0) {
            return Optional.empty();
        }

        String nodeId = node.getId().toString();
        if (visitedNodeIds.contains(nodeId)) {
            return Optional.empty();
        }
        visitedNodeIds.add(nodeId);

        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(node.getIpAddress(), node.getPort())
                .usePlaintext()
                .build();

        KademliaServiceGrpc.KademliaServiceBlockingStub stub = KademliaServiceGrpc.newBlockingStub(channel);

        try {
            FindValueRequest request = FindValueRequest.newBuilder()
                    .setKey(key)
                    .build();

            FindValueResponse response = stub.findValue(request);

            if (response.getFound()) {
                return Optional.of(new HashSet<>(response.getValueList()));
            } else {
                for (NodeInfo nodeInfo : response.getNodesList()) {
                    Node nextNode = new Node(
                            new BigInteger(nodeInfo.getId()),
                            nodeInfo.getIp(),
                            nodeInfo.getPort()
                    );

                    Optional<Set<String>> result = findValue(key, nextNode, ttl - 1, visitedNodeIds);
                    if (result.isPresent()) {
                        return result;
                    }
                }

                return Optional.empty();
            }

        } catch (Exception e) {
            e.printStackTrace();
            return Optional.empty();
        } finally {
            channel.shutdown();
        }
    }




    public static BlockMessage gossipBlock(Block block, Node localNode) {
        BlockMessage blockMessage;

        try {
            com.kademlia.grpc.Block protoBlock = com.kademlia.grpc.Block.newBuilder()
                    .setBlockId(block.getIndex())
                    .setPreviousHash(block.getPreviousBlockHash())
                    .setTimestamp(block.getTimestamp())
                    .setNonce(block.getNonce())
                    .setHash(block.getBlockHash())
                    .addAllTransactions(
                            block.getTransactions().stream().map(Utils::convertTransactionToResponse
                            ).collect(Collectors.toList())
                    )
                    .build();

            blockMessage = BlockMessage.newBuilder()
                    .setBlockData(protoBlock)
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

    public static void gossipTransaction(Transaction transaction, byte[] signature, Node localNode, BigInteger senderNodeId) {
        TransactionMessage transactionMessage;

        try {
            com.kademlia.grpc.Transaction protoTx = com.kademlia.grpc.Transaction.newBuilder()
                    .setTransactionId(transaction.getTransactionId() != null ? transaction.getTransactionId().toString() : "")
                    .setType(transaction.getType() != null ? transaction.getType().ordinal() : 0)
                    .setTimestamp(transaction.getTimestamp() != null ? transaction.getTimestamp().toString() : "")
                    .setSenderPublicKey(transaction.getSender() != null ? ByteString.copyFrom(transaction.getSender().getEncoded()) : ByteString.EMPTY)
                    .setAuctionId(transaction.getAuctionId() != null ? transaction.getAuctionId().toString() : "")
                    .setAmount(transaction.getAmount() != null ? transaction.getAmount().toString() : "")
                    .build();



            transactionMessage = TransactionMessage.newBuilder()
                    .setTransactionData(protoTx)
                    .setSignature(ByteString.copyFrom(signature))
                    .setSenderNodeId(localNode.getId().toString())
                    .build();

        } catch (Exception e) {
            System.err.println("Failed to convert Transaction to Protobuf: " + e.getMessage());
            return;
        }
        
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

    public List<AuctionMapEntry> getAuctionListFromNetwork() {
        String key = Utils.sha256("auction_index");
        Set<String> auctionEntries = new HashSet<>();
        List<AuctionMapEntry> result = new ArrayList<>();

        RpcClient.findValue(key, localNode, 10).ifPresent(auctionEntries::addAll);

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

    public void PublishAuctionBid(UUID auctionId, String key, String payload) {
        String auctionKey = sha256("auction-subs:" + auctionId);
        Set<String> subscribers = localNode.getValues(auctionKey);
        Gson gson = new Gson();
        StoreValue value = new StoreValue(StoreValue.Type.BID,payload);
        String payloadJson = gson.toJson(value);
        for (String nodeId : subscribers) {
            findNode(new BigInteger(nodeId)).thenAccept(closeNodes -> {
                for(Node node : closeNodes){
                    if(Objects.equals(node.getId().toString(), nodeId)){
                        RpcClient.store(node.getIpAddress(),node.getPort(),key, payloadJson);
                    }
                }
            });
        }
    }

    public void PublishAuctionClose(String key, String payload) {
        String auctionKey = sha256("auction-subs:" + payload);
        Set<String> subscribers = localNode.getValues(auctionKey);
        Gson gson = new Gson();
        StoreValue value = new StoreValue(StoreValue.Type.CLOSE,payload);
        String payloadJson = gson.toJson(value);
        for (String nodeId : subscribers) {
            findNode(new BigInteger(nodeId)).thenAccept(closeNodes -> {
                for(Node node : closeNodes){
                    if(Objects.equals(node.getId().toString(), nodeId)){
                        RpcClient.store(node.getIpAddress(),node.getPort(),key, payloadJson);
                    }
                }
            });
        }
    }

    public void sendPaymentRequest(Node winner, double amount, UUID auctionId) {
        try {
            PaymentRequest request = PaymentRequest.newBuilder()
                    .setAuctionId(auctionId.toString())
                    .setFrom(this.localNode.getId().toString())
                    .setTo(winner.getId().toString())
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
        } catch (Exception e) {
            System.err.println("Failed to send payment request to " + winner.getId() + ": " + e.getMessage());
        }
    }

    public void gossipReputation(Reputation reputation, BigInteger targetNodeId, byte[] signature, Node localNode, BigInteger senderNodeId) {
        GossipReputationRequest request;

        try {
            request = GossipReputationRequest.newBuilder()
                    .setReputationMessageId(reputation.getReputationId().toString())
                    .setSenderId(localNode.getId().toString())
                    .setNodeId(targetNodeId.toString())
                    .setScore(reputation.getScore())
                    .setLastUpdated(reputation.getLastUpdated().toEpochMilli())
                    .setSignature(ByteString.copyFrom(signature))
                    .build();
        } catch (Exception e) {
            System.err.println("Failed to build GossipReputationRequest: " + e.getMessage());
            return;
        }

        for (Node neighbor : localNode.getAllNeighbours()) {

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


}
