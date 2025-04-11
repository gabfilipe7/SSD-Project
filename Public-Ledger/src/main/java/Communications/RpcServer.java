package Communications;

import com.kademlia.grpc.KademliaServiceGrpc;
import com.kademlia.grpc.PingRequest;
import com.kademlia.grpc.PingResponse;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;

public class RpcServer {

    public static void main(String[] args) throws IOException, InterruptedException {
        Server server = ServerBuilder
                .forPort(8080)
                .addService(new RpcServiceImpl())
                .build();

        System.out.println("Server starting on port 8080...");
        server.start();
        server.awaitTermination();
    }

    static class RpcServiceImpl extends KademliaServiceGrpc.KademliaServiceImplBase {
        @Override
        public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
            System.out.println("Received ping from: " + request.getNodeId());

            PingResponse response = PingResponse.newBuilder()
                    .setIsAlive(true)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
