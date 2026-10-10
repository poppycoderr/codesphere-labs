import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Random;
import org.rocksdb.CompactionStyle;
import org.rocksdb.CompressionType;
import org.rocksdb.FlushOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.Statistics;
import org.rocksdb.TickerType;

/**
 * LSM 树的三种放大：写一份数据，磁盘上实际写了几份；删掉数据之后空间什么时候回来；同一批键反复覆盖时磁盘上留着多少旧版本。
 * 用 RocksDB 在容器自己的临时目录里写入约 190 MB 数据（关闭压缩，便于按字节对账），从它自带的统计里读出刷盘与压实写入的字节数。
 */
public class LsmLab {
    static final int KEYS = 1_500_000, VALUE = 110;

    static Options options(CompactionStyle style, Statistics stats) {
        return new Options().setCreateIfMissing(true).setCompressionType(CompressionType.NO_COMPRESSION).setStatistics(stats)
                .setWriteBufferSize(8L << 20).setTargetFileSizeBase(8L << 20).setMaxBytesForLevelBase(32L << 20)
                .setCompactionStyle(style).setMaxBackgroundJobs(4);
    }
    static byte[] key(long k) { return ByteBuffer.allocate(16).putLong(0x6b65795f00000000L).putLong(k).array(); }
    static long sst(RocksDB db) throws Exception { return Long.parseLong(db.getProperty("rocksdb.total-sst-files-size")); }
    static void settle(RocksDB db) throws Exception {
        try (FlushOptions fo = new FlushOptions().setWaitForFlush(true)) { db.flush(fo); }
        while (Long.parseLong(db.getProperty("rocksdb.compaction-pending")) > 0 || Long.parseLong(db.getProperty("rocksdb.num-running-compactions")) > 0) Thread.sleep(100);
    }
    static String mb(long bytes) { return String.format("%.0f MB", bytes / 1048576.0); }

    static void load(String name, CompactionStyle style, boolean random) throws Exception {
        File dir = Files.createTempDirectory("lsm").toFile();
        try (Statistics stats = new Statistics(); Options o = options(style, stats); RocksDB db = RocksDB.open(o, dir.getPath())) {
            Random rnd = new Random(20261010L); byte[] value = new byte[VALUE]; long user = 0;
            for (int i = 0; i < KEYS; i++) {
                rnd.nextBytes(value);
                byte[] k = key(random ? rnd.nextLong() & 0xffffffffffL : i);
                db.put(k, value); user += k.length + value.length;
            }
            settle(db);
            long flush = stats.getTickerCount(TickerType.FLUSH_WRITE_BYTES), compact = stats.getTickerCount(TickerType.COMPACT_WRITE_BYTES);
            System.out.println(name + ".user_mb\t" + String.format("%.0f", user / 1048576.0));
            System.out.println(name + ".flush_mb\t" + String.format("%.0f", flush / 1048576.0));
            System.out.println(name + ".compaction_mb\t" + String.format("%.0f", compact / 1048576.0));
            System.out.println(name + ".write_amplification\t" + String.format("%.1f", (flush + compact) / (double) user));
            System.out.println(name + ".sst_mb\t" + String.format("%.0f", sst(db) / 1048576.0));
            System.out.println(name + ".levels\t" + db.getProperty("rocksdb.levelstats").replaceAll("\\s+", " ").trim());
        }
    }

    public static void main(String[] args) throws Exception {
        RocksDB.loadLibrary();
        System.out.println("env\tjava.version=" + System.getProperty("java.version") + " rocksdb=" + RocksDB.rocksdbVersion());
        System.out.println("setup\t" + KEYS + " 个键，每个键 16 字节、值 " + VALUE + " 字节，关闭压缩；内存表 8 MB，第 1 层目标 32 MB");
        load("sequential_leveled", CompactionStyle.LEVEL, false);
        load("random_leveled", CompactionStyle.LEVEL, true);
        load("random_universal", CompactionStyle.UNIVERSAL, true);

        // 覆盖与删除：先写 30 万个键，再把它们各覆盖 4 次，再全部删除
        File dir = Files.createTempDirectory("lsm").toFile();
        try (Statistics stats = new Statistics(); Options o = options(CompactionStyle.LEVEL, stats).setDisableAutoCompactions(true); RocksDB db = RocksDB.open(o, dir.getPath())) {
            Random rnd = new Random(7); byte[] value = new byte[VALUE]; int n = 300_000;
            for (int round = 0; round < 5; round++) { for (int i = 0; i < n; i++) { rnd.nextBytes(value); db.put(key(i), value); } try (FlushOptions fo = new FlushOptions().setWaitForFlush(true)) { db.flush(fo); } }
            long live = (long) n * (16 + VALUE);
            System.out.println("overwrite.live_mb\t" + String.format("%.0f", live / 1048576.0));
            System.out.println("overwrite.sst_before_compaction_mb\t" + String.format("%.0f", sst(db) / 1048576.0));
            db.compactRange();
            System.out.println("overwrite.sst_after_compaction_mb\t" + String.format("%.0f", sst(db) / 1048576.0));
            for (int i = 0; i < n; i++) db.delete(key(i));
            try (FlushOptions fo = new FlushOptions().setWaitForFlush(true)) { db.flush(fo); }
            System.out.println("delete.sst_after_delete_mb\t" + String.format("%.0f", sst(db) / 1048576.0));
            System.out.println("delete.visible_keys\t" + (db.get(key(1)) == null ? 0 : 1));
            db.compactRange();
            System.out.println("delete.sst_after_compaction_mb\t" + String.format("%.0f", sst(db) / 1048576.0));
        }
    }
}
