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
import Kademlia.Node;

import java.io.File;
import java.security.PublicKey;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import Blockchain.Block;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.math.BigInteger;
import java.util.*;
import static Utils.Utils.sha256;
import static java.lang.Math.min;

public class RpcClient {

    private static final long TIMEOUT_SECONDS = 10 ;
    private ExecutorService ExecutorService;
    private final Node LocalNode;
    private final Blockchain Blockchain;
    Gson gson = new GsonBuilder()
            .registerTypeAdapter(Instant.class, new InstantAdapter())
            .registerTypeHierarchyAdapter(PublicKey.class, new PublicKeyAdapter())
            .create();

    public RpcClient(Node localNode,Blockchain blockchain) {
        this.LocalNode = localNode;
        this.Blockchain = blockchain;
        this.ExecutorService = Executors.newFixedThreadPool(10);

        String currentDir = System.getProperty("user.dir");
        System.out.println("Current working directory: " + currentDir);
    }

    public boolean ping(Node peer) {
        ManagedChannel channel = null;


        try {
            SslContext sslContext = GrpcSslContexts
                    .forClient()
                    .trustManager(new File("../../Public-Ledger/certs/ca.crt"))
                    .build();

            channel = NettyChannelBuilder.forAddress(peer.getIpAddress(), peer.getPort())
                    .sslContext(sslContext)
                    .overrideAuthority("127.0.0.1")
                    .build();


            KademliaServiceGrpc.KademliaServiceBlockingStub stub = KademliaServiceGrpc.newBlockingStub(channel);

            NodeInfo nodeInfo = NodeInfo.newBuilder()
                    .setId(LocalNode.getId().toString())
                    .setIp(LocalNode.getIpAddress())
                    .setPort(LocalNode.getPort())
                    .build();


            PingRequest request = PingRequest.newBuilder()
                    .setNode(nodeInfo)
                    .build();

            PingResponse response = stub.ping(request);

            if(response.getIsAlive()){

                Reputation rep = LocalNode.ReputationMap.get(peer.getId());

                if(rep != null){
                    double newScore = min(rep.getScore() + 0.005,1);
                    rep.setScore(newScore);
                    rep.setLastUpdated(Instant.now());
                    LocalNode.ReputationMap.put(peer.getId(),rep);

                    byte[] signature = rep.signReputation(LocalNode.getPrivateKey(),peer.getId());
                    CompletableFuture.runAsync(() -> {
                        gossipReputation(rep, peer.getId(), signature, LocalNode);
                    });
                }
                else{
                    Reputation reputation = new Reputation(0.3,Instant.now());
                    LocalNode.ReputationMap.put(peer.getId(),reputation);
                }

                return true;
            }
            else{
                return false;
            }


        } catch (Exception e) {
            return false;
        } finally {
            if (channel != null) {
                channel.shutdown();
            }
        }
    }

