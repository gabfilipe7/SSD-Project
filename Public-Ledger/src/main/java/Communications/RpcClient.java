package Communications;

import com.kademlia.grpc.KademliaServiceGrpc;
import com.kademlia.grpc.PingRequest;
import com.kademlia.grpc.PingResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

public class RpcClient {

    public static void main(String[] args) {

        ManagedChannel channel = ManagedChannelBuilder
                .forAddress("localhost", 8080)
                .usePlaintext()  // Disable SSL/TLS encryption (use plaintext connection)
                .build();

        KademliaServiceGrpc.KademliaServiceBlockingStub stub =
                KademliaServiceGrpc.newBlockingStub(channel);


        PingRequest request = PingRequest.newBuilder()
                .setNodeId("node123")
                .build();

        PingResponse response = stub.ping(request);

        System.out.println("Server responded: is_alive = " + response.getIsAlive());

        channel.shutdown();
    }
}
