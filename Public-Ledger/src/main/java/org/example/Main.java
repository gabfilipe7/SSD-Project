package org.example;

import Auction.Auction;
import Blockchain.Block;
import Blockchain.Blockchain;
import Blockchain.Transaction;
import Communications.RpcClient;
import Communications.RpcServer;
import Kademlia.Node;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Scanner;

public class Main {

    Scanner scanner = new Scanner(System.in);
    Node localNode;
    Blockchain blockchain = new Blockchain();
    RpcClient rpcClient;
    RpcServer rpcServer;

    //fazer bootstrap hardwire e configurar bash para inicilizar direto
    //proof of reputation
    //segurança resistance attacks
    //leilão mechanisms
    // publisher subscriber
    //kademlia stotres findvalues
    //fault mechanism

    public static void main(String[] args) {
        boolean isBootstrap = false;
        int port = 5000;

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

        this.localNode = new Node("127.0.0.1",port,20,isBootstrap);
        this.rpcServer = new RpcServer(localNode, blockchain);
        this.startGrpcServer();
        this.rpcClient = new RpcClient(localNode, blockchain);



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
            System.out.println("(5) Exit");
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
        System.out.printf("The auction for the product %s was created successfully.%n", productName);
        Transaction transaction = new Transaction(Transaction.TransactionType.CREATE_AUCTION,this.localNode.getPublicKey());
        transaction.setAuctionId(newAuction.getAuctionId());
        transaction.setStartTime(Instant.now());
        transaction.setItemDescription(productName);
        transaction.signTransaction(this.localNode.getPrivateKey());
        this.blockchain.addTransactionToMempool(transaction.getTransactionId(),transaction);
        RpcClient.gossipTransaction(transaction, transaction.getSignature(), this.localNode);
    }

    private void listAuctions(){
        List<Auction> auctions = this.localNode.GetListedAuctions();
        if (auctions.isEmpty()) {
            System.out.println("There are no auctions to close.");
            return;
        }
        else{
            System.out.println("This is the list of currently listed auctions:");
            System.out.println("----------------------------------------------");
            int count  = 0;
            for(Auction auction : auctions){
                System.out.printf("(%s) ---- %s ----- %s%n", count,auction.getItem(),auction.isClosed());
                count++;
            }
            System.out.print("Press any key to return to menu");
            String response = this.scanner.nextLine();
        }
    }

    private void closeAuction() {
        List<Auction> auctions = this.localNode.GetActiveAuctions();

        if (auctions.isEmpty()) {
            System.out.println("There are no auctions to close.");
            return;
        }

        System.out.println("Select an auction to close:");
        System.out.println("------------------------------");
        int count = 0;

        for (Auction auction : auctions) {
            System.out.printf("(%d) %s%n", count, auction.getItem());
            count++;
        }

        System.out.print("Enter the number of the auction to close: ");

        int choice = -1;
        try {
            choice = Integer.parseInt(scanner.nextLine());
        } catch (NumberFormatException e) {
            System.out.println("Invalid input. Returning to menu.");
            return;
        }

        if (choice < 0 || choice >= auctions.size()) {
            System.out.println("Invalid choice. Returning to menu.");
            return;
        }

        Auction selectedAuction = auctions.get(choice);

        boolean success = this.localNode.closeAuction(selectedAuction.getAuctionId());

        if (success) {
            System.out.printf("Auction for product %s closed successfully!%n", selectedAuction.getItem());
            Transaction transaction = new Transaction(Transaction.TransactionType.CLOSE_AUCTION,this.localNode.getPublicKey());
            transaction.setAuctionId(selectedAuction.getAuctionId());
            transaction.setEndTime(Instant.now());
            transaction.signTransaction(this.localNode.getPrivateKey());
            this.blockchain.addTransactionToMempool(transaction.getTransactionId(),transaction);
            RpcClient.gossipTransaction(transaction, transaction.getSignature(), this.localNode);
        } else {
            System.out.println("Failed to close auction. It might have already been closed or you might not be the creator of this auction.");
        }
    }

    public void placeBid() {
        List<Auction> auctions = this.localNode.GetActiveAuctions();

        if (auctions.isEmpty()) {
            System.out.println("There are no auctions to bid.");
            return;
        }

        System.out.println("Select an auction to close:");
        System.out.println("------------------------------");
        int count = 0;

        for (Auction auction : auctions) {
            System.out.printf("(%d) %s%n", count, auction.getItem());
            count++;
        }

        System.out.print("Enter the number of the auction to close: ");

        int choice = -1;
        try {
            choice = Integer.parseInt(scanner.nextLine());
        } catch (NumberFormatException e) {
            System.out.println("Invalid input. Returning to menu.");
            return;
        }

        if (choice < 0 || choice >= auctions.size()) {
            System.out.println("Invalid choice. Returning to menu.");
            return;
        }

        Auction auction = auctions.get(choice);

        if (auction == null) {
            System.out.println("Auction not found.");
            return;
        }

        if (auction.isClosed()) {
            System.out.println("Auction is already closed.");
            return;
        }

        System.out.print("Insert the value of the bid:");
        double bidValue = Integer.parseInt(scanner.nextLine());

        auction.placeBid(this.localNode.getPublicKey(), bidValue);
        System.out.println("Bid placed successfully.");

        Transaction transaction = new Transaction(Transaction.TransactionType.PLACE_BID ,this.localNode.getPublicKey());
        transaction.setAuctionId(auction.getAuctionId());
        transaction.setItemDescription(auction.getItem());
        transaction.setBidAmount(bidValue);
        transaction.signTransaction(this.localNode.getPrivateKey());
        this.blockchain.addTransactionToMempool(transaction.getTransactionId(),transaction);
        RpcClient.gossipTransaction(transaction, transaction.getSignature(), this.localNode);
    }

    private void connectToBootstrapNodes() {
        List<Node> bootstrapNodes = List.of(
                new Node( "127.0.0.1", 5000,20,true),
                new Node( "127.0.0.1", 5001,20,true)
        );

        for (Node bootstrap : bootstrapNodes) {
            System.out.println("Attempting to connect to bootstrap node at " + bootstrap.getIpAddress() + ":" + bootstrap.getPort());
            boolean connected = RpcClient.ping(bootstrap);
            if (connected) {
                System.out.println("Successfully connected to bootstrap node at " + bootstrap.getIpAddress() + ":" + bootstrap.getPort());
                break;
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

}
