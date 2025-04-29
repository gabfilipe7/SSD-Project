package Communications;

import Blockchain.Blockchain;
import Blockchain.Transaction;
import Kademlia.Node;
import Kademlia.Utils;
import com.kademlia.grpc.*;
import Blockchain.Block;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.stream.Collectors;

public class RpcServer extends KademliaServiceGrpc.KademliaServiceImplBase {

    private final Node localNode;
    private final Map<UUID, Transaction> transactions = new ConcurrentHashMap<>();
    private final Blockchain blockchain;

    public RpcServer(Node localNode, Blockchain blockchain) {
        this.localNode = localNode;
        this.blockchain = blockchain;
    }

    @Override
    public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
        PingResponse response = PingResponse.newBuilder()
                .setIsAlive(true)
                .build();

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
    public void store(StoreRequest request, StreamObserver<StoreResponse> responseObserver) {
        try {

            byte[] key = request.getKey().toByteArray();
            String value = request.getValue();
            int ttl = request.getTtl();

            SourceAddress src = request.getSrc();
            DestinationAddress dst = request.getDst();

      /*     // Log/store origin info (useful for Kademlia routing table updates)
            System.out.printf("Received STORE request from Node(ID=%s) at %s:%d%n",
                    bytesToHex(src.getId().toByteArray()), src.getIp(), src.getPort());

            // ✅ Simulated storage logic — you should store it in your local DHT map or DB
            boolean storedLocally = storeLocally(key, value, ttl);

            // Prepare and send response
            StoreResponseType type = storedLocally
                    ? StoreResponseType.LOCAL_STORE
                    : StoreResponseType.UNKNOWN_TYPE_STORE;

            StoreResponse response = StoreResponse.newBuilder()
                    .setResponseType(type)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
*/
        } catch (Exception e) {
            e.printStackTrace();
            responseObserver.onError(e);
        }
    }

    @Override
    public void gossipTransaction(TransactionMessage request, StreamObserver<GossipResponse> responseObserver) {
        try {

            com.kademlia.grpc.Transaction tx = request.getTransactionData();

            if (transactions.containsKey(UUID.fromString(tx.getTransactionId()))) {
                responseObserver.onNext(GossipResponse.newBuilder().setSuccess(false).build());
                responseObserver.onCompleted();
                return;
            }

            Transaction assembledTransaction = new Transaction(UUID.fromString(tx.getTransactionId()),
                    Transaction.TransactionType.values()[tx.getType()],
                    Instant.parse(tx.getTimestamp()),
                    Utils.byteStringToPublicKey(tx.getSenderPublicKey()));


            assembledTransaction.setSignature(request.getSignature().toByteArray());
            boolean valid = assembledTransaction.validateTransaction();
            if (valid) {
                transactions.put(UUID.fromString(tx.getTransactionId()), assembledTransaction);
                RpcClient.gossipTransaction(assembledTransaction, request.getSignature().toByteArray(),this.localNode);
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
            Transaction transaction = new Transaction(UUID.fromString(tr.getTransactionId()),
                    Transaction.TransactionType.values()[tr.getType()],
                    Instant.parse(tr.getTimestamp()),
                    Utils.byteStringToPublicKey(tr.getSenderPublicKey()));
            transactions.add(transaction);
        }

        if (!blockchain.Contains(receivedBlock.getBlockId())) {
            Block block = new Block(receivedBlock.getBlockId(),
                                receivedBlock.getHash(),
                                receivedBlock.getPreviousHash(),
                                receivedBlock.getTimestamp(),
                                transactions,
                                receivedBlock.getNonce());

            if (blockchain.verifyBlock(block)) {
                blockchain.AddNewBlock(block);
                RpcClient.gossipBlock(block, request.getSignature().toByteArray(),this.localNode);
                System.out.println("✅ Received and accepted new block: " + receivedBlock.getBlockId());
            } else {
                System.out.println("❌ Invalid block received: " + receivedBlock.getBlockId());
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

}
