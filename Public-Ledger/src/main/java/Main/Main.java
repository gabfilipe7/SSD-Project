package Main;

import Auction.AuctionMapEntry;
import Auction.Auction;
import Auction.Bid;
import Blockchain.Blockchain;
import Communications.RpcClient;
import Communications.RpcServer;
import Identity.Reputation;
import Kademlia.KBucket;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import static Utils.Utils.sha256;

public class Main {

    Scanner Scanner = new Scanner(System.in);
    Node LocalNode;
    Blockchain Blockchain = new Blockchain();
    RpcClient RpcClient;
    RpcServer RpcServer;
    ScheduledExecutorService Scheduler = Executors.newScheduledThreadPool(1);
    
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
                } catch (NumberFormatException ignored) {}
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
            } catch (Exception ignored) {}
        }

        this.LocalNode = new Node("127.0.0.1",port,20,isBootstrap,keys);
        this.LocalNode.setK(20);
        this.RpcServer = new RpcServer(LocalNode, Blockchain);
        this.startGRpcServer();
        this.RpcClient = new RpcClient(LocalNode, Blockchain);
        this.RpcServer.RpcClient = this.RpcClient;
        this.Blockchain.createGenesisBlock();
        Scheduler.scheduleAtFixedRate(() -> refreshRoutingTable(), 0, 30, TimeUnit.SECONDS);
        Scheduler.scheduleAtFixedRate(() -> pingBuckets(), 0, 30, TimeUnit.SECONDS);


