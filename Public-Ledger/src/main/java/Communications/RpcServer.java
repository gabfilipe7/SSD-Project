package Communications;

import Auction.Auction;
import Auction.Bid;
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
import java.util.concurrent.ConcurrentHashMap;

import java.math.BigInteger;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;

import static Utils.Utils.sha256;

public class RpcServer extends KademliaServiceGrpc.KademliaServiceImplBase {

    public static RpcClient rpcClient;
    private final Node localNode;
    private final Blockchain blockchain;
    private int maxTransactionsPerBlock;
    private volatile boolean isMining = false;
    private volatile Block currentBlockMining;
    private Thread miningThread;
    private Set<UUID> reputationIds = new HashSet<>();

    Gson gson = new GsonBuilder()
            .registerTypeAdapter(Instant.class, new InstantAdapter())
            .registerTypeHierarchyAdapter(PublicKey.class, new PublicKeyAdapter())
            .create();

    public RpcServer(Node localNode, Blockchain blockchain) {
        this.localNode = localNode;
        this.blockchain = blockchain;
        this.maxTransactionsPerBlock = 1;
    }

    @Override
    public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
        PingResponse response = PingResponse.newBuilder()
                .setIsAlive(true)
                .build();

        Node node = new Node(new BigInteger(request.getNode().getId()), request.getNode().getIp(),request.getNode().getPort());
        this.localNode.addNode(node);

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }


    @Override
    public void findNode(FindNodeRequest request, StreamObserver<FindNodeResponse> responseObserver) {
        BigInteger targetId = new BigInteger(request.getTargetId());
        List<Node> closest = localNode.findClosestNodes(targetId, localNode.getK());

        List<NodeInfo> nodeInfos = closest.stream()
                .filter(node -> !node.getId().equals(localNode.getId()))
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

            if (this.blockchain.containsTransaction(assembledTransaction.getTransactionId())){
                responseObserver.onNext(GossipResponse.newBuilder().setSuccess(false).build());
                responseObserver.onCompleted();
                return;
            }

            Reputation senderReputation = localNode.reputationMap.get(new BigInteger(request.getSenderNodeId()));
            if (senderReputation == null || senderReputation.getScore() < 0.2) {
                responseObserver.onNext(GossipResponse.newBuilder().setSuccess(false).build());
                responseObserver.onCompleted();
                return;
            }


            assembledTransaction.setSignature(request.getSignature().toByteArray());
            double score = assembledTransaction.validateTransaction();
            if (score == 1) {
                BigInteger nodeId =  new BigInteger(Utils.sha256(assembledTransaction.getSender().getEncoded()),16);

                Reputation rep = this.localNode.reputationMap.get(nodeId);

                if(rep==null){
                    rep = new Reputation(0.01,Instant.now());
                    rep.generateId();
                    this.localNode.reputationMap.put(nodeId,rep);
                }
                else{
                    double newScore = rep.getScore() + 0.01;
                    rep.setScore(newScore);
                    rep.setLastUpdated(Instant.now());
                    this.localNode.reputationMap.put(nodeId,rep);
                }
                Reputation finalRep = rep;
                byte[] signature = finalRep.signReputation(localNode.getPrivateKey(), nodeId);
                CompletableFuture.runAsync(() -> {
                    System.out.println("BATATAS5"+ finalRep.toString());
                    rpcClient.gossipReputation(finalRep, nodeId, signature, localNode);
                });

                if(assembledTransaction.getType() == Transaction.TransactionType.AuctionPayment && assembledTransaction.getAuctionOwnerId().equals(localNode.getId())){
                    this.localNode.updateBalance(assembledTransaction.getAmount());
                    System.out.println("⚠️  [WARNING] You received " + assembledTransaction.getAmount() + " for auction ID: " + assembledTransaction.getAuctionId());

                }

                rpcClient.gossipTransaction(assembledTransaction, request.getSignature().toByteArray(),this.localNode, new BigInteger(request.getSenderNodeId()));
                manageMempool(assembledTransaction);
            } else{
                BigInteger nodeId = new BigInteger(Utils.sha256(assembledTransaction.getSender().toString()));
                Reputation rep = this.localNode.reputationMap.get(nodeId);

                if(rep==null){
                    rep = new Reputation(0,Instant.now());
                    rep.generateId();
                    this.localNode.reputationMap.put(nodeId,rep);
                }
                else{
                    double newScore = rep.getScore() - score;
                    rep.setScore(newScore);
                    rep.setLastUpdated(Instant.now());
                    this.localNode.reputationMap.put(nodeId,rep);
                }
                Reputation finalRep = rep;
                byte[] signature = rep.signReputation(localNode.getPrivateKey(), nodeId);
                CompletableFuture.runAsync(() -> {
                    System.out.println("BATATAS1");
                    rpcClient.gossipReputation(finalRep, nodeId, signature, localNode);
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

            Reputation senderReputation = localNode.reputationMap.get(new BigInteger(request.getSenderId()));
            if (senderReputation == null || senderReputation.getScore() < 0.2) {
                responseObserver.onNext(GossipResponse.newBuilder().setSuccess(false).build());
                responseObserver.onCompleted();
                return;
            }

            List<Transaction> transactions = new ArrayList<>();

            for(String tr: request.getBlockData().getTransactionsList()){
                transactions.add(gson.fromJson(tr,Transaction.class));
            }

            if (receivedBlock.getBlockId() <= blockchain.GetLastBlock().getIndex()) {
                responseObserver.onNext(GossipResponse.newBuilder().setSuccess(false).build());
                responseObserver.onCompleted();
                return;
            }

            if (!blockchain.Contains(receivedBlock.getBlockId())) {
                Block block = new Block(receivedBlock.getBlockId(),
                                    receivedBlock.getHash(),
                                    receivedBlock.getPreviousHash(),
                                    receivedBlock.getTimestamp(),
                                    transactions,
                                    receivedBlock.getNonce());

                double score = blockchain.verifyBlock(block);
                if (score == 1) {
                    if(currentBlockMining != null){
                        if(Objects.equals(currentBlockMining.getPreviousBlockHash(), block.getPreviousBlockHash())){
                            stopMining();
                        }
                    }

                    BigInteger senderId = new BigInteger(String.valueOf(request.getSenderId()));
                    Reputation rep = this.localNode.reputationMap.get(senderId);
                    if(rep==null){
                        rep = new Reputation(0,Instant.now());
                        rep.generateId();
                        this.localNode.reputationMap.put(senderId,rep);
                    }
                    else{
                        double newScore = rep.getScore() + 0.03;
                        rep.setScore(newScore);
                        rep.setLastUpdated(Instant.now());
                        this.localNode.reputationMap.put(senderId,rep);
                    }

                    Reputation finalRep = rep;
                    byte[] signature = rep.signReputation(localNode.getPrivateKey(), senderId);
                    CompletableFuture.runAsync(() -> {
                        rpcClient.gossipReputation(finalRep, senderId, signature, localNode);
                    });

                    blockchain.AddNewBlock(block);
                    rpcClient.gossipBlock(block,this.localNode);
                }
                else if (!blockchain.Contains(receivedBlock.getBlockId() - 1)) {
                    stopMining();
                    RpcClient.updateBlockChain(this.localNode, blockchain,blockchain.GetLastBlock().getIndex());
                    responseObserver.onNext(GossipResponse.newBuilder().setSuccess(false).build());
                    responseObserver.onCompleted();
                    return;
                }
                else {
                    BigInteger senderId = new BigInteger(String.valueOf(request.getSenderId()));
                    Reputation rep = this.localNode.reputationMap.get(senderId);

                    if(rep==null){
                        rep = new Reputation(0,Instant.now());
                        rep.generateId();
                        this.localNode.reputationMap.put(senderId,rep);
                    }
                    else{
                        double newScore = rep.getScore() - score;
                        rep.setScore(newScore);
                        rep.setLastUpdated(Instant.now());
                        this.localNode.reputationMap.put(senderId,rep);
                    }
                    Reputation finalRep = rep;
                    byte[] signature = rep.signReputation(localNode.getPrivateKey(), senderId);
                    CompletableFuture.runAsync(() -> {
                        System.out.println("BATATAS2");
                        rpcClient.gossipReputation(finalRep, senderId, signature, localNode);
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
                    handleAuction(key, value.getPayload());
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
        List<Block> blocks = blockchain.getBlocksFrom(startIndex);

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

        Set<String> values = localNode.getValues(key);

        FindValueResponse.Builder responseBuilder = FindValueResponse.newBuilder();

        if (values != null) {
            responseBuilder.setFound(true);
            responseBuilder.addAllValue(values);
        } else {
            BigInteger targetId = Utils.hashKeyToId(key);
            List<Node> closest = localNode.findClosestNodes(targetId, localNode.getK());

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
        if (!toNodeId.equals(this.localNode.getId().toString())) {
            responseObserver.onNext(PaymentRequestResponse.newBuilder().setSuccess(false).build());
            responseObserver.onCompleted();
            return;
        }

        double amount = request.getAmount();
        String auctionId = request.getAuctionId();

        String key = sha256("bid:" + auctionId);
        Set<String> values = this.localNode.getValues(key);

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

        if (this.localNode.getBalance() >= amount) {
            this.localNode.updateBalance(-amount);

            Transaction transaction = new Transaction(Transaction.TransactionType.AuctionPayment, this.localNode.getPublicKey(), new BigInteger(request.getAuctionOwnerId()),  (double) amount);

            transaction.signTransaction(this.localNode.getPrivateKey());

            rpcClient.gossipTransaction(transaction, transaction.getSignature(), localNode, localNode.getId());

            manageMempool(transaction);

            responseObserver.onNext(PaymentRequestResponse.newBuilder().setSuccess(true).build());
        } else {
            System.out.println("Insufficient balance for auction " + auctionId);
            responseObserver.onNext(PaymentRequestResponse.newBuilder().setSuccess(false).build());
        }

        responseObserver.onCompleted();
    }

    private void manageMempool(Transaction assembledTransaction){
        if(blockchain.getMempoolSize() == (maxTransactionsPerBlock - 1) && this.localNode.isMiner()){
            blockchain.addTransactionToMempool(assembledTransaction.getTransactionId(), assembledTransaction);
            Block lastBlock = blockchain.GetLastBlock();
            Block newBlock = new Block(lastBlock.getIndex() + 1, lastBlock.getBlockHash(), new ArrayList<>(blockchain.getMempoolValues()));
            blockchain.clearMempool();
            startMining(newBlock);
        }
        else{
            blockchain.addTransactionToMempool(assembledTransaction.getTransactionId(), assembledTransaction);
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
            System.out.println("Costa rica 1");
            Signature sig = Signature.getInstance("SHA256withRSA", "BC");
            sig.initVerify(publicKey);
            sig.update(message);
            boolean validSignature = sig.verify(request.getSignature().toByteArray());
            System.out.println("ASSINATURA VALIDA OU NÃO:" +validSignature );
            if(!validSignature){
                System.out.println("Costa rica 2");
                BigInteger nodeId = new BigInteger(request.getNodeId());
                Reputation rep = this.localNode.reputationMap.get(nodeId);
                if(rep==null){
                    rep = new Reputation(0,Instant.now());
                    rep.generateId();
                    this.localNode.reputationMap.put(nodeId,rep);
                }
                else{
                    double newScore = rep.getScore() - 0.2;
                    rep.setScore(newScore);
                    rep.setLastUpdated(Instant.now());
                    this.localNode.reputationMap.put(nodeId,rep);
                }
                System.out.println("Costa rica 3");
                Reputation finalRep = rep;
                byte[] signature = rep.signReputation(localNode.getPrivateKey(), nodeId);
                CompletableFuture.runAsync(() -> {
                    rpcClient.gossipReputation(finalRep, nodeId, signature, localNode);
                });
                System.out.println("Costa rica 4");
                responseObserver.onNext(GossipReputationResponse.newBuilder()
                        .setAccepted(false)
                        .build());
                responseObserver.onCompleted();
                return;
            }
            System.out.println("Costa rica 5");
            if (reputationIds.contains(reputationId)) {
                responseObserver.onNext(GossipReputationResponse.newBuilder()
                        .setAccepted(true)
                        .build());
                responseObserver.onCompleted();
                return;
            }
            else{
                reputationIds.add(reputationId);
            }

            BigInteger senderId = new BigInteger(request.getSenderId());
            BigInteger targetNodeId = new BigInteger(request.getNodeId());
            Instant incomingTime = Instant.ofEpochMilli(request.getLastUpdated());
            double incomingScore = request.getScore();

            Reputation senderReputation = localNode.reputationMap.get(senderId);
            if (senderReputation == null || senderReputation.getScore() < 0.2) {
                responseObserver.onNext(GossipReputationResponse.newBuilder()
                        .setAccepted(false)
                        .build());
                responseObserver.onCompleted();
                return;
            }

            Reputation localRep = localNode.reputationMap.get(targetNodeId);
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

            localNode.reputationMap.put(targetNodeId, updatedRep);

            byte[] signature = updatedRep.signReputation(localNode.getPrivateKey(), targetNodeId);
            System.out.println("BATATAS6");
            rpcClient.gossipReputation(updatedRep, targetNodeId, signature ,localNode);

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


    public void startMining(Block blockToMine) {
        isMining = true;
        this.currentBlockMining = blockToMine;
        miningThread = new Thread(() -> {
            blockToMine.mine(() -> isMining);
            if (isMining && blockchain.verifyBlock(blockToMine) == 1) {
                blockchain.AddNewBlock(blockToMine);
                rpcClient.gossipBlock(blockToMine, localNode);
                isMining = false;
                currentBlockMining = null;
            }
        });
        miningThread.start();
    }

    public void stopMining() {
        isMining = false;
        currentBlockMining = null;
        if (miningThread != null && miningThread.isAlive()) {
            try {
                miningThread.join(100);
            } catch (InterruptedException e) {
                miningThread.interrupt();
            }
        }
    }

    public void handleBid(String key, String payload){
        localNode.addKey(key, payload);

        Bid bid = gson.fromJson(payload, Bid.class);
        String auctionKey = sha256("auction-info:" + bid.getAuctionId());
        String auctionJson = localNode.getValues(auctionKey).iterator().next();

        Auction auction = gson.fromJson(auctionJson, Auction.class);

        auction.placeBid(bid);

        String auctionJsonUpdated = gson.toJson(auction);

        localNode.addKeyWithReplace(auctionKey,auctionJsonUpdated);

        rpcClient.PublishAuctionBid(bid.getAuctionId(), key, payload);
    }

    public void handleClose(String key, String payload) {
        localNode.addKey(key, payload);

        String auctionKey = sha256("auction-info:" + payload);
        String auctionJson = localNode.getValues(auctionKey).iterator().next();

        Auction auction = gson.fromJson(auctionJson, Auction.class);

        auction.closeAuction();

        String auctionJsonUpdated = gson.toJson(auction);

        localNode.removeAuction(auction.getAuctionId());

        localNode.addKey(auctionKey,auctionJsonUpdated);

        rpcClient.PublishAuctionClose(key, payload);
    }
    public void handleAuction(String key, String payload){
        localNode.addKey(key,payload);

        Auction auction = gson.fromJson(payload,Auction.class);

        localNode.addAuctionToAuctions(auction);
    }
    public void handleSubscription(String key, String payload){
        localNode.addKey(key,payload);
    }
}