    public CompletableFuture<List<Node>> findNode(BigInteger targetId) {
        List<Node> initialPeers = this.LocalNode.findClosestNodes(targetId, 3);
        if (initialPeers == null) {
            initialPeers = new ArrayList<>();
        }
        Set<Node> alreadyChecked = new HashSet<>();
        Set<Node> discovered = new HashSet<>(initialPeers);

        return findNodeRecursive(targetId, initialPeers, alreadyChecked, discovered, 20, 20);
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
                }, ExecutorService));
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
        ManagedChannel channel = null;
        try {
            SslContext sslContext = GrpcSslContexts
                    .forClient()
                    .trustManager(new File("../../Public-Ledger/certs/ca.crt"))
                    .build();

            channel = NettyChannelBuilder.forAddress(peer.getIpAddress(), peer.getPort())
                    .sslContext(sslContext)
                    .overrideAuthority("127.0.0.1")
                    .build();

            KademliaServiceGrpc.KademliaServiceBlockingStub stub =
                    KademliaServiceGrpc.newBlockingStub(channel);

            FindNodeRequest request = FindNodeRequest.newBuilder()
                    .setTargetId(targetId.toString())
                    .build();

            FindNodeResponse response = stub.findNode(request);

            channel.shutdown();
            try {
                if (!channel.awaitTermination(3, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                channel.shutdownNow();
            }

            List<Node> nodes = response.getNodesList().stream()
                    .map(nodeInfo -> new Node(
                            new BigInteger(nodeInfo.getId()),
                            nodeInfo.getIp(),
                            nodeInfo.getPort()
                    ))
                    .filter(node -> !node.getId().equals(LocalNode.getId()))
                    .collect(Collectors.toList());

            for (Node node : nodes) {
                if (!LocalNode.containsNode(node.getId()) && !node.getId().equals(LocalNode.getId())) {
                    LocalNode.addNode(node);
                }
            }

            return nodes;
        } catch (Exception e) {
            return List.of();
        } finally {
            if (channel != null) {
                channel.shutdown();
                try {
                    channel.awaitTermination(3, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    public boolean store(String ip, int port, String key, String value) {
        ManagedChannel channel = null;
        try {
            SslContext sslContext = GrpcSslContexts
                    .forClient()
                    .trustManager(new File("../../Public-Ledger/certs/ca.crt"))
                    .build();

            channel = NettyChannelBuilder.forAddress(ip,port)
                    .sslContext(sslContext)
                    .overrideAuthority("127.0.0.1")
                    .build();

            KademliaServiceGrpc.KademliaServiceBlockingStub stub = KademliaServiceGrpc.newBlockingStub(channel);

            StoreRequest request = StoreRequest.newBuilder()
                    .setKey(key)
                    .setValue(value)
                    .setSrc(LocalNode.getId().toString())
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
        PriorityQueue<Node> queue = new PriorityQueue<>(Comparator.comparing(n -> Utils.xorDistance(key, n.getId())));

        List<Node> initialNodes = LocalNode.findClosestNodes(new BigInteger(key,16),LocalNode.getK());
        if(initialNodes==null){
            return Optional.of(new HashSet<>());
        }
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
                        SslContext sslContext = GrpcSslContexts
                                .forClient()
                                .trustManager(new File("../../Public-Ledger/certs/ca.crt"))
                                .build();

                        channel = NettyChannelBuilder.forAddress(node.getIpAddress(), node.getPort())
                                .sslContext(sslContext)
                                .overrideAuthority("127.0.0.1")
                                .build();

                        KademliaServiceGrpc.KademliaServiceBlockingStub stub = KademliaServiceGrpc.newBlockingStub(channel);

                        FindValueRequest request = FindValueRequest.newBuilder()
                                .setKey(key)
                                .build();

                        FindValueResponse response = stub.findValue(request);

                        if (response.getFound()) {
                            foundValues.addAll(response.getValueList());

                            Reputation rep = this.LocalNode.ReputationMap.get(node.getId());
                            if (rep != null) {
                                double newScore = min(rep.getScore() + 0.01,1);
                                rep.setScore(newScore);
                                rep.setLastUpdated(Instant.now());
                                this.LocalNode.ReputationMap.put(node.getId(), rep);

                                byte[] signature = rep.signReputation(this.LocalNode.getPrivateKey(), node.getId());
                                gossipReputation(rep, node.getId(), signature, LocalNode);
                            } else {
                                Reputation newReputation = new Reputation(0.3, Instant.now());
                                newReputation.generateId();
                                this.LocalNode.ReputationMap.put(node.getId(), newReputation);
                            }
                            valueFound.set(true);
                        } else {
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

                    } catch (Exception ignored) {
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
                } catch (Exception ignored) {
                }
            }

            if (valueFound.get()) {
                return Optional.of(foundValues);
            }
        }

        return foundValues.isEmpty() ? Optional.empty() : Optional.of(foundValues);
    }

    public void gossipBlock(Block block) {
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
                    .setSenderId(LocalNode.getId().toString())
                    .build();
        } catch (Exception e) {
            return;
        }

        for (Node neighbor : LocalNode.getAllNeighbours()) {
            ManagedChannel channel = null;
            try {
                SslContext sslContext = GrpcSslContexts
                        .forClient()
                        .trustManager(new File("../../Public-Ledger/certs/ca.crt"))
                        .build();

                channel = NettyChannelBuilder.forAddress(neighbor.getIpAddress(), neighbor.getPort())
                        .sslContext(sslContext)
                        .overrideAuthority("127.0.0.1")
                        .build();
                KademliaServiceGrpc.KademliaServiceBlockingStub stub = KademliaServiceGrpc.newBlockingStub(channel);

                GossipResponse response = stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                        .gossipBlock(blockMessage);

                if (response.getSuccess()) {
                    System.out.println("\n Successfully gossiped block to node: " + neighbor.getId());
                }

            } catch (Exception ignored) {
            } finally {
                if (channel != null) {
                    try {
                        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }

    }

    public void gossipTransaction(Transaction transaction, byte[] signature, BigInteger senderNodeId) {
        TransactionMessage transactionMessage;

        try {

            String transactionJson = gson.toJson(transaction,Transaction.class);

            transactionMessage = TransactionMessage.newBuilder()
                    .setTransactionData(transactionJson)
                    .setSignature(ByteString.copyFrom(signature))
                    .setSenderNodeId(LocalNode.getId().toString())
                    .build();

        } catch (Exception e) {
            return;
        }

        for (Node neighbor : LocalNode.getAllNeighbours()) {
            if(senderNodeId!= null){
                if(neighbor.getId().equals(senderNodeId)){
                    continue;
                }
            }
            ManagedChannel channel = null;
            try {
                SslContext sslContext = GrpcSslContexts
                        .forClient()
                        .trustManager(new File("../../Public-Ledger/certs/ca.crt"))
                        .build();

                channel = NettyChannelBuilder.forAddress(neighbor.getIpAddress(), neighbor.getPort())
                        .sslContext(sslContext)
                        .overrideAuthority("127.0.0.1")
                        .build();

                KademliaServiceGrpc.KademliaServiceBlockingStub stub = KademliaServiceGrpc.newBlockingStub(channel);

                GossipResponse response = stub.withDeadlineAfter(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .gossipTransaction(transactionMessage);


            } catch (Exception tht) {

            } finally {
                if (channel != null) {
                    try {
                        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                  }
                }
            }
        }

    }

    public List<Block> requestBlocksFrom(Node peer, long startIndex) {
        try {
            SslContext sslContext = GrpcSslContexts
                    .forClient()
                    .trustManager(new File("../../Public-Ledger/certs/ca.crt"))
                    .build();

            ManagedChannel channel = NettyChannelBuilder.forAddress(peer.getIpAddress(), peer.getPort())
                    .sslContext(sslContext)
                    .overrideAuthority("127.0.0.1")
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
        catch (Exception ignored){
            return null;
        }
    }

    public void synchronizeBlockchain() {
        Map<List<Block>, Integer> chainFrequency = new HashMap<>();

        for (Node neighbor : LocalNode.getAllNeighbours()) {
            try {
                List<Block> receivedBlocks = requestBlocksFrom(neighbor, 0);

                boolean found = false;
                for (List<Block> chain : chainFrequency.keySet()) {
                    if (Blockchain.chainsAreEqual(chain, receivedBlocks)) {
                        chainFrequency.put(chain, chainFrequency.get(chain) + 1);
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    chainFrequency.put(receivedBlocks, 1);
                }

            } catch (Exception ignored) {
            }
        }


        List<Block> mostCommonChain = null;
        int maxCount = 0;
        for (Map.Entry<List<Block>, Integer> entry : chainFrequency.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                mostCommonChain = entry.getKey();
            }
        }

        if (mostCommonChain != null && !mostCommonChain.isEmpty()) {
            Blockchain.replaceBlockchain(mostCommonChain);
        }
    }


    public void publishAuctionBid(UUID auctionId, String key, String payload) {
        String auctionKey = sha256("auction-subs:" + auctionId);
        Set<String> subscribers = LocalNode.getValues(auctionKey);

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

    public void publishAuctionClose(String key, String payload) {
        String auctionKey = sha256("auction-subs:" + payload);
        Set<String> subscribers = LocalNode.getValues(auctionKey);

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
                    .setAuctionOwnerId(this.LocalNode.getId().toString())
                    .setAuctionWinnerId(winner.getId().toString())
                    .setAmount(amount)
                    .build();

            SslContext sslContext = GrpcSslContexts
                    .forClient()
                    .trustManager(new File("../../Public-Ledger/certs/ca.crt"))
                    .build();

            ManagedChannel channel = NettyChannelBuilder.forAddress(winner.getIpAddress(), winner.getPort())
                    .sslContext(sslContext)
                    .overrideAuthority("127.0.0.1")
                    .build();


            KademliaServiceGrpc.KademliaServiceBlockingStub stub = KademliaServiceGrpc.newBlockingStub(channel);

            PaymentRequestResponse response = stub.sendPaymentRequest(request);

            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
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
            return;
        }

        for (Node neighbor : localNode.getAllNeighbours()) {

            if(neighbor.getId().equals(targetNodeId) || neighbor.getId().equals(localNode.getId())){
                continue;
            }

            ManagedChannel channel = null;
            try {
                SslContext sslContext = GrpcSslContexts
                        .forClient()
                        .trustManager(new File("../../Public-Ledger/certs/ca.crt"))
                        .build();

                channel = NettyChannelBuilder.forAddress(neighbor.getIpAddress(), neighbor.getPort())
                        .sslContext(sslContext)
                        .overrideAuthority("127.0.0.1")
                        .build();

                KademliaServiceGrpc.KademliaServiceBlockingStub stub = KademliaServiceGrpc.newBlockingStub(channel);

                GossipReputationResponse response = stub
                        .withDeadlineAfter(5, TimeUnit.SECONDS)
                        .gossipReputation(request);

            } catch (Exception ignored) {
            } finally {
                if (channel != null) {
                    try {
                        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
    }

    public void findNodeAndUpdateRoutingTable(BigInteger targetId) {
        final int MAX_NEW_NODES = 5;

        findNode(targetId).thenAccept(nodesReturned -> {
            int addedCount = 0;

            for (Node newNode : nodesReturned) {
                if (addedCount >= MAX_NEW_NODES) break;

                if (!LocalNode.getId().equals(newNode.getId())
                        && !LocalNode.containsNode(newNode.getId())) {
                    LocalNode.addNode(newNode);
                    addedCount++;
                }
            }

        }).exceptionally(ex -> {
            return null;
        });
    }

    public CompletableFuture<Boolean> pay(Transaction transaction, byte[] signature) {
        CompletableFuture<Boolean> futureResult = new CompletableFuture<>();

        TransactionMessage transactionMessage;
        try {
            String transactionJson = gson.toJson(transaction, Transaction.class);

            transactionMessage = TransactionMessage.newBuilder()
                    .setTransactionData(transactionJson)
                    .setSignature(ByteString.copyFrom(signature))
                    .setSenderNodeId(LocalNode.getId().toString())
                    .build();

        } catch (Exception e) {
            futureResult.complete(false);
            return futureResult;
        }

        findNode(transaction.getAuctionOwnerId()).thenAccept(closeToWinner -> {
            boolean success = false;

            for (Node node : closeToWinner) {
                if (node.getId().equals(transaction.getAuctionOwnerId())) {
                    ManagedChannel channel = null;
                    try {

                        SslContext sslContext = GrpcSslContexts
                                .forClient()
                                .trustManager(new File("../../Public-Ledger/certs/ca.crt"))
                                .build();

                        channel = NettyChannelBuilder.forAddress(node.getIpAddress(), node.getPort())
                                .sslContext(sslContext)
                                .overrideAuthority("127.0.0.1")
                                .build();

                        KademliaServiceGrpc.KademliaServiceBlockingStub stub = KademliaServiceGrpc.newBlockingStub(channel);

                        GossipResponse response = stub.withDeadlineAfter(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                                .pay(transactionMessage);

                        if (response.getSuccess()) {

                            success = true;
                        }
                    }catch (Exception ex) {

                    } finally {
                        if (channel != null) {
                            try {
                                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                            } catch (InterruptedException ignored) {
                            }
                        }
                    }
                }
            }

            futureResult.complete(success);

        }).exceptionally(ex -> {

            futureResult.complete(false);
            return null;
        });

        return futureResult;
    }


}
