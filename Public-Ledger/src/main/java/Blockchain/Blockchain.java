package Blockchain;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Blockchain {

    private List<Block> Chain;
    private final Map<UUID, Transaction> Mempool = new ConcurrentHashMap<>();

    public Blockchain() {
        Chain = new ArrayList<>();
    }

    public Block getLastBlock(){
        int lastBlockIndex = this.Chain.size() - 1;
        return this.Chain.get(lastBlockIndex);
    }

    public boolean contains(long blockId) {
        for (Block block : this.Chain) {
            if (block.getIndex() == (blockId)) {
                return true;
            }
        }
        return false;
    }

    public double verifyBlock(Block block) {
        Block lastBlock = getLastBlock();
        if (!block.getPreviousBlockHash().equals(lastBlock.getBlockHash())) {
            return 0.05;
        }
        for (Transaction tr : block.getTransactions()) {
            if (tr.validateTransaction() != 1) {
                return tr.validateTransaction();
            }
        }
        String computedHash = block.calculateBlockHash();
        if (!computedHash.equals(block.getBlockHash())) {
            return 0.2;
        }
        if (!computedHash.startsWith("0".repeat(block.getDifficulty()))) {
            return 0.1;
        }
        return 1;
    }

    public static double verifyBlockWithoutBlockchain(Block block) {
        for (Transaction tr : block.getTransactions()) {
            if (tr.validateTransaction() != 1) {
                return tr.validateTransaction();
            }
        }
        String computedHash = block.calculateBlockHash();
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
        for (Block block : this.Chain) {
            if (block.getIndex() >= startIndex) {
                result.add(block);
            }
        }
        return result;
    }

    public void printMempoolValues() {

      for(Transaction tr : Mempool.values()){
          System.out.println(tr.toString());
      }
    }

    public void print() {
        StringBuilder sb = new StringBuilder();
        String blockTop = "╔═════════════════════════════════════════════════════════════════════════╗";
        String blockBottom = "╚═════════════════════════════════════════════════════════════════════════╝";
        String blockConnector = "                ║";
        String arrow = "                                    ║\n                                    ║";

        for (int i = 0; i < Chain.size(); i++) {
            Block block = Chain.get(i);

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

            if (i < Chain.size() - 1) {
                sb.append(arrow).append("\n");
            }
        }

        System.out.println(sb.toString());
    }

    public void createGenesisBlock() {
        String hash = "0000000000000000000000000000000000000000000000000000000000000000";
        long timestamp = System.currentTimeMillis();
        List<Transaction> firstTransaction = new ArrayList<>();
        Block genesisBlock = new Block(0,hash,hash,timestamp,firstTransaction,0);
        this.Chain.add(genesisBlock);
    }

    public static boolean chainsAreEqual(List<Block> chain1, List<Block> chain2) {
        if (chain1.size() != chain2.size()) return false;
        for (int i = 0; i < chain1.size(); i++) {
            if (!chain1.get(i).getBlockHash().equals(chain2.get(i).getBlockHash())) {
                return false;
            }
        }
        return true;
    }

    public void addNewBlock(Block block) {
        this.Chain.add(block);
    }

    public void replaceBlockchain(List<Block> newBlockchain)
    {
        this.Chain = newBlockchain;
    }

    public void clearMempool() {
        Mempool.clear();
    }

    public List<Block> getChain() {
        return this.Chain;
    }

    public void addTransactionToMempool(UUID key, Transaction transaction){
        Mempool.put(key, transaction);
    }

    public boolean containsTransaction(UUID key){
        return Mempool.containsKey(key);
    }

    public int getMempoolSize(){
        return Mempool.size();
    }

    public Collection <Transaction> getMempoolValues() {
        return Mempool.values();
    }

    public void removeLastBlock() {
        Chain.remove(Chain.size() - 1);
    }


}
