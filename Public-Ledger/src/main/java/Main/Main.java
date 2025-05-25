package Main;

import Auction.AuctionMapEntry;
import Auction.Auction;
import Auction.Bid;
import Blockchain.Blockchain;
import Communications.RpcClient;
import Communications.RpcServer;
import Identity.Reputation;
import Kademlia.Node;
import Identity.Authentication;
import Utils.InstantAdapter;
import Utils.PublicKeyAdapter;
import Utils.StoreValue;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.PublicKey;
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


    Gson gson = new GsonBuilder()
            .registerTypeAdapter(Instant.class, new InstantAdapter())
            .registerTypeHierarchyAdapter(PublicKey.class, new PublicKeyAdapter())
            .create();

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
            System.out.println("POTATO");
            try {
                keys = Authentication.loadKeyPair(algorithm);
                System.out.println("Loaded keys from file.");
            } catch (Exception e) {
                System.err.println("Failed to load keys, generating new keys.");
            }
        }

        this.localNode = new Node("127.0.0.1",port,20,isBootstrap,keys);
        this.localNode.setK(2);
        this.rpcServer = new RpcServer(localNode, blockchain);
        this.startGrpcServer();
        this.rpcClient = new RpcClient(localNode, blockchain);
        this.rpcServer.rpcClient = this.rpcClient;
        this.blockchain.createGenesisBlock();
        scheduler.scheduleAtFixedRate(() -> refreshRoutingTable(), 0, 30, TimeUnit.SECONDS);


