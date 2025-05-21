package Main;

import Auction.AuctionMapEntry;
import Auction.Auction;
import Auction.Bid;
import Blockchain.Blockchain;
import Communications.RpcClient;
import Communications.RpcServer;
import Kademlia.Node;
import Identity.Authentication;
import Utils.StoreValue;
import com.google.gson.Gson;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static Utils.Utils.sha256;

public class Main {

    Scanner scanner = new Scanner(System.in);
    Node localNode;
    Blockchain blockchain = new Blockchain();
    RpcClient rpcClient;
    RpcServer rpcServer;

    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);


    //fazer bootstrap hardwire e configurar bash para inicilizar direto
    //proof of reputation
    //segurança resistance attacks
    //fault mechanism
    //merkle tree

    public static void main(String[] args) {
        boolean isBootstrap = false;
        int port = 5002;

        for (String arg : args) {
            if (arg.equalsIgnoreCase("--bootstrap")) {
                isBootstrap = true;
            } else if (arg.startsWith("--port=")) {
                try {
                    port = Integer.parseInt(arg.split("=")[1]);
                } catch (NumberFormatException e) {
                    System.out.println("Invalid port number. Using default port 5000.");
                }
            }
        }

        new Main().boot(isBootstrap, port);
    }

    public void boot(boolean isBootstrap, int port ) {

        KeyPair keys = null;
        String algorithm = "RSA";


        if (Authentication.keysExist()) {
            try {
                keys = Authentication.loadKeyPair(algorithm);
                System.out.println("Loaded keys from file.");
            } catch (Exception e) {
                System.err.println("Failed to load keys, generating new keys.");
            }
        }

        this.localNode = new Node("127.0.0.1",port,20,isBootstrap,keys);
        this.localNode.setIsMiner(true);
        this.localNode.setK(20);
        this.rpcServer = new RpcServer(localNode, blockchain);
        this.startGrpcServer();
        this.rpcClient = new RpcClient(localNode, blockchain);
        this.rpcServer.rpcClient = this.rpcClient;
        this.blockchain.createGenesisBlock();
        scheduler.scheduleAtFixedRate(() -> refreshRoutingTable(), 0, 5, TimeUnit.MINUTES);


/*
        if(port==5000){
            isBootstrap = true;
        }
*/
        if(!isBootstrap){
            this.connectToBootstrapNodes();
        }

        while (true) {
            System.out.println("Welcome to the auction manager!");
            System.out.println("----------------------");
            System.out.println("(1) Create Auction");
            System.out.println("(2) List auctions");
            System.out.println("(3) Place Bid");
            System.out.println("(4) Close Auction");
            System.out.println("(5) Show Mempool");
            System.out.println("(6) Subscribe to auction");
            System.out.println("(7) Exit");
            System.out.println("----------------------");
            System.out.print("Select an option:");

            int choice = this.scanner.nextInt();
            scanner.nextLine();

            switch (choice) {
                case 1:
                    createAuction();
                    break;
                case 2:
                    listAuctions();
                    break;
                case 3:
                     placeBid();
                    break;
                case 4:
                    closeAuction();
                    break;
                case 5:
                    System.out.println("Printing Mempool");
                    this.blockchain.printMempoolValues();
                    break;
                case 6:
                    subscribeAuction();
                    break;
                case 7:
                    System.out.println("Exiting...");
                    return;
                default:
                    System.out.println("Invalid option. Try again.");
            }
        }
    }

    private void createAuction(){
        System.out.println("Creating Auction!");
        System.out.println("----------------------");
        System.out.print("Insert the product you pretend to auction:");
        String productName = this.scanner.nextLine();
        System.out.println("----------------------");
        Auction newAuction = this.localNode.createAuction(productName, Instant.now());
        String key = sha256("auction-info:" + newAuction.getAuctionId());
        Gson gson = new Gson();
        String newAuctionJson = gson.toJson(newAuction);
        localNode.addKey(key, newAuctionJson);
        System.out.printf("The auction for the product %s was created successfully.%n", productName);
        rpcClient.findNode(new BigInteger(key)).thenAccept(nodes -> {
            for(Node node : nodes){
                StoreValue value = new StoreValue(StoreValue.Type.AUCTION,newAuctionJson);
                RpcClient.store(node.getIpAddress(),node.getPort(),key,gson.toJson(value));
            }
        });
    }

    private void listAuctions(){
        List<AuctionMapEntry> auctions = rpcClient.getAuctionListFromNetwork();
        if (auctions.isEmpty()) {
            System.out.println("There are no auctions to show.");
            return;
        }
        else{
            System.out.println("This is the list of currently listed auctions:");
            System.out.println("----------------------------------------------");
            int count  = 0;
            for(AuctionMapEntry auction : auctions){
                System.out.printf("(%s) ---- %s ----- %s%n", count,auction.getItemName() ,auction.getOwnerNode());
                count++;
            }
            System.out.print("Press any key to return to menu");
            String response = this.scanner.nextLine();
        }
    }

    private void closeAuction() {
        System.out.print("Insert the Id of the auction you want to close: ");

        String auctionIdInput = this.scanner.nextLine();

        String key = sha256("auction-info:" + UUID.fromString(auctionIdInput));

        Set<String> values = rpcClient.findValue(key, localNode, 10).orElse(new HashSet<>());
        String auctionJson = values.iterator().next();

        Gson gson = new Gson();
        Auction auction = gson.fromJson(auctionJson, Auction.class);

        if (auction == null) {
            System.out.println("Auction not found.");
            return;
        }

        if (auction.isClosed()) {
            System.out.println("Auction is already closed.");
            return;
        }

        auction.closeAuction();
        rpcClient.findNode(new BigInteger(key)).thenAccept(nodes -> {
            for(Node node : nodes){
                StoreValue value = new StoreValue(StoreValue.Type.CLOSE,auctionIdInput);
                RpcClient.store(node.getIpAddress(),node.getPort(),"auction-close"+ UUID.fromString(auctionIdInput),gson.toJson(value));
            }
            System.out.println("Auction closed successfully.");
            Bid winningBid = auction.getWinningBid().orElse(null);

            assert winningBid != null;
            rpcClient.findNode(winningBid.getBidder()).thenAccept(closeToWinner -> {
                for(Node node : closeToWinner){
                    if(node.getId().equals(winningBid.getBidder())){
                        rpcClient.sendPaymentRequest(node, winningBid.getAmount(),winningBid.getAuctionId());
                    }
                }
            });
        });

    }

    public void placeBid() {

        System.out.print("Insert the Id of the auction you want to bid: ");

        String auctionIdInput = this.scanner.nextLine();

        String key = sha256("auction-info:" + UUID.fromString(auctionIdInput));

        Set<String> values = rpcClient.findValue(key, localNode, 10).orElse(new HashSet<>());
        String auctionJson = values.iterator().next();

        Gson gson = new Gson();
        Auction auction = gson.fromJson(auctionJson, Auction.class);


        if (auction == null) {
            System.out.println("Auction not found.");
            return;
        }

        if (auction.isClosed()) {
            System.out.println("Auction is already closed.");
            return;
        }

        System.out.print("Insert the value of the bid for the item " + auction.getItem() + ": ");
        double bidValue = Integer.parseInt(scanner.nextLine());

        Bid bid = new Bid(auction.getAuctionId(), this.localNode.getId(),bidValue, Instant.now());
        String bidJson = gson.toJson(bid);

        String storeKey = sha256("bid:" + auction.getAuctionId());

        rpcClient.findNode(new BigInteger(key)).thenAccept(nodes -> {
            for(Node node : nodes){
                StoreValue value = new StoreValue(StoreValue.Type.BID,bidJson);
                RpcClient.store(node.getIpAddress(),node.getPort(),storeKey,gson.toJson(value));
            }
            System.out.println("Bid placed successfully.");
        });

    }

    private void connectToBootstrapNodes() {
        List<Node> bootstrapNodes = List.of(
                new Node( "127.0.0.1", 5000,20,true,null),
                new Node( "127.0.0.1", 5001,20,true,null)
        );

        for (Node bootstrap : bootstrapNodes) {
            System.out.println("Attempting to connect to bootstrap node at " + bootstrap.getIpAddress() + ":" + bootstrap.getPort());
            boolean connected = rpcClient.ping(bootstrap,localNode);
            if (connected) {
                this.localNode.addNode(bootstrap);
                this.rpcClient.findNode(bootstrap.getId());
                System.out.println("Successfully connected to bootstrap node at " + bootstrap.getIpAddress() + ":" + bootstrap.getPort());
            } else {
                System.out.println("Failed to connect to bootstrap node at " + bootstrap.getIpAddress() + ":" + bootstrap.getPort());
            }
        }
    }
    private void startGrpcServer() {

        new Thread(() -> {
            try {
                Server server = ServerBuilder.forPort(this.localNode.getPort())
                        .addService(this.rpcServer)
                        .build();

                System.out.println("Starting gRPC server on port " + this.localNode.getPort() + "...");
                server.start();
                System.out.println("Server started successfully!");


                server.awaitTermination();
            } catch (IOException | InterruptedException e) {
                System.err.println("Error while starting the gRPC server: " + e.getMessage());
            }
        }).start();


        try {
            System.out.println("Waiting for 4 seconds before continuing...");
            Thread.sleep(4000);
        } catch (InterruptedException e) {
            System.err.println("Thread sleep interrupted: " + e.getMessage());
        }

        System.out.println("4 seconds passed. Now continuing with the next steps...");
    }

    private void subscribeAuction(){
        System.out.println("Insert the Id of the auction you wish to subscribe: ");
        String auctionIdInput = this.scanner.nextLine();
        String key = sha256("auction-subs:" + auctionIdInput);
        Gson gson = new Gson();
        rpcClient.findNode(new BigInteger(key)).thenAccept(nodes -> {
            for(Node node : nodes){
                StoreValue value = new StoreValue(StoreValue.Type.SUBSCRIPTION ,localNode.getId().toString());
                RpcClient.store(node.getIpAddress(),node.getPort(),key,gson.toJson(value));
            }
        });
    }

    public void refreshRoutingTable() {
        System.out.println("Refreshing routing table...");

        for (int i = 0; i < 5; i++) {
            BigInteger randomId = Utils.Utils.generateRandomNodeId();
            rpcClient.findNodeAndUpdateRoutingTable(randomId);
        }
    }


}
