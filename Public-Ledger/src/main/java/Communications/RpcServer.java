package Communications;

import Blockchain.Blockchain;
import Blockchain.Transaction;
import Kademlia.Node;
import Utils.Utils;
import com.kademlia.grpc.*;
import Blockchain.Block;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import java.math.BigInteger;
import java.util.stream.Collectors;

public class RpcServer extends KademliaServiceGrpc.KademliaServiceImplBase {

    private final Node localNode;
    private final Blockchain blockchain;
    private int maxTransactionsPerBlock;
    private volatile boolean isMining = false;
    private volatile Block currentBlockMining;
    private Thread miningThread;
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
                RpcClient.gossipTransaction(assembledTransaction, request.getSignature().toByteArray(),this.localNode, new BigInteger(request.getSenderNodeId()));
                if(blockchain.getMempoolSize() == (maxTransactionsPerBlock - 1) && this.localNode.isMiner()){
                    blockchain.addTransactionToMempool(UUID.fromString(tx.getTransactionId()), assembledTransaction);
                    Block lastBlock = blockchain.GetLastBlock();
                    Block newBlock = new Block(lastBlock.getIndex() + 1, lastBlock.getBlockHash(), new ArrayList<>(blockchain.getMempoolValues()));
                    blockchain.clearMempool();
                    startMining(newBlock);
                }
                else{
                    blockchain.addTransactionToMempool(UUID.fromString(tx.getTransactionId()), assembledTransaction);
                }
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
            String value = request.getValue();

            localNode.addKey(key, value);

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

        String value = localNode.getValue(key);

        FindValueResponse.Builder responseBuilder = FindValueResponse.newBuilder();

        if (value != null) {
            responseBuilder.setFound(true);
            responseBuilder.setValue(value);
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


    public void startMining(Block blockToMine) {
        isMining = true;
        this.currentBlockMining = blockToMine;
        miningThread = new Thread(() -> {
            blockToMine.mine(() -> isMining);
            if (isMining && blockchain.verifyBlock(blockToMine)) {
                blockchain.AddNewBlock(blockToMine);
                localNode.handleBlockTransactions(blockToMine);
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


}