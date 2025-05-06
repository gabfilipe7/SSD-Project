package org.example;

import Auction.Auction;
import Blockchain.Block;
import Blockchain.Blockchain;
import Blockchain.Transaction;
import Communications.RpcClient;
import Communications.RpcServer;
import Kademlia.Node;

import java.time.Instant;
import java.util.List;
import java.util.Scanner;

public class Main {

    Scanner scanner = new Scanner(System.in);
    Node localNode = new Node("127.0.0.1",50051,20);
    Blockchain blockchain = new Blockchain();
    RpcClient rpcClient = new RpcClient(localNode, blockchain);
    RpcServer rpcServer = new RpcServer(localNode, blockchain);


    public static void main(String[] args) {
        new Main().boot();
    }

    public void boot() {

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
        Auction newAuction = this.localNode.createAuction(productName, Instant.now());
        System.out.printf("The auction for the product %s was created successfully.%n", productName);
        Transaction transaction = new Transaction(Transaction.TransactionType.CREATE_AUCTION,this.localNode.getPublicKey());
        transaction.setAuctionId(newAuction.getAuctionId());
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
                System.out.println(String.format("(%s) ---- %s ----- %s", count,auction.getItem()));
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



}
