package Communications;

import Auction.Auction;
import Auction.Bid;
import Blockchain.Blockchain;
import Blockchain.Transaction;
import Identity.Reputation;
import Kademlia.Node;
import Utils.Utils;
import Utils.StoreValue;
import com.google.gson.Gson;
import com.kademlia.grpc.*;
import Blockchain.Block;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.security.PublicKey;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import java.math.BigInteger;
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


    public RpcServer(Node localNode, Blockchain blockchain) {
        this.localNode = localNode;
        this.blockchain = blockchain;
        this.maxTransactionsPerBlock = 3;
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

            com.kademlia.grpc.Transaction tx = request.getTransactionData();

            if (this.blockchain.containsTransaction(UUID.fromString(tx.getTransactionId()))) {
                responseObserver.onNext(GossipResponse.newBuilder().setSuccess(false).build());
                responseObserver.onCompleted();
                return;
            }

            Transaction assembledTransaction = Utils.convertResponseToTransaction(tx);

            assembledTransaction.setSignature(request.getSignature().toByteArray());
            boolean valid = assembledTransaction.validateTransaction();
            if (valid) {

                if(assembledTransaction.getType() == Transaction.TransactionType.AuctionPayment && tx.getTargetPublicKey().equals(localNode.getPublicKey())){
                    this.localNode.updateBalance(assembledTransaction.getAmount());
                }

                RpcClient.gossipTransaction(assembledTransaction, request.getSignature().toByteArray(),this.localNode, new BigInteger(request.getSenderNodeId()));
                manageMempool(assembledTransaction);
            }

            responseObserver.onNext(GossipResponse.newBuilder().setSuccess(valid).build());
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

            List<Transaction> transactions = new ArrayList<>();

            for (com.kademlia.grpc.Transaction tr : receivedBlock.getTransactionsList()){
                Transaction transaction = Utils.convertResponseToTransaction(tr);
                transactions.add(transaction);
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

                if (blockchain.verifyBlock(block)) {
                    if(currentBlockMining != null){
                        if(Objects.equals(currentBlockMining.getPreviousBlockHash(), block.getPreviousBlockHash())){
                            stopMining();
                        }
                    }
                    blockchain.AddNewBlock(block);
                    RpcClient.gossipBlock(block,this.localNode);
                }
                else if (!blockchain.Contains(receivedBlock.getBlockId() - 1)) {
                    stopMining();
                    RpcClient.updateBlockChain(this.localNode, blockchain,blockchain.GetLastBlock().getIndex());
                    responseObserver.onNext(GossipResponse.newBuilder().setSuccess(false).build());
                    responseObserver.onCompleted();
                    return;
                }
                else {
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

            Gson gson = new Gson();
            StoreValue value = gson.fromJson(request.getValue(), StoreValue.class);

            switch (value.getType()) {
                case AUCTION:
                    handleAuction(key, value.getPayload());
                    break;
                case SUBSCRIPTION:
                    handleSubscription(key, value.getPayload());
                    break;
                case BID:
                    handleBid(key, value.getPayload());
                    break;
                case CLOSE:
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
        String toNodeId = request.getTo();
        if (!toNodeId.equals(this.localNode.getId().toString())) {
            responseObserver.onNext(PaymentRequestResponse.newBuilder().setSuccess(false).build());
            responseObserver.onCompleted();
            return;
        }

        double amount = request.getAmount();
        String auctionId = request.getAuctionId();

        String key = sha256("bid:" + auctionId);
        Set<String> values = this.localNode.getValues(key);

        int maxBidValue = values.stream()
                .mapToInt(Integer::parseInt)
                .max()
                .orElse(0);


        if(maxBidValue != amount){
            responseObserver.onNext(PaymentRequestResponse.newBuilder().setSuccess(false).build());
            responseObserver.onCompleted();
            return;
        }

        if (this.localNode.getBalance() >= amount) {
            this.localNode.updateBalance(-amount);

            Transaction transaction = new Transaction(Transaction.TransactionType.AuctionPayment, this.localNode.getPublicKey(),  (double) amount);

            transaction.signTransaction(this.localNode.getPrivateKey());

            RpcClient.gossipTransaction(transaction, transaction.getSignature(), localNode, localNode.getId());

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

            if (reputationIds.contains(reputationId)) {
                responseObserver.onNext(GossipReputationResponse.newBuilder()
                        .setAccepted(true)
                        .build());
                responseObserver.onCompleted();
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
            rpcClient.gossipReputation(updatedRep, targetNodeId, signature ,localNode, new BigInteger(request.getSenderId()));

            responseObserver.onNext(GossipReputationResponse.newBuilder()
                .setAccepted(true)
                .build());
            responseObserver.onCompleted();

        } catch (Exception e) {
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
            if (isMining && blockchain.verifyBlock(blockToMine)) {
                blockchain.AddNewBlock(blockToMine);
                RpcClient.gossipBlock(blockToMine, localNode);
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

        Gson gson = new Gson();
        Bid bid = gson.fromJson(payload, Bid.class);
        String auctionKey = sha256("auction-info:" + bid.getAuctionId());
        String auctionJson = localNode.getValues(auctionKey).iterator().next();

        Auction auction = gson.fromJson(auctionJson, Auction.class);

        auction.placeBid(bid);

        String auctionJsonUpdated = gson.toJson(auction);

        localNode.addKey(auctionKey,auctionJsonUpdated );

        rpcClient.PublishAuctionBid(bid.getAuctionId(), key, payload);
    }

    public void handleClose(String key, String payload) {
        localNode.addKey(key, payload);

        Gson gson = new Gson();
        String auctionKey = sha256("auction-info:" + payload);
        String auctionJson = localNode.getValues(auctionKey).iterator().next();

        Auction auction = gson.fromJson(auctionJson, Auction.class);

        auction.closeAuction();

        String auctionJsonUpdated = gson.toJson(auction);

        localNode.addKey(auctionKey,auctionJsonUpdated);

        rpcClient.PublishAuctionClose(key, payload);
    }
    public void handleAuction(String key, String payload){
        localNode.addKey(key,payload);
    }
    public void handleSubscription(String key, String payload){
        localNode.addKey(key,payload);
    }
}