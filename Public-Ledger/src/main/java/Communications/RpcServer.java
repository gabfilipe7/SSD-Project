package Communications;

import Auction.Auction;
import Auction.Bid;
import Auction.AuctionMapEntry;
import Blockchain.Blockchain;
import Blockchain.Transaction;
import Identity.Reputation;
import Kademlia.Node;
import Utils.Utils;
import Utils.InstantAdapter;
import Utils.PublicKeyAdapter;
import Utils.StoreValue;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kademlia.grpc.*;
import Blockchain.Block;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.math.BigInteger;
import java.util.stream.Collectors;
import static Utils.Utils.sha256;
import static java.lang.Math.max;
import static java.lang.Math.min;

public class RpcServer extends KademliaServiceGrpc.KademliaServiceImplBase {

    public static RpcClient RpcClient;
    private final Node LocalNode;
    private final Blockchain Blockchain;
    private int MaxTransactionsPerBlock;
    private volatile boolean IsMining = false;
    private volatile Block CurrentBlockMining;
    private Thread MiningThread;
    private Set<UUID> ReputationIds = new HashSet<>();
    private Set<UUID> TransactionIds = new HashSet<>();

    Gson gson = new GsonBuilder()
            .registerTypeAdapter(Instant.class, new InstantAdapter())
            .registerTypeHierarchyAdapter(PublicKey.class, new PublicKeyAdapter())
            .create();

    public RpcServer(Node localNode, Blockchain blockchain) {
        this.LocalNode = localNode;
        this.Blockchain = blockchain;
        this.MaxTransactionsPerBlock = 1;
    }

    @Override
    public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
        PingResponse response = PingResponse.newBuilder()
                .setIsAlive(true)
                .build();

