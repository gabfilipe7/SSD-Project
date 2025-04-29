package Communications;

import Blockchain.Blockchain;
import Blockchain.Transaction;
import Kademlia.Utils;
import com.google.protobuf.ByteString;
import com.kademlia.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import Kademlia.Node;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.stream.Collectors;
import Blockchain.Block;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class RpcClient {

    private static final long TIMEOUT_SECONDS = 10 ;
    private final ManagedChannel channel;
    private final KademliaServiceGrpc.KademliaServiceBlockingStub stub;
    private ExecutorService executorService;
    private final Node localNode;


    public RpcClient(String host, int port, Node localNode) {
        this.localNode = localNode;
        this.channel = ManagedChannelBuilder
                .forAddress(host, port)
                .usePlaintext()
                .build();
        this.stub = KademliaServiceGrpc.newBlockingStub(channel);
    }

    public boolean ping(String nodeId) {
        PingRequest request = PingRequest.newBuilder()
                .setNodeId(nodeId)
                .build();

        PingResponse response = stub.ping(request);
        return response.getIsAlive();
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

    public StoreResponseType storeValue(Node targetNode, byte[] key, String value, Node localNode, int ttl) {
        StoreRequest request = StoreRequest.newBuilder()
                .setKey(com.google.protobuf.ByteString.copyFrom(key))
                .setValue(value)
                .setTtl(ttl)
                .setSrc(SourceAddress.newBuilder()
                        .setId(com.google.protobuf.ByteString.copyFrom(localNode.getId().toByteArray()))
                        .setIp(localNode.getIpAddress())
                        .setPort(localNode.getPort())
                        .build())
                .setDst(DestinationAddress.newBuilder()
                        .setIp(targetNode.getIpAddress())
                        .setPort(targetNode.getPort())
                        .build())
                .build();

        StoreResponse response = stub.store(request);
        return response.getResponseType();
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
                            block.getTransactions().stream().map(tx ->
                                    com.kademlia.grpc.Transaction.newBuilder()
                                            .setTransactionId(tx.getTransactionId().toString())
                                            .setType(tx.getType().ordinal())
                                            .setTimestamp(tx.getTimestamp().toString())
                                            .setSenderPublicKey(ByteString.copyFrom(tx.getSender().getEncoded()))
                                            .setFrom("PLACEHOLDER")
                                            .setTo("PLACEHOLDER")
                                            .build()
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

    public static TransactionMessage gossipTransaction(Transaction transaction, byte[] signature, Node localNode) {
        TransactionMessage transactionMessage;

        try {
            com.kademlia.grpc.Transaction protoTx = com.kademlia.grpc.Transaction.newBuilder()
                    .setTransactionId(transaction.getTransactionId().toString())
                    .setType(transaction.getType().ordinal())
                    .setTimestamp(transaction.getTimestamp().toString())
                    .setSenderPublicKey(ByteString.copyFrom(transaction.getSender().getEncoded()))
                    .setFrom("PLACEHOLDER")
                    .setTo("PLACEHOLDER")
                    .build();

            transactionMessage = TransactionMessage.newBuilder()
                    .setTransactionData(protoTx)
                    .setSignature(ByteString.copyFrom(signature))
                    .build();

        } catch (Exception e) {
            System.err.println("Failed to convert Transaction to Protobuf: " + e.getMessage());
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

        return transactionMessage;
    }



}
