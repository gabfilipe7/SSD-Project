package Communications;

import Blockchain.Blockchain;
import Blockchain.Transaction;
import Utils.Utils;
import com.google.protobuf.ByteString;
import com.kademlia.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import Kademlia.Node;
import io.grpc.StatusRuntimeException;

import java.util.stream.Collectors;
import Blockchain.Block;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

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



    public CompletableFuture<Optional<Node>> findNode(BigInteger targetId) {
        List<Node> initialPeers = this.localNode.findClosestNodes(targetId,  this.localNode.getK());
        Set<Node> alreadyChecked = new HashSet<>();

        return findNodeRecursive(targetId, initialPeers, alreadyChecked,20);
    }

    private CompletableFuture<Optional<Node>> findNodeRecursive(
            BigInteger targetId,
            List<Node> peers,
            Set<Node> alreadyChecked,
            int ttl
    ) {
        if (ttl <= 0) {
            List<Node> closestNodes = new ArrayList<>(alreadyChecked);
            return CompletableFuture.completedFuture(Optional.ofNullable(closestNodes.isEmpty() ? null : closestNodes.get(0)));
        }

        Utils nodeComparator = new Utils(this.localNode);
        peers.sort(nodeComparator);

        List<CompletableFuture<List<Node>>> tasks = new ArrayList<>();
        Queue<Node> closerPeers = new ConcurrentLinkedQueue<>();

        for (Node peer : peers) {
            if (!alreadyChecked.contains(peer)) {
                alreadyChecked.add(peer);

                tasks.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        List<Node> response = findNode(peer, targetId);
                        if (!response.isEmpty() && response.get(0).getId().compareTo(targetId) == 0) {
                            return List.of(response.get(0));
                        } else {
                            closerPeers.addAll(response);
                            return List.of();
                        }
                    } catch (Exception e) {
                        return List.of();
                    }
                }, executorService));
            }
        }

        return CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]))
                .thenCompose(v -> {
                    for (CompletableFuture<List<Node>> task : tasks) {
                        List<Node> result = task.join();
                        if (!result.isEmpty()) {
                            Node firstNode = result.get(0);
                            // Check if the first node is the target
                            if (firstNode.getId().equals(targetId)) {
                                return CompletableFuture.completedFuture(Optional.of(firstNode));
                            }
                        }
                    }

                    List<Node> closerPeersList = new ArrayList<>();
                    for (CompletableFuture<List<Node>> task : tasks) {
                        List<Node> result = task.join();
                        closerPeersList.addAll(result);
                    }

                    Set<Node> uniquePeers = closerPeersList.stream()
                            .filter(n -> !alreadyChecked.contains(n))
                            .collect(Collectors.toSet());

                    return findNodeRecursive(targetId, new ArrayList<>(uniquePeers), alreadyChecked, ttl - 1)
                            .thenApply(optionalNode -> {
                                if (optionalNode.isEmpty()) {
                                    List<Node> closestNodes = new ArrayList<>(alreadyChecked);
                                    return Optional.ofNullable(closestNodes.isEmpty() ? null : closestNodes.get(0));
                                }
                                return optionalNode;
                            });
                });
    }






    public static List<Node> findNode(Node peer, BigInteger targetId) {
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

        return response.getNodesList().stream()
                .map(nodeInfo -> new Node(
                        new BigInteger(nodeInfo.getId()),
                        nodeInfo.getIp(),
                        nodeInfo.getPort()
                ))
                .collect(Collectors.toList());
    }

    public void shutdown() {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdown();
        }
    }

    public static boolean storeKeyValue(String ip, int port, String key, String value, int ttl) {
        ManagedChannel channel = null;
        try {
            channel = ManagedChannelBuilder.forAddress(ip, port)
                    .usePlaintext()
                    .build();

            KademliaServiceGrpc.KademliaServiceBlockingStub stub = KademliaServiceGrpc.newBlockingStub(channel);

            StoreRequest request = StoreRequest.newBuilder()
                    .setKey(key)
                    .setValue(value)
                    .setTtl(ttl)
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
    public static Optional<String> findValue(String key, Node node) {
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
                return Optional.of(response.getValue());
            } else {
                //ver esta parte depois
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
                    .setItemDescription(transaction.getItemDescription() != null ? transaction.getItemDescription() : "")
                    .setStartTime(transaction.getStartTime() != null ? transaction.getStartTime().toString() : "")
                    .setEndTime(transaction.getEndTime() != null ? transaction.getEndTime().toString() : "")
                    .setBidAmount(transaction.getBidAmount() != null ? transaction.getBidAmount().toString() : "")
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


}