        Node node = new Node(new BigInteger(request.getNode().getId()), request.getNode().getIp(),request.getNode().getPort());
        this.LocalNode.addNode(node);

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }


    @Override
    public void findNode(FindNodeRequest request, StreamObserver<FindNodeResponse> responseObserver) {
        BigInteger targetId = new BigInteger(request.getTargetId());
        List<Node> closest = LocalNode.findClosestNodes(targetId, LocalNode.getK());

        List<NodeInfo> nodeInfos = closest.stream()
                .filter(node -> !node.getId().equals(LocalNode.getId()))
                .map(node -> NodeInfo.newBuilder()
                        .setId(node.getId().toString())
                        .setIp(node.getIpAddress())
                        .setPort(node.getPort())
                        .build())
                .collect(Collectors.toList());

        FindNodeResponse response = FindNodeResponse.newBuilder()
                .addAllNodes(nodeInfos)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }


    @Override
    public void gossipTransaction(TransactionMessage request, StreamObserver<GossipResponse> responseObserver) {
        try {

            Transaction assembledTransaction = gson.fromJson(request.getTransactionData(),Transaction.class);

            if (TransactionIds.contains(assembledTransaction.getTransactionId())) {
                responseObserver.onNext(GossipResponse.newBuilder().setSuccess(false).build());
                responseObserver.onCompleted();
                return;
            }
            else{
                TransactionIds.add(assembledTransaction.getTransactionId());
            }

            if (this.Blockchain.containsTransaction(assembledTransaction.getTransactionId())){
                responseObserver.onNext(GossipResponse.newBuilder().setSuccess(false).build());
                responseObserver.onCompleted();
                return;
            }

            Reputation senderReputation = LocalNode.ReputationMap.get(new BigInteger(request.getSenderNodeId()));
            if (senderReputation == null || senderReputation.getScore() < 0.2) {
                responseObserver.onNext(GossipResponse.newBuilder().setSuccess(false).build());
                responseObserver.onCompleted();
                return;
            }


            assembledTransaction.setSignature(request.getSignature().toByteArray());
            double score = assembledTransaction.validateTransaction();
            if (score == 1) {
                BigInteger nodeId =  new BigInteger(Utils.sha256(assembledTransaction.getSender().getEncoded()),16);

                Reputation rep = this.LocalNode.ReputationMap.get(nodeId);

                if(rep==null){
                    rep = new Reputation(0.51,Instant.now());
                    rep.generateId();
                    this.LocalNode.ReputationMap.put(nodeId,rep);
                }
                else{
                    double newScore = rep.getScore() + 0.01;
                    rep.setScore(newScore);
                    rep.setLastUpdated(Instant.now());
                    this.LocalNode.ReputationMap.put(nodeId,rep);
                }
                Reputation finalRep = rep;
                byte[] signature = finalRep.signReputation(LocalNode.getPrivateKey(), nodeId);
                CompletableFuture.runAsync(() -> {
                    RpcClient.gossipReputation(finalRep, nodeId, signature, LocalNode);
                });
                RpcClient.gossipTransaction(assembledTransaction, request.getSignature().toByteArray(), new BigInteger(request.getSenderNodeId()));
                manageMempool(assembledTransaction);
            } else{
                BigInteger nodeId = new BigInteger(Utils.sha256(assembledTransaction.getSender().toString()));
                Reputation rep = this.LocalNode.ReputationMap.get(nodeId);

                if(rep==null){
                    rep = new Reputation(0.3,Instant.now());
                    rep.generateId();
                    this.LocalNode.ReputationMap.put(nodeId,rep);
                }
                else{
                    double newScore = rep.getScore() - score;
                    rep.setScore(newScore);
                    rep.setLastUpdated(Instant.now());
                    this.LocalNode.ReputationMap.put(nodeId,rep);
                }
                Reputation finalRep = rep;
                byte[] signature = rep.signReputation(LocalNode.getPrivateKey(), nodeId);
                CompletableFuture.runAsync(() -> {
                    RpcClient.gossipReputation(finalRep, nodeId, signature, LocalNode);
                });

            }

            responseObserver.onNext(GossipResponse.newBuilder().setSuccess(score == 1).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            e.printStackTrace();
            responseObserver.onError(Status.INTERNAL.withDescription("Failed to process transaction").asRuntimeException());
        }
    }
    @Override
    public void gossipBlock(BlockMessage request, StreamObserver<GossipResponse> responseObserver) {
        try{
            com.kademlia.grpc.Block receivedBlock = request.getBlockData();

            Reputation senderReputation = LocalNode.ReputationMap.get(new BigInteger(request.getSenderId()));
            if (senderReputation == null || senderReputation.getScore() < 0.2) {
                responseObserver.onNext(GossipResponse.newBuilder().setSuccess(false).build());
                responseObserver.onCompleted();
                return;
            }

            List<Transaction> transactions = new ArrayList<>();

            for(String tr: request.getBlockData().getTransactionsList()){
                transactions.add(gson.fromJson(tr,Transaction.class));
            }

            if (receivedBlock.getBlockId() <= Blockchain.getLastBlock().getIndex()) {
                responseObserver.onNext(GossipResponse.newBuilder().setSuccess(false).build());
                responseObserver.onCompleted();
                return;
            }

            if (!Blockchain.contains(receivedBlock.getBlockId())) {
                Block block = new Block(receivedBlock.getBlockId(),
                                    receivedBlock.getHash(),
                                    receivedBlock.getPreviousHash(),
                                    receivedBlock.getTimestamp(),
                                    transactions,
                                    receivedBlock.getNonce());

                double score = Blockchain.verifyBlock(block);
                if (score == 1) {
                    if(CurrentBlockMining != null){
                        if(Objects.equals(CurrentBlockMining.getPreviousBlockHash(), block.getPreviousBlockHash())){
                            stopMining();
                        }
                    }

                    BigInteger senderId = new BigInteger(String.valueOf(request.getSenderId()));
                    Reputation rep = this.LocalNode.ReputationMap.get(senderId);
                    if(rep==null){
                        rep = new Reputation(0.3,Instant.now());
                        rep.generateId();
                        this.LocalNode.ReputationMap.put(senderId,rep);
                    }
                    else{
                        double newScore = rep.getScore() + 0.03;
                        rep.setScore(newScore);
                        rep.setLastUpdated(Instant.now());
                        this.LocalNode.ReputationMap.put(senderId,rep);
                    }

                    Reputation finalRep = rep;
                    byte[] signature = rep.signReputation(LocalNode.getPrivateKey(), senderId);
                    CompletableFuture.runAsync(() -> {
                        RpcClient.gossipReputation(finalRep, senderId, signature, LocalNode);
                    });

                    Blockchain.addNewBlock(block);
                    RpcClient.gossipBlock(block);
                }
                else if (!Blockchain.contains(receivedBlock.getBlockId() - 1)) {
                    stopMining();
                    RpcClient.synchronizeBlockchain();
                    responseObserver.onNext(GossipResponse.newBuilder().setSuccess(false).build());
                    responseObserver.onCompleted();
                    return;
                }
                else {
                    BigInteger senderId = new BigInteger(String.valueOf(request.getSenderId()));
                    Reputation rep = this.LocalNode.ReputationMap.get(senderId);

                    if(rep==null){
                        rep = new Reputation(0.3,Instant.now());
                        rep.generateId();
                        this.LocalNode.ReputationMap.put(senderId,rep);
                    }
                    else{
                        double newScore = rep.getScore() - score;
                        rep.setScore(newScore);
                        rep.setLastUpdated(Instant.now());
                        this.LocalNode.ReputationMap.put(senderId,rep);
                    }
                    Reputation finalRep = rep;
                    byte[] signature = rep.signReputation(LocalNode.getPrivateKey(), senderId);
                    CompletableFuture.runAsync(() -> {
                        System.out.println("BATATAS2");
                        RpcClient.gossipReputation(finalRep, senderId, signature, LocalNode);
                    });


                    responseObserver.onNext(GossipResponse.newBuilder().setSuccess(false).build());
                    responseObserver.onCompleted();
                    return;
                }
            }

            GossipResponse response = GossipResponse.newBuilder().setSuccess(true).build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        }
        catch (Exception e) {
            e.printStackTrace();
            responseObserver.onError(e);
        }
    }

    @Override
    public void store(StoreRequest request, StreamObserver<StoreResponse> responseObserver) {
        try {
            String key = request.getKey();

            StoreValue value = gson.fromJson(request.getValue(), StoreValue.class);

            switch (value.getType()) {
                case AUCTION:
                    System.out.println("Recieved new auction information.");
                    handleAuction(key, value.getPayload(), request.getSrc());
                    break;

                case SUBSCRIPTION:
                    System.out.println("Recieved new auction subscription.");
                    handleSubscription(key, value.getPayload());
                    break;
                case BID:
                    System.out.println("Recieved new bid.");
                    handleBid(key, value.getPayload());
                    break;
                case CLOSE:
                    System.out.println("Recieved auction closed.");
                    handleClose(key, value.getPayload());
                    break;
                case ADD_CATALOG:
                    System.out.println("Recieved catalog add.");
                    handleAddCatalog(key, value.getPayload());
                    break;
                case REMOVE_CATALOG:
                    System.out.println("Recieved catalog remove.");
                    handleRemoveCatalog(key, value.getPayload());
                    break;
            }

            StoreResponse response = StoreResponse.newBuilder()
                    .setResponseType(StoreResponseType.LOCAL_STORE)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            e.printStackTrace();
            responseObserver.onError(
                    Status.INTERNAL.withDescription("Failed to store key-value pair").asRuntimeException()
            );
        }
    }


    @Override
    public void getBlocksFrom(GetBlocksRequest request, StreamObserver<GetBlocksResponse> responseObserver) {
        long startIndex = request.getStartIndex();
        List<Block> blocks = Blockchain.getBlocksFrom(startIndex);

        List<BlockMessage> blockMessages = new ArrayList<>();
        for (Block block : blocks) {
            BlockMessage.Builder blockBuilder = BlockMessage.newBuilder()
                    .setBlockData(Utils.convertBlockToResponse(block));
            blockMessages.add(blockBuilder.build());
        }

        GetBlocksResponse response = GetBlocksResponse.newBuilder()
                .addAllBlocks(blockMessages)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void findValue(FindValueRequest request, StreamObserver<FindValueResponse> responseObserver) {
        String key = request.getKey();

        Set<String> values = LocalNode.getValues(key);

        FindValueResponse.Builder responseBuilder = FindValueResponse.newBuilder();

        if (values != null) {
            responseBuilder.setFound(true);
            responseBuilder.addAllValue(values);
        } else {
            BigInteger targetId = Utils.hashKeyToId(key);
            List<Node> closest = LocalNode.findClosestNodes(targetId, LocalNode.getK());

            List<NodeInfo> nodeInfos = closest.stream()
                    .map(node -> NodeInfo.newBuilder()
                            .setId(node.getId().toString())
                            .setIp(node.getIpAddress())
                            .setPort(node.getPort())
                            .build())
                    .collect(Collectors.toList());

            responseBuilder.setFound(false);
            responseBuilder.addAllNodes(nodeInfos);
        }

        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void sendPaymentRequest(PaymentRequest request, StreamObserver<PaymentRequestResponse> responseObserver) {
        String toNodeId = request.getAuctionWinnerId();
        if (!toNodeId.equals(this.LocalNode.getId().toString())) {
            responseObserver.onNext(PaymentRequestResponse.newBuilder().setSuccess(false).build());
            responseObserver.onCompleted();
            return;
        }

        double amount = request.getAmount();
        String auctionId = request.getAuctionId();

        String key = sha256("bid:" + auctionId);
        Set<String> values = this.LocalNode.getValues(key);

        double maxBidValue = values.stream()
                .map(json -> {
                    try {
                        return gson.fromJson(json, Bid.class);
                    } catch (Exception e) {
                        System.err.println("Failed to parse bid JSON: " + json);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .mapToDouble(Bid::getAmount)
                .max()
                .orElse(0.0);


        if(maxBidValue != amount){
            responseObserver.onNext(PaymentRequestResponse.newBuilder().setSuccess(false).build());
            responseObserver.onCompleted();
            return;
        }

        if (this.LocalNode.getBalance() >= amount) {

            Transaction transaction = new Transaction(Transaction.TransactionType.AuctionPayment, this.LocalNode.getPublicKey(), new BigInteger(request.getAuctionOwnerId()),  (double) amount);

            transaction.setAuctionId(UUID.fromString(auctionId));

            transaction.signTransaction(this.LocalNode.getPrivateKey());

            RpcClient.pay(transaction, transaction.getSignature()).thenAccept(success -> {
                if (success) {
                    this.LocalNode.updateBalance(-amount);
                } else {
                    System.out.println("Payment failed");
                }
            });


            responseObserver.onNext(PaymentRequestResponse.newBuilder().setSuccess(true).build());
        } else {
            System.out.println("Insufficient balance for auction " + auctionId);
            responseObserver.onNext(PaymentRequestResponse.newBuilder().setSuccess(false).build());
        }

        responseObserver.onCompleted();
    }

    private void manageMempool(Transaction assembledTransaction){
        if(Blockchain.getMempoolSize() == (MaxTransactionsPerBlock - 1) && this.LocalNode.isMiner()){
            Blockchain.addTransactionToMempool(assembledTransaction.getTransactionId(), assembledTransaction);
            Block lastBlock = Blockchain.getLastBlock();
            Block newBlock = new Block(lastBlock.getIndex() + 1, lastBlock.getBlockHash(), new ArrayList<>(Blockchain.getMempoolValues()));
            Blockchain.clearMempool();
            startMining(newBlock);
        }
        else{
            Blockchain.addTransactionToMempool(assembledTransaction.getTransactionId(), assembledTransaction);
        }
    }

    @Override
    public void gossipReputation(GossipReputationRequest request, StreamObserver<GossipReputationResponse> responseObserver) {
        try {
            UUID reputationId = UUID.fromString(request.getReputationMessageId());

            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(request.getSenderPublicKey().toByteArray());
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PublicKey publicKey = keyFactory.generatePublic(keySpec);

            String lastUpdate = Instant.ofEpochMilli(request.getLastUpdated()).truncatedTo(ChronoUnit.SECONDS).toString();
            String data = request.getNodeId() + request.getScore() + lastUpdate ;
            byte[] message = data.getBytes(StandardCharsets.UTF_8);

            Signature sig = Signature.getInstance("SHA256withRSA", "BC");
            sig.initVerify(publicKey);
            sig.update(message);
            boolean validSignature = sig.verify(request.getSignature().toByteArray());
            if(!validSignature){

                BigInteger nodeId = new BigInteger(request.getNodeId());
                Reputation rep = this.LocalNode.ReputationMap.get(nodeId);
                if(rep==null){
                    rep = new Reputation(0.3,Instant.now());
                    rep.generateId();
                    this.LocalNode.ReputationMap.put(nodeId,rep);
                }
                else{
                    double newScore = max(rep.getScore() - 0.2,0);
                    rep.setScore(newScore);
                    rep.setLastUpdated(Instant.now());
                    this.LocalNode.ReputationMap.put(nodeId,rep);
                }

                Reputation finalRep = rep;
                byte[] signature = rep.signReputation(LocalNode.getPrivateKey(), nodeId);
                CompletableFuture.runAsync(() -> {
                    RpcClient.gossipReputation(finalRep, nodeId, signature, LocalNode);
                });
                System.out.println("Costa rica 4");
                responseObserver.onNext(GossipReputationResponse.newBuilder()
                        .setAccepted(false)
                        .build());
                responseObserver.onCompleted();
                return;
            }

            if (ReputationIds.contains(reputationId)) {
                responseObserver.onNext(GossipReputationResponse.newBuilder()
                        .setAccepted(true)
                        .build());
                responseObserver.onCompleted();
                return;
            }
            else{
                ReputationIds.add(reputationId);
            }

            BigInteger senderId = new BigInteger(request.getSenderId());
            BigInteger targetNodeId = new BigInteger(request.getNodeId());
            Instant incomingTime = Instant.ofEpochMilli(request.getLastUpdated());
            double incomingScore = request.getScore();

            Reputation senderReputation = LocalNode.ReputationMap.get(senderId);
            if (senderReputation == null || senderReputation.getScore() < 0.2) {
                responseObserver.onNext(GossipReputationResponse.newBuilder()
                        .setAccepted(false)
                        .build());
                responseObserver.onCompleted();
                return;
            }

            Reputation localRep = LocalNode.ReputationMap.get(targetNodeId);
            double localScore = (localRep != null) ? localRep.getScore() : 0.0;
            double diff = Math.abs(localScore - incomingScore);

            if (localRep != null && !incomingTime.isAfter(localRep.getLastUpdated())) {
                responseObserver.onNext(GossipReputationResponse.newBuilder()
                        .setAccepted(false)
                        .build());
                responseObserver.onCompleted();
                return;
            }

            double senderScore = Math.min(senderReputation.getScore(), 1.0);
            double weight;

            if (diff < 0.1) {
                weight = senderScore;
            } else if (diff > 0.5) {
                weight = Math.min(0.2, senderScore * 0.5);
            } else {
                weight = senderScore * (1.0 - diff);
            }

            double newScore = (1 - weight) * localScore + weight * incomingScore;

            Reputation updatedRep = new Reputation(newScore,incomingTime);

            updatedRep.setReputationId(reputationId);

            LocalNode.ReputationMap.put(targetNodeId, updatedRep);

            byte[] signature = updatedRep.signReputation(LocalNode.getPrivateKey(), targetNodeId);
            RpcClient.gossipReputation(updatedRep, targetNodeId, signature ,LocalNode);

            responseObserver.onNext(GossipReputationResponse.newBuilder()
                .setAccepted(true)
                .build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            System.out.println(e);
            responseObserver.onNext(GossipReputationResponse.newBuilder()
                    .setAccepted(false)
                    .build());
            responseObserver.onCompleted();
        }
    }


    @Override
    public void pay(TransactionMessage request, StreamObserver<GossipResponse> responseObserver) {
        try {

            Transaction assembledTransaction = gson.fromJson(request.getTransactionData(),Transaction.class);

            Reputation senderReputation = LocalNode.ReputationMap.get(new BigInteger(request.getSenderNodeId()));
            if (senderReputation == null || senderReputation.getScore() < 0.2) {
                responseObserver.onNext(GossipResponse.newBuilder().setSuccess(false).build());
                responseObserver.onCompleted();
                return;
            }


            assembledTransaction.setSignature(request.getSignature().toByteArray());
            double score = assembledTransaction.validateTransaction();
            if (score == 1) {
                BigInteger nodeId =  new BigInteger(Utils.sha256(assembledTransaction.getSender().getEncoded()),16);

                Reputation rep = this.LocalNode.ReputationMap.get(nodeId);

                if(rep==null){
                    rep = new Reputation(0.2,Instant.now());
                    rep.generateId();
                    this.LocalNode.ReputationMap.put(nodeId,rep);
                }
                else{
                    double newScore = min(rep.getScore() + 0.2,1);
                    rep.setScore(newScore);
                    rep.setLastUpdated(Instant.now());
                    this.LocalNode.ReputationMap.put(nodeId,rep);
                }

                Reputation finalRep = rep;
                byte[] signature = finalRep.signReputation(LocalNode.getPrivateKey(), nodeId);
                CompletableFuture.runAsync(() -> {
                    RpcClient.gossipReputation(finalRep, nodeId, signature, LocalNode);
                });

                if(assembledTransaction.getType() == Transaction.TransactionType.AuctionPayment && assembledTransaction.getAuctionOwnerId().equals(LocalNode.getId())){
                    this.LocalNode.updateBalance(assembledTransaction.getAmount());
                    System.out.println("⚠️  [WARNING] You received " + assembledTransaction.getAmount() + " for auction ID: " + assembledTransaction.getAuctionId());

                }
                RpcClient.gossipTransaction(assembledTransaction, request.getSignature().toByteArray(), new BigInteger(request.getSenderNodeId()));
                manageMempool(assembledTransaction);
            } else{
                BigInteger nodeId = new BigInteger(Utils.sha256(assembledTransaction.getSender().toString()));
                Reputation rep = this.LocalNode.ReputationMap.get(nodeId);

                if(rep==null){
                    rep = new Reputation(0.3,Instant.now());
                    rep.generateId();
                    this.LocalNode.ReputationMap.put(nodeId,rep);
                }
                else{
                    double newScore = max(rep.getScore() - score,0);
                    rep.setScore(newScore);
                    rep.setLastUpdated(Instant.now());
                    this.LocalNode.ReputationMap.put(nodeId,rep);
                }
                Reputation finalRep = rep;
                byte[] signature = rep.signReputation(LocalNode.getPrivateKey(), nodeId);
                CompletableFuture.runAsync(() -> {
                    RpcClient.gossipReputation(finalRep, nodeId, signature, LocalNode);
                });

            }

            responseObserver.onNext(GossipResponse.newBuilder().setSuccess(score == 1).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            e.printStackTrace();
            responseObserver.onError(Status.INTERNAL.withDescription("Failed to process transaction").asRuntimeException());
        }
    }

    public void startMining(Block blockToMine) {
    IsMining = true;

    Transaction transaction = new Transaction(Transaction.TransactionType.BlockMinedPayment,LocalNode.getPublicKey(),null,10.0 );
    transaction.signTransaction(LocalNode.getPrivateKey());

    blockToMine.addTransaction(transaction);

    this.CurrentBlockMining = blockToMine;
    MiningThread = new Thread(() -> {
        blockToMine.mine(() -> IsMining);
        if (IsMining && Blockchain.verifyBlock(blockToMine) == 1) {
            RpcClient.gossipBlock(blockToMine);
            this.Blockchain.addNewBlock(blockToMine);
            LocalNode.updateBalance(10);

            IsMining = false;
            CurrentBlockMining = null;
        }
    });
    MiningThread.start();
    }

    public void stopMining() {
        IsMining = false;
        CurrentBlockMining = null;
        if (MiningThread != null && MiningThread.isAlive()) {
            try {
                MiningThread.join(100);
            } catch (InterruptedException e) {
                MiningThread.interrupt();
            }
        }
    }

    public void handleBid(String key, String payload){
        LocalNode.addKey(key, payload);

        Bid bid = gson.fromJson(payload, Bid.class);
        String auctionKey = sha256("auction-info:" + bid.getAuctionId());

        Set<String> auctionValue = LocalNode.getValues(auctionKey);

        if(auctionValue == null || auctionValue.isEmpty() ){
            return;
        }

        String auctionJson = auctionValue.iterator().next();

        Auction auction = gson.fromJson(auctionJson, Auction.class);

        auction.placeBid(bid);

        String auctionJsonUpdated = gson.toJson(auction);

        LocalNode.addKeyWithReplace(auctionKey,auctionJsonUpdated);

        RpcClient.publishAuctionBid(bid.getAuctionId(), key, payload);
    }

    public void handleClose(String key, String payload) {
        LocalNode.addKey(key, payload);

        String auctionKey = sha256("auction-info:" + payload);

        Set<String> auctionValue = LocalNode.getValues(auctionKey);

        if(auctionValue == null || auctionValue.isEmpty() ){
            return;
        }

        String auctionJson = auctionValue.iterator().next();

        Auction auction = gson.fromJson(auctionJson, Auction.class);

        auction.closeAuction();

        String auctionJsonUpdated = gson.toJson(auction);

        LocalNode.removeAuction(auction.getAuctionId());

        LocalNode.addKey(auctionKey,auctionJsonUpdated);

        RpcClient.publishAuctionClose(key, payload);
    }

    public void handleAuction(String key, String payload, String srcNodeId){
        LocalNode.addKey(key,payload);

        Auction auction = gson.fromJson(payload,Auction.class);

        LocalNode.addKey(sha256("auction-subs:" + auction.getAuctionId()), srcNodeId);

        LocalNode.addAuctionToAuctions(auction);
    }
    public void handleSubscription(String key, String payload){
        LocalNode.addKey(key,payload);
    }

    public void handleAddCatalog(String key, String payload){
        LocalNode.addKey(key,payload);
    }

    public void handleRemoveCatalog(String key, String payload){
        Set<String> entries = LocalNode.getValues(key);
        if (entries == null || entries.isEmpty()) {
            System.out.println("No entries found for key: " + key);
            return;
        }

        AuctionMapEntry auction = gson.fromJson(payload, AuctionMapEntry.class);

        String toRemove = null;
        for (String entry : entries) {
            if (entry.contains(auction.getAuctionId().toString())) {
                toRemove = entry;
                break;
            }
        }

        if (toRemove != null) {
            entries.remove(toRemove);
            LocalNode.addKeyWithReplace(key, entries);
        }
    }
}