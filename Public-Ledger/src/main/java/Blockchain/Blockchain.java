package Blockchain;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Blockchain {

    private List<Block> chain;
    private final Map<UUID, Transaction> mempool = new ConcurrentHashMap<>();

    public Blockchain() {


        chain = new ArrayList<>();
        createGenesisBlock();
    }

    public Blockchain(List<Block> Chain) {
        this.chain = Chain;
    }


  /*  public void AddNewBlock(List<Transaction> transactions) {

        Block lastBlock = this.GetLastBlock();

        int blockIndex = this.chain.size();
        Block newBlock = new Block(blockIndex, lastBlock.getBlockHash(), transactions);

        newBlock.mine();

        this.chain.add(newBlock);
    }*/

  public boolean isTransactionInBlockchain(UUID targetTransactionId) {

      for (Block block : this.chain) {
          for (Transaction transaction : block.getTransactions()) {
              if (transaction.getTransactionId().equals(targetTransactionId)) {
                  return true;
              }
          }
      }
      return false;
  }

    public void AddNewBlock(Block block) {
        this.chain.add(block);
    }


    public boolean validateBlockChain(){

        for(int i = 1; i < chain.size(); i++){
            Block previousBlock = this.chain.get(i-1);
            Block presentBlock = this.chain.get(i);

            String presentBlockHash = presentBlock.getBlockHash();

            if (!Objects.equals(presentBlock.CalculateBlockHash(), presentBlockHash)) {
                return false;
            }
            if (!presentBlockHash.startsWith("0".repeat(presentBlock.getDifficulty()))) {
                return false;
            }
            if (!Objects.equals(previousBlock.getBlockHash(), presentBlock.getPreviousBlockHash())) {
                return false;
            }


        }
        return true;
    }

    public Block GetLastBlock(){
        int lastBlockIndex = this.chain.size() - 1;
        return this.chain.get(lastBlockIndex);
    }

    public boolean Contains(long blockId) {
        for (Block block : this.chain) {
            if (block.getIndex() == (blockId)) {
                return true;
            }
        }
        return false;
    }

    public double verifyBlock(Block block) {

        Block lastBlock = GetLastBlock();
        if (!block.getPreviousBlockHash().equals(lastBlock.getBlockHash())) {
            return 0.05;
        }

        for (Transaction tr : block.getTransactions()) {
            if (tr.validateTransaction() != 1) {
                return tr.validateTransaction();
            }
        }

        String computedHash = block.CalculateBlockHash();
        if (!computedHash.equals(block.getBlockHash())) {
            return 0.2;
        }

        if (!computedHash.startsWith("0".repeat(block.getDifficulty()))) {
            return 0.1;
        }

        return 1;
    }

    public List<Block> getBlocksFrom(long startIndex) {
        List<Block> result = new ArrayList<>();
        for (Block block : this.chain) {
            if (block.getIndex() >= startIndex) {
                result.add(block);
            }
        }
        return result;
    }

    public List<Block> getChain() {
        return this.chain;
    }

    public synchronized void replaceFromIndex(long startIndex, List<Block> newBlocks) {
        try{
            if (startIndex < 0 || startIndex > chain.size()) {
                throw new IllegalArgumentException("Invalid start index");
            }

            while (chain.size() > startIndex) {
                chain.remove(chain.size() - 1);
            }

            chain.addAll(newBlocks);

        }catch(Exception ex){
            return;
        }
    }

    public void addTransactionToMempool(UUID key, Transaction transaction){
        mempool.put(key, transaction);
    }

    public boolean containsTransaction(UUID key){
        return mempool.containsKey(key);
    }

    public int getMempoolSize(){
        return mempool.size();
    }

    public Collection <Transaction> getMempoolValues() {
        return mempool.values();
    }

    public void printMempoolValues() {

      for(Transaction tr : mempool.values()){
          System.out.println(tr.toString());
      }
    }

    public void print() {
        StringBuilder sb = new StringBuilder();
        String blockTop = "╔═════════════════════════════════════════════════════════════════════════╗";
        String blockBottom = "╚═════════════════════════════════════════════════════════════════════════╝";
        String blockConnector = "                ║";
        String arrow = "                                    ║\n                                    ║";

        for (int i = 0; i < chain.size(); i++) {
            Block block = chain.get(i);

            sb.append(blockTop).append("\n");
            sb.append("║ Block #").append(String.format("%-63s", block.getIndex())).append("  ║\n");
            sb.append("║ Hash: ").append(String.format("%-59s", block.getBlockHash())).append("  ║\n");
            sb.append("║ Prev: ").append(String.format("%-64s", block.getPreviousBlockHash())).append("  ║\n");
            sb.append("║ Time: ").append(String.format("%-66s", new Date(block.getTimestamp()))).append("║\n");
            sb.append("║ Nonce: ").append(String.format("%-65s", block.getNonce())).append("║\n");
            sb.append("║ Transactions:").append(String.format("%-59s", "")).append("║\n");

            for (Transaction tx : block.getTransactions()) {
                sb.append("║   • ").append(String.format("%-60s", tx.getTransactionId())).append("        ║\n");
            }

            sb.append(blockBottom).append("\n");

            if (i < chain.size() - 1) {
                sb.append(arrow).append("\n");
            }
        }

        System.out.println(sb.toString());
    }



    public void clearMempool() {
        mempool.clear();
    }

    public void createGenesisBlock() {
        String hash = "0000000000000000000000000000000000000000000000000000000000000000";
        long timestamp = System.currentTimeMillis();
        List<Transaction> firstTransaction = new ArrayList<>();
        Block genesisBlock = new Block(0,hash,hash,timestamp,firstTransaction,0);
        this.chain.add(genesisBlock);
    }
}