/*
        if(port==5000){
            isBootstrap = true;
        }
*/

        this.connectToBootstrapNodes();

       // System.out.print("Would you like to contribute with your computer's resources to help mine new blocks for the Blockchain? (yes/no): ");
      //  String input = Scanner.nextLine().trim().toLowerCase();

        //boolean permission = input.equals("yes") || input.equals("y");
        this.LocalNode.setIsMiner(true);

        this.RpcClient.synchronizeBlockchain();
        this.LocalNode.calculateLocalNodeBalance(Blockchain);

        while (true) {
            System.out.println("\n===============================");
            System.out.println(" Welcome to the Auction Manager");
            System.out.println("===============================\n");
            System.out.println("Please select an option:\n");

            System.out.println("  1) Create Auction");
            System.out.println("  2) List Auctions");
            System.out.println("  3) Place a Bid");
            System.out.println("  4) Close Auction");
            System.out.println("  5) Print Bids");
            System.out.println("  6) Print Blockchain");
            System.out.println("  7) Subscribe to Auction");
            System.out.println("  8) Check Your Balance");
            System.out.println("  9) Check Reputation Table");
 //           System.out.println(" 10) Print Neighbours");
            System.out.println(" 11) Exit");

            System.out.print("\nYour choice:");
            int choice = this.Scanner.nextInt();
            Scanner.nextLine();
            System.out.print("\n");
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
                    printBids();
                    break;
                case 6:
                    Blockchain.print();
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
                    LocalNode.printAllNeighbours();
                    break;
                case 11:
                    System.out.println("Exiting...");
                    System.exit(0);
                    break;

                default:
                    System.out.println("Invalid option. Try again.");
            }
        }
    }

    private void createAuction(){
        System.out.println("\nCreating Auction!");
        System.out.println("----------------------");
        System.out.print("Insert the product you pretend to auction:");
        String productName = this.Scanner.nextLine();
        Auction newAuction = this.LocalNode.createAuction(productName, Instant.now());
        String key = sha256("auction-info:" + newAuction.getAuctionId());
        String newAuctionJson = gson.toJson(newAuction);
        LocalNode.addKey(key, newAuctionJson);
        System.out.printf("\nThe auction for the product %s was created successfully.%n", productName);
       RpcClient.findNode(new BigInteger(key,16)).thenAccept(nodes -> {
            for(Node node : nodes){
                StoreValue value = new StoreValue(StoreValue.Type.AUCTION,newAuctionJson);
                RpcClient.store(node.getIpAddress(),node.getPort(),key,gson.toJson(value));
            }
        });

        String catalogKey = sha256("auction-global-catalog");
        AuctionMapEntry entry = new AuctionMapEntry(newAuction.getAuctionId(),newAuction.getItem(),newAuction.getOwner());
        String auctionEntryJson = gson.toJson(entry);
        RpcClient.findNode(new BigInteger(catalogKey,16)).thenAccept(nodes -> {
            for(Node node : nodes){
                StoreValue value = new StoreValue(StoreValue.Type.ADD_CATALOG,auctionEntryJson);
                RpcClient.store(node.getIpAddress(),node.getPort(),catalogKey,gson.toJson(value));
            }
        });
    }

    private void listAuctions() {

        Set<String> values = RpcClient.findValue(sha256("auction-global-catalog"), 10).orElse(new HashSet<>()) ;
        List<AuctionMapEntry> auctions = new ArrayList<>();

        for (String auction : values) {
            auctions.add(gson.fromJson(auction,AuctionMapEntry.class));
        }

        if (auctions.isEmpty()) {
            System.out.println("There are no auctions to show.");
            return;
        }

        System.out.println("This is the list of currently active auctions:");
        System.out.println("------------------------------------------------------------------------------------------------------------------------------------");

        System.out.printf("%-80s | %-10s | %-36s%n", "Owner", "Item", "Auction ID");
        System.out.println("------------------------------------------------------------------------------------------------------------------------------------");

        int count = 0;
        for (AuctionMapEntry auction : auctions) {
            System.out.printf("%-80d | %-10s | %-36s%n", auction.getOwnerNode(), auction.getItemName(), auction.getAuctionId());
            count++;
        }

        System.out.println("------------------------------------------------------------------------------------------------------------------------------------");
        System.out.print("Press Enter to return to the menu...");
        Scanner.nextLine();
    }


    private void closeAuction() {
        Set<String> auctionList = RpcClient.findValue(sha256("auction-global-catalog"), 10).orElse(new HashSet<>()) ;

        System.out.println("This is the list of active auctions owned by you:");
        System.out.println("-----------------------------------------------------------------");
        System.out.printf(" %-15s | %-36s%n", "  Item", "Auction ID");
        System.out.println("-----------------------------------------------------------------");


        List<AuctionMapEntry> myAuctions = new ArrayList<>();
        int index = 0;
        for (String json : auctionList) {
            AuctionMapEntry auction = gson.fromJson(json, AuctionMapEntry.class);
            if (auction != null && auction.getOwnerNode().equals(LocalNode.getId())) {
                System.out.printf(index + ": " + "%-13s | %-36s%n", auction.getItemName(), auction.getAuctionId());
                System.out.println("-----------------------------------------------------------------");
                myAuctions.add(auction);
                index++;
            }
        }

        if (myAuctions.isEmpty()) {
            System.out.println("\nNo active auctions found that you can close.");
            return;
        }

        System.out.print("\nSelect the auction number you want to close (0-" + (myAuctions.size() - 1) + "): ");
        int selection = -1;
        while (true) {
            try {
                selection = Integer.parseInt(Scanner.nextLine());
                if (selection < 0 || selection >= myAuctions.size()) {
                    System.out.println("Invalid selection. Please choose a valid auction number:");
                } else {
                    break;
                }
            } catch (NumberFormatException e) {
                System.out.println("Invalid input. Please enter a valid number:");
            }
        }


        String key = sha256("auction-info:" +  myAuctions.get(selection).getAuctionId());

     //   Set<String> auction = RpcClient.findValue(key, 10).orElse(new HashSet<>());
        Set<String> auction = LocalNode.getValues(key);

        if(auction.isEmpty()){
            System.out.print("\nThat auction is not available anymore.");
            return;
        }

        String auctionJson = auction.iterator().next();



        Auction selectedAuction = gson.fromJson(auctionJson, Auction.class);


        selectedAuction.closeAuction();

        CompletableFuture<List<Node>> future = RpcClient.findNode(new BigInteger(key, 16));

        CompletableFuture<Void> closeAuction = future.thenAccept(nodes -> {
            for (Node node : nodes) {
                StoreValue value = new StoreValue(StoreValue.Type.CLOSE, selectedAuction.getAuctionId().toString());
                RpcClient.store(node.getIpAddress(), node.getPort(), sha256("auction-close" + selectedAuction.getAuctionId().toString()), gson.toJson(value));
            }
            System.out.println("\nAuction closed successfully.");

            Bid winningBid = selectedAuction.getWinningBid().orElse(null);
            if (winningBid != null) {
                System.out.println("ALL bids:");
                for(Bid bid :selectedAuction.getbids()){
                    System.out.println(bid.getAmount() + " - " + bid.getBidder());
                }
                System.out.println("WInning bid is " + winningBid.getAmount());
                RpcClient.findNode(winningBid.getBidder()).thenAccept(closeToWinner -> {
                    for (Node node : closeToWinner) {
                        if (node.getId().equals(winningBid.getBidder())) {
                            RpcClient.sendPaymentRequest(node, winningBid.getAmount(), winningBid.getAuctionId());
                        }
                    }
                });
            }
        });

        try {
            closeAuction.get();
        } catch (Exception ignored) {}

        String globalCatalogKey = sha256("auction-global-catalog");
        AuctionMapEntry entry = new AuctionMapEntry(selectedAuction.getAuctionId(), selectedAuction.getItem(), selectedAuction.getOwner());
        String auctionEntryJson = gson.toJson(entry);
        RpcClient.findNode(new BigInteger(globalCatalogKey, 16)).thenAccept(nodes -> {
            for (Node node : nodes) {
                StoreValue value = new StoreValue(StoreValue.Type.REMOVE_CATALOG, auctionEntryJson);
                RpcClient.store(node.getIpAddress(), node.getPort(), globalCatalogKey, gson.toJson(value));
            }
        });
    }


    public void placeBid() {
        Set<String> values = RpcClient.findValue(sha256("auction-global-catalog"), 10).orElse(new HashSet<>()) ;


        if (values.isEmpty()) {
            System.out.println("No auctions found.");
            return;
        }

        System.out.println("This is the list of currently active auctions:");
        System.out.println("---------------------------------------------------------------------------------------------------------------------------------------");
        System.out.printf("%-83s | %-10s | %-36s%n", "   Owner", "Item", "Auction ID");
        System.out.println("---------------------------------------------------------------------------------------------------------------------------------------");

        List<AuctionMapEntry> auctions = new ArrayList<>();
        int index = 0;
        for (String json : values) {
            AuctionMapEntry auction = gson.fromJson(json, AuctionMapEntry.class);
            if (auction != null) {
                System.out.printf(index + ": " + "%-80d | %-10s | %-36s%n", auction.getOwnerNode(), auction.getItemName(), auction.getAuctionId());
                System.out.println("---------------------------------------------------------------------------------------------------------------------------------------");
                auctions.add(auction);
                index++;
            }
        }

        if (auctions.isEmpty()) {
            System.out.println("No valid auctions found.");
            return;
        }

        System.out.print("\nSelect the auction number you want to bid on (0-" + (auctions.size() - 1) + "): ");
        int selection = -1;
        while (true) {
            try {
                selection = Integer.parseInt(Scanner.nextLine());
                if (selection < 0 || selection >= auctions.size()) {
                    System.out.println("Invalid selection. Please choose a valid auction number:");
                } else {
                    break;
                }
            } catch (NumberFormatException e) {
                System.out.println("Invalid input. Please enter a valid number:");
            }
        }

        String key = sha256("auction-info:" +  auctions.get(selection).getAuctionId());

        Set<String> auction = RpcClient.findValue(key, 10).orElse(new HashSet<>());

        if(values.isEmpty()){
            System.out.print("That auction is not available anymore.");
            return;
        }

        String auctionJson = auction.iterator().next();

        Auction selectedAuction = gson.fromJson(auctionJson, Auction.class);

        if (selectedAuction.isClosed()) {
            System.out.println("Auction is already closed.");
            return;
        }

        System.out.print("\nInsert the value of the bid for the item " + selectedAuction.getItem() + ": ");
        double bidValue = 0;
        while (true) {
            try {
                bidValue = Double.parseDouble(Scanner.nextLine());
                if (bidValue <= 0) {
                    System.out.println("The bid value must be a positive number. Please try again:");
                } else if (bidValue > LocalNode.getBalance()) {
                    System.out.println("Insufficient balance! Your current balance is " + LocalNode.getBalance() + ". Please enter a lower bid:");
                } else {
                    break;
                }
            } catch (NumberFormatException e) {
                System.out.println("Invalid input. Please enter a valid number:");
            }
        }

        Bid bid = new Bid(selectedAuction.getAuctionId(), this.LocalNode.getId(), bidValue, Instant.now());
        String bidJson = gson.toJson(bid);
        String storeKey = sha256("bid:" + selectedAuction.getAuctionId());

        LocalNode.addKey(storeKey, bidJson);

        CompletableFuture<List<Node>> future = RpcClient.findNode(
                new BigInteger(sha256("auction-info:" + selectedAuction.getAuctionId()), 16)
        );

        CompletableFuture<Void> bidPlacement = future.thenAccept(nodes -> {
            for (Node node : nodes) {
                StoreValue value = new StoreValue(StoreValue.Type.BID, bidJson);
                RpcClient.store(node.getIpAddress(), node.getPort(), storeKey, gson.toJson(value));
            }
            System.out.println("\nBid placed successfully.");
        });

        try {
            bidPlacement.get();
        } catch (Exception ignored) {}
    }

    private void connectToBootstrapNodes() {
        List<Node> bootstrapNodes = List.of(
                new Node( new BigInteger("114463119885993250460859498894823303590894975338136063695510593414907458792199"),"127.0.0.1", 5000,20,true),
                new Node( new BigInteger("12345678901234565747082763456095068247603666210263000000526401266846914547799"),"127.0.0.1", 5001,20,true)
        );

        for (Node bootstrap : bootstrapNodes) {
            if(!bootstrap.getId().equals(LocalNode.getId()))
            {
               boolean connected = RpcClient.ping(bootstrap);
                Reputation reputation = new Reputation(0.7,Instant.now());
                reputation.generateId();
                LocalNode.ReputationMap.put(bootstrap.getId(),reputation);
                if (connected) {
                    this.LocalNode.addNode(bootstrap);
                    this.RpcClient.findNode(bootstrap.getId());
                }
            }
        }
    }
    private void startGRpcServer() {

        new Thread(() -> {
            try {
                Server server = ServerBuilder.forPort(this.LocalNode.getPort())
                        .addService(this.RpcServer)
                        .build();

                server.start();

                server.awaitTermination();
            } catch (IOException | InterruptedException e) {
                System.err.println("Error while starting the gRPC server: " + e.getMessage());
            }
        }).start();


    }


    private void subscribeAuction() {
        Set<String> auctionList = RpcClient.findValue(sha256("auction-global-catalog"), 10).orElse(new HashSet<>());

        System.out.println("This is the list of currently active auctions:");
        System.out.println("---------------------------------------------------------------------------------------------------------------------------------------");
        System.out.printf("%-83s | %-10s | %-36s%n", "   Owner", "Item", "Auction ID");
        System.out.println("---------------------------------------------------------------------------------------------------------------------------------------");

        List<AuctionMapEntry> auctions = new ArrayList<>();
        int index = 0;
        for (String json : auctionList) {
            AuctionMapEntry auction = gson.fromJson(json, AuctionMapEntry.class);
            if (auction != null) {
                System.out.printf(index + ": " + "%-80d | %-10s | %-36s%n", auction.getOwnerNode(), auction.getItemName(), auction.getAuctionId());
                System.out.println("---------------------------------------------------------------------------------------------------------------------------------------");
                auctions.add(auction);
                index++;
            }
        }

        if (auctions.isEmpty()) {
            System.out.println("No active auctions available to subscribe to.");
            return;
        }

        System.out.print("Select the auction number you want to subscribe to (0-" + (auctions.size() - 1) + "): ");
        int selection = -1;
        while (true) {
            try {
                selection = Integer.parseInt(Scanner.nextLine());
                if (selection < 0 || selection >= auctions.size()) {
                    System.out.println("Invalid selection. Please choose a valid auction number:");
                } else {
                    break;
                }
            } catch (NumberFormatException e) {
                System.out.println("Invalid input. Please enter a valid number:");
            }
        }

        AuctionMapEntry selectedAuction = auctions.get(selection);
        String auctionIdInput = String.valueOf(selectedAuction.getAuctionId());
        String subscribeKey = sha256("auction-subs:" + auctionIdInput);
        String auctionKey = sha256("auction-info:" + auctionIdInput);

        RpcClient.findNode(new BigInteger(auctionKey,16)).thenAccept(nodes -> {
            for(Node node : nodes){
                StoreValue value = new StoreValue(StoreValue.Type.SUBSCRIPTION, LocalNode.getId().toString());
                RpcClient.store(node.getIpAddress(), node.getPort(), subscribeKey, gson.toJson(value));
            }
            RpcServer.SubscribedAuctions.add(selectedAuction.getAuctionId());
            System.out.println("Subscribed to auction: " + auctionIdInput + " (Item: " + selectedAuction.getItemName() + ")");
        });
    }

    public void refreshRoutingTable() {
        for (int i = 0; i < 5; i++) {
            BigInteger randomId = Utils.Utils.generateRandomNodeId();
            RpcClient.findNodeAndUpdateRoutingTable(randomId);
        }
    }

    public void showBalance() {
        System.out.println("\n-------------------------------");
        System.out.println("         Your Balance");
        System.out.printf("         %s coins\n", LocalNode.getBalance());
        System.out.println("-------------------------------\n");
    }


    public void showPeerReputations() {

        System.out.println();
        if (LocalNode.ReputationMap.isEmpty()) {
            System.out.println("No peer reputations available.");
            return;
        }

        System.out.println("This is the list of your peers' reputations:\n");
        System.out.println("--------------------------------------------------------------------------------------------");
        System.out.printf("%-40s                                       | %-10s%n", "Peer ID", "Reputation");
        System.out.println("--------------------------------------------------------------------------------------------");

        for (Map.Entry<BigInteger, Reputation> entry : LocalNode.ReputationMap.entrySet()) {
            BigInteger peerId = entry.getKey();
            Reputation reputation = entry.getValue();

            System.out.printf("%-78s | %-10s%n", peerId, reputation.getScore());
        }

        System.out.println("--------------------------------------------------------------------------------------------");
        System.out.print("Press Enter to return to menu...");
        Scanner.nextLine();
    }

    public void pingBuckets() {
        for (KBucket bucket : LocalNode.getRoutingTable()) {
            Node node = bucket.getLeastRecentlySeenNode();
            if (node != null) {
                boolean alive = RpcClient.ping(node);
                if (!alive) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    alive = RpcClient.ping(node);
                    if (!alive) {
                        bucket.removeNode(node);
                    }
                }
            }
        }
    }

    public void printBids(){
        Set<String> auctionList = RpcClient.findValue(sha256("auction-global-catalog"), 10).orElse(new HashSet<>());
        System.out.println("This is the list of currently active auctions:");
        System.out.println("---------------------------------------------------------------------------------------------------------------------------------------");
        System.out.printf("%-83s | %-10s | %-36s%n", "   Owner", "Item", "Auction ID");
        System.out.println("---------------------------------------------------------------------------------------------------------------------------------------");

        List<AuctionMapEntry> auctions = new ArrayList<>();
        int index = 0;
        for (String json : auctionList) {
            AuctionMapEntry auction = gson.fromJson(json, AuctionMapEntry.class);
            if (auction != null) {
                System.out.printf(index + ": " + "%-80d | %-10s | %-36s%n", auction.getOwnerNode(), auction.getItemName(), auction.getAuctionId());
                System.out.println("---------------------------------------------------------------------------------------------------------------------------------------");
                auctions.add(auction);
                index++;
            }
        }

        if (auctions.isEmpty()) {
            System.out.println("No active auctions available");
            return;
        }

        System.out.print("Select the auction number you want to see the bids of (0-" + (auctions.size() - 1) + "): ");
        int selection = -1;
        while (true) {
            try {
                selection = Integer.parseInt(Scanner.nextLine());
                if (selection < 0 || selection >= auctions.size()) {
                    System.out.println("Invalid selection. Please choose a valid auction number:");
                } else {
                    break;
                }
            } catch (NumberFormatException e) {
                System.out.println("Invalid input. Please enter a valid number:");
            }
        }

        AuctionMapEntry selectedAuction = auctions.get(selection);
        System.out.println("\nThis is the list of bids");
        System.out.println("------------------------------------------------------------------------------------------");
        System.out.printf("%-10s | %-20s\n", "Amount", "Bidder");
        System.out.println("------------------------------------------------------------------------------------------");

        String bidsKey = sha256("bid:" + selectedAuction.getAuctionId());
        Set<String> values = RpcClient.findValue(bidsKey, 10).orElse(new HashSet<>()) ;
        for (String bidjson : values) {
            Bid bid = gson.fromJson(bidjson, Bid.class);
            String amountStr = String.format("%.2f", bid.getAmount());
            String bidderStr = String.valueOf(bid.getBidder());

            System.out.printf("%-10s | %-20s\n", amountStr, bidderStr);
            System.out.println("------------------------------------------------------------------------------------------");

        }

    }


}