/*
        if(port==5000){
            isBootstrap = true;
        }
*/

        this.connectToBootstrapNodes();

       // System.out.print("Would you like to contribute with your computer's resources to help mine new blocks for the blockchain? (yes/no): ");
      //  String input = scanner.nextLine().trim().toLowerCase();

        //boolean permission = input.equals("yes") || input.equals("y");
        this.localNode.setIsMiner(true);

        this.rpcClient.synchronizeBlockchain();
        this.localNode.calculateLocalNodeBalance(blockchain);

        System.out.println("My id is " + localNode.getId().toString());

        while (true) {
            System.out.println("Welcome to the auction manager!");
            System.out.println("----------------------");
            System.out.println("(1) Create Auction");
            System.out.println("(2) List auctions");
            System.out.println("(3) Place Bid");
            System.out.println("(4) Close Auction");
            System.out.println("(5) Show Mempool");
            System.out.println("(6) Print Blockchain");
            System.out.println("(7) Subscribe to auction");
            System.out.println("(8) Check your balance");
            System.out.println("(9) Check the reputation table");
            System.out.println("(10) Print neighbours");
            System.out.println("(11) Exit");
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
                    blockchain.print();
                    break;
                case 7:
                    subscribeAuction();
                    break;
                case 8:
                    showBalance();
                    break;
                case 9:
                    showPeerReputations();
                    break;
                case 10:
                    localNode.printAllNeighbours();
                    break;
                case 11:
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
        String newAuctionJson = gson.toJson(newAuction);
        localNode.addKey(key, newAuctionJson);
        System.out.printf("The auction for the product %s was created successfully.%n", productName);
        System.out.println("Chave:" + new BigInteger(key,16));
        rpcClient.findNode(new BigInteger(key,16)).thenAccept(nodes -> {
            for(Node node : nodes){
                StoreValue value = new StoreValue(StoreValue.Type.AUCTION,newAuctionJson);
                rpcClient.store(node.getIpAddress(),node.getPort(),key,gson.toJson(value));
            }
        });
    }

    private void listAuctions() {
        List<Auction> auctions = new ArrayList<>(localNode.getAuctionsMap().values());

        if (auctions.isEmpty()) {
            System.out.println("There are no auctions to show.");
            return;
        }

        System.out.println("This is the list of currently listed auctions:");
        System.out.println("---------------------------------------------------------------");

        System.out.printf("%-5s | %-30s | %-36s%n", "No.", "Item", "Auction ID");
        System.out.println("---------------------------------------------------------------");

        int count = 0;
        for (Auction auction : auctions) {
            System.out.printf("%-5d | %-30s | %-36s%n", count, auction.getItem(), auction.getAuctionId());
            count++;
        }

        System.out.println("---------------------------------------------------------------");
        System.out.print("Press Enter to return to the menu...");
        scanner.nextLine();
    }


    private void closeAuction() {
        System.out.print("Insert the Id of the auction you want to close: ");

        String auctionIdInput = this.scanner.nextLine();

        String key = sha256("auction-info:" + UUID.fromString(auctionIdInput));

        Set<String> auctionJson = localNode.getValues(key);

        if (auctionJson == null || auctionJson.isEmpty()) {
            System.out.print("No auction was found with that Id");
            return;
        }

        Auction auction = gson.fromJson(auctionJson.iterator().next(),Auction.class);


        if (auction == null) {
            System.out.println("Auction not found.");
            return;
        }
        if (!auction.getOwner().equals(localNode.getId())) {
            System.out.println("You cannot close an auction that does not belong to you.");
            return;
        }
        if (auction.isClosed()) {
            System.out.println("Auction is already closed.");
            return;
        }

        auction.closeAuction();
        rpcClient.findNode(new BigInteger(key,16)).thenAccept(nodes -> {
            for(Node node : nodes){
                StoreValue value = new StoreValue(StoreValue.Type.CLOSE,auctionIdInput);
                rpcClient.store(node.getIpAddress(),node.getPort(),"auction-close"+ UUID.fromString(auctionIdInput),gson.toJson(value));
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

        String key = sha256("auction-info:" + auctionIdInput);

        Set<String> values = rpcClient.findValue(key, 10).orElse(new HashSet<>());

        if(values.isEmpty()){
            System.out.print("No auction was found with that Id");
            return;
        }

        String auctionJson = values.iterator().next();

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
        double bidValue = 0;
        while (true) {
            bidValue = Integer.parseInt(scanner.nextLine());
            if (bidValue <= 0) {
                System.out.println("The bid value must be a positive number. Please try again:");
                continue;
            }
            if (bidValue > localNode.getBalance()) {
                System.out.println("Insufficient balance! Your current balance is " + localNode.getBalance() + ". Please enter a lower bid:");
                continue;
            }
            break;
        }

        Bid bid = new Bid(auction.getAuctionId(), this.localNode.getId(),bidValue, Instant.now());
        String bidJson = gson.toJson(bid);

        String storeKey = sha256("bid:" + auction.getAuctionId());
        System.out.println("Chave Bid:" + storeKey);

        localNode.addKey(storeKey,bidJson);
        rpcClient.findNode(new BigInteger(key,16)).thenAccept(nodes -> {
            for(Node node : nodes){
                System.out.println(node.getId());
                StoreValue value = new StoreValue(StoreValue.Type.BID,bidJson);
                rpcClient.store(node.getIpAddress(),node.getPort(),storeKey,gson.toJson(value));
            }
            System.out.println("Bid placed successfully.");
        });

    }

    private void connectToBootstrapNodes() {
        List<Node> bootstrapNodes = List.of(
                new Node( new BigInteger("114463119885993250460859498894823303590894975338136063695510593414907458792199"),"127.0.0.1", 5000,20,true),
                new Node( new BigInteger("12345678901234565747082763456095068247603666210263000000526401266846914547799"),"127.0.0.1", 5001,20,true)
        );

        for (Node bootstrap : bootstrapNodes) {
            if(!bootstrap.getId().equals(localNode.getId()))
            {
                System.out.println("Attempting to connect to bootstrap node at " + bootstrap.getIpAddress() + ":" + bootstrap.getPort());
                boolean connected = rpcClient.ping(bootstrap,localNode);
                Reputation reputation = new Reputation(0.7,Instant.now());
                reputation.generateId();
                localNode.reputationMap.put(bootstrap.getId(),reputation);
                if (connected) {
                    this.localNode.addNode(bootstrap);
                    this.rpcClient.findNode(bootstrap.getId());
                    System.out.println("Successfully connected to bootstrap node at " + bootstrap.getIpAddress() + ":" + bootstrap.getPort());
                } else {
                    System.out.println("Failed to connect to bootstrap node at " + bootstrap.getIpAddress() + ":" + bootstrap.getPort());
                }
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
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            System.err.println("Thread sleep interrupted: " + e.getMessage());
        }

        System.out.println("4 seconds passed. Now continuing with the next steps...");
    }

    private void subscribeAuction(){
        System.out.println("Insert the Id of the auction you wish to subscribe: ");
        String auctionIdInput = this.scanner.nextLine();
        String subscribeKey = sha256("auction-subs:" + auctionIdInput);
        String auctionKey = sha256("auction-info:" + auctionIdInput);
        rpcClient.findNode(new BigInteger(auctionKey,16)).thenAccept(nodes -> {
            for(Node node : nodes){
                StoreValue value = new StoreValue(StoreValue.Type.SUBSCRIPTION ,localNode.getId().toString());
                rpcClient.store(node.getIpAddress(),node.getPort(),subscribeKey,gson.toJson(value));
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

    public void showBalance() {
        System.out.println();
        System.out.println("Your current balance is: " + localNode.getBalance() + " tokens");
    }
    public void showPeerReputations() {

        System.out.println();
        if (localNode.reputationMap.isEmpty()) {
            System.out.println("No peer reputations available.");
            return;
        }

        System.out.println("Peer Reputations:");
        System.out.println("----------------------------------------------------------------------------------------------");
        System.out.printf("%-40s                                      | %-10s%n", "Peer ID", "Reputation");
        System.out.println("---------------------------------------------------------------------------------------------");

        for (Map.Entry<BigInteger, Reputation> entry : localNode.reputationMap.entrySet()) {
            BigInteger peerId = entry.getKey();
            Reputation reputation = entry.getValue();

            System.out.printf("%-40s | %-10s%n", peerId, reputation.getScore());
        }

        System.out.println("---------------------------------------------------------------------------------------------");
        System.out.print("Press Enter to return to menu...");
        scanner.nextLine();
    }



}